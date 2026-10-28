import { act, cleanup, fireEvent, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { PracticeScreen } from "@/parent/features/practice/PracticeScreen";
import { deferred, errorResponse, jsonResponse } from "../../../support/fixtures";
import { resetRouter } from "../../../support/nextNavigation";
import { renderParent } from "../../../support/readyActivityHarness";
import { goal, PATH, RESTORE_UNTIL } from "../../../support/practiceFixtures";

vi.mock("next/navigation", async () => (await import("../../../support/nextNavigation")).nextNavigationMock);
const goals = (count: number, favorites = 0) => Array.from({ length: count }, (_, i) => goal({
  goalSelectionId: `10000000-0000-4000-8000-${String(i + 1).padStart(12, "0")}`,
  displayName: `목표 ${i + 1}`, sortOrder: i + 1, isFavorite: i < favorites,
}));
async function tick() { await act(async () => { await vi.advanceTimersByTimeAsync(1); }); }
const checkbox = (n: number) => screen.getByRole("checkbox", { name: `선택 — 목표 ${n}` });
beforeEach(() => { vi.useFakeTimers(); resetRouter(); });
afterEach(() => { cleanup(); vi.useRealTimers(); vi.restoreAllMocks(); });

it("shows five saved goals per page, keeps favorites visible and preserves a check across pages", async () => {
  const rows = goals(17, 5);
  const h = renderParent(<PracticeScreen />, { handler: () => jsonResponse(200, { items: rows }) }); await tick();
  expect(screen.getAllByRole("checkbox")).toHaveLength(10);
  expect(screen.getByText("5 / 5개")).toBeVisible();
  expect(screen.getByText("1 / 3 페이지 · 전체 12개")).toBeVisible();
  fireEvent.click(checkbox(6)); fireEvent.click(screen.getByRole("button", { name: "다음" })); await tick();
  expect(screen.queryByRole("checkbox", { name: "선택 — 목표 6" })).toBeNull();
  expect(checkbox(1)).toBeVisible(); expect(checkbox(11)).not.toBeChecked();
  expect(screen.getByTestId("practice-start")).toHaveTextContent("선택한 1개");
  fireEvent.click(screen.getByRole("button", { name: "이전" })); await tick(); expect(checkbox(6)).toBeChecked();
  fireEvent.click(screen.getByRole("button", { name: "다음" }));
  fireEvent.click(screen.getByRole("button", { name: "다음" })); await tick();
  expect(screen.getAllByRole("checkbox")).toHaveLength(7);
  expect(screen.getByRole("button", { name: "다음" })).toBeDisabled();
  expect(h.requests(/activities/)).toHaveLength(0);
});

it("starts with the checked goal even when that goal is on another page", async () => {
  const rows = goals(7);
  const h = renderParent(<PracticeScreen />, { handler: (path) => path === PATH ? jsonResponse(200, { items: rows }) : errorResponse(503, "REALTIME_UNAVAILABLE") });
  await tick(); fireEvent.click(checkbox(1)); fireEvent.click(screen.getByRole("button", { name: "다음" }));
  fireEvent.click(screen.getByTestId("practice-start")); await tick();
  expect(h.requests(/activities/)[0]?.body).toMatchObject({ goalSelectionIds: [rows[0].goalSelectionId] });
});

it("disables a sixth favorite but permits removing an existing favorite", async () => {
  let rows = goals(6, 5);
  renderParent(<PracticeScreen />, { handler: (_path, init) => {
    if (init.method === "PATCH") { rows = rows.map((r, i) => i === 0 ? { ...r, isFavorite: false } : r); return jsonResponse(200, rows[0]); }
    return jsonResponse(200, { items: rows });
  } }); await tick();
  expect(screen.getByRole("button", { name: "즐겨찾기 추가 — 목표 6" })).toBeDisabled();
  fireEvent.click(screen.getByRole("button", { name: "즐겨찾기 해제 — 목표 1" })); await tick();
  expect(screen.getByRole("button", { name: "즐겨찾기 추가 — 목표 6" })).toBeEnabled();
});

it("reserves the last slot during rapid concurrent clicks", async () => {
  const rows = goals(6, 4), patch = deferred<Response>();
  const h = renderParent(<PracticeScreen />, { handler: (_path, init) => init.method === "PATCH" ? patch.promise : jsonResponse(200, { items: rows }) });
  await tick();
  fireEvent.click(screen.getByRole("button", { name: "즐겨찾기 추가 — 목표 5" }));
  fireEvent.click(screen.getByRole("button", { name: "즐겨찾기 추가 — 목표 6" })); await tick();
  expect(h.requests(/goal-selections\//)).toHaveLength(1);
  patch.resolve(jsonResponse(200, { ...rows[4], isFavorite: true })); await tick();
  expect(screen.getByRole("button", { name: "즐겨찾기 추가 — 목표 6" })).toBeDisabled();
});

it("shows the server limit refusal without losing the goal or check", async () => {
  const rows = goals(1);
  renderParent(<PracticeScreen />, { handler: (_path, init) => init.method === "PATCH" ? errorResponse(409, "FAVORITE_LIMIT_REACHED") : jsonResponse(200, { items: rows }) });
  await tick(); fireEvent.click(checkbox(1)); fireEvent.click(screen.getByRole("button", { name: "즐겨찾기 추가 — 목표 1" })); await tick();
  expect(checkbox(1)).toBeChecked();
  expect(screen.getByTestId("toast")).toHaveTextContent("먼저 다른 목표의 즐겨찾기를 해제");
  expect(screen.getByRole("button", { name: "즐겨찾기 추가 — 목표 1" })).toBeEnabled();
});

it("clamps the last page after deletion and keeps undo available", async () => {
  const rows = goals(6);
  renderParent(<PracticeScreen />, { handler: (_path, init) => init.method === "DELETE" ? jsonResponse(200, { restoreUntil: RESTORE_UNTIL }) : jsonResponse(200, { items: rows }) });
  await tick(); fireEvent.click(screen.getByRole("button", { name: "다음" }));
  fireEvent.click(screen.getByRole("button", { name: "삭제 — 목표 6" })); await tick();
  expect(screen.getAllByRole("checkbox")).toHaveLength(5);
  expect(screen.queryByRole("navigation", { name: "담은 목표 페이지" })).toBeNull();
  expect(screen.getByRole("button", { name: "되돌리기" })).toBeVisible();
});

it("explains a full favorite list when undo is refused and leaves the goal deleted", async () => {
  const rows = goals(1, 1);
  let allowRestore = false;
  renderParent(<PracticeScreen />, { handler: (path, init) => path.endsWith("/restore") ? allowRestore ? jsonResponse(200, rows[0]) : errorResponse(409, "FAVORITE_LIMIT_REACHED")
    : init.method === "DELETE" ? jsonResponse(200, { restoreUntil: RESTORE_UNTIL }) : jsonResponse(200, { items: rows }) });
  await tick(); fireEvent.click(screen.getByRole("button", { name: "삭제 — 목표 1" })); await tick();
  fireEvent.click(screen.getByRole("button", { name: "되돌리기" })); await tick();
  expect(screen.getByTestId("toast")).toHaveTextContent("먼저 다른 목표의 즐겨찾기를 해제");
  expect(screen.queryByRole("checkbox", { name: "선택 — 목표 1" })).toBeNull();
  expect(screen.queryByTestId("practice-recovery")).toBeNull();
  allowRestore = true; fireEvent.click(screen.getByRole("button", { name: "다시 시도" })); await tick();
  expect(checkbox(1)).toBeVisible();
});

it("allows reducing pre-existing overflow favorites without hiding or deleting them", async () => {
  const rows = goals(7, 6);
  renderParent(<PracticeScreen />, { handler: () => jsonResponse(200, { items: rows }) }); await tick();
  expect(screen.getAllByRole("checkbox")).toHaveLength(7);
  for (let n = 1; n <= 6; n++) expect(screen.getByRole("button", { name: `즐겨찾기 해제 — 목표 ${n}` })).toBeEnabled();
  expect(screen.getByRole("button", { name: "즐겨찾기 추가 — 목표 7" })).toBeDisabled();
  expect(within(screen.getByRole("region", { name: /즐겨찾기/ })).getAllByRole("listitem")).toHaveLength(6);
});
