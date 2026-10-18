import path from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, createApiClient, type FetchLike } from "@/parent/common/api/apiClient";
import { createCsrfStore } from "@/parent/common/api/csrf";
import { registerDevice } from "@/parent/features/device/api";
import { startFreeTalk } from "@/parent/features/free/api";
import { fetchHome } from "@/parent/features/home/api";
import { endSession } from "@/parent/features/live/api";
import { devLogin } from "@/parent/features/login/api";
import { ResponseShapeError, type CsrfToken } from "@/parent/common/api/types";
import { SESSION_ID, TRACE_ID, deferred, errorResponse, homeBody, jsonResponse, makeCsrfToken } from "../support/fixtures";
import { codeOf, frontendRoot, relative, sourceFiles } from "../support/sourceScan";

// 보호자 REST 클라이언트 시험 (U1 3.5 Step 42 · SD-6 · BR8.6 · BR8.8 · RD-21 · 편차 (e)(u) · 표 2.9-C)
// CSRF는 메모리에만, 403 CSRF_TOKEN_INVALID면 1회 재취득 + 같은 본문 1회 재시도, 두 번째 403은 멈춤,
// 요청 제한 시간(parent.request-timeout)은 주입한 값으로 끊는다.

interface Call {
  readonly path: string;
  readonly init: RequestInit;
}

function client(handler: (path: string, init: RequestInit, index: number) => Response | Promise<Response>, timeoutMs = 10_000) {
  const calls: Call[] = [];
  const tokens: string[] = [];
  const fetchImpl = vi.fn<FetchLike>(async (path, init) => {
    calls.push({ path, init });
    if (path === "/api/session/csrf") {
      const token = makeCsrfToken();
      tokens.push(token);
      return jsonResponse(200, { token, headerName: "X-CSRF-TOKEN" });
    }
    return handler(path, init, calls.filter((call) => call.path === path).length);
  });
  return { api: createApiClient({ timeoutMs, fetchImpl }), calls, tokens, fetchImpl };
}

function headers(call: Call): Record<string, string> {
  return call.init.headers as Record<string, string>;
}

afterEach(() => {
  vi.useRealTimers();
});

describe("apiClient — 요청 형식", () => {
  it("조회는 같은 origin 쿠키 · no-store로 보내고 CSRF 헤더를 붙이지 않는다", async () => {
    const { api, calls } = client(() => jsonResponse(200, homeBody("STABLE")));
    const home = await fetchHome(api);
    expect(home.device.network.state).toBe("STABLE");
    expect(calls).toHaveLength(1);
    expect(calls[0].init).toMatchObject({ method: "GET", credentials: "same-origin", cache: "no-store" });
    expect(headers(calls[0])).toEqual({ Accept: "application/json" });
  });

  it("변경 요청은 먼저 CSRF 토큰을 받아 headerName 헤더로 싣고 본문은 JSON이다(SD-6 · BR8.8)", async () => {
    const { api, calls, tokens } = client(() => jsonResponse(201, { deviceId: "d", codePrefix: "7F3K", registeredAt: "t" }));
    await registerDevice(api, "7F3K92QD");
    expect(calls.map((call) => call.path)).toEqual(["/api/session/csrf", "/api/device/register"]);
    expect(headers(calls[1])).toEqual({ Accept: "application/json", "Content-Type": "application/json", "X-CSRF-TOKEN": tokens[0] });
    expect(calls[1].init.body).toBe(JSON.stringify({ code: "7F3K92QD" }));
  });

  it("동시에 보낸 변경 요청 둘은 CSRF 토큰을 한 번만 받는다", async () => {
    const { api, calls } = client(() => jsonResponse(202, { sessionId: SESSION_ID, status: "REQUESTED" }));
    await Promise.all([startFreeTalk(api, { idempotencyKey: "k1" }), startFreeTalk(api, { idempotencyKey: "k2" })]);
    expect(calls.filter((call) => call.path === "/api/session/csrf")).toHaveLength(1);
  });
});

