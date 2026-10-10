#!/usr/bin/env node
// C안 끝-끝 시나리오 실행기 — 기기 시뮬레이터 한 턴 (U1 3.5 Step 45 · 계획 ## 검증 명령(C안) C 1 ~ 12 · J103 · J131 · J143 · J144)
//
// scripts/verify/c-skeleton-turn.sh가 부른다(compose · host 두 모드 공통). 실행 중 스택(Nginx 또는 host 모드 대역 tools/dev-edge)에
// https로 붙어 D0 → 시험 로그인 → 05a → claim → 켜기 → 04 안정 → 08 → ACTIVE → 음성 1턴 → 게이트 경유 → P1 → ENDED를 차례로 단언한다.
//   - 표준 출력에는 단계마다 한 줄(C<n> PASS|FAIL|TIMEOUT 소요 시간 설명)과 끝에 요약 한 줄만 쓴다(엔진 1 MiB 제한, J134).
//   - 모든 대기에 기한이 있다(SEC-05). 넘으면 단계 id와 TIMEOUT을 쓰고 종료 코드 1이다. 기한은 application.yml 시간 키로 계산한다.
//   - 본 비밀 값은 --secrets-file(기본 build/verify/c/secrets.list, 0600)에만 적고 출력하지 않는다(B1 ③ 로그 위생 대조 목록).
//   - 변경 요청에는 허용 Origin을 붙이고(F4), 자체 서명 CA는 ca 옵션으로만 쓴다(TLS 검증을 끄지 않음, 프록시 없음).
//
// 종료 코드: 0 = C1 ~ C12 통과, 1 = 단언 실패 · 기한 초과, 2 = 인자 · 전제 누락(통과로 보고하지 않음).
import { spawn } from "node:child_process";
import { closeSync, existsSync, fchmodSync, fsyncSync, mkdirSync, openSync, readFileSync, statSync, writeFileSync, writeSync } from "node:fs";
import path from "node:path";
import { createHash } from "node:crypto";
import { fileURLToPath, pathToFileURL } from "node:url";
import { CookieJar } from "../lib/cookieJar.mjs";
import { createHttpClient } from "../lib/httpClient.mjs";
import { JsonlTail } from "../lib/jsonlTail.mjs";
import { Redactor, createLineWriter, formatSeconds } from "../lib/log.mjs";
import { StepFailure, StepTimeout } from "./assertions.mjs";
import { loadDeadlines } from "./deadlines.mjs";
import { STEPS } from "./steps.mjs";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
export const DEFAULT_REPO_ROOT = path.resolve(scriptDir, "..", "..");

export const USAGE = `C안 끝-끝 시나리오(기기 시뮬레이터 한 턴, 가짜 공급자)

쓰는 법: node tools/skeleton-e2e/run-c.mjs --provider-record <파일> --realtime-log <파일> --sql-cmd <실행 파일> [옵션]

필수
  --provider-record <파일>  가짜 공급자 기록(JSON 줄). host 모드는 그 프로세스 stdout 파일,
                            compose 모드는 'docker compose logs -f --no-log-prefix fake-provider'를 받은 파일
  --realtime-log <파일>     realtime 로그(JSON 줄, LATENCY_TURN · SESSION_ITEMS_SERVED 확인)
  --sql-cmd <실행 파일>     '<실행 파일> core|realtime'으로 불러 표준 입력의 SQL을 그 앱 역할로 실행하고
                            psql -At -F'|' 꼴로 출력하는 래퍼(.sh면 bash로 부른다). 비밀번호는 래퍼가 파일로만 다룬다

선택
  --base-url <url>          스택 입구 (기본 https://localhost:8443)
  --origin <origin>         허용 Origin (기본 base-url의 origin)
  --evil-origin <origin>    거부돼야 하는 Origin (기본 https://evil.invalid)
  --ca <파일>               자체 서명 CA PEM (https 기본 <저장소>/build/verify/certs/ca.crt)
  --secrets-file <파일>     본 비밀 값 목록, 0600 (기본 <저장소>/build/verify/c/secrets.list, 덧붙여 씀)
  --out <파일>              단계 결과 · 측정값 JSON (기본 <저장소>/build/verify/c/result.json, 비밀 없음)
  --repo-root <디렉터리>    application.yml을 읽을 저장소 루트 (기본 이 스크립트의 두 단계 위)
  --total-timeout <초>      전체 기한(엔진 모드 등). 단계 기한보다 짧으면 이 값이 이긴다
  --help                    이 설명

단계(모두 기한 있음, application.yml 시간 키로 계산 — tools/skeleton-e2e/deadlines.mjs)
${STEPS.map((step) => `  ${step.id.padEnd(4)} ${step.title}`).join("\n")}

출력: 단계마다 한 줄 'C<n> PASS|FAIL|TIMEOUT <초> <설명>', 끝에 'C PASS|FAIL ...' 한 줄
종료 코드: 0 통과, 1 단언 실패 · 기한 초과, 2 인자 · 전제 누락
`;

