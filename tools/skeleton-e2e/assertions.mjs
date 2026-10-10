// C안 단언 도구 (U1 3.5 Step 45 · J144 · SEC-05 · SEC-07 · SEC-16 · SD-5 · OD-7 · OD-8)
//
// 단언이 어긋나면 StepFailure, 대기 기한을 넘으면 StepTimeout을 던진다. 메시지는 한국어이고 값은 상태 코드 · 오류 code · 키 이름 ·
// 개수만 담는다(쿠키 · 토큰 · 등록 코드 · 전사 원문은 담지 않는다, BR8.7). 실행기(run-c.mjs)는 출력 전에 등록된 비밀을 한 번 더 가린다.
import { parseSetCookie } from "../lib/cookieJar.mjs";
import { DATE_TIME_PATTERN, UUID_PATTERN } from "../lib/envelope.mjs";

/** 단언 실패(종료 코드 1). */
export class StepFailure extends Error {
  constructor(message) {
    super(message);
    this.name = "StepFailure";
  }
}

/** 대기 기한 초과(종료 코드 1, 단계 id와 함께 TIMEOUT으로 보고). */
export class StepTimeout extends Error {
  constructor(what, timeoutMs) {
    super(`${what} — ${timeoutMs}ms 기한 초과`);
    this.name = "StepTimeout";
    this.timeoutMs = timeoutMs;
  }
}

export function fail(message) {
  throw new StepFailure(message);
}

export function check(condition, message) {
  if (!condition) {
    fail(message);
  }
}

export function isUuid(value) {
  return typeof value === "string" && UUID_PATTERN.test(value);
}

export function isDateTime(value) {
  return typeof value === "string" && DATE_TIME_PATTERN.test(value);
}

/** 응답을 '상태(code)' 꼴로 짧게. */
export function describe(res) {
  const code = res.errorCode;
  return code ? `${res.status}(${code})` : String(res.status);
}

/** 상태 코드 단언. */
export function expectStatus(res, status, what) {
  check(res.status === status, `${what}: 기대 ${status}, 실제 ${describe(res)}`);
}

/**
 * 오류 봉투 단언: 상태 · code, 봉투 모양 {code, message, traceId}, traceId = X-Trace-Id(OD-7).
 */
export function expectError(res, status, code, what) {
  check(res.status === status && res.errorCode === code, `${what}: 기대 ${status}(${code}), 실제 ${describe(res)}`);
  const body = res.json();
  check(typeof body.message === "string" && body.message.length > 0, `${what}: 오류 봉투에 message가 없다`);
  const keys = Object.keys(body).sort().join(",");
  check(keys === "code,message,traceId" || keys === "code,message", `${what}: 오류 봉투 키가 계약과 다르다(${keys})`);
  if (body.traceId !== undefined) {
    check(/^[0-9a-f]{32}$/.test(body.traceId), `${what}: 오류 봉투 traceId 형식이 틀렸다`);
    check(res.traceId === body.traceId, `${what}: 오류 봉투 traceId와 X-Trace-Id가 다르다`);
  }
}

/** JSON 본문(객체)을 돌려준다. 아니면 실패. */
export function jsonBody(res, what) {
  const body = res.json();
  check(body !== null && typeof body === "object" && !Array.isArray(body), `${what}: 본문이 JSON 객체가 아니다`);
  return body;
}

/** 응답 Set-Cookie 중 그 이름의 마지막 것(파싱 결과). 없으면 실패. */
export function setCookieFrom(res, name, what) {
  const found = res.setCookies.map(parseSetCookie).filter((cookie) => cookie !== null && cookie.name === name);
  check(found.length > 0, `${what}: Set-Cookie ${name}이 없다`);
  return found[found.length - 1];
}

/**
 * 쿠키 속성 단언(SD-5 · SEC-16): HttpOnly · Secure · SameSite · Path 정확, Domain 없음, Max-Age(기대값이 있으면 같음, 없으면 양수).
 * @param {object} cookie parseSetCookie 결과
 * @param {{sameSite: string, path: string, maxAgeSeconds?: number|null, deleted?: boolean}} expected
 */
export function expectCookieAttributes(cookie, expected, what) {
  const a = cookie.attributes;
  const label = `${what}: 쿠키 ${cookie.name}`;
  check(a.httpOnly, `${label}에 HttpOnly가 없다`);
  check(a.secure, `${label}에 Secure가 없다`);
  check(
    typeof a.sameSite === "string" && a.sameSite.toLowerCase() === expected.sameSite.toLowerCase(),
    `${label}의 SameSite가 ${expected.sameSite}가 아니다(${a.sameSite ?? "없음"})`,
  );
  check(a.path === expected.path, `${label}의 Path가 ${expected.path}가 아니다(${a.path ?? "없음"})`);
  check(a.domain === null && !cookie.attributeNames.includes("domain"), `${label}에 Domain이 있다`);
  if (expected.deleted) {
    check(a.maxAge !== null && a.maxAge <= 0, `${label}이 지워지지 않았다(Max-Age=0이 아님)`);
    return;
  }
  check(cookie.value.length > 0, `${label} 값이 비었다`);
  if (expected.maxAgeSeconds !== undefined && expected.maxAgeSeconds !== null) {
    check(a.maxAge === expected.maxAgeSeconds, `${label}의 Max-Age가 ${expected.maxAgeSeconds}가 아니다(${a.maxAge ?? "없음"})`);
  } else {
    check(a.maxAge !== null && a.maxAge > 0, `${label}에 양수 Max-Age가 없다`);
  }
}

/** Cache-Control: no-store(SD-4). */
export function expectNoStore(res, what) {
  const value = (res.header("cache-control") ?? "").toLowerCase();
  check(value.split(",").map((part) => part.trim()).includes("no-store"), `${what}: Cache-Control no-store가 없다`);
}

/** 객체 경로 값(없으면 undefined). */
export function at(object, dotted) {
  return dotted.split(".").reduce((value, key) => (value === null || value === undefined ? undefined : value[key]), object);
}

/** LATENCY_TURN에 있어야 하는 키(J143 · OD-8). 시각 키 8종 + 출처 · 오프셋 · 왕복 · 응답 · 조용 시간. */
export const LATENCY_TURN_KEYS = Object.freeze([
  "tSpeechEnd",
  "tProviderFirstOut",
  "tSegmentConfirmed",
  "tGatePass",
  "tFirstFrameSent",
  "tDevicePlayStart",
  "tDevicePlayStartRecv",
  "tTranscriptFinal",
  "tSpeechEndSource",
  "clockOffsetMs",
  "rttMs",
  "providerResponseMs",
  "quietWaitMs",
]);

/** SESSION_ITEMS_SERVED에 있어야 하는 키(J143 · NFR5.3). */
export const ITEMS_SERVED_KEYS = Object.freeze(["tViewItemReady", "tViewItemFirstServed"]);

/** 로그 한 줄(attrs)에서 빠진 키. */
export function missingAttrs(entry, keys) {
  const attrs = entry && typeof entry.attrs === "object" && entry.attrs !== null ? entry.attrs : {};
  return keys.filter((key) => attrs[key] === undefined || attrs[key] === null);
}

/** 기록에 남길 값만 고른다(시각 · 숫자 · 열거, 원문 없음). */
export function pickAttrs(entry, keys) {
  const attrs = entry?.attrs ?? {};
  const out = {};
  for (const key of keys) {
    if (attrs[key] !== undefined) {
      out[key] = attrs[key];
    }
  }
  return out;
}