describe("apiClient — 403 CSRF_TOKEN_INVALID 1회 재취득(SD-6 · 표 2.9-C)", () => {
  it("첫 403이면 토큰을 다시 받아 같은 본문 · 새 토큰으로 한 번 더 보낸다", async () => {
    const { api, calls, tokens } = client((path, _init, index) =>
      index === 1 ? errorResponse(403, "CSRF_TOKEN_INVALID") : jsonResponse(202, { status: "CLOSING" }),
    );
    expect(await endSession(api, SESSION_ID, "req-1")).toEqual({ status: "CLOSING" });
    const ends = calls.filter((call) => call.path.endsWith("/end"));
    expect(ends).toHaveLength(2);
    expect(ends[0].init.body).toBe(ends[1].init.body);
    expect(headers(ends[0])["X-CSRF-TOKEN"]).toBe(tokens[0]);
    expect(headers(ends[1])["X-CSRF-TOKEN"]).toBe(tokens[1]);
    expect(tokens).toHaveLength(2);
  });

  it("두 번째 403이면 멈추고 오류를 돌려준다(재시도 2회 없음)", async () => {
    const { api, calls } = client(() => errorResponse(403, "CSRF_TOKEN_INVALID"));
    const error = await registerDevice(api, "7F3K92QD").catch((reason: unknown) => reason);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ kind: "http", status: 403, code: "CSRF_TOKEN_INVALID" });
    expect(calls.filter((call) => call.path === "/api/device/register")).toHaveLength(2);
    expect(calls.filter((call) => call.path === "/api/session/csrf")).toHaveLength(2);
  });

  it("다른 403(예: CONSENT_MISSING)은 재시도하지 않는다", async () => {
    const { api, calls } = client(() => errorResponse(403, "CONSENT_MISSING"));
    await expect(startFreeTalk(api, { idempotencyKey: "k" })).rejects.toMatchObject({ status: 403, code: "CONSENT_MISSING" });
    expect(calls.filter((call) => call.path === "/api/activities/free")).toHaveLength(1);
  });
});

describe("apiClient — 실패 종류(RD-21)", () => {
  it("요청 제한 시간(주입 값 50ms)이 지나면 끊고 timeout(전달 불명)으로 알린다", async () => {
    vi.useFakeTimers();
    const { api } = client(
      (_path, init) =>
        new Promise<Response>((_resolve, reject) => {
          init.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
        }),
      50,
    );
    const outcome = fetchHome(api).catch((reason: unknown) => reason);
    await vi.advanceTimersByTimeAsync(49);
    let settled = false;
    void outcome.then(() => {
      settled = true;
    });
    await vi.advanceTimersByTimeAsync(0);
    expect(settled).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    const error = await outcome;
    expect(error).toMatchObject({ kind: "timeout", status: null, code: "UNKNOWN" });
    expect((error as ApiError).isDeliveryUnknown).toBe(true);
  });

  it("응답 본문을 읽다 시간이 다 되어도 timeout이다", async () => {
    vi.useFakeTimers();
    const { api } = client(
      (_path, init) =>
        ({
          ok: true,
          status: 200,
          headers: new Headers(),
          text: () =>
            new Promise<string>((_resolve, reject) => {
              init.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
            }),
        }) as unknown as Response,
      30,
    );
    const outcome = fetchHome(api).catch((reason: unknown) => reason);
    await vi.advanceTimersByTimeAsync(30);
    expect(await outcome).toMatchObject({ kind: "timeout" });
  });

  it("망 오류는 network, 호출자가 끊으면 aborted다", async () => {
    const offline = client(() => {
      throw new TypeError("Failed to fetch");
    });
    await expect(fetchHome(offline.api)).rejects.toMatchObject({ kind: "network", isDeliveryUnknown: true });
    const controller = new AbortController();
    const pending = deferred<Response>();
    const aborting = client((_path, init) => {
      init.signal?.addEventListener("abort", () => pending.reject(new DOMException("aborted", "AbortError")));
      return pending.promise;
    });
    const outcome = fetchHome(aborting.api, controller.signal);
    controller.abort();
    await expect(outcome).rejects.toMatchObject({ kind: "aborted", isDeliveryUnknown: false });
  });

  it("오류 봉투의 code를 좁히고 traceId는 본문 또는 X-Trace-Id 헤더에서 읽는다(BR8.6)", async () => {
    const known = client(() => errorResponse(409, "SESSION_NOT_ACTIVE"));
    await expect(fetchHome(known.api)).rejects.toMatchObject({ status: 409, code: "SESSION_NOT_ACTIVE", traceId: TRACE_ID });
    const unknown = client(() => jsonResponse(418, { code: "TEAPOT" }, { "X-Trace-Id": "f".repeat(32) }));
    await expect(fetchHome(unknown.api)).rejects.toMatchObject({ status: 418, code: "UNKNOWN", traceId: "f".repeat(32) });
    const html = client(() => new Response("<html>bad gateway</html>", { status: 502 }));
    await expect(fetchHome(html.api)).rejects.toMatchObject({ kind: "http", status: 502, code: "UNKNOWN" });
    const unauthenticated = client(() => jsonResponse(401, { code: "UNAUTHENTICATED", message: "서버-문장-표식", traceId: TRACE_ID }));
    const error = (await fetchHome(unauthenticated.api).catch((reason: unknown) => reason)) as ApiError;
    expect(error.isUnauthenticated).toBe(true);
    // 서버 message는 화면 · 오류 문장에 옮기지 않는다(화면 문구는 code로 고른다).
    expect(error.message).not.toContain("서버-문장-표식");
  });

  it("2xx인데 본문이 JSON이 아니거나 계약 모양이 아니면 invalidResponse다", async () => {
    const notJson = client(() => new Response("ok", { status: 200 }));
    await expect(fetchHome(notJson.api)).rejects.toMatchObject({ kind: "invalidResponse", status: 200 });
    const wrongShape = client(() => jsonResponse(200, { child: null }));
    await expect(fetchHome(wrongShape.api)).rejects.toMatchObject({ kind: "invalidResponse" });
    const empty = client(() => new Response(null, { status: 202 }));
    await expect(endSession(empty.api, SESSION_ID, "r")).rejects.toMatchObject({ kind: "invalidResponse", status: 202 });
  });

  it("읽기 함수의 계약 밖 예외는 그대로 올린다", async () => {
    const { api } = client(() => jsonResponse(200, {}));
    const boom = new Error("bug");
    await expect(
      api.request({
        method: "GET",
        path: "/api/x",
        csrf: false,
        parse: () => {
          throw boom;
        },
      }),
    ).rejects.toBe(boom);
  });
});

