import { describe, expect, it } from "vitest";
import { createApiClient } from "@/parent/common/api/apiClient";
import { createParentQueryClient } from "@/parent/common/api/queries";
import { hasCurrentLiveFacts, invalidateLiveIdentity, leaveLiveSession, readCurrentLiveView as read } from "@/parent/common/api/liveUpdates";
import { mergeLiveView } from "@/parent/common/api/live";
import { parseLiveView } from "@/parent/common/api/types";
import { bubble, deferred, errorResponse, jsonResponse, liveViewBody, SESSION_ID, OTHER_SESSION_ID } from "../../support/fixtures";

const facts = (goodbye: string) => ({ schemaVersion: 1, notice: "COMPLETED", participation: "ACCEPTED", goodbye, confirmedStopBubbleSeq: null });
function harness() {
  const pending = [deferred<Response>(), deferred<Response>(), deferred<Response>()];
  let count = 0;
  const api = createApiClient({ timeoutMs: 10000, fetchImpl: () => pending[count++].promise });
  const client = createParentQueryClient();
  return { pending, api, client };
}
describe("현재 진행 응답의 공통 적용 경계", () => {
  it.each(["identity", "sequence", "leave"] as const)("%s 경계 이전의 늦은403은 새 성공의 캐시와 현재 사실을 지우지 않는다", async (boundary) => {
    const { api, client, pending } = harness();
    try {
      const old = read(api, client, SESSION_ID);
      const outcome = old.catch((error: unknown) => error);
      if (boundary === "identity") invalidateLiveIdentity(api);
      if (boundary === "leave") leaveLiveSession(api, SESSION_ID);
      const fresh = read(api, client, SESSION_ID);
      pending[1].resolve(jsonResponse(200, liveViewBody("ACTIVE", { guardrail: facts("STOPPED") })));
      const accepted = await fresh;
      expect(hasCurrentLiveFacts(api, accepted)).toBe(true);
      pending[0].resolve(errorResponse(403, "FORBIDDEN"));
      const error = await outcome;
      expect(client.getQueryData(["session", SESSION_ID])).toBe(accepted);
      expect(hasCurrentLiveFacts(api, accepted)).toBe(true);
      expect(error).toMatchObject({ kind: "aborted" });
    } finally { client.clear(); }
  });
  it("취소를 무시한 늦은403은 현재 세대와 순번이 같아도 기존 성공을 지우지 않는다", async () => {
    const { api, client, pending } = harness();
    try {
      const first = read(api, client, SESSION_ID);
      pending[0].resolve(jsonResponse(200, liveViewBody("ACTIVE", { guardrail: facts("STOPPED") })));
      const accepted = await first;
      const controller = new AbortController();
      const cancelled = read(api, client, SESSION_ID, controller.signal);
      const outcome = cancelled.catch((error: unknown) => error);
      controller.abort();
      pending[1].resolve(errorResponse(403, "FORBIDDEN"));
      expect(await outcome).toMatchObject({ kind: "aborted" });
      expect(client.getQueryData(["session", SESSION_ID])).toBe(accepted);
      expect(hasCurrentLiveFacts(api, accepted)).toBe(true);
    } finally { client.clear(); }
  });
  it("폴링과 수동 충돌 재조회 역전에서 최신 발행 응답만 적용한다", async () => {
    const { api, client, pending } = harness();
    const old = read(api, client, SESSION_ID); const rejected = expect(old).rejects.toMatchObject({ kind: "aborted" });
    const fresh = read(api, client, SESSION_ID);
    pending[1].resolve(jsonResponse(200, liveViewBody("CLOSING", { guardrail: facts("STOPPED") })));
    await fresh;
    pending[0].resolve(jsonResponse(200, liveViewBody("ACTIVE", { guardrail: facts("PLAYING") })));
    await rejected;
    expect(client.getQueryData(["session", SESSION_ID])).toMatchObject({ status: "CLOSING", guardrail: { goodbye: "STOPPED" } });
  });
  it("FREE_TALK 낮은 상태판은 items와 facts 모두 역행하지 않는다", () => {
    const old = parseLiveView(liveViewBody("CLOSING", { statusVersion: 5, guardrail: facts("STOPPED") }));
    expect(mergeLiveView(old, parseLiveView(liveViewBody("ACTIVE", { statusVersion: 4, guardrail: facts("PLAYING") })))).toBe(old);
  });
  it("같은 판은 PLAYING→STOPPED→null로 교체하며 items만 seq 병합한다", () => {
    const a = parseLiveView(liveViewBody("CLOSING", { guardrail: facts("PLAYING"), items: [bubble({ seq: 1, speaker: "CHILD", text: "기존" })] }));
    const b = mergeLiveView(a, parseLiveView(liveViewBody("CLOSING", { guardrail: facts("STOPPED") })));
    expect(b.guardrail?.goodbye).toBe("STOPPED"); expect(b.items).toHaveLength(1);
    expect(mergeLiveView(b, parseLiveView(liveViewBody("CLOSING"))).guardrail).toBeNull();
  });
  it("직접 Done 진입의 최초 읽기도 같은 파서와 적용 경계를 쓴다", async () => {
    const { api, client, pending } = harness(); const next = read(api, client, SESSION_ID);
    pending[0].resolve(jsonResponse(200, liveViewBody("CLOSING", { guardrail: facts("PLAYING") })));
    expect(await next).toMatchObject({ status: "CLOSING", guardrail: { goodbye: "PLAYING" } });
    expect(client.getQueryData(["session", SESSION_ID])).toEqual(await next);
  });
  it("세션 전환 뒤 취소를 무시한 이전 응답과 items를 재사용하지 않는다", async () => {
    const { api, client, pending } = harness(); const a = read(api, client, SESSION_ID); const rejected = expect(a).rejects.toMatchObject({ kind: "aborted" });
    const b = read(api, client, OTHER_SESSION_ID);
    pending[1].resolve(jsonResponse(200, liveViewBody("ACTIVE", { sessionId: OTHER_SESSION_ID })));
    await b;
    pending[0].resolve(jsonResponse(200, liveViewBody("ACTIVE", { items: [bubble({ seq: 1, speaker: "CHILD", text: "과거" })] })));
    await rejected;
    expect(client.getQueryData(["session", SESSION_ID])).toBeUndefined();
    expect(client.getQueryData(["session", OTHER_SESSION_ID])).toMatchObject({ items: [], guardrail: null });
  });
  it("로그아웃/로그인 세대 무효화 뒤 늦은 응답은 캐시를 복구하지 않는다", async () => {
    const { api, client, pending } = harness(); const next = read(api, client, SESSION_ID); const rejected = expect(next).rejects.toMatchObject({ kind: "aborted" });
    invalidateLiveIdentity(api);
    pending[0].resolve(jsonResponse(200, liveViewBody("CLOSING", { guardrail: facts("PLAYING") })));
    await rejected; expect(client.getQueryData(["session", SESSION_ID])).toBeUndefined();
  });
  it("AbortSignal과 화면 이탈을 fetch가 무시해도 적용하지 않는다", async () => {
    for (const abort of [true, false]) {
      const { api, client, pending } = harness(); const controller = new AbortController();
      const next = read(api, client, SESSION_ID, controller.signal); const rejected = expect(next).rejects.toMatchObject({ kind: "aborted" });
      if (abort) controller.abort(); else leaveLiveSession(api, SESSION_ID);
      pending[0].resolve(jsonResponse(200, liveViewBody("ACTIVE")));
      await rejected; expect(client.getQueryData(["session", SESSION_ID])).toBeUndefined();
    }
  });
  it("401 권한 실패는 기존 캐시를 제거하고 모드 전환은 이전 facts를 보충하지 않는다", async () => {
    const { api, client, pending } = harness(); const a = read(api, client, SESSION_ID);
    pending[0].resolve(jsonResponse(200, liveViewBody("CLOSING", { guardrail: facts("PLAYING") }))); await a;
    const b = read(api, client, SESSION_ID); const rejected = expect(b).rejects.toMatchObject({ status: 401 });
    pending[1].resolve(errorResponse(401, "UNAUTHENTICATED")); await rejected;
    expect(client.getQueryData(["session", SESSION_ID])).toBeUndefined();
    const c = read(api, client, SESSION_ID); pending[2].resolve(jsonResponse(200, liveViewBody("ACTIVE", { activityType: "PRACTICE" })));
    expect(await c).toMatchObject({ activityType: "PRACTICE", guardrail: null });
  });
});
