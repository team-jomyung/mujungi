// 시험 도구 출력 · 비밀 가리기 (U1 3.5 Step 45 · BR8.7 · SD-15 · NFR9.8 · J144 · SEC-06)
//
// - Redactor: 실행 중 본 비밀 값(기기 토큰 · bootstrap 비밀 · 세션 쿠키 · CSRF 토큰 · 등록 코드 · 전사 원문)을 등록해 두고,
//   도구가 쓰는 모든 출력 줄에서 그 값을 [REDACTED]로 바꾼다. 도구는 처음부터 값을 출력하지 않게 짜고, 이것은 두 번째 방어선이다.
// - JSON 한 줄 기록(jsonLine): 가짜 공급자 · Nginx 대역이 stdout에 남기는 기록 형식. 키 순서는 넣은 순서다.
// - 진행 줄(StepWriter): C안 시나리오가 표준 출력에 단계마다 한 줄만 쓴다(엔진 1 MiB 제한, J134).

export const REDACTED = "[REDACTED]";
/** 이보다 짧은 값은 등록하지 않는다(짧은 값을 가리면 정상 출력이 망가진다). */
export const MIN_SECRET_LENGTH = 6;

export class Redactor {
  #values = new Set();

  /** 값을 등록한다. 짧거나 빈 값은 무시하고 거짓을 돌려준다. */
  add(value) {
    if (typeof value !== "string" || value.length < MIN_SECRET_LENGTH) {
      return false;
    }
    this.#values.add(value);
    return true;
  }

  get size() {
    return this.#values.size;
  }

  /** 문자열 안의 등록 값을 모두 가린다(긴 값부터 바꿔 부분 겹침을 막는다). */
  redact(text) {
    let out = String(text);
    const values = [...this.#values].sort((a, b) => b.length - a.length);
    for (const value of values) {
      if (out.includes(value)) {
        out = out.split(value).join(REDACTED);
      }
    }
    return out;
  }

  toString() {
    return `Redactor[${this.#values.size}개]`;
  }
}

/** 한 줄 JSON. 줄바꿈은 JSON.stringify가 이스케이프한다. */
export function jsonLine(object) {
  return `${JSON.stringify(object)}\n`;
}

/** 쓰기 대상(스트림 · write 함수)을 감싸 줄 단위로 가려서 쓴다. */
export function createLineWriter(target, redactor = null) {
  const write = typeof target === "function" ? target : (text) => target.write(text);
  return (line) => {
    const text = line.endsWith("\n") ? line : `${line}\n`;
    write(redactor ? redactor.redact(text) : text);
  };
}

/** 초 단위 소수 한 자리. */
export function formatSeconds(ms) {
  return `${(ms / 1000).toFixed(1)}s`;
}
