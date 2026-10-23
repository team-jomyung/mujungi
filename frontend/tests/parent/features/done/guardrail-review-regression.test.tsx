import { act, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DoneScreen } from "@/parent/features/done/DoneScreen";
import { renderParent } from "../../../support/parentHarness";
import { bubble, deferred, errorResponse, jsonResponse, liveViewBody, OTHER_SESSION_ID, SESSION_ID } from "../../../support/fixtures";
import { createApiClient } from "@/parent/common/api/apiClient";
import { createParentQueryClient } from "@/parent/common/api/queries";
import { hasCurrentLiveFacts, readCurrentLiveView, subscribeLiveConnection } from "@/parent/common/api/liveUpdates";
import { endSession } from "@/parent/common/api/live";
import { parseLiveView } from "@/parent/common/api/types";
import { resetRouter } from "../../../support/nextNavigation";

vi.mock("next/navigation", async () => (await import("../../../support/nextNavigation")).nextNavigationMock);

const originalOnline = Object.getOwnPropertyDescriptor(navigator, "onLine");
const facts = { schemaVersion: 1, notice: "COMPLETED", participation: "ACCEPTED", goodbye: "PLAYING", confirmedStopBubbleSeq: 7 };

beforeEach(resetRouter);
afterEach(() => {
  if (originalOnline === undefined) Reflect.deleteProperty(navigator, "onLine");
  else Object.defineProperty(navigator, "onLine", originalOnline);
  act(() => window.dispatchEvent(new Event("online")));
});

