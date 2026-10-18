import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, createApiClient } from "@/parent/common/api/apiClient";
import { ResponseShapeError } from "@/parent/common/api/types";
import { deleteGoal, fetchGoalSelections, parseDeleteResult, parseGoalSelection, parseGoalSelections, restoreGoal, selectGoal, sortGoals, updateGoalFavorite } from "@/parent/common/api/practice";
import { goalMeta } from "@/parent/features/practice/copy";
import { defaultTiming, resolveTiming } from "@/shared/timing/timing";
import { messageForErrorCode, normalizeErrorCode } from "@/shared/errors/codeToCopy";
import { errorResponse, jsonResponse } from "../u1/support/fixtures";
import { G1, G2, G3, GOALS, PATH, RESTORE_UNTIL, goal } from "./fixtures";

afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); });

describe("C4 response boundary", () => {
  it("defaults slow-read notice to 5 seconds and accepts runtime overrides", () => {
    expect(defaultTiming().parentLoadingNoticeDelayMs).toBe(5000);
    expect(resolveTiming({ MUJOONG_TIMING_PARENT_LOADINGNOTICEDELAY: "80ms" }).parentLoadingNoticeDelayMs).toBe(80);
    expect(() => resolveTiming({ MUJOONG_TIMING_PARENT_LOADINGNOTICEDELAY: "broken" })).toThrow();
  });
  it("reads only the parent allowlist, ignores additive fields, and tolerates unknown activity enums", () => {
    const parsed = parseGoalSelection({ ...goal(), sourceActivityType: "NEW_KIND", scenarioText: "not for parent", result: "functional" });
    expect(parsed.sourceActivityType).toBe("UNKNOWN");
    expect(parsed).not.toHaveProperty("scenarioText");
    expect(parsed).not.toHaveProperty("result");
    expect(goalMeta(parsed)).toBe("담은 기록 확인 중 · 연습 2회");
  });

  it("missing optional integration metadata remains unknown, not zero practice count", () => {
    const legacy: Record<string, unknown> = { ...goal() };
    delete legacy.sourceActivityType;
    delete legacy.sourceActivityAt;
    delete legacy.practiceCount;
    const parsed = parseGoalSelection(legacy);
    expect(parsed.practiceCount).toBeNull();
    expect(goalMeta(parsed)).toBe("담은 기록 확인 중 · 연습 횟수 확인 중");
  });

  it.each([
    null, [], {}, { ...goal(), goalSelectionId: "bad" }, { ...goal(), displayName: "  " },
    { ...goal(), isFavorite: "false" }, { ...goal(), sortOrder: -1 }, { ...goal(), sortOrder: 1.2 },
    { ...goal(), selectedAt: "2026-10-12" }, { ...goal(), selectedAt: "not-a-dateZ" },
    { ...goal(), sourceActivityAt: "bad" }, { ...goal(), sourceActivityType: 4 }, { ...goal(), practiceCount: -1 },
  ])("rejects a malformed row %j", (value) => expect(() => parseGoalSelection(value)).toThrow(ResponseShapeError));

  it("rejects malformed list, duplicate identities and malformed delete expiration", () => {
    expect(() => parseGoalSelections({ items: null })).toThrow(ResponseShapeError);
    expect(() => parseGoalSelections({ items: [goal(), goal()] })).toThrow(ResponseShapeError);
    expect(() => parseDeleteResult({ restoreUntil: false })).toThrow(ResponseShapeError);
    expect(parseGoalSelections({ items: [] })).toEqual([]);
  });

  it("orders favorites then saved order and stable identity, without changing input", () => {
    expect(parseGoalSelections({ items: GOALS }).map((row) => row.goalSelectionId)).toEqual([G3, G1, G2]);
    expect(GOALS[0].goalSelectionId).toBe(G2);
    expect(sortGoals([goal({ goalSelectionId: G2 }), goal()]).map((row) => row.goalSelectionId)).toEqual([G1, G2]);
  });

  it("formats real source kinds and Seoul date boundaries independently of selectedAt", () => {
    expect(goalMeta(goal())).toBe("10/6 자유대화에서 담음 · 연습 2회");
    expect(goalMeta(goal({ sourceActivityType: "PRACTICE", practiceCount: 0 }))).toBe("10/6 연습에서 담음 · 아직 안 함");
  });
});

