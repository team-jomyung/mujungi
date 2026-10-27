import { normalizeErrorCode, type ErrorCodeOrUnknown } from "@/shared/errors/codeToCopy";

export type AdminDataErrorKind =
  | "unauthenticated"
  | "forbidden"
  | "network"
  | "timeout"
  | "aborted"
  | "invalidResponse"
  | "http"
  | "notReady";

export class AdminDataError extends Error {
  constructor(
    readonly kind: AdminDataErrorKind,
    readonly status: number | null = null,
    readonly code: ErrorCodeOrUnknown = "UNKNOWN",
    readonly traceId: string | null = null,
  ) {
    super(`관리자 조회 실패: ${kind}`);
    this.name = "AdminDataError";
  }
}

/** 서버의 원시 message는 표시하지 않고 알려진 code와 추적 ID만 보존한다. */
export function errorFromResponse(response: Response, body: unknown): AdminDataError {
  const envelope = body && typeof body === "object" && !Array.isArray(body)
    ? body as Record<string, unknown>
    : {};
  const traceId = typeof envelope.traceId === "string"
    ? envelope.traceId
    : response.headers.get("X-Trace-Id");
  const kind = response.status === 401
    ? "unauthenticated"
    : response.status === 403 ? "forbidden" : "http";
  return new AdminDataError(kind, response.status, normalizeErrorCode(envelope.code), traceId);
}

export function assertNotAborted(signal?: AbortSignal): void {
  if (signal?.aborted)
    throw new AdminDataError("aborted");
}