describe("검토에서 발견한 현재성·권한 회귀", () => {
  it("단절 뒤 online만 복구되면 새 현재 응답 전까지 캐시 인사를 표시하지 않는다", async () => {
    const pending = deferred<Response>();
    let reads = 0;
    const h = renderParent(<DoneScreen sessionId={SESSION_ID} />, {
      timing: { parentPollIntervalMs: 40 },
      handler: () => ++reads === 1
        ? jsonResponse(200, liveViewBody("CLOSING", { guardrail: facts }))
        : pending.promise,
    });
    try {
      await screen.findByTestId("done-goodbye");
      Object.defineProperty(navigator, "onLine", { configurable: true, value: false });
      act(() => window.dispatchEvent(new Event("offline")));
      expect(screen.queryByTestId("done-goodbye")).toBeNull();
      Object.defineProperty(navigator, "onLine", { configurable: true, value: true });
      act(() => window.dispatchEvent(new Event("online")));
      await waitFor(() => expect(reads).toBeGreaterThan(1));
      expect(screen.queryByTestId("done-goodbye")).toBeNull();
    } finally {
      h.view.unmount();
      pending.resolve(jsonResponse(200, liveViewBody("CLOSING", { guardrail: null })));
    }
  });

  it("현재 세션 GET403 뒤 허용됐던 종료 인용과 캐시 내용을 제거한다", async () => {
    let denied = false;
    const h = renderParent(<DoneScreen sessionId={SESSION_ID} />, {
      handler: path => path.endsWith("/result/status")
        ? jsonResponse(200, { jobStatus: "REQUESTED", sessionStatus: "ENDED", confirmed: true })
        : denied
          ? errorResponse(403, "FORBIDDEN")
          : jsonResponse(200, liveViewBody("ENDED", {
            guardrail: facts,
            endReason: "CHILD_STOP",
            items: [bubble({ seq: 7, speaker: "CHILD", text: "여기서 그만" })],
          })),
    });
    try {
      await screen.findByTestId("done-stop-quote");
      denied = true;
      await act(async () => {
        await h.queryClient.refetchQueries({ queryKey: ["session", SESSION_ID], exact: true });
      });
      expect(screen.queryByTestId("done-stop-quote")).toBeNull();
      expect(h.queryClient.getQueryData(["session", SESSION_ID])).toBeUndefined();
    } finally {
      h.view.unmount();
    }
  });

  it("online 복구 뒤 새로 발행한 동일 PLAYING 응답 성공에서만 인사를 복구한다", async () => {
    const pending = deferred<Response>(); let reads = 0;
    const h = renderParent(<DoneScreen sessionId={SESSION_ID} />, {
      timing: { parentPollIntervalMs: 40 },
      handler: () => ++reads === 1 ? jsonResponse(200, liveViewBody("CLOSING", { guardrail: facts })) : pending.promise,
    });
    try {
      await screen.findByTestId("done-goodbye");
      Object.defineProperty(navigator, "onLine", { configurable: true, value: false });
      act(() => window.dispatchEvent(new Event("offline")));
      Object.defineProperty(navigator, "onLine", { configurable: true, value: true });
      act(() => window.dispatchEvent(new Event("online")));
      await waitFor(() => expect(reads).toBeGreaterThan(1));
      expect(screen.queryByTestId("done-goodbye")).toBeNull();
      await act(async () => pending.resolve(jsonResponse(200, liveViewBody("CLOSING", { guardrail: facts }))));
      expect(await screen.findByTestId("done-goodbye")).toBeInTheDocument();
    } finally { h.view.unmount(); }
  });

  it("복구 뒤 실패/낮은 상태판/단절 이전 지연 응답은 새 인사 신뢰가 아니다", async () => {
    const pending = [deferred<Response>(), deferred<Response>(), deferred<Response>(), deferred<Response>(), deferred<Response>()];
    let index = 0;
    const api = createApiClient({ timeoutMs: 10000, fetchImpl: () => pending[index++].promise });
    const client = createParentQueryClient(); const unsubscribe = subscribeLiveConnection(api, () => undefined);
    try {
      const initial = readCurrentLiveView(api, client, SESSION_ID);
      pending[0].resolve(jsonResponse(200, liveViewBody("CLOSING", { guardrail: facts, statusVersion: 5 })));
      const cached = await initial; expect(hasCurrentLiveFacts(api, cached)).toBe(true);
      const beforeOffline = readCurrentLiveView(api, client, SESSION_ID);
      const rejected = expect(beforeOffline).rejects.toMatchObject({ kind: "aborted" });
      window.dispatchEvent(new Event("offline")); window.dispatchEvent(new Event("online"));
      pending[1].resolve(jsonResponse(200, liveViewBody("CLOSING", { guardrail: facts, statusVersion: 5 })));
      await rejected; expect(hasCurrentLiveFacts(api, cached)).toBe(false);
      const failed = readCurrentLiveView(api, client, SESSION_ID); const failure = expect(failed).rejects.toMatchObject({ status: 503 });
      pending[2].resolve(errorResponse(503, "INTERNAL_ERROR")); await failure; expect(hasCurrentLiveFacts(api, cached)).toBe(false);
      const lower = readCurrentLiveView(api, client, SESSION_ID);
      pending[3].resolve(jsonResponse(200, liveViewBody("CLOSING", { guardrail: facts, statusVersion: 4 })));
      expect(hasCurrentLiveFacts(api, await lower)).toBe(false);
      const current = readCurrentLiveView(api, client, SESSION_ID);
      pending[4].resolve(jsonResponse(200, liveViewBody("CLOSING", { guardrail: facts, statusVersion: 5 })));
      expect(hasCurrentLiveFacts(api, await current)).toBe(true);
    } finally { unsubscribe(); client.clear(); }
  });

  it("세션403은 이전 items/facts·대기 응답만 제거하고 다른 세션과 계정 캐시는 보존한다", async () => {
    const pending = [deferred<Response>(), deferred<Response>(), deferred<Response>()]; let index = 0;
    const api = createApiClient({ timeoutMs: 10000, fetchImpl: () => pending[index++].promise });
    const client = createParentQueryClient();
    const first = readCurrentLiveView(api, client, SESSION_ID);
    pending[0].resolve(jsonResponse(200, liveViewBody("CLOSING", { guardrail: facts, items: [bubble({ seq: 7, speaker: "CHILD", text: "이전 원문" })] })));
    await first;
    const other = parseLiveView(liveViewBody("ACTIVE", { sessionId: OTHER_SESSION_ID }));
    client.setQueryData(["session", OTHER_SESSION_ID], other); client.setQueryData(["me"], { guardianId: "synthetic" });
    const old = readCurrentLiveView(api, client, SESSION_ID); const late = expect(old).rejects.toMatchObject({ kind: "aborted" });
    const denied = readCurrentLiveView(api, client, SESSION_ID); const rejection = expect(denied).rejects.toMatchObject({ status: 403 });
    pending[2].resolve(errorResponse(403, "FORBIDDEN")); await rejection;
    expect(client.getQueryData(["session", SESSION_ID])).toBeUndefined();
    pending[1].resolve(jsonResponse(200, liveViewBody("CLOSING", { guardrail: facts, items: [bubble({ seq: 7, speaker: "CHILD", text: "늦은 원문" })] })));
    await late;
    expect(client.getQueryData(["session", SESSION_ID])).toBeUndefined();
    expect(client.getQueryData(["session", OTHER_SESSION_ID])).toEqual(other);
    expect(client.getQueryData(["me"])).toEqual({ guardianId: "synthetic" }); client.clear();
  });

  it("POST의 CSRF403은 토큰을 한 번 갱신하고 같은 끝내기 요청을 재시도하며 세션 사실을 지우지 않는다", async () => {
    let csrfReads = 0; let posts = 0; const bodies: unknown[] = [];
    const api = createApiClient({ timeoutMs: 10000, fetchImpl: async (path, init) => {
      if (path.endsWith("/csrf")) return jsonResponse(200, { token: `synthetic-${++csrfReads}`, headerName: "X-CSRF-TOKEN" });
      if (init.method === "POST") {
        bodies.push(JSON.parse(init.body as string));
        return ++posts === 1 ? errorResponse(403, "CSRF_TOKEN_INVALID") : jsonResponse(202, { status: "CLOSING" });
      }
      return jsonResponse(200, liveViewBody("CLOSING", { guardrail: facts }));
    } });
    const client = createParentQueryClient(); const current = await readCurrentLiveView(api, client, SESSION_ID);
    await expect(endSession(api, SESSION_ID, "same-request")).resolves.toEqual({ status: "CLOSING" });
    expect(csrfReads).toBe(2); expect(posts).toBe(2); expect(bodies).toEqual([{ requestId: "same-request" }, { requestId: "same-request" }]);
    expect(client.getQueryData(["session", SESSION_ID])).toEqual(current); expect(hasCurrentLiveFacts(api, current)).toBe(true); client.clear();
  });
});
