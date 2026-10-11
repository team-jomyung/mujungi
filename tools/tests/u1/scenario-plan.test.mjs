// C안 시나리오 실행기 시험 (U1 3.5 Step 46 · 계획 ## 검증 명령(C안) C1 ~ C12 · J144 · SEC-05 · SEC-06 · BR8.7 · SD-15)
// C1 ~ C12 순서, 실패하면 비0 종료, 대기 기한을 넘으면 단계 id와 종료 코드 1, 비밀 값을 출력에 쓰지 않음을 확인한다.
// 실제 스택 대신 루프백 http 대역 서버(무작위 포트)와 임시 파일을 쓴다. 끝-끝 통과 자체는 검증 명령 C(Step 49 · 51)가 본다.
import { mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { JsonlTail, TailTimeoutError, parseJsonLine } from "../../lib/jsonlTail.mjs";
import { REDACTED, Redactor, createLineWriter, jsonLine } from "../../lib/log.mjs";
import {
  StepFailure,
  StepTimeout,
  expectCookieAttributes,
  missingAttrs,
  LATENCY_TURN_KEYS,
} from "../../skeleton-e2e/assertions.mjs";
import {
  computeDeadlines,
  flattenYaml,
  loadDeadlines,
  parseDurationMs,
} from "../../skeleton-e2e/deadlines.mjs";
import {
  SecretBook,
  USAGE,
  main,
  parseArgs,
  resolveOptions,
  runSql,
  runSteps,
} from "../../skeleton-e2e/run-c.mjs";
import { STEPS, STEP_IDS } from "../../skeleton-e2e/steps.mjs";
import { parseSetCookie } from "../../lib/cookieJar.mjs";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..", "..");

let tmp;
beforeEach(() => {
  tmp = mkdtempSync(path.join(os.tmpdir(), "mujoong-runc-"));
});
afterEach(() => {
  rmSync(tmp, { recursive: true, force: true });
});

function collector() {
  const lines = [];
  return { lines, write: (text) => lines.push(text), text: () => lines.join("") };
}

describe("단계 계획(C1 ~ C12)", () => {
  it("단계는 C1 ~ C12 순서이고 모두 실행 함수 · 양수 기한이 있다", () => {
    expect(STEP_IDS).toEqual([
      "C1",
      "C2",
      "C3",
      "C4",
      "C5",
      "C6",
      "C7",
      "C8",
      "C9",
      "C10",
      "C11",
      "C12",
    ]);
    const deadlines = loadDeadlines(repoRoot);
    for (const step of STEPS) {
      expect(typeof step.run, step.id).toBe("function");
      expect(step.title.length, step.id).toBeGreaterThan(0);
      expect(step.budget(deadlines), step.id).toBeGreaterThan(0);
    }
    expect(USAGE).toContain("C12");
    expect(Object.isFrozen(STEPS)).toBe(true);
  });

  it("기한은 application.yml 시간 키로 계산하고 계획 값(REGISTERED 30 · STABLE 90 · ACTIVE 30 · 턴 30 · ENDED 60초)과 같다", () => {
    const deadlines = loadDeadlines(repoRoot);
    expect(deadlines).toMatchObject({
      registered: 30_000,
      stable: 90_000,
      active: 30_000,
      turn: 30_000,
      ended: 60_000,
    });
    expect(deadlines.request).toBeGreaterThan(0);
    expect(deadlines.settle).toBeGreaterThan(800);
  });

  it("시간 키가 빠지거나 형식이 틀리면 기한을 만들지 않는다", () => {
    const realtime = flattenYaml(
      readFileSync(
        path.join(repoRoot, "backend/realtime-activity/src/main/resources/application.yml"),
        "utf8",
      ),
    );
    const core = flattenYaml(
      readFileSync(
        path.join(repoRoot, "backend/core-api/src/main/resources/application.yml"),
        "utf8",
      ),
    );
    const missing = new Map(realtime);
    missing.delete("mujoong.timing.session.closing-confirm");
    expect(() => computeDeadlines(missing, core)).toThrow(/closing-confirm/);
    const malformed = new Map(realtime);
    malformed.set("mujoong.timing.device.disconnect", "forty");
    expect(() => computeDeadlines(malformed, core)).toThrow(RangeError);
    expect(parseDurationMs("800ms")).toBe(800);
    expect(parseDurationMs("2m")).toBe(120_000);
    expect(() => parseDurationMs("5 s")).toThrow(RangeError);
  });

  it("YAML 펴기는 들여쓰기 · 주석 · 따옴표 · 목록 줄을 다룬다", () => {
    const map = flattenYaml(
      ["a:", "  b: 5s # 주석", '  "c": "x#y"', "  list:", "    - 1", "d: 'q'", "# 전체 주석"].join(
        "\n",
      ),
    );
    expect(map.get("a.b")).toBe("5s");
    expect(map.get("a.c")).toBe("x#y");
    expect(map.get("d")).toBe("q");
    expect(map.has("a.list")).toBe(false);
  });
});

describe("단계 실행(runSteps · SEC-05)", () => {
  const deadlines = { request: 1000 };
  const step = (id, run, budgetMs = 500) => ({
    id,
    title: `단계 ${id}`,
    budget: () => budgetMs,
    run,
  });

  it("모두 통과하면 단계마다 한 줄 PASS를 순서대로 쓰고 종료 코드 0이다", async () => {
    const out = collector();
    const order = [];
    const steps = ["C1", "C2", "C3"].map((id) => step(id, async () => order.push(id)));
    const outcome = await runSteps(steps, { deadlines }, { write: out.write });
    expect(outcome.exitCode).toBe(0);
    expect(order).toEqual(["C1", "C2", "C3"]);
    expect(out.lines.map((line) => line.split(" ").slice(0, 2).join(" "))).toEqual([
      "C1 PASS",
      "C2 PASS",
      "C3 PASS",
    ]);
  });

  it("단언이 실패하면 그 단계 id로 FAIL을 쓰고 뒤 단계를 돌리지 않으며 종료 코드 1이다", async () => {
    const out = collector();
    const ran = [];
    const steps = [
      step("C1", async () => ran.push("C1")),
      step("C2", async () => {
        throw new StepFailure("기대 201, 실제 500");
      }),
      step("C3", async () => ran.push("C3")),
    ];
    const outcome = await runSteps(steps, { deadlines }, { write: out.write });
    expect(outcome).toMatchObject({ exitCode: 1, failedStep: "C2" });
    expect(ran).toEqual(["C1"]);
    expect(out.lines[1]).toMatch(/^C2 FAIL \d+\.\ds 기대 201, 실제 500$/);
    expect(outcome.results.map((result) => result.status)).toEqual(["PASS", "FAIL"]);
  });

  it("단계가 기한 안에 끝나지 않으면 TIMEOUT과 단계 id, 종료 코드 1이다(끝나지 않는 대기라도)", async () => {
    const out = collector();
    let aborted = false;
    const steps = [
      step("C1", async () => {}),
      step(
        "C2",
        (ctx) =>
          new Promise(() => {
            ctx.signal.addEventListener("abort", () => {
              aborted = true;
            });
          }),
        120,
      ),
    ];
    const started = Date.now();
    const outcome = await runSteps(steps, { deadlines }, { write: out.write });
    expect(Date.now() - started).toBeLessThan(2000);
    expect(outcome).toMatchObject({ exitCode: 1, failedStep: "C2" });
    expect(out.lines[1]).toMatch(/^C2 TIMEOUT /);
    expect(aborted).toBe(true);
  });

  it("전체 기한(--total-timeout)이 단계 기한보다 짧으면 전체 기한이 이기고, 다 쓰면 다음 단계를 시작하지 않는다", async () => {
    const out = collector();
    let now = 0;
    const steps = [
      step(
        "C1",
        async () => {
          now += 900;
        },
        10_000,
      ),
      step(
        "C2",
        async () => {
          now += 200;
        },
        10_000,
      ),
      step("C3", async () => {}, 10_000),
    ];
    const outcome = await runSteps(
      steps,
      { deadlines },
      { write: out.write, totalTimeoutMs: 1000, now: () => now },
    );
    // C2는 남은 100ms 기한으로 돌고(가짜 시계라 통과), C3는 남은 기한이 0이라 시작하지 않는다
    expect(outcome).toMatchObject({ exitCode: 1, failedStep: "C3" });
    expect(out.lines[2]).toBe("C3 TIMEOUT 0.0s 전체 기한(--total-timeout) 소진");
  });

  it("단계 기한에 깎인 대기의 제한 시간 초과는 TIMEOUT이고, 깎이지 않은 요청 제한 시간 초과는 FAIL이다(J393)", async () => {
    const timeoutError = () => {
      const error = new Error("GET /x 응답이 오지 않았다");
      error.name = "HttpTimeoutError";
      return error;
    };
    // 깎임: 단계 기한 300ms가 요청 제한 60초를 깎았다 → 그 요청의 제한 시간 초과는 단계 기한 초과
    const bound = collector();
    const boundOutcome = await runSteps(
      [
        step(
          "C1",
          async (ctx) => {
            ctx.limit(60_000);
            throw timeoutError();
          },
          300,
        ),
      ],
      { deadlines },
      { write: bound.write },
    );
    expect(boundOutcome).toMatchObject({ exitCode: 1, failedStep: "C1" });
    expect(bound.lines[0]).toMatch(/^C1 TIMEOUT .*단계 기한 .*300ms 기한 초과 \(GET \/x 응답이 오지 않았다\)/);
    // 전체 기한이 깎은 경우는 그 이름으로
    const total = collector();
    let now = 0;
    const totalOutcome = await runSteps(
      [
        step(
          "C1",
          async (ctx) => {
            ctx.limit(60_000);
            throw timeoutError();
          },
          10_000,
        ),
      ],
      { deadlines },
      { write: total.write, totalTimeoutMs: 1000, now: () => now },
    );
    expect(totalOutcome.exitCode).toBe(1);
    expect(total.lines[0]).toMatch(/^C1 TIMEOUT .*전체 기한\(--total-timeout\) — 1000ms 기한 초과/);
    // 깎이지 않음: 요청 제한 100ms가 단계 기한 300ms 안에 끝났다 → 요청 자체의 실패
    const plain = collector();
    const plainOutcome = await runSteps(
      [
        step(
          "C1",
          async (ctx) => {
            ctx.limit(100);
            throw timeoutError();
          },
          300,
        ),
      ],
      { deadlines },
      { write: plain.write },
    );
    expect(plainOutcome.exitCode).toBe(1);
    expect(plain.lines[0]).toMatch(/^C1 FAIL .*예외 HttpTimeoutError/);
  });

  it("ctx.limit은 단계 남은 시간을 넘지 않고, 예기치 못한 예외도 FAIL(종료 코드 1)이다", async () => {
    const out = collector();
    let limited = null;
    const steps = [
      step(
        "C1",
        async (ctx) => {
          limited = ctx.limit(60_000);
          throw new TypeError("예상 밖");
        },
        300,
      ),
    ];
    const outcome = await runSteps(steps, { deadlines }, { write: out.write });
    expect(limited).toBeGreaterThan(0);
    expect(limited).toBeLessThanOrEqual(300);
    expect(outcome.exitCode).toBe(1);
    expect(out.lines[0]).toContain("예외 TypeError: 예상 밖");
  });
});

describe("비밀 값 다루기(BR8.7 · SD-15 · B1 ③)", () => {
  it("출력 줄은 등록된 비밀을 [REDACTED]로 바꾼다(실패 메시지에 섞여도)", async () => {
    const redactor = new Redactor();
    const out = collector();
    const write = createLineWriter(out.write, redactor);
    redactor.add("device-token-value-123");
    const outcome = await runSteps(
      [
        {
          id: "C4",
          title: "claim",
          budget: () => 500,
          run: async () => {
            throw new StepFailure("토큰 device-token-value-123 불일치");
          },
        },
      ],
      { deadlines: { request: 100 } },
      { write },
    );
    expect(outcome.exitCode).toBe(1);
    expect(out.text()).not.toContain("device-token-value-123");
    expect(out.text()).toContain(REDACTED);
    expect(redactor.add("short")).toBe(false);
    expect(String(redactor)).toBe("Redactor[1개]");
  });

  it("비밀 목록 파일은 0600이고 빈 값 · 줄바꿈 값 · 중복을 쓰지 않으며 토큰은 SHA-256 두 형식도 넣는다", () => {
    const file = path.join(tmp, "c", "secrets.list");
    const redactor = new Redactor();
    const book = new SecretBook(file, redactor);
    expect(book.addToken("기기 토큰", "tok-AAAA")).toBe(3);
    expect(book.addToken("같은 값", "tok-AAAA")).toBe(0);
    expect(book.add("")).toBe(0);
    expect(book.add("a\nb")).toBe(0);
    expect(book.addCode("ABCD2345")).toBe(3);
    expect(book.addCode("short")).toBe(0);
    book.close();
    const lines = readFileSync(file, "utf8")
      .split("\n")
      .filter((line) => line.length > 0);
    expect((statSync(file).mode & 0o777).toString(8)).toBe("600");
    expect(lines).toEqual(
      expect.arrayContaining(["tok-AAAA", "ABCD2345", "ABCD 2345", "ABCD-2345"]),
    );
    expect(lines).toHaveLength(6);
    expect(readFileSync(file, "utf8").includes("\n\n")).toBe(false);
    expect(redactor.redact("x ABCD-2345 y")).toBe(`x ${REDACTED} y`);
  });

  it("쿠키 속성 단언은 값 대신 이름 · 속성만 메시지에 쓴다(SD-5)", () => {
    const cookie = parseSetCookie(
      "MUJOONG_DEVICE=very-secret-token; Path=/; Secure; SameSite=Strict; Max-Age=10",
    );
    const error = (() => {
      try {
        expectCookieAttributes(
          cookie,
          { sameSite: "Strict", path: "/", maxAgeSeconds: 10 },
          "claim",
        );
        return null;
      } catch (e) {
        return e;
      }
    })();
    expect(error).toBeInstanceOf(StepFailure);
    expect(error.message).toContain("HttpOnly");
    expect(error.message).not.toContain("very-secret-token");
    const withDomain = parseSetCookie(
      "MUJOONG_DEVICE=v; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=10; Domain=localhost",
    );
    expect(() =>
      expectCookieAttributes(
        withDomain,
        { sameSite: "Strict", path: "/", maxAgeSeconds: 10 },
        "claim",
      ),
    ).toThrow(/Domain/);
  });
});

describe("CLI(main) 종료 코드", () => {
  let server;
  let behaviour;
  let baseUrl;
  const BOOTSTRAP_SECRET = "bootstrap-secret-value-xyz";

  beforeEach(async () => {
    behaviour = "c1-cookie-without-httponly";
    server = http.createServer((req, res) => {
      const send = (status, body, headers = {}) => {
        res.writeHead(status, {
          "content-type": "application/json",
          "x-trace-id": "0123456789abcdef0123456789abcdef",
          ...headers,
        });
        res.end(JSON.stringify(body));
      };
      if (behaviour === "hang") {
        return;
      }
      if (req.url === "/api/device/me") {
        send(401, {
          code: "DEVICE_NOT_REGISTERED",
          message: "등록되지 않은 기기예요.",
          traceId: "0123456789abcdef0123456789abcdef",
        });
      } else if (req.url === "/api/device/bootstrap/status") {
        send(401, {
          code: "BOOTSTRAP_NOT_FOUND",
          message: "등록 정보를 찾을 수 없어요.",
          traceId: "0123456789abcdef0123456789abcdef",
        });
      } else if (req.url === "/api/device/bootstrap" && req.method === "POST") {
        send(
          201,
          { code: "ABCD2345", expiresAt: new Date(Date.now() + 86_400_000).toISOString() },
          // HttpOnly가 빠진 쿠키 → C1 단언 실패
          {
            "set-cookie": [
              `MUJOONG_DEVICE_BOOTSTRAP=${BOOTSTRAP_SECRET}; Path=/api/device; Max-Age=86400; Secure; SameSite=Strict`,
            ],
          },
        );
      } else {
        send(404, {
          code: "NOT_FOUND",
          message: "찾을 수 없어요.",
          traceId: "0123456789abcdef0123456789abcdef",
        });
      }
    });
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    baseUrl = `http://127.0.0.1:${server.address().port}`;
  });

  afterEach(async () => {
    server.closeAllConnections();
    await new Promise((resolve) => server.close(resolve));
  });

  function files() {
    const record = path.join(tmp, "provider.jsonl");
    const log = path.join(tmp, "realtime.log");
    const sql = path.join(tmp, "sql.sh");
    writeFileSync(record, "");
    writeFileSync(log, "");
    writeFileSync(sql, "#!/usr/bin/env bash\nexit 0\n", { mode: 0o700 });
    return {
      args: [
        "--base-url",
        baseUrl,
        "--provider-record",
        record,
        "--realtime-log",
        log,
        "--sql-cmd",
        sql,
        "--secrets-file",
        path.join(tmp, "c", "secrets.list"),
        "--out",
        path.join(tmp, "c", "result.json"),
      ],
      secrets: path.join(tmp, "c", "secrets.list"),
      result: path.join(tmp, "c", "result.json"),
    };
  }

  function io() {
    const out = collector();
    const err = collector();
    return { out, err, io: { stdout: { write: out.write }, stderr: { write: err.write } } };
  }

  it("--help는 사용법(단계 · 종료 코드)을 보이고 0이다", async () => {
    const { out, io: streams } = io();
    expect(await main(["--help"], streams)).toBe(0);
    expect(out.text()).toContain("C1");
    expect(out.text()).toContain("종료 코드: 0 통과, 1 단언 실패 · 기한 초과, 2 인자 · 전제 누락");
  });

  it("인자 · 전제 누락은 종료 코드 2이고 통과로 보고하지 않는다", async () => {
    const { out, err, io: streams } = io();
    expect(await main(["--bogus"], streams)).toBe(2);
    expect(await main([], streams)).toBe(2);
    expect(
      await main(
        ["--provider-record", path.join(tmp, "none"), "--realtime-log", "x", "--sql-cmd", "y"],
        streams,
      ),
    ).toBe(2);
    expect(await main([...files().args, "--total-timeout", "0"], streams)).toBe(2);
    expect(
      await main(
        [
          ...files().args,
          "--ca",
          path.join(tmp, "missing.crt"),
          "--base-url",
          "https://localhost:1",
        ],
        streams,
      ),
    ).toBe(2);
    expect(err.text()).toContain("--help");
    expect(out.text()).not.toMatch(/PASS/);
    expect(() => parseArgs(["--out"])).toThrow(/값이 없다/);
    expect(() =>
      resolveOptions({ providerRecord: "a", realtimeLog: "b", sqlCmd: "c", baseUrl: "ftp://x" }),
    ).toThrow(/https/);
  });

  it("C1 단언이 실패하면 'C1 FAIL'과 요약 FAIL, 종료 코드 1이고 본 비밀은 출력 대신 0600 목록에만 있다", async () => {
    const { out, err, io: streams } = io();
    const { args, secrets, result } = files();
    const code = await main(args, streams);
    expect(code).toBe(1);
    const lines = out.text().trim().split("\n");
    expect(lines[0]).toMatch(/^C1 FAIL .*HttpOnly/);
    expect(lines.at(-1)).toMatch(/^C FAIL C1 \(0\/12 통과\)/);
    expect(lines).toHaveLength(2);
    const printed = out.text() + err.text();
    expect(printed).not.toContain(BOOTSTRAP_SECRET);
    expect(printed).not.toContain("ABCD2345");
    const listed = readFileSync(secrets, "utf8");
    expect(listed).toContain(BOOTSTRAP_SECRET);
    expect(listed).toContain("ABCD2345");
    expect(listed).toContain("ABCD 2345");
    expect((statSync(secrets).mode & 0o777).toString(8)).toBe("600");
    const summary = JSON.parse(readFileSync(result, "utf8"));
    expect(summary).toMatchObject({ status: "FAIL", failedStep: "C1" });
    expect(JSON.stringify(summary)).not.toContain(BOOTSTRAP_SECRET);
  });

  it("응답이 오지 않으면 전체 기한 안에 'C1 TIMEOUT', 종료 코드 1이다", async () => {
    behaviour = "hang";
    const { out, io: streams } = io();
    const started = Date.now();
    const code = await main([...files().args, "--total-timeout", "1"], streams);
    expect(code).toBe(1);
    expect(Date.now() - started).toBeLessThan(5000);
    expect(out.text()).toMatch(/^C1 TIMEOUT /);
    expect(out.text()).toMatch(/C FAIL C1/);
  });
});

describe("SQL 래퍼 · 기록 따라 읽기", () => {
  it("SQL 래퍼 출력은 '|' 구분 행이고, 실패는 가린 첫 줄 · 시간 초과는 죽이고 오류다", async () => {
    const ok = path.join(tmp, "ok.sh");
    writeFileSync(ok, 'cat > /dev/null; [ "$1" = realtime ] && echo "f|abc" && echo "t|def"\n');
    expect(await runSql(ok, "realtime", "SELECT 1;")).toEqual([
      ["f", "abc"],
      ["t", "def"],
    ]);

    const failing = path.join(tmp, "fail.sh");
    writeFileSync(
      failing,
      'cat > /dev/null; echo "FATAL password authentication failed pw-secret-123" >&2; exit 3\n',
    );
    const redactor = new Redactor();
    redactor.add("pw-secret-123");
    const error = await runSql(failing, "core", "SELECT 1;", { redactor }).catch((e) => e);
    expect(error.message).toContain("종료 코드 3");
    expect(error.message).not.toContain("pw-secret-123");

    const slow = path.join(tmp, "slow.sh");
    writeFileSync(slow, "sleep 5\n");
    const started = Date.now();
    await expect(runSql(slow, "core", "SELECT 1;", { timeoutMs: 200 })).rejects.toThrow(/200ms/);
    expect(Date.now() - started).toBeLessThan(3000);
  });

  it("JSON 줄 따라 읽기는 접두가 붙은 줄도 읽고, 파일이 새로 쓰이면 처음부터 다시 읽는다", async () => {
    const file = path.join(tmp, "log.jsonl");
    const tail = new JsonlTail(file);
    expect(tail.poll()).toBe(0);
    writeFileSync(
      file,
      `fake-provider  | ${jsonLine({ ev: "listening" }).trim()}\nnot json\n{"ev":"par`,
    );
    expect(tail.poll()).toBe(1);
    writeFileSync(file, `${readFileSync(file, "utf8")}tial"}\n`);
    expect(tail.poll()).toBe(1);
    expect(tail.entries.map((entry) => entry.ev)).toEqual(["listening", "partial"]);
    writeFileSync(file, '{"ev":"restarted"}\n');
    tail.poll();
    expect(tail.entries.at(-1).ev).toBe("restarted");
    expect(parseJsonLine("[1,2]")).toBeNull();
  });

  it("기록 대기는 양수 기한이 있어야 하고, 넘으면 TailTimeoutError다(SEC-05)", async () => {
    const file = path.join(tmp, "realtime.log");
    writeFileSync(file, `${jsonLine({ event: "LATENCY_TURN", attrs: { tSpeechEnd: "x" } })}`);
    const tail = new JsonlTail(file);
    await expect(tail.waitFor(() => true, { timeoutMs: 0, what: "x" })).rejects.toBeInstanceOf(
      RangeError,
    );
    await expect(
      tail.waitFor((e) => e.event === "NEVER", { timeoutMs: 100, what: "없는 줄" }),
    ).rejects.toBeInstanceOf(TailTimeoutError);
    const latency = await tail.waitFor((e) => e.event === "LATENCY_TURN", {
      timeoutMs: 100,
      what: "LATENCY_TURN",
    });
    expect(missingAttrs(latency, LATENCY_TURN_KEYS)).toHaveLength(LATENCY_TURN_KEYS.length - 1);
    expect(new StepTimeout("x", 5)).toBeInstanceOf(Error);
  });
});