/** 인자 오류 · 전제 누락(종료 코드 2). */
export class UsageError extends Error {
  constructor(message) {
    super(message);
    this.name = "UsageError";
  }
}

/** 인자를 읽는다. */
export function parseArgs(argv) {
  const options = {};
  const keys = {
    "--base-url": "baseUrl",
    "--origin": "origin",
    "--evil-origin": "evilOrigin",
    "--ca": "ca",
    "--provider-record": "providerRecord",
    "--realtime-log": "realtimeLog",
    "--sql-cmd": "sqlCmd",
    "--secrets-file": "secretsFile",
    "--out": "out",
    "--repo-root": "repoRoot",
    "--total-timeout": "totalTimeout",
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--help" || arg === "-h") {
      options.help = true;
      continue;
    }
    const key = keys[arg];
    if (!key) {
      throw new UsageError(`모르는 인자: ${arg}`);
    }
    const value = argv[i + 1];
    if (value === undefined || value.startsWith("--")) {
      throw new UsageError(`${arg}에 값이 없다`);
    }
    options[key] = value;
    i += 1;
  }
  return options;
}

/** 인자를 검사해 실행 설정을 만든다. 틀리면 UsageError. */
export function resolveOptions(raw) {
  const repoRoot = path.resolve(raw.repoRoot ?? DEFAULT_REPO_ROOT);
  const baseUrl = new URL(raw.baseUrl ?? "https://localhost:8443");
  if (baseUrl.protocol !== "https:" && baseUrl.protocol !== "http:") {
    throw new UsageError("--base-url은 https:// 또는 http://여야 한다");
  }
  for (const required of ["providerRecord", "realtimeLog", "sqlCmd"]) {
    if (!raw[required]) {
      throw new UsageError(`--${required.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`)}가 필요하다`);
    }
  }
  const providerRecord = path.resolve(raw.providerRecord);
  const realtimeLog = path.resolve(raw.realtimeLog);
  const sqlCmd = path.resolve(raw.sqlCmd);
  for (const [label, file] of [
    ["--provider-record", providerRecord],
    ["--realtime-log", realtimeLog],
    ["--sql-cmd", sqlCmd],
  ]) {
    if (!existsSync(file)) {
      throw new UsageError(`${label} 파일이 없다: ${file}`);
    }
  }
  let ca = null;
  if (baseUrl.protocol === "https:") {
    const caPath = path.resolve(raw.ca ?? path.join(repoRoot, "build", "verify", "certs", "ca.crt"));
    if (!existsSync(caPath)) {
      throw new UsageError(`CA 파일이 없다: ${caPath}`);
    }
    ca = readFileSync(caPath);
  }
  let totalTimeoutMs = null;
  if (raw.totalTimeout !== undefined) {
    if (!/^[0-9]+$/.test(raw.totalTimeout) || Number(raw.totalTimeout) <= 0) {
      throw new UsageError("--total-timeout은 양의 정수(초)여야 한다");
    }
    totalTimeoutMs = Number(raw.totalTimeout) * 1000;
  }
  const origin = raw.origin ?? baseUrl.origin;
  const wsUrl = `${baseUrl.protocol === "https:" ? "wss:" : "ws:"}//${baseUrl.host}/ws/device`;
  return {
    repoRoot,
    baseUrl: baseUrl.origin,
    origin,
    evilOrigin: raw.evilOrigin ?? "https://evil.invalid",
    wsUrl,
    ca,
    providerRecord,
    realtimeLog,
    sqlCmd,
    secretsFile: path.resolve(raw.secretsFile ?? path.join(repoRoot, "build", "verify", "c", "secrets.list")),
    out: path.resolve(raw.out ?? path.join(repoRoot, "build", "verify", "c", "result.json")),
    totalTimeoutMs,
  };
}

/**
 * 본 비밀 값 목록(B1 ③). 한 줄에 값 하나, 파일은 0600, 덧붙여 쓴다(gen-env 값과 한 파일). 빈 값 · 줄바꿈 값은 쓰지 않는다
 * (grep -F -f에서 빈 줄은 모든 줄과 맞는다). 같은 값은 한 번만. 등록한 값은 Redactor로도 가린다.
 */
export class SecretBook {
  #fd = null;
  #seen = new Set();

  constructor(file, redactor) {
    this.file = file;
    this.redactor = redactor;
  }

  #open() {
    if (this.#fd === null) {
      mkdirSync(path.dirname(this.file), { recursive: true, mode: 0o700 });
      this.#fd = openSync(this.file, "a", 0o600);
      fchmodSync(this.#fd, 0o600);
    }
    return this.#fd;
  }

  /** 값 하나. 쓴 줄 수(0 또는 1). */
  add(value) {
    if (typeof value !== "string" || value.length === 0 || /[\r\n]/.test(value) || this.#seen.has(value)) {
      return 0;
    }
    this.#seen.add(value);
    this.redactor.add(value);
    const fd = this.#open();
    writeSync(fd, `${value}\n`);
    fsyncSync(fd);
    return 1;
  }

  /** 쿠키 · 토큰: 원값과 SHA-256(16진 · base64url). */
  addToken(_label, value) {
    if (typeof value !== "string" || value.length === 0) {
      return 0;
    }
    const digest = createHash("sha256").update(value, "utf8").digest();
    return this.add(value) + this.add(digest.toString("hex")) + this.add(digest.toString("base64url"));
  }

  /** Spring Session 쿠키(세션 id의 base64)를 풀어 세션 id도 넣는다. */
  addDecodedSessionId(cookieValue) {
    try {
      const decoded = Buffer.from(cookieValue, "base64").toString("utf8");
      if (/^[0-9a-fA-F-]{16,64}$/.test(decoded)) {
        return this.add(decoded);
      }
    } catch {
      // 풀리지 않으면 원값만으로 충분하다
    }
    return 0;
  }

  /** 등록 코드 8자와 4 · 4 표시형(공백 · 하이픈). */
  addCode(code) {
    if (typeof code !== "string" || code.length !== 8) {
      return 0;
    }
    return this.add(code) + this.add(`${code.slice(0, 4)} ${code.slice(4)}`) + this.add(`${code.slice(0, 4)}-${code.slice(4)}`);
  }

  /** 전사 · AI 말 원문. */
  addText(text) {
    return this.add(text);
  }

  get count() {
    return this.#seen.size;
  }

  close() {
    if (this.#fd !== null) {
      closeSync(this.#fd);
      this.#fd = null;
    }
  }
}

/**
 * --sql-cmd 래퍼로 SQL 한 문장을 실행한다. 출력은 psql -At -F'|' 꼴(줄마다 '|' 구분). 제한 시간을 넘으면 죽이고 오류.
 * @returns {Promise<string[][]>}
 */
export function runSql(command, role, sql, { timeoutMs = 10_000, redactor = null } = {}) {
  return new Promise((resolve, reject) => {
    const useBash = command.endsWith(".sh");
    const child = spawn(useBash ? "bash" : command, useBash ? [command, role] : [role], { stdio: ["pipe", "pipe", "pipe"] });
    const out = [];
    const err = [];
    let done = false;
    const finish = (fn, value) => {
      if (!done) {
        done = true;
        clearTimeout(timer);
        fn(value);
      }
    };
    const timer = setTimeout(() => {
      child.kill("SIGKILL");
      finish(reject, new Error(`SQL 명령이 ${timeoutMs}ms 안에 끝나지 않았다`));
    }, timeoutMs);
    child.stdout.on("data", (chunk) => out.push(chunk));
    child.stderr.on("data", (chunk) => err.push(chunk));
    child.on("error", (error) => finish(reject, new Error(`SQL 명령을 실행하지 못했다(${error.code ?? error.name})`)));
    child.on("close", (code, signal) => {
      if (code !== 0) {
        const first = Buffer.concat(err).toString("utf8").split("\n")[0].slice(0, 200);
        const text = redactor ? redactor.redact(first) : first;
        finish(reject, new Error(`SQL 명령 실패(종료 코드 ${code ?? signal}): ${text}`));
        return;
      }
      const rows = Buffer.concat(out)
        .toString("utf8")
        .split("\n")
        .filter((line) => line.length > 0)
        .map((line) => line.split("|"));
      finish(resolve, rows);
    });
    child.stdin.on("error", () => {});
    child.stdin.end(sql);
  });
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, Math.max(0, ms)));

/**
 * 단계들을 차례로 돌린다. 첫 실패 · 기한 초과에서 멈춘다.
 * @param {Array<{id: string, title: string, budget: (d: object) => number, run: (ctx: object) => Promise<void>}>} steps
 * @param {object} ctx deadlines 등을 담은 문맥(limit · sleep · throwIfAborted를 여기서 채운다)
 * @param {{write: (line: string) => void, totalTimeoutMs?: number|null, now?: () => number}} io
 * @returns {Promise<{exitCode: number, results: Array<object>, failedStep: string|null}>}
 */
export async function runSteps(steps, ctx, { write, totalTimeoutMs = null, now = () => Date.now() }) {
  const startedAt = now();
  const results = [];
  ctx.sleep ??= sleep;
  for (const step of steps) {
    const stepStart = now();
    let budget = step.budget(ctx.deadlines);
    let totalLimited = false;
    if (totalTimeoutMs !== null) {
      const remaining = totalTimeoutMs - (stepStart - startedAt);
      if (remaining < budget) {
        budget = Math.max(0, remaining);
        totalLimited = true;
      }
    }
    if (budget <= 0) {
      results.push({ id: step.id, title: step.title, status: "TIMEOUT", ms: 0, message: "전체 기한 소진" });
      write(`${step.id} TIMEOUT 0.0s 전체 기한(--total-timeout) 소진`);
      return { exitCode: 1, results, failedStep: step.id };
    }
    const controller = new AbortController();
    const stepDeadline = stepStart + budget;
    ctx.signal = controller.signal;
    ctx.limit = (ms) => Math.max(1, Math.min(ms, stepDeadline - now()));
    ctx.throwIfAborted = () => {
      if (controller.signal.aborted) {
        throw new StepTimeout(`${step.id} 단계 기한`, budget);
      }
    };
    let timer = null;
    const outer = new Promise((_, reject) => {
      timer = setTimeout(() => {
        controller.abort();
        reject(new StepTimeout(totalLimited ? "전체 기한(--total-timeout)" : `단계 기한 ${formatSeconds(budget)}`, budget));
      }, budget);
    });
    try {
      await Promise.race([Promise.resolve().then(() => step.run(ctx)), outer]);
      const ms = now() - stepStart;
      results.push({ id: step.id, title: step.title, status: "PASS", ms });
      write(`${step.id} PASS ${formatSeconds(ms)} ${step.title}`);
    } catch (error) {
      controller.abort();
      const ms = now() - stepStart;
      const status = error instanceof StepTimeout ? "TIMEOUT" : "FAIL";
      const message =
        error instanceof StepFailure || error instanceof StepTimeout ? error.message : `예외 ${error?.name ?? "Error"}: ${error?.message ?? String(error)}`;
      results.push({ id: step.id, title: step.title, status, ms, message });
      write(`${step.id} ${status} ${formatSeconds(ms)} ${message}`);
      return { exitCode: 1, results, failedStep: step.id };
    } finally {
      clearTimeout(timer);
    }
  }
  return { exitCode: 0, results, failedStep: null };
}

/** 실행 문맥을 만든다(HTTP 클라이언트 둘 · 기록 따라 읽기 · SQL · 비밀 목록). */
export function createContext(options, { redactor, deadlines }) {
  const deviceJar = new CookieJar();
  const parentJar = new CookieJar();
  const timeoutMs = deadlines.request;
  const secrets = new SecretBook(options.secretsFile, redactor);
  const ctx = {
    opts: options,
    deadlines,
    ca: options.ca,
    deviceJar,
    parentJar,
    device: createHttpClient({ baseUrl: options.baseUrl, ca: options.ca, origin: options.origin, jar: deviceJar, timeoutMs }),
    parent: createHttpClient({ baseUrl: options.baseUrl, ca: options.ca, origin: options.origin, jar: parentJar, timeoutMs }),
    media: createHttpClient({ baseUrl: options.baseUrl, ca: options.ca, origin: options.origin, jar: null, timeoutMs }),
    provider: new JsonlTail(options.providerRecord),
    realtimeLog: new JsonlTail(options.realtimeLog),
    secrets,
    measurements: {},
    state: {},
    closers: [() => secrets.close()],
    sleep,
  };
  ctx.sql = (role, sql, { timeoutMs: limit } = {}) => runSql(options.sqlCmd, role, sql, { timeoutMs: limit ?? timeoutMs, redactor });
  return ctx;
}

function writeResult(file, result) {
  try {
    mkdirSync(path.dirname(file), { recursive: true, mode: 0o700 });
    writeFileSync(file, `${JSON.stringify(result, null, 2)}\n`, { mode: 0o644 });
  } catch {
    // 결과 파일은 참고용이다. 쓰지 못해도 판정(종료 코드)은 바뀌지 않는다
  }
}

/**
 * CLI 본체. 프로세스를 끝내지 않고 종료 코드를 돌려준다(시험이 직접 부른다).
 * @param {string[]} argv
 * @param {{stdout: {write: Function}, stderr: {write: Function}}} io
 */
export async function main(argv = process.argv.slice(2), io = { stdout: process.stdout, stderr: process.stderr }) {
  const redactor = new Redactor();
  const out = createLineWriter(io.stdout, redactor);
  const err = createLineWriter(io.stderr, redactor);
  let raw;
  let options;
  let deadlines;
  try {
    raw = parseArgs(argv);
    if (raw.help) {
      io.stdout.write(USAGE);
      return 0;
    }
    options = resolveOptions(raw);
    deadlines = loadDeadlines(options.repoRoot);
  } catch (error) {
    err(`run-c: ${error.message}`);
    if (error instanceof UsageError) {
      err("run-c --help로 사용법을 본다");
    }
    return 2;
  }
  const ctx = createContext(options, { redactor, deadlines });
  const startedAt = Date.now();
  let outcome;
  try {
    outcome = await runSteps(STEPS, ctx, { write: out, totalTimeoutMs: options.totalTimeoutMs });
  } finally {
    for (const close of ctx.closers.reverse()) {
      try {
        close();
      } catch {
        // 정리 경로
      }
    }
  }
  const totalMs = Date.now() - startedAt;
  const passed = outcome.results.filter((result) => result.status === "PASS").length;
  writeResult(options.out, {
    status: outcome.exitCode === 0 ? "PASS" : "FAIL",
    failedStep: outcome.failedStep,
    startedAt: new Date(startedAt).toISOString(),
    totalMs,
    steps: outcome.results.map((result) => ({ ...result, message: result.message ? redactor.redact(result.message) : undefined })),
    deadlinesMs: deadlines,
    measurements: ctx.measurements,
    sessionId: ctx.state.sessionId ?? null,
    secretsWritten: ctx.secrets.count,
  });
  if (outcome.exitCode === 0) {
    out(`C PASS ${passed}/${STEPS.length} ${formatSeconds(totalMs)}`);
  } else {
    out(`C FAIL ${outcome.failedStep} (${passed}/${STEPS.length} 통과) ${formatSeconds(totalMs)}`);
  }
  return outcome.exitCode;
}

const isEntryPoint = process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url;
if (isEntryPoint) {
  main()
    .then((code) => {
      process.exit(code);
    })
    .catch((error) => {
      process.stderr.write(`run-c: 예기치 못한 오류 ${error?.name ?? "Error"}\n`);
      process.exit(1);
    });
}

/** 시험용: 파일 권한(8진 문자열). */
export function fileMode(file) {
  return (statSync(file).mode & 0o777).toString(8);
}
