// 아이 기기 REST (U1 3.5 Step 38 · 계약 4.1 · contracts/device-api.yaml · BR1 · BR3.1 · BR3.5 · BR9.1 · 편차 (a)(b)(c))
//
// - 비밀은 HttpOnly 쿠키로만 오가고 JS는 쿠키 유무를 보지 않는다. 그래서 상태는 항상 서버 응답으로 판정한다(BR9.1 · AC6.1.4).
//   localStorage · sessionStorage 같은 클라이언트 표시값을 두지 않는다.
// - bootstrap · claim(그리고 me · status)은 단일 비행이다: 같은 요청이 진행 중이면 새로 보내지 않고 그 결과를 함께 받는다(BR9.1 · PD-13).
// - 응답은 화면 동작에 필요한 종류로만 줄인다. 모르는 상태 코드 · 응답 형식 · 네트워크 오류는 'failed'(5초 뒤 같은 단계, BR9.4).

const REGISTRATION_CODE_PATTERN = /^[2-9A-HJKMNP-Z]{8}$/;

export type MeResult = { readonly kind: "registered"; readonly deviceId: string } | { readonly kind: "unregistered" } | { readonly kind: "failed" };

export type BootstrapStatusResult =
  | { readonly kind: "pending" }
  | { readonly kind: "registered" }
  | { readonly kind: "expired" }
  | { readonly kind: "notFound" }
  | { readonly kind: "failed" };

export type BootstrapResult =
  | { readonly kind: "issued"; readonly code: string; readonly expiresAt: string }
  | { readonly kind: "alreadyRegistered" }
  | { readonly kind: "failed" };

export type ClaimResult =
  | { readonly kind: "claimed"; readonly deviceId: string }
  | { readonly kind: "notFound" }
  | { readonly kind: "notRegisteredYet" }
  | { readonly kind: "closed" }
  | { readonly kind: "failed" };

export interface DeviceApi {
  /** GET /api/device/me — 200이면 등록된 기기(쿠키 수명 갱신), 401이면 D0. */
  me(): Promise<MeResult>;
  /** GET /api/device/bootstrap/status — PENDING · REGISTERED · EXPIRED, 401이면 쿠키 없음 · 모르는 값. */
  status(): Promise<BootstrapStatusResult>;
  /** POST /api/device/bootstrap — 201 코드 발급(같은 쿠키면 같은 코드), 409 ALREADY_REGISTERED이면 claim으로. */
  bootstrap(): Promise<BootstrapResult>;
  /** POST /api/device/bootstrap/claim — 200 기기 토큰 쿠키, 401 · 409 · 410은 화면 분기. */
  claim(): Promise<ClaimResult>;
}

type FetchLike = (input: string, init?: RequestInit) => Promise<Response>;

interface JsonResponse {
  readonly status: number;
  readonly body: Record<string, unknown> | null;
}

async function call(fetchImpl: FetchLike, method: "GET" | "POST", path: string): Promise<JsonResponse | null> {
  let response: Response;
  try {
    response = await fetchImpl(path, {
      method,
      credentials: "same-origin",
      cache: "no-store",
      headers: { Accept: "application/json" },
    });
  } catch {
    return null;
  }
  let body: Record<string, unknown> | null = null;
  try {
    const parsed: unknown = await response.json();
    body = typeof parsed === "object" && parsed !== null && !Array.isArray(parsed) ? (parsed as Record<string, unknown>) : null;
  } catch {
    body = null;
  }
  return { status: response.status, body };
}

function readString(body: Record<string, unknown> | null, field: string): string | null {
  const value = body?.[field];
  return typeof value === "string" && value.length > 0 ? value : null;
}

/** 진행 중인 같은 요청이 있으면 그 약속을 함께 쓴다(단일 비행). */
function singleFlight<T>(run: () => Promise<T>): () => Promise<T> {
  let inFlight: Promise<T> | null = null;
  return () => {
    if (inFlight === null) {
      inFlight = run().finally(() => {
        inFlight = null;
      });
    }
    return inFlight;
  };
}

export function createDeviceApi(fetchImpl: FetchLike = (input, init) => fetch(input, init)): DeviceApi {
  const me = singleFlight<MeResult>(async () => {
    const response = await call(fetchImpl, "GET", "/api/device/me");
    if (response === null) {
      return { kind: "failed" };
    }
    if (response.status === 200) {
      const deviceId = readString(response.body, "deviceId");
      return deviceId === null ? { kind: "failed" } : { kind: "registered", deviceId };
    }
    return response.status === 401 ? { kind: "unregistered" } : { kind: "failed" };
  });

  const status = singleFlight<BootstrapStatusResult>(async () => {
    const response = await call(fetchImpl, "GET", "/api/device/bootstrap/status");
    if (response === null) {
      return { kind: "failed" };
    }
    if (response.status === 401) {
      return { kind: "notFound" };
    }
    if (response.status !== 200) {
      return { kind: "failed" };
    }
    switch (readString(response.body, "status")) {
      case "PENDING":
        return { kind: "pending" };
      case "REGISTERED":
        return { kind: "registered" };
      case "EXPIRED":
        return { kind: "expired" };
      default:
        return { kind: "failed" };
    }
  });

  const bootstrap = singleFlight<BootstrapResult>(async () => {
    const response = await call(fetchImpl, "POST", "/api/device/bootstrap");
    if (response === null) {
      return { kind: "failed" };
    }
    if (response.status === 201 || response.status === 200) {
      const code = readString(response.body, "code");
      const expiresAt = readString(response.body, "expiresAt");
      if (code === null || expiresAt === null || !REGISTRATION_CODE_PATTERN.test(code)) {
        return { kind: "failed" };
      }
      return { kind: "issued", code, expiresAt };
    }
    if (response.status === 409) {
      const code = readString(response.body, "code");
      return code === null || code === "ALREADY_REGISTERED" ? { kind: "alreadyRegistered" } : { kind: "failed" };
    }
    // 429 RATE_LIMITED · 503 CODE_ISSUE_FAILED · SERVICE_NOT_READY · 500 · 400은 '연결을 확인하는 중' 뒤 5초 재요청(BR9.4).
    return { kind: "failed" };
  });

  const claim = singleFlight<ClaimResult>(async () => {
    const response = await call(fetchImpl, "POST", "/api/device/bootstrap/claim");
    if (response === null) {
      return { kind: "failed" };
    }
    switch (response.status) {
      case 200: {
        const deviceId = readString(response.body, "deviceId");
        return deviceId === null ? { kind: "failed" } : { kind: "claimed", deviceId };
      }
      case 401:
        return { kind: "notFound" };
      case 409:
        return { kind: "notRegisteredYet" };
      case 410:
        return { kind: "closed" };
      default:
        return { kind: "failed" };
    }
  });

  return { me, status, bootstrap, claim };
}
