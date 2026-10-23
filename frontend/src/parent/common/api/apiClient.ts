import { createCsrfStore, type CsrfStore } from "./csrf";
import { parseCsrfToken, ResponseShapeError, type CsrfToken } from "./types";
import { normalizeErrorCode, type ErrorCodeOrUnknown } from "@/shared/errors/codeToCopy";
import { invalidateLiveIdentity } from "./liveUpdates";

// 보호자 웹 REST 클라이언트 (U1 3.5 Step 39 · 계약 2 · 3절)
//
// - 같은 origin의 /api/*를 부른다. 세션 쿠키(MUJOONG_SESSION)는 브라우저가 싣고 JS는 보지 않는다. CORS가 없다.
// - 요청마다 mujoong.timing.parent.request-timeout(10초)이 지나면 끊고 'timeout' 오류로 알린다(RD-21: 끝내기 '전송하지 못함').
// - 상태 변경 요청(POST)은 X-CSRF-TOKEN을 붙인다. 403 CSRF_TOKEN_INVALID면 토큰을 한 번만 다시 받아 같은 요청을 같은
//   본문(같은 idempotencyKey · requestId)으로 한 번 더 보낸다. 두 번째도 403이면 멈추고 오류를 돌려준다(SD-6 · 표 2.9-C).
// - 오류 봉투 {code, message, traceId}를 읽어 code로 화면 문구를 고르게 한다. 모르는 code는 UNKNOWN(계약 10절).

export type ApiErrorKind = "http" | "network" | "timeout" | "aborted" | "invalidResponse";

export class ApiError extends Error {
  constructor(
    readonly kind: ApiErrorKind,
    readonly status: number | null,
    readonly code: ErrorCodeOrUnknown,
    readonly traceId: string | null,
  ) {
    super(`API 요청 실패: ${kind}${status === null ? "" : ` ${status}`} ${code}`);
    this.name = "ApiError";
  }

  /** 401(로그인 필요 · 만료). */
  get isUnauthenticated(): boolean {
    return this.status === 401;
  }

  /** 서버에 닿았는지 모르는 실패(응답 없음 · 시간 초과). 재시도는 같은 키로 한다(표 2.9-C). */
  get isDeliveryUnknown(): boolean {
    return this.kind === "network" || this.kind === "timeout";
  }
}

export interface ApiRequest<T> {
  /** U2가 PUT · PATCH · DELETE를 더했다(/api/consents · /api/child · /api/me). */
  readonly method: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  readonly path: string;
  readonly body?: unknown;
  readonly parse: (json: unknown) => T;
  /** 상태 변경 요청이면 true(CSRF 면제 경로는 false). */
  readonly csrf: boolean;
  readonly signal?: AbortSignal;
  /** A bounded endpoint-specific wait; ordinary requests retain the shared default. */
  readonly timeoutMs?: number;
}

export type FetchLike = (input: string, init: RequestInit) => Promise<Response>;

export interface ApiClientOptions {
  readonly timeoutMs: number;
  readonly fetchImpl?: FetchLike;
}

export interface ApiClient {
  request<T>(request: ApiRequest<T>): Promise<T>;
  readonly csrf: CsrfStore;
}

const CSRF_PATH = "/api/session/csrf";

async function readJson(response: Response): Promise<unknown> {
  const text = await response.text();
  if (text.length === 0) {
    return undefined;
  }
  return JSON.parse(text) as unknown;
}

function errorFromResponse(response: Response, body: unknown): ApiError {
  const envelope = typeof body === "object" && body !== null ? (body as Record<string, unknown>) : {};
  const traceFromBody = typeof envelope.traceId === "string" ? envelope.traceId : null;
  return new ApiError("http", response.status, normalizeErrorCode(envelope.code), traceFromBody ?? response.headers.get("X-Trace-Id"));
}

export function createApiClient(options: ApiClientOptions): ApiClient {
  const fetchImpl: FetchLike = options.fetchImpl ?? ((input, init) => fetch(input, init));

  async function send<T>(request: ApiRequest<T>, csrfToken: CsrfToken | null): Promise<T> {
    const controller = new AbortController();
    let timedOut = false;
    const timer = setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, request.timeoutMs ?? options.timeoutMs);
    const onExternalAbort = () => controller.abort();
    request.signal?.addEventListener("abort", onExternalAbort);

    const headers: Record<string, string> = { Accept: "application/json" };
    if (request.body !== undefined) {
      headers["Content-Type"] = "application/json";
    }
    if (csrfToken !== null) {
      headers[csrfToken.headerName] = csrfToken.token;
    }

    try {
      let response: Response;
      try {
        response = await fetchImpl(request.path, {
          method: request.method,
          headers,
          body: request.body === undefined ? undefined : JSON.stringify(request.body),
          credentials: "same-origin",
          cache: "no-store",
          signal: controller.signal,
        });
      } catch {
        if (timedOut) {
          throw new ApiError("timeout", null, "UNKNOWN", null);
        }
        throw new ApiError(request.signal?.aborted === true ? "aborted" : "network", null, "UNKNOWN", null);
      }
      let body: unknown;
      try {
        body = await readJson(response);
      } catch {
        if (timedOut) {
          throw new ApiError("timeout", null, "UNKNOWN", null);
        }
        if (!response.ok) {
          throw errorFromResponse(response, undefined);
        }
        throw new ApiError("invalidResponse", response.status, "UNKNOWN", response.headers.get("X-Trace-Id"));
      }
      if (!response.ok) {
        throw errorFromResponse(response, body);
      }
      try {
        return request.parse(body);
      } catch (error) {
        if (error instanceof ResponseShapeError) {
          throw new ApiError("invalidResponse", response.status, "UNKNOWN", response.headers.get("X-Trace-Id"));
        }
        throw error;
      }
    } finally {
      clearTimeout(timer);
      request.signal?.removeEventListener("abort", onExternalAbort);
    }
  }

  const csrf = createCsrfStore(() =>
    send({ method: "GET", path: CSRF_PATH, parse: parseCsrfToken, csrf: false }, null),
  );

  async function request<T>(request: ApiRequest<T>): Promise<T> {
    if (request.path === "/api/auth/logout" || request.path === "/api/auth/google" || request.path === "/api/dev/login" || (request.path === "/api/me" && request.method === "DELETE")) invalidateLiveIdentity(client);
    try {
    if (!request.csrf) {
      return await send(request, null);
    }
    const token = await csrf.ensure();
    try {
      return await send(request, token);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403 && error.code === "CSRF_TOKEN_INVALID") {
        // 상태를 바꾸기 전의 거절이라 같은 본문으로 한 번만 다시 보낸다. 두 번째 403은 그대로 돌려준다.
        const renewed = await csrf.refresh();
        return await send(request, renewed);
      }
      throw error;
    }
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) invalidateLiveIdentity(client);
      throw error;
    }
  }

  const client: ApiClient = { request, csrf };
  return client;
}
