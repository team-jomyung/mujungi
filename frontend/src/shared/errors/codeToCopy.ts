import { TEMP_COPY } from "../copy/ko";

// 오류 봉투 code → 화면 문장 (U1 3.5 Step 37 · BR8.6 · 계약 2절 · contracts/errors.md · J118)
// 화면은 원시 code를 보이지 않는다(접근성 U6). 받는 쪽은 모르는 code를 일반 오류로 처리한다(계약 10절).

export const ERROR_CODES = [
  "UNAUTHENTICATED",
  "NOT_FOUND",
  "VALIDATION_FAILED",
  "CONSENT_MISSING",
  "ACCOUNT_DELETION_IN_PROGRESS",
  "CSRF_TOKEN_INVALID",
  "SESSION_IN_PROGRESS",
  "IDEMPOTENCY_CONFLICT",
  "SESSION_NOT_ACTIVE",
  "ALREADY_SELECTED",
  "RESTORE_EXPIRED",
  "SCENARIO_GEN_FAILED",
  "DEVICE_NOT_STABLE",
  "REALTIME_UNAVAILABLE",
  "CODE_NOT_FOUND",
  "CODE_ALREADY_REGISTERED",
  "DEVICE_ALREADY_ACTIVE",
  "TOO_MANY_ATTEMPTS",
  "RATE_LIMITED",
  "CODE_ISSUE_FAILED",
  "BOOTSTRAP_NOT_FOUND",
  "NOT_REGISTERED_YET",
  "BOOTSTRAP_CLOSED",
  "DEVICE_NOT_REGISTERED",
  "ALREADY_REGISTERED",
  "INVALID_ENVELOPE",
  "EVENT_CONFLICT",
  "INVALID_DEVICE_TOKEN",
  "DEVICE_NOT_CONNECTED",
  "SERVICE_NOT_READY",
  "INTERNAL_ERROR",
  "UPSTREAM_UNAVAILABLE",
  // U2 보호자 계정
  "GOOGLE_AUTH_FAILED",
  "CHILD_NOT_REGISTERED",
  "CHILD_EXISTS",
] as const;

export type ErrorCode = (typeof ERROR_CODES)[number];

/** 모르는 code · 응답 없음 · 시간 초과를 함께 부르는 값. */
export type ErrorCodeOrUnknown = ErrorCode | "UNKNOWN";

export function isErrorCode(value: unknown): value is ErrorCode {
  return typeof value === "string" && (ERROR_CODES as readonly string[]).includes(value);
}

/** 받은 code를 알려진 값으로 좁힌다. 모르면 UNKNOWN. */
export function normalizeErrorCode(value: unknown): ErrorCodeOrUnknown {
  return isErrorCode(value) ? value : "UNKNOWN";
}

/** code의 고정 문장. UNKNOWN은 일반 문장이다. */
export function messageForErrorCode(code: ErrorCodeOrUnknown): string {
  return code === "UNKNOWN" ? TEMP_COPY.networkError : TEMP_COPY.errors[code];
}
