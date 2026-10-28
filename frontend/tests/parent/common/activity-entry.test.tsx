import { act, cleanup, fireEvent, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { FreeTalkScreen } from "@/parent/features/free/FreeTalkScreen";
import { PracticeScreen } from "@/parent/features/practice/PracticeScreen";
import { RecordsScreen } from "@/parent/features/records/RecordsScreen";
import { renderParent } from "../../support/parentHarness";
import { errorResponse, homeBody, jsonResponse, liveViewBody, SESSION_ID, deferred } from "../../support/fixtures";
import { GOALS } from "../../support/practiceFixtures";
import { resetRouter, router } from "../../support/nextNavigation";

vi.mock("next/navigation", async () => ({ ...(await import("../../support/nextNavigation")).nextNavigationMock, useSearchParams: () => new URLSearchParams() }));
class Events extends EventTarget {
  static instances: Events[] = [];
  onerror: (() => void) | null = null;
  close = vi.fn();
  constructor(readonly url: string) { super(); Events.instances.push(this); }
}
const record = { sessionId: SESSION_ID, activityType: "FREE_TALK", startedAt: "2026-10-15T01:00:00Z", endedAt: "2026-10-15T01:03:00Z", finalStatus: "ENDED", linkable: true, goalCount: 0, interrupted: false };
const views = {
  free: <FreeTalkScreen returnedFailed={false} />,
  practice: <PracticeScreen />,
  records: <RecordsScreen />,
};
function setup(kind: keyof typeof views, home: () => Response | Promise<Response>) {
  return renderParent(views[kind], {
    timing: { parentPollIntervalMs: 40, homeReconcileIntervalMs: 10000 },
    handler: path => path === "/api/home" ? home()
      : path === "/api/goal-selections" ? jsonResponse(200, { items: GOALS })
        : path.startsWith("/api/records?month=") ? jsonResponse(200, { days: [] })
          : path.startsWith("/api/records?date=") ? jsonResponse(200, { items: [record] })
            : path.startsWith("/api/reports?") ? jsonResponse(200, { items: [], hasMore: false }) : errorResponse(503, "UNEXPECTED_REQUEST"),
  });
}
beforeEach(() => { resetRouter(); Events.instances = []; vi.stubGlobal("EventSource", Events); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
function emit(name: string) { act(() => { Events.instances.at(-1)!.dispatchEvent(new Event(name)); }); }

it.each(["free", "practice", "records"] as const)("%s에서 SSE 끊김·재연결을 탭과 시작에 반영하고 내용을 보존한다", async kind => {
  let disconnected = false;
  const h = setup(kind, () => jsonResponse(200, homeBody(disconnected ? "DISCONNECTED" : "STABLE")));
  await waitFor(() => expect(screen.getByTestId("tab-free")).not.toHaveAttribute("aria-disabled"));
  emit("ready");
  if (kind === "free") fireEvent.change(screen.getByTestId("free-known-content"), { target: { value: "입력한 참고 내용" } });
  if (kind === "practice") fireEvent.click(await screen.findByRole("checkbox", { name: "선택 — 친구에게 부탁하기" }));
  if (kind !== "records") await waitFor(() => expect(screen.getByTestId(`${kind}-start`)).toBeEnabled());
  disconnected = true; emit("home-changed");
  await waitFor(() => expect(screen.getByTestId("tab-free")).toHaveAttribute("aria-disabled", "true"));
  expect(screen.getByTestId("tab-practice")).toHaveAttribute("aria-disabled", "true");
  expect(screen.getByTestId("tab-records")).not.toHaveAttribute("aria-disabled");
  if (kind !== "records") expect(screen.getByTestId(`${kind}-start`)).toBeDisabled();
  fireEvent.click(screen.getByTestId("tab-free")); expect(router.push).toHaveBeenLastCalledWith("/parent/device");
  if (kind === "records") {
    fireEvent.click(screen.getByTestId(`records-item-${SESSION_ID}`));
    expect(router.push).toHaveBeenLastCalledWith(`/parent/records/${SESSION_ID}`);
  }
  disconnected = false; emit("home-changed");
  await waitFor(() => expect(screen.getByTestId("tab-practice")).not.toHaveAttribute("aria-disabled"));
  if (kind !== "records") expect(screen.getByTestId(`${kind}-start`)).toBeEnabled();
  if (kind === "free") expect(screen.getByTestId("free-known-content")).toHaveValue("입력한 참고 내용");
  if (kind === "practice") expect(screen.getByRole("checkbox", { name: "선택 — 친구에게 부탁하기" })).toBeChecked();
  expect(h.requests(/\/api\/activities\//)).toHaveLength(0);
  expect(Events.instances).toHaveLength(1); expect(Events.instances[0].url).toBe("/api/home/events");
  h.view.unmount(); expect(Events.instances[0].close).toHaveBeenCalledOnce();
});

it.each(["free", "practice"] as const)("%s는 최초 상태를 확인하기 전 시작 요청을 보내지 않는다", async kind => {
  const response = deferred<Response>(); const h = setup(kind, () => response.promise);
  if (kind === "practice") fireEvent.click(await screen.findByRole("checkbox", { name: "선택 — 친구에게 부탁하기" }));
  const start = screen.getByTestId(`${kind}-start`); expect(start).toBeDisabled(); fireEvent.click(start);
  if (kind === "free") fireEvent.submit(screen.getByTestId("free-form"));
  expect(h.requests(/\/api\/activities\//)).toHaveLength(0);
  await act(async () => { response.resolve(jsonResponse(200, homeBody("STABLE"))); });
  await waitFor(() => expect(start).toBeEnabled());
});

it.each(["ACTIVE", "CLOSING"])("기록의 대화 탭은 %s 활동이 있으면 새 시작 대신 진행 화면을 연다", async status => {
  setup("records", () => jsonResponse(200, { ...homeBody("DISCONNECTED"), currentSession: liveViewBody(status) }));
  await waitFor(() => expect(screen.getByTestId("tab-free")).not.toHaveAttribute("aria-disabled"));
  fireEvent.click(screen.getByTestId("tab-practice"));
  expect(router.push).toHaveBeenLastCalledWith(`/parent/sessions/${SESSION_ID}${status === "CLOSING" ? "/done" : ""}`);
});

it("기록 조회는 유지하면서 상태 조회 실패 때 시작 진입을 잠그고 재조회 후 복구한다", async () => {
  let fail = false; setup("records", () => fail ? errorResponse(500, "INTERNAL_ERROR") : jsonResponse(200, homeBody("STABLE")));
  await screen.findByTestId(`records-item-${SESSION_ID}`); emit("ready");
  fail = true; emit("home-changed");
  await waitFor(() => expect(screen.getByTestId("tab-free")).toHaveAttribute("aria-disabled", "true"));
  expect(screen.getByTestId(`records-item-${SESSION_ID}`)).toBeVisible();
  fail = false; emit("home-changed");
  await waitFor(() => expect(screen.getByTestId("tab-free")).not.toHaveAttribute("aria-disabled"));
});

it("SSE를 사용할 수 없으면 기존 조회 복구로 자유대화 시작 상태를 갱신한다", async () => {
  vi.stubGlobal("EventSource", undefined); let disconnected = false;
  setup("free", () => jsonResponse(200, homeBody(disconnected ? "DISCONNECTED" : "STABLE")));
  await waitFor(() => expect(screen.getByTestId("free-start")).toBeEnabled());
  disconnected = true;
  await waitFor(() => expect(screen.getByTestId("free-start")).toBeDisabled());
});
