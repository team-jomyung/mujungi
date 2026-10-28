import { act, cleanup, fireEvent, screen } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { PracticeScreen } from "@/parent/features/practice/PracticeScreen";
import { deferred, errorResponse, jsonResponse } from "../../../support/fixtures";
import { resetRouter } from "../../../support/nextNavigation";
import { renderParent } from "../../../support/readyActivityHarness";
import { G1, G2, GOALS, RESTORE_UNTIL, goal } from "../../../support/practiceFixtures";

vi.mock("next/navigation", async () => (await import("../../../support/nextNavigation")).nextNavigationMock);

class Source extends EventTarget {
  static instances: Source[] = [];
  onerror: (() => void) | null = null;
  close = vi.fn();
  constructor() { super(); Source.instances.push(this); }
}
const extra = goal({ goalSelectionId: "10000000-0000-4000-8000-000000000004", displayName: "새 목표", sortOrder: 4 });
async function tick(ms = 1) { await act(async () => { await vi.advanceTimersByTimeAsync(ms); }); }
async function signal(name = "goal-selections-changed") {
  act(() => { Source.instances.at(-1)!.dispatchEvent(new Event(name)); }); await tick();
}
beforeEach(() => { vi.useFakeTimers(); resetRouter(); Source.instances = []; vi.stubGlobal("EventSource", Source); });
afterEach(() => { cleanup(); vi.useRealTimers(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

it("adds generated goals through the shared SSE while preserving the manual check and never starting", async () => {
  let rows = GOALS;
  const h = renderParent(<PracticeScreen />, { handler: () => jsonResponse(200, { items: rows }) });
  await tick();
  fireEvent.click(screen.getByRole("checkbox", { name: "선택 — 친구에게 부탁하기" }));
  rows = [...GOALS, extra]; await signal();
  expect(screen.getByRole("checkbox", { name: "선택 — 새 목표" })).not.toBeChecked();
  expect(screen.getByRole("checkbox", { name: "선택 — 친구에게 부탁하기" })).toBeChecked();
  expect(Source.instances).toHaveLength(1);
  expect(h.requests(/activities/)).toHaveLength(0);
  h.view.unmount(); expect(Source.instances[0].close).toHaveBeenCalled();
});

it("reads again when a signal arrives during an older GET", async () => {
  const stale = deferred<Response>(); let reads = 0;
  renderParent(<PracticeScreen />, { handler: () => ++reads === 1 ? stale.promise : jsonResponse(200, { items: [...GOALS, extra] }) });
  await signal(); stale.resolve(jsonResponse(200, { items: GOALS })); await tick();
  expect(screen.getByRole("checkbox", { name: "선택 — 새 목표" })).not.toBeChecked();
  expect(reads).toBe(2);
});

it("defers refresh during favorite changes and preserves the acknowledged edit", async () => {
  const patch = deferred<Response>(); let reads = 0; let rows = GOALS;
  renderParent(<PracticeScreen />, { handler: (_path, init) => {
    if (init.method === "PATCH") return patch.promise;
    reads++; return jsonResponse(200, { items: rows });
  } });
  await tick(); fireEvent.click(screen.getByRole("button", { name: "즐겨찾기 추가 — 친구에게 부탁하기" })); await tick();
  rows = [...GOALS.filter(g => g.goalSelectionId !== G1), goal({ isFavorite: true }), extra];
  await signal(); expect(reads).toBe(1);
  patch.resolve(jsonResponse(200, goal({ isFavorite: true }))); await tick(); await tick();
  expect(screen.getByRole("button", { name: "즐겨찾기 해제 — 친구에게 부탁하기" })).toBeVisible();
  expect(screen.getByRole("checkbox", { name: "선택 — 새 목표" })).toBeVisible();
});

it("does not dismiss undo or restore a deleted row on an automatic refresh", async () => {
  let rows = GOALS;
  renderParent(<PracticeScreen />, { handler: (_path, init) => {
    if (init.method === "DELETE") { rows = GOALS.filter(g => g.goalSelectionId !== G2); return jsonResponse(200, { restoreUntil: RESTORE_UNTIL }); }
    return jsonResponse(200, { items: rows });
  } });
  await tick(); fireEvent.click(screen.getByRole("button", { name: "삭제 — 차례 기다리기" })); await tick();
  await signal();
  expect(screen.queryByTestId(`goal-${G2}`)).toBeNull();
  expect(screen.getByRole("button", { name: "되돌리기" })).toBeVisible();
});

it("reconciles lost signals on the configured interval and stops reads after access denial", async () => {
  let rows = GOALS; let denied = false; let reads = 0;
  renderParent(<PracticeScreen />, { timing: { homeReconcileIntervalMs: 1000 }, handler: () => {
    reads++; return denied ? errorResponse(403, "FORBIDDEN") : jsonResponse(200, { items: rows });
  } });
  await tick(); rows = [...GOALS, extra]; await tick(1000);
  expect(screen.getByRole("checkbox", { name: "선택 — 새 목표" })).toBeVisible();
  denied = true; await signal(); const stopped = reads; await tick(3000); await signal();
  expect(reads).toBe(stopped);
});

it("pauses while hidden and catches up on foreground and SSE reconnection", async () => {
  let rows = GOALS; let reads = 0;
  const visible = vi.spyOn(document, "visibilityState", "get").mockReturnValue("visible");
  renderParent(<PracticeScreen />, { timing: { homeReconcileIntervalMs: 1000 }, handler: () => {
    reads++; return jsonResponse(200, { items: rows });
  } });
  await tick(); const before = reads;
  visible.mockReturnValue("hidden");
  act(() => { document.dispatchEvent(new Event("visibilitychange")); });
  await tick(2000); expect(reads).toBe(before);
  expect(Source.instances[0].close).toHaveBeenCalled();
  rows = [...GOALS, extra]; visible.mockReturnValue("visible");
  act(() => { document.dispatchEvent(new Event("visibilitychange")); }); await tick();
  expect(screen.getByRole("checkbox", { name: "선택 — 새 목표" })).not.toBeChecked();
  const afterForeground = reads; await signal("ready"); expect(reads).toBeGreaterThan(afterForeground);
});