describe("C4 authenticated API requests", () => {
  it.each([
    ["ALREADY_SELECTED", "이미 담은 목표예요."],
    ["RESTORE_EXPIRED", "되돌릴 수 있는 시간이 지났어요."],
    ["SCENARIO_GEN_FAILED", "연습 상황을 준비하지 못했어요."],
  ])("retains the contracted error message for %s", (code, message) => {
    expect(normalizeErrorCode(code)).toBe(code);
    expect(messageForErrorCode(normalizeErrorCode(code))).toBe(message);
  });
  it("preserves paths, exact mutation bodies, cookie credentials, and CSRF on POST/PATCH/DELETE/restore", async () => {
    const fetchImpl = vi.fn(async (path: string, init: RequestInit) => {
      if (path === "/api/session/csrf") return jsonResponse(200, { token: "test-csrf", headerName: "X-CSRF-TOKEN" });
      if (init.method === "GET") return jsonResponse(200, { items: [goal()] });
      if (init.method === "DELETE") return jsonResponse(200, { restoreUntil: RESTORE_UNTIL });
      return jsonResponse(init.method === "POST" ? 201 : 200, goal({ isFavorite: true }));
    });
    const api = createApiClient({ timeoutMs: 1000, fetchImpl });
    expect(await fetchGoalSelections(api)).toEqual([goal()]);
    await selectGoal(api, G2);
    await updateGoalFavorite(api, G1, true);
    expect(await deleteGoal(api, G1)).toEqual({ restoreUntil: RESTORE_UNTIL });
    await restoreGoal(api, G1);
    const calls = fetchImpl.mock.calls.filter(([path]) => path !== "/api/session/csrf");
    expect(calls.map(([path, init]) => [path, init.method])).toEqual([[PATH, "GET"], [PATH, "POST"], [`${PATH}/${G1}`, "PATCH"], [`${PATH}/${G1}`, "DELETE"], [`${PATH}/${G1}/restore`, "POST"]]);
    expect(calls[1][1].body).toBe(JSON.stringify({ recommendationId: G2 }));
    expect(calls[2][1].body).toBe(JSON.stringify({ isFavorite: true }));
    expect(calls[3][1].body).toBeUndefined();
    for (const [, init] of calls.slice(1)) {
      expect(init.credentials).toBe("same-origin");
      expect(init.cache).toBe("no-store");
      expect(new Headers(init.headers).get("X-CSRF-TOKEN")).toBe("test-csrf");
    }
  });

  it("retries PATCH after CSRF rejection only once, preserving the absolute favorite value", async () => {
    let patchCount = 0;
    const fetchImpl = vi.fn(async (path: string) => {
      if (path === "/api/session/csrf") return jsonResponse(200, { token: "csrf", headerName: "X-CSRF-TOKEN" });
      patchCount += 1;
      return errorResponse(403, "CSRF_TOKEN_INVALID");
    });
    const api = createApiClient({ timeoutMs: 1000, fetchImpl });
    await expect(updateGoalFavorite(api, G1, true)).rejects.toMatchObject({ status: 403, code: "CSRF_TOKEN_INVALID" });
    expect(patchCount).toBe(2);
  });

  it("normalizes future error codes while preserving HTTP 410 for restore convergence", async () => {
    const api = createApiClient({ timeoutMs: 1000, fetchImpl: async (path) => path === "/api/session/csrf" ? jsonResponse(200, { token: "csrf", headerName: "X-CSRF-TOKEN" }) : errorResponse(410, "RESTORE_EXPIRED") });
    await expect(restoreGoal(api, G1)).rejects.toMatchObject({ kind: "http", status: 410 });
  });

  it.each([
    [() => Promise.reject(new TypeError("offline")), "network"],
    [async () => jsonResponse(200, { items: [{}] }), "invalidResponse"],
    [async () => new Response("{bad", { status: 200 }), "invalidResponse"],
    [async () => new Response("bad", { status: 502 }), "http"],
  ])("makes transport/response failures explicit", async (fetchImpl, kind) => {
    const api = createApiClient({ timeoutMs: 1000, fetchImpl });
    await expect(fetchGoalSelections(api)).rejects.toMatchObject({ kind });
  });

  it("aborts a timed-out read and does not silently retry", async () => {
    vi.useFakeTimers();
    const fetchImpl = vi.fn((_path: string, init: RequestInit) => new Promise<Response>((_resolve, reject) => init.signal?.addEventListener("abort", () => reject(new TypeError("aborted")))));
    const api = createApiClient({ timeoutMs: 10, fetchImpl });
    const pending = expect(fetchGoalSelections(api)).rejects.toMatchObject({ kind: "timeout" });
    await vi.advanceTimersByTimeAsync(11);
    await pending;
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it("recognizes authentication and unknown delivery separately", () => {
    expect(new ApiError("http", 401, "UNAUTHENTICATED", null).isUnauthenticated).toBe(true);
    expect(new ApiError("network", null, "UNKNOWN", null).isDeliveryUnknown).toBe(true);
    expect(new ApiError("http", 403, "CSRF_TOKEN_INVALID", null).isDeliveryUnknown).toBe(false);
  });
});
