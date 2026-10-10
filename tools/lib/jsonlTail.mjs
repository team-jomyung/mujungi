// JSON 한 줄 기록 파일 따라 읽기 (U1 3.5 Step 45 · J131 · J143 · SEC-05)
//
// C안 시나리오가 가짜 공급자 기록(stdout JSON 줄)과 realtime 로그(LATENCY_TURN · SESSION_ITEMS_SERVED)를 실행 중에 읽는 데 쓴다.
// compose 모드는 c-skeleton-turn.sh가 `docker compose logs -f`를 파일로 받고, host 모드는 프로세스 출력 파일을 그대로 준다.
// 줄 앞에 접두(서비스 이름 · 시각)가 붙어 있어도 첫 '{'부터 JSON으로 읽고, JSON이 아닌 줄은 건너뛴다. 모든 대기에는 기한이 있다(SEC-05).
import { closeSync, openSync, readSync, statSync } from "node:fs";

/** 기한 안에 조건을 만족하는 기록이 오지 않았다. */
export class TailTimeoutError extends Error {
  constructor(what, timeoutMs) {
    super(`${what}: ${timeoutMs}ms 안에 오지 않았다`);
    this.name = "TailTimeoutError";
    this.timeoutMs = timeoutMs;
  }
}

/** 줄 하나를 JSON 객체로(접두 허용). 아니면 null. */
export function parseJsonLine(line) {
  const at = line.indexOf("{");
  if (at < 0) {
    return null;
  }
  try {
    const value = JSON.parse(line.slice(at));
    return value !== null && typeof value === "object" && !Array.isArray(value) ? value : null;
  } catch {
    return null;
  }
}

export class JsonlTail {
  #path;
  #offset = 0;
  #remainder = "";
  #entries = [];
  #sleep;

  /**
   * @param {string} path 따라 읽을 파일(아직 없어도 된다)
   * @param {{sleep?: (ms: number) => Promise<void>}} [options]
   */
  constructor(path, { sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms)) } = {}) {
    this.#path = path;
    this.#sleep = sleep;
  }

  get path() {
    return this.#path;
  }

  /** 지금까지 읽은 JSON 기록들. */
  get entries() {
    return this.#entries;
  }

  /** 파일에서 새로 붙은 줄을 읽는다. 읽은 기록 수. 파일이 줄었으면(새로 만들어짐) 처음부터 다시 읽는다. */
  poll() {
    let size;
    try {
      size = statSync(this.#path).size;
    } catch {
      return 0;
    }
    if (size < this.#offset) {
      this.#offset = 0;
      this.#remainder = "";
    }
    if (size === this.#offset) {
      return 0;
    }
    const fd = openSync(this.#path, "r");
    let text;
    try {
      const length = size - this.#offset;
      const buffer = Buffer.alloc(length);
      let read = 0;
      while (read < length) {
        const n = readSync(fd, buffer, read, length - read, this.#offset + read);
        if (n === 0) {
          break;
        }
        read += n;
      }
      this.#offset += read;
      text = this.#remainder + buffer.subarray(0, read).toString("utf8");
    } finally {
      closeSync(fd);
    }
    const lines = text.split("\n");
    this.#remainder = lines.pop() ?? "";
    let added = 0;
    for (const line of lines) {
      const value = parseJsonLine(line);
      if (value !== null) {
        this.#entries.push(value);
        added += 1;
      }
    }
    return added;
  }

  /** 조건에 맞는 기록들(읽기 전에 poll한다). */
  find(predicate) {
    this.poll();
    return this.#entries.filter(predicate);
  }

  /**
   * 조건에 맞는 기록이 올 때까지 기다린다.
   * @param {(entry: object) => boolean} predicate
   * @param {{timeoutMs: number, what: string, intervalMs?: number, from?: number}} options from은 이 번호부터 찾는다
   */
  async waitFor(predicate, { timeoutMs, what, intervalMs = 50, from = 0 }) {
    if (!(timeoutMs > 0)) {
      throw new RangeError("기다림에는 양수 기한이 있어야 한다(SEC-05)");
    }
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      this.poll();
      for (let i = from; i < this.#entries.length; i += 1) {
        if (predicate(this.#entries[i])) {
          return this.#entries[i];
        }
      }
      if (Date.now() >= deadline) {
        throw new TailTimeoutError(what, timeoutMs);
      }
      await this.#sleep(Math.min(intervalMs, Math.max(1, deadline - Date.now())));
    }
  }
}