describe("csrf 보관 · 시험 로그인", () => {
  it("토큰은 메모리에만 두고 clear 중 받은 옛 세션 토큰은 보관하지 않는다", async () => {
    const pending = deferred<CsrfToken>();
    const fetchToken = vi.fn(() => pending.promise);
    const store = createCsrfStore(fetchToken);
    const first = store.ensure();
    expect(store.ensure()).toBe(first);
    store.clear();
    pending.resolve({ token: "old", headerName: "X-CSRF-TOKEN" });
    await first;
    expect(store.peek()).toBeNull();
    fetchToken.mockResolvedValueOnce({ token: "new", headerName: "X-CSRF-TOKEN" });
    expect(await store.refresh()).toEqual({ token: "new", headerName: "X-CSRF-TOKEN" });
    expect(store.peek()?.token).toBe("new");
    expect(await store.ensure()).toEqual({ token: "new", headerName: "X-CSRF-TOKEN" });
    expect(fetchToken).toHaveBeenCalledTimes(2);
  });

  it("시험 로그인은 CSRF 없이 보내고 성공하면 토큰을 버리고 새로 받는다(AC1.1.7 · SD-6 단계 1)", async () => {
    const { api, calls } = client(() => jsonResponse(200, { guardianId: "g" }));
    await api.csrf.ensure();
    expect(await devLogin(api)).toEqual({ guardianId: "g" });
    const login = calls.find((call) => call.path === "/api/dev/login");
    expect(login === undefined ? undefined : headers(login)["X-CSRF-TOKEN"]).toBeUndefined();
    expect(calls.map((call) => call.path)).toEqual(["/api/session/csrf", "/api/dev/login", "/api/session/csrf"]);
  });

  it("로그인 직후 CSRF 받기가 실패해도 로그인 결과는 그대로다", async () => {
    const fetchImpl = vi.fn<FetchLike>(async (path) =>
      path === "/api/dev/login" ? jsonResponse(200, { guardianId: "g" }) : errorResponse(503, "SERVICE_NOT_READY"),
    );
    const api = createApiClient({ timeoutMs: 1_000, fetchImpl });
    expect(await devLogin(api)).toEqual({ guardianId: "g" });
    expect(api.csrf.peek()).toBeNull();
  });

  it("보호자 영역 코드는 토큰을 localStorage · sessionStorage · 쿠키에 두지 않는다(SD-6)", () => {
    const offenders = sourceFiles(path.join(frontendRoot, "src/parent"))
      .filter((file) => /localStorage|sessionStorage|document\.cookie|indexedDB/.test(codeOf(file)))
      .map(relative);
    expect(offenders).toEqual([]);
    expect(new ResponseShapeError("x").message).toContain("x");
  });
});
