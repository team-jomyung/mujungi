// C안 대기 기한 — application.yml 시간 키에서 계산 (U1 3.5 Step 45 · SEC-05 · J144 · BR8.11)
//
// 모든 대기에 기한을 둔다. 기한은 숫자를 코드에 박지 않고 두 앱의 application.yml 시간 키로 계산한다(BR8.11 · NFR6.1).
// 계획 Step 45의 값(REGISTERED 30초 · 04 STABLE 90초 · ACTIVE 30초 · 턴마다 30초 · ENDED 60초)은 기본 설정값에서 아래 식이 내는 값이다.
//
//   registered = 6 × health.db-check-interval(5s)                          — 같은 DB 행을 읽으므로 즉시. 앱이 잠깐 NOT_READY가
//                                                                            되어도(Retry-After = db-check-interval) 6번 다시 본다
//   stable     = device.disconnect(40s) + core.device-status-stale(30s)
//                + 2 × device.status-report-interval(10s)                  — 첫 보고가 늦거나 한 번 빠져도 판정이 바뀔 시간
//   active     = session.device-ack-timeout(20s) + 2 × health.db-check-interval(5s)
//   turn       = device.output-queue-max(20s) + 2 × health.db-check-interval(5s)
//   ended      = 2 × session.closing-confirm(30s)
//   hello      = 2 × device.hello-timeout(5s)
//   settle     = gate.segment-quiet(800ms) + 1s                             — "더 오지 않음"을 볼 때 기다리는 짧은 시간
//
// 읽는 파일: backend/realtime-activity/src/main/resources/application.yml · backend/core-api/src/main/resources/application.yml.
// 이 모듈의 YAML 읽기는 두 파일이 쓰는 모양(들여쓰기 매핑 · 스칼라 · 흐름 목록 · 주석)만 다룬다. 키가 없거나 형식이 틀리면 오류다.
import { readFileSync } from "node:fs";
import path from "node:path";

/** 이 모듈이 읽는 키(앱별). */
export const REQUIRED_KEYS = Object.freeze({
  realtime: [
    "mujoong.timing.session.device-ack-timeout",
    "mujoong.timing.session.closing-confirm",
    "mujoong.timing.device.disconnect",
    "mujoong.timing.device.heartbeat-interval",
    "mujoong.timing.device.hello-timeout",
    "mujoong.timing.device.status-report-interval",
    "mujoong.timing.device.output-queue-max",
    "mujoong.gate.segment-quiet",
    "mujoong.health.db-check-interval",
  ],
  core: [
    "mujoong.timing.core.device-status-stale",
    "mujoong.timing.bootstrap.code-ttl",
    "mujoong.timing.device.cookie-max-age",
    "mujoong.security.session-cookie-max-age",
  ],
});

/**
 * 들여쓰기 매핑 YAML을 '점으로 이은 키 → 원시 문자열 값'으로 편다. 목록 항목(- ...)과 여러 줄 값은 다루지 않는다(두 파일에 없음).
 * @returns {Map<string, string>}
 */
export function flattenYaml(text) {
  const out = new Map();
  const stack = [];
  const lines = String(text).split(/\r?\n/);
  for (const rawLine of lines) {
    const line = stripComment(rawLine);
    if (line.trim().length === 0) {
      continue;
    }
    const indent = line.length - line.trimStart().length;
    const body = line.trim();
    if (body.startsWith("- ") || body === "-") {
      continue;
    }
    const match = /^("([^"]+)"|'([^']+)'|[^:]+?)\s*:(?:\s+(.*))?$/.exec(body);
    if (!match) {
      continue;
    }
    const key = match[2] ?? match[3] ?? match[1].trim();
    const value = match[4] === undefined ? "" : unquote(match[4].trim());
    while (stack.length > 0 && stack[stack.length - 1].indent >= indent) {
      stack.pop();
    }
    const fullKey = [...stack.map((entry) => entry.key), key].join(".");
    if (value === "") {
      stack.push({ indent, key });
    } else {
      out.set(fullKey, value);
    }
  }
  return out;
}

function stripComment(line) {
  let inSingle = false;
  let inDouble = false;
  for (let i = 0; i < line.length; i += 1) {
    const c = line[i];
    if (c === "'" && !inDouble) {
      inSingle = !inSingle;
    } else if (c === '"' && !inSingle) {
      inDouble = !inDouble;
    } else if (c === "#" && !inSingle && !inDouble && (i === 0 || /\s/.test(line[i - 1]))) {
      return line.slice(0, i);
    }
  }
  return line;
}

function unquote(value) {
  if (
    value.length >= 2 &&
    ((value.startsWith('"') && value.endsWith('"')) ||
      (value.startsWith("'") && value.endsWith("'")))
  ) {
    return value.slice(1, -1);
  }
  return value;
}

const UNIT_MS = { ms: 1, s: 1000, m: 60_000, h: 3_600_000, d: 86_400_000 };

/** Spring Duration 짧은 표기(500ms · 5s · 10m · 24h · 30d)를 ms로. 틀리면 RangeError. */
export function parseDurationMs(text) {
  const match = /^([0-9]+)(ms|s|m|h|d)$/.exec(String(text).trim());
  if (!match) {
    throw new RangeError(`시간 값 형식이 아니다: ${text}`);
  }
  return Number(match[1]) * UNIT_MS[match[2]];
}

function requireDuration(map, key, label) {
  if (!map.has(key)) {
    throw new RangeError(`${label}에 ${key}가 없다`);
  }
  return parseDurationMs(map.get(key));
}

/** 키 표에서 기한(ms)을 계산한다. */
export function computeDeadlines(realtime, core) {
  const r = (key) => requireDuration(realtime, key, "realtime application.yml");
  const c = (key) => requireDuration(core, key, "core-api application.yml");
  const dbCheck = r("mujoong.health.db-check-interval");
  const deadlines = {
    registered: 6 * dbCheck,
    stable:
      r("mujoong.timing.device.disconnect") +
      c("mujoong.timing.core.device-status-stale") +
      2 * r("mujoong.timing.device.status-report-interval"),
    active: r("mujoong.timing.session.device-ack-timeout") + 2 * dbCheck,
    turn: r("mujoong.timing.device.output-queue-max") + 2 * dbCheck,
    ended: 2 * r("mujoong.timing.session.closing-confirm"),
    hello: 2 * r("mujoong.timing.device.hello-timeout"),
    settle: r("mujoong.gate.segment-quiet") + 1000,
    heartbeatInterval: r("mujoong.timing.device.heartbeat-interval"),
    request: 2 * dbCheck,
    // 기한이 아니라 쿠키 Max-Age · 코드 만료 단언에 쓰는 기대값
    codeTtl: c("mujoong.timing.bootstrap.code-ttl"),
    deviceCookieMaxAge: c("mujoong.timing.device.cookie-max-age"),
    sessionCookieMaxAge: c("mujoong.security.session-cookie-max-age"),
  };
  return Object.freeze(deadlines);
}

/** 저장소 루트에서 두 application.yml을 읽어 기한을 계산한다. */
export function loadDeadlines(repoRoot) {
  const read = (relative) => flattenYaml(readFileSync(path.join(repoRoot, relative), "utf8"));
  const realtime = read("backend/realtime-activity/src/main/resources/application.yml");
  const core = read("backend/core-api/src/main/resources/application.yml");
  return computeDeadlines(realtime, core);
}
