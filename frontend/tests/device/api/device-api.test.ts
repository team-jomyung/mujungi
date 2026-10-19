import path from "node:path";
import { describe, expect, it, vi } from "vitest";
import { createDeviceApi } from "@/device/api/deviceApi";
import { DEVICE_ID, deferred, errorResponse, jsonResponse } from "../../support/fixtures";
import { codeOf, frontendRoot, relative, sourceFiles } from "../../support/sourceScan";

// 아이 기기 REST 시험 (U1 3.5 Step 41 · 계약 4.1 · BR1.3 · BR3.1 · BR3.5 · BR9.1 · BR9.4 · 편차 (a)(b)(c) · AC6.1.4)
// 응답을 화면 동작 종류로 줄이고, 같은 요청은 한 번에 하나만 보낸다(단일 비행). 쿠키 · 토큰은 JS가 다루지 않는다.

type Route = (method: string) => Response | Promise<Response>;

function apiWith(routes: Record<string, Route>) {
  const fetchImpl = vi.fn(async (input: string, init?: RequestInit) => {
    const route = routes[input];
    if (route === undefined) {
      throw new TypeError("network");
    }
    return route(init?.method ?? "GET");
  });
  return { api: createDeviceApi(fetchImpl), fetchImpl };
}

describe("deviceApi — /api/device/me · status", () => {
  it("me 200은 등록된 기기, 401은 미등록, 그 밖과 형식 오류 · 네트워크는 실패다(BR3.5)", async () => {
    expect(await apiWith({ "/api/device/me": () => jsonResponse(200, { deviceId: DEVICE_ID }) }).api.me()).toEqual({
      kind: "registered",
      deviceId: DEVICE_ID,
    });
    expect(await apiWith({ "/api/device/me": () => errorResponse(401, "DEVICE_NOT_REGISTERED") }).api.me()).toEqual({ kind: "unregistered" });
    expect(await apiWith({ "/api/device/me": () => jsonResponse(200, {}) }).api.me()).toEqual({ kind: "failed" });
    expect(await apiWith({ "/api/device/me": () => errorResponse(503, "SERVICE_NOT_READY") }).api.me()).toEqual({ kind: "failed" });
    expect(await apiWith({}).api.me()).toEqual({ kind: "failed" });
  });

  it.each([
    ["PENDING", "pending"],
    ["REGISTERED", "registered"],
    ["EXPIRED", "expired"],
  ])("status 200 %s → %s", async (value, kind) => {
    const { api } = apiWith({ "/api/device/bootstrap/status": () => jsonResponse(200, { status: value }) });
    expect(await api.status()).toEqual({ kind });
  });

  it("status 401(쿠키 없음 · 모르는 값)은 새 코드 경로, 모르는 상태 · 본문 오류 · 5xx는 실패다(BR9.3)", async () => {
    expect(await apiWith({ "/api/device/bootstrap/status": () => errorResponse(401, "BOOTSTRAP_NOT_FOUND") }).api.status()).toEqual({
      kind: "notFound",
    });
    expect(await apiWith({ "/api/device/bootstrap/status": () => jsonResponse(200, { status: "LATER" }) }).api.status()).toEqual({
      kind: "failed",
    });
    expect(await apiWith({ "/api/device/bootstrap/status": () => new Response("<html>", { status: 200 }) }).api.status()).toEqual({
      kind: "failed",
    });
    expect(await apiWith({ "/api/device/bootstrap/status": () => jsonResponse(200, ["PENDING"]) }).api.status()).toEqual({ kind: "failed" });
    expect(await apiWith({ "/api/device/bootstrap/status": () => errorResponse(500, "INTERNAL_ERROR") }).api.status()).toEqual({
      kind: "failed",
    });
    expect(await apiWith({}).api.status()).toEqual({ kind: "failed" });
  });
});

