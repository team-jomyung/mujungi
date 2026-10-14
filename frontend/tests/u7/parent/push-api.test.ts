import { describe, expect, it, vi } from "vitest";
import { createApiClient, ApiError } from "@/parent/api/apiClient";
import { fetchPushConfig, putPushSubscription, deletePushSubscription } from "@/parent/api/push";
import { jsonResponse } from "../../u1/support/fixtures";

const endpoint = "https://push.example.test/subscription/test";
const body = { endpoint, keys: { p256dh: "generated-public-key", auth: "generated-auth" }, platform: "WEB" } as const;

function client(handler: (path: string, init: RequestInit) => Response) {
  const fetchImpl = vi.fn(async (path: string, init: RequestInit) => path === "/api/session/csrf"
    ? jsonResponse(200, { token: "runtime-test-csrf", headerName: "X-CSRF-TOKEN" }) : handler(path, init));
  return { api: createApiClient({ timeoutMs: 1000, fetchImpl }), fetchImpl };
}

describe("U7 C6 구독 API", () => {
  it.each([{ enabled: true, publicKey: "runtime-public-key" }, { enabled: false, publicKey: null }])("기동 설정 %j을 읽고 GET에는 CSRF를 붙이지 않는다", async (config) => {
    const { api, fetchImpl } = client(() => jsonResponse(200, config));
    await expect(fetchPushConfig(api)).resolves.toEqual(config);
    expect(fetchImpl).toHaveBeenCalledOnce();
    expect(fetchImpl.mock.calls[0][0]).toBe("/api/push-subscription/vapid-public-key");
    expect(fetchImpl.mock.calls[0][1].credentials).toBe("same-origin");
  });

  it.each([{ enabled: true, publicKey: null }, { enabled: false, publicKey: "unexpected" }, { publicKey: null }, null])("잘못된 설정 %j은 켜짐으로 추정하지 않는다", async (config) => {
    const { api } = client(() => jsonResponse(200, config));
    await expect(fetchPushConfig(api)).rejects.toMatchObject({ kind: "invalidResponse" });
  });

  it("PUT과 현재 endpoint DELETE는 같은 세션 CSRF를 쓰며 204를 처리한다", async () => {
    const { api, fetchImpl } = client(() => new Response(null, { status: 204 }));
    await putPushSubscription(api, body);
    await deletePushSubscription(api, endpoint);
    const writes = fetchImpl.mock.calls.filter(([path]) => path === "/api/push-subscription");
    expect(writes.map(([, init]) => init.method)).toEqual(["PUT", "DELETE"]);
    expect(writes.map(([, init]) => JSON.parse(String(init.body)))).toEqual([body, { endpoint }]);
    expect(writes.every(([, init]) => new Headers(init.headers).get("X-CSRF-TOKEN") === "runtime-test-csrf")).toBe(true);
  });

  it("타인 endpoint 충돌 코드를 보존한다", async () => {
    const { api } = client(() => jsonResponse(409, { code: "PUSH_SUBSCRIPTION_CONFLICT" }));
    await expect(putPushSubscription(api, body)).rejects.toEqual(expect.objectContaining<ApiError>({ code: "PUSH_SUBSCRIPTION_CONFLICT" } as ApiError));
  });
});
