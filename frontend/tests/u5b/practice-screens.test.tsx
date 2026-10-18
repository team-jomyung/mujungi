import { act, cleanup, fireEvent, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { parseHome, parseLiveView } from "@/parent/api/types";
import { mergeLiveView } from "@/parent/api/queries";
import { PracticeScreen } from "@/parent/features/practice/PracticeScreen";
import ParentPracticePage from "@/app/parent/practice/page";
import { LiveScreen } from "@/parent/features/live/LiveScreen";
import { SESSION_ID, OTHER_SESSION_ID, deferred, errorResponse, homeBody, jsonResponse, liveViewBody } from "../u1/support/fixtures";
import { resetRouter, router } from "../u1/support/nextNavigation";
import { renderParent, type RequestHandler } from "../u1/support/parentHarness";
import { G1, G2, GOALS, PATH } from "../u5a/fixtures";

vi.mock("next/navigation", async () => (await import("../u1/support/nextNavigation")).nextNavigationMock);
const START = "/api/activities/practice";
const progress = { goalIndex: 1, goalCount: 2, goalName: "친구에게 부탁하기", stage: "FIRST_RESPONSE" };
const check = () => screen.getByRole("checkbox", { name: "선택 — 친구에게 부탁하기" });
const start = () => screen.getByTestId("practice-start");
async function advance(ms = 1) { await act(async () => { await vi.advanceTimersByTimeAsync(ms); }); }
function setupStart(handler: RequestHandler) {
  return renderParent(<PracticeScreen />, { handler: (path, init) => path === PATH ? jsonResponse(200, { items: GOALS }) : handler(path, init) });
}
function setupLive(handler: RequestHandler) {
  return renderParent(<LiveScreen sessionId={SESSION_ID} />, {
    handler, timing: { parentPollIntervalMs: 40 },
    prepare: (client) => client.setQueryData(["home"], parseHome(homeBody("STABLE"))),
  });
}
beforeEach(() => { vi.useFakeTimers(); resetRouter(); });
afterEach(() => { cleanup(); vi.useRealTimers(); vi.restoreAllMocks(); });

it("18은 선택만으로 시작하지 않고 CSRF와 목표 ID를 보내 수락된 세션으로 한 번 이동한다", async () => {
  const pending = deferred<Response>(); const h = setupStart(() => pending.promise);
  await advance(); expect(start()).toBeDisabled(); fireEvent.click(check()); expect(start()).toBeEnabled();
  expect(h.requests(START)).toHaveLength(0); fireEvent.click(start()); fireEvent.click(start()); await advance();
  expect(start()).toBeDisabled(); expect(check()).toBeDisabled(); expect(h.requests(START)).toHaveLength(1);
  expect(h.requests(START)[0].body).toEqual({ idempotencyKey: expect.any(String), goalSelectionIds: [G1] });
  expect(h.requests(START)[0].headers["x-csrf-token"]).toBe(h.csrfToken);
  pending.resolve(jsonResponse(202, { sessionId: SESSION_ID, status: "REQUESTED", goalCount: 1 })); await advance();
  expect(router.push).toHaveBeenCalledExactlyOnceWith(`/parent/sessions/${SESSION_ID}`);
});

it.each(["network", "pending", "invalid", "server"])("18의 %s 미확인 결과는 선택과 키를 보존하여 다시 확인한다", async (kind) => {
  let calls = 0; const h = setupStart(() => {
    if (++calls > 1) return jsonResponse(202, { sessionId: SESSION_ID });
    if (kind === "network") throw new TypeError("연결 실패");
    if (kind === "server") return errorResponse(500, "INTERNAL_ERROR");
    return kind === "pending" ? errorResponse(503, "SERVICE_NOT_READY") : jsonResponse(202, {});
  });
  await advance(); fireEvent.click(check()); fireEvent.click(start()); await advance();
  expect(screen.getByText(/시작 결과를 확인하지 못했어요/)).toBeVisible(); expect(check()).toBeDisabled();
  expect(start()).toBeEnabled(); expect(router.push).not.toHaveBeenCalled();
  fireEvent.click(start()); await advance();
  expect(h.requests(START)).toHaveLength(2); expect(h.requests(START)[1].body).toEqual(h.requests(START)[0].body);
  expect(router.push).toHaveBeenCalledTimes(1);
});

it("18으로 돌아온 시작 실패는 실제 경로의 사유를 표시한다", async () => {
  const page = await ParentPracticePage({ searchParams: Promise.resolve({ failed: "1", reason: "SCENARIO_GEN_FAILED" }) });
  renderParent(page, { handler: () => jsonResponse(200, { items: GOALS }) }); await advance();
  expect(screen.getByText("연습 상황을 만들지 못했어요. 잠시 뒤 다시 시도해 주세요.")).toHaveAttribute("role", "alert");
});

it("12 준비 중에는 목표 순번을 지어내지 않고 연습 제목만 표시한다", async () => {
  setupLive(() => jsonResponse(200, liveViewBody("PREPARING", { activityType: "PRACTICE", practice: null })));
  await advance(); expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(/^연습$/);
  expect(screen.getByTestId("live-status")).toHaveTextContent("준비하고 있어요");
  expect(screen.queryByText("현재 연습 단계를 확인할 수 없어요.")).toBeNull(); expect(screen.getByTestId("live-end")).toBeDisabled();
});

it("18 확정 상황 실패는 선택을 유지하고 명시 재시도에 새 키를 쓴다", async () => {
  let calls = 0; const h = setupStart(() => ++calls === 1 ? errorResponse(422, "SCENARIO_GEN_FAILED") : jsonResponse(202, { sessionId: SESSION_ID }));
  await advance(); fireEvent.click(check()); fireEvent.click(start()); await advance();
  expect(screen.getByText("연습 상황을 만들지 못했어요. 잠시 뒤 다시 시도해 주세요.")).toBeVisible();
  expect(check()).toBeChecked(); expect(check()).toBeEnabled(); fireEvent.click(start()); await advance();
  const bodies = h.requests(START).map((r) => r.body as { idempotencyKey: string; goalSelectionIds: string[] });
  expect(bodies[1].idempotencyKey).not.toBe(bodies[0].idempotencyKey); expect(bodies[1].goalSelectionIds).toEqual([G1]);
});

it("18은 삭제 대기 중 숨긴 목표를 시작 본문에 넣지 않는다", async () => {
  const h = setupStart(() => jsonResponse(202, { sessionId: SESSION_ID })); await advance();
  fireEvent.click(check()); fireEvent.click(screen.getByRole("checkbox", { name: "선택 — 차례 기다리기" }));
  fireEvent.click(screen.getByRole("button", { name: "삭제 — 친구에게 부탁하기" })); fireEvent.click(start()); await advance();
  expect(h.requests(START)[0].body).toEqual({ idempotencyKey: expect.any(String), goalSelectionIds: [G2] });
  expect(h.requests(`${PATH}/${G1}`)).toHaveLength(0);
});

it("18 시작 중 401은 공통 로그인 안내로 이동한다", async () => {
  setupStart(() => errorResponse(401, "UNAUTHENTICATED")); await advance(); fireEvent.click(check()); fireEvent.click(start()); await advance();
  expect(router.replace).toHaveBeenCalledWith("/parent/login"); expect(router.push).not.toHaveBeenCalled();
  expect(screen.getByTestId("toast")).toHaveTextContent("로그인이 필요해요.");
});

it("18을 떠난 뒤 늦은 시작 수락은 화면을 다시 이동시키지 않는다", async () => {
  const pending = deferred<Response>(); const h = setupStart(() => pending.promise);
  await advance(); fireEvent.click(check()); fireEvent.click(start()); await advance(); h.view.unmount();
  pending.resolve(jsonResponse(202, { sessionId: SESSION_ID })); await advance(); expect(router.push).not.toHaveBeenCalled();
});

it("12 실제 조회에서 연습 머리와 정본 단계, 부제, 끝내기, 탭을 표시한다", async () => {
  const h = setupLive(() => jsonResponse(200, liveViewBody("ACTIVE", { activityType: "PRACTICE", practice: { ...progress, judgmentCriteria: "비밀 기준", scenario: "비공개 상황" } })));
  await advance(); expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("연습 · 1/2번째 목표 · 친구에게 부탁하기");
  expect(screen.getByText("먼저 말해 보는 중")).toBeVisible(); expect(screen.getByTestId("live-status")).toHaveTextContent("하늘이 연습하고 있어요");
  expect(screen.getByTestId("live-end")).toHaveTextContent("연습 끝내기"); expect(screen.getByTestId("tab-practice")).toHaveAttribute("aria-selected", "true");
  expect(document.body.textContent).not.toMatch(/비밀 기준|비공개 상황/); expect(h.requests("/api/home")).toHaveLength(0);
});

it("12 연습 조회 실패와 잘못된 진행은 마지막 확인 내용을 보존한다", async () => {
  let calls = 0; setupLive(() => ++calls === 1 ? jsonResponse(200, liveViewBody("ACTIVE", { activityType: "PRACTICE", practice: progress }))
    : calls === 2 ? errorResponse(503, "UPSTREAM_UNAVAILABLE") : jsonResponse(200, liveViewBody("ACTIVE", { activityType: "PRACTICE", practice: { ...progress, stage: "INTERNAL" } })));
  await advance(); await advance(42); expect(screen.getByText(/마지막 확인 내용을 표시해요/)).toBeVisible();
  expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("1/2번째 목표");
  await advance(42); expect(screen.getByText("현재 연습 단계를 확인할 수 없어요.")).toBeVisible();
  expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("1/2번째 목표");
});

it("12는 다른 세션 응답을 거절하고 낮은 상태 버전으로 진행을 되돌리지 않는다", async () => {
  let calls = 0; setupLive(() => jsonResponse(200, liveViewBody("ACTIVE", calls++ === 0
    ? { activityType: "PRACTICE", practice: progress, statusVersion: 4 }
    : { sessionId: OTHER_SESSION_ID, activityType: "PRACTICE", practice: { ...progress, goalIndex: 2 }, statusVersion: 5 })));
  await advance(); await advance(42); expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("1/2번째 목표");
  const prior = parseLiveView(liveViewBody("ACTIVE", { activityType: "PRACTICE", practice: progress, statusVersion: 4 }));
  const old = parseLiveView(liveViewBody("ACTIVE", { activityType: "PRACTICE", practice: { ...progress, goalIndex: 2 }, statusVersion: 3 }));
  expect(mergeLiveView(prior, old)).toEqual(prior);
});

it("12 연습 시작 실패는 18로 돌아가며 자유대화와 완료 이동은 유지한다", async () => {
  const h = setupLive(() => jsonResponse(200, liveViewBody("FAILED", { activityType: "PRACTICE", failureCode: "DEVICE_NO_ACK", startedAt: null })));
  await advance(); expect(router.replace).toHaveBeenCalledWith("/parent/practice?failed=1&reason=DEVICE_NO_ACK");
  h.view.unmount(); resetRouter(); setupLive(() => jsonResponse(200, liveViewBody("CLOSING", { activityType: "PRACTICE", practice: progress })));
  await advance(); expect(router.replace).toHaveBeenCalledWith(`/parent/sessions/${SESSION_ID}/done`);
});

it("12 연습 끝내기는 기존 P1과 멱등 종료 API를 사용한다", async () => {
  const h = setupLive((path) => path.endsWith("/end") ? jsonResponse(202, { status: "CLOSING" }) : jsonResponse(200, liveViewBody("ACTIVE", { activityType: "PRACTICE", practice: progress })));
  await advance(); fireEvent.click(screen.getByTestId("live-end"));
  fireEvent.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "네, 끝낼게요" })); await advance();
  expect(h.requests(`/api/sessions/${SESSION_ID}/end`)).toHaveLength(1); expect(router.push).toHaveBeenCalledWith(`/parent/sessions/${SESSION_ID}/done`);
});