describe("deviceApi — bootstrap · claim", () => {
  it("bootstrap 201은 발급 알파벳 8자 코드일 때만 받는다(BR1.1)", async () => {
    const issued = apiWith({ "/api/device/bootstrap": () => jsonResponse(201, { code: "7F3K92QD", expiresAt: "2026-10-16T01:00:00Z" }) });
    expect(await issued.api.bootstrap()).toEqual({ kind: "issued", code: "7F3K92QD", expiresAt: "2026-10-16T01:00:00Z" });
    expect(issued.fetchImpl).toHaveBeenCalledWith("/api/device/bootstrap", expect.objectContaining({ method: "POST" }));
    for (const code of ["7f3k92qd", "OOOO1111", "7F3K92Q", "7F3K-92QD"]) {
      const { api } = apiWith({ "/api/device/bootstrap": () => jsonResponse(201, { code, expiresAt: "x" }) });
      expect(await api.bootstrap(), code).toEqual({ kind: "failed" });
    }
    expect(await apiWith({ "/api/device/bootstrap": () => jsonResponse(201, { code: "7F3K92QD" }) }).api.bootstrap()).toEqual({ kind: "failed" });
  });

  it("bootstrap 망 오류는 실패이고, 대역 없이 만들면 전역 fetch를 쓴다", async () => {
    expect(await apiWith({}).api.bootstrap()).toEqual({ kind: "failed" });
    const fetchMock = vi.fn(async () => errorResponse(401, "DEVICE_NOT_REGISTERED"));
    vi.stubGlobal("fetch", fetchMock);
    try {
      expect(await createDeviceApi().me()).toEqual({ kind: "unregistered" });
      expect(fetchMock).toHaveBeenCalledWith("/api/device/me", expect.objectContaining({ method: "GET" }));
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("(a) bootstrap 409 ALREADY_REGISTERED는 claim으로, 다른 409는 실패다", async () => {
    expect(await apiWith({ "/api/device/bootstrap": () => errorResponse(409, "ALREADY_REGISTERED") }).api.bootstrap()).toEqual({
      kind: "alreadyRegistered",
    });
    expect(await apiWith({ "/api/device/bootstrap": () => errorResponse(409, "SOMETHING") }).api.bootstrap()).toEqual({ kind: "failed" });
  });

  it.each([
    [429, "RATE_LIMITED"],
    [503, "CODE_ISSUE_FAILED"],
    [503, "SERVICE_NOT_READY"],
    [500, "INTERNAL_ERROR"],
  ])("(c) bootstrap %i %s는 '연결을 확인하는 중' 뒤 재요청할 실패다(BR9.4)", async (statusCode, code) => {
    expect(await apiWith({ "/api/device/bootstrap": () => errorResponse(statusCode, code) }).api.bootstrap()).toEqual({ kind: "failed" });
  });

  it("claim 200 · 401(b) · 409 · 410 · 그 밖을 화면 분기로 줄인다(BR3.1 · BR3.2)", async () => {
    const claimWith = async (response: () => Response) => apiWith({ "/api/device/bootstrap/claim": response }).api.claim();
    expect(await claimWith(() => jsonResponse(200, { deviceId: DEVICE_ID }))).toEqual({ kind: "claimed", deviceId: DEVICE_ID });
    expect(await claimWith(() => jsonResponse(200, {}))).toEqual({ kind: "failed" });
    expect(await claimWith(() => errorResponse(401, "BOOTSTRAP_NOT_FOUND"))).toEqual({ kind: "notFound" });
    expect(await claimWith(() => errorResponse(409, "NOT_REGISTERED_YET"))).toEqual({ kind: "notRegisteredYet" });
    expect(await claimWith(() => errorResponse(410, "BOOTSTRAP_CLOSED"))).toEqual({ kind: "closed" });
    expect(await claimWith(() => errorResponse(500, "INTERNAL_ERROR"))).toEqual({ kind: "failed" });
    expect(await apiWith({}).api.claim()).toEqual({ kind: "failed" });
  });
});

describe("deviceApi — 단일 비행 · 쿠키만", () => {
  it("응답이 오기 전 같은 요청은 다시 보내지 않고 결과를 함께 받는다(BR9.1 · PD-13)", async () => {
    const pending = deferred<Response>();
    const { api, fetchImpl } = apiWith({ "/api/device/bootstrap": () => pending.promise });
    const first = api.bootstrap();
    const second = api.bootstrap();
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    pending.resolve(jsonResponse(201, { code: "7F3K92QD", expiresAt: "x" }));
    expect(await second).toBe(await first);
    const pendingClaim = deferred<Response>();
    const claimApi = apiWith({ "/api/device/bootstrap/claim": () => pendingClaim.promise });
    void claimApi.api.claim();
    void claimApi.api.claim();
    expect(claimApi.fetchImpl).toHaveBeenCalledTimes(1);
    pendingClaim.resolve(jsonResponse(200, { deviceId: DEVICE_ID }));
  });

  it("응답이 오면 다음 요청은 새로 보낸다", async () => {
    const { api, fetchImpl } = apiWith({ "/api/device/me": () => errorResponse(401, "DEVICE_NOT_REGISTERED") });
    await api.me();
    await api.me();
    expect(fetchImpl).toHaveBeenCalledTimes(2);
  });

  it("요청은 같은 origin 쿠키로만 가고 캐시 · 토큰 헤더 · 본문이 없다(BR9.1 · AC6.1.4)", async () => {
    const { api, fetchImpl } = apiWith({ "/api/device/bootstrap/claim": () => jsonResponse(200, { deviceId: DEVICE_ID }) });
    await api.claim();
    const init = fetchImpl.mock.calls[0][1] as RequestInit;
    expect(init).toEqual({ method: "POST", credentials: "same-origin", cache: "no-store", headers: { Accept: "application/json" } });
  });

  it("기기 영역 코드는 localStorage · sessionStorage · document.cookie를 쓰지 않는다(BR9.1 · BR1.5)", () => {
    const offenders = sourceFiles(path.join(frontendRoot, "src/device"))
      .filter((file) => /localStorage|sessionStorage|document\.cookie|indexedDB/.test(codeOf(file)))
      .map(relative);
    expect(offenders).toEqual([]);
  });
});
