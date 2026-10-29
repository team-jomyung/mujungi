import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { ReportRangeScreen } from "@/parent/features/reports/ReportRangeScreen";
import { renderParent, jsonResponse, navigation, clock, resetRouter, REPORT_ID, deferred } from "../../../support/resultSupport";
vi.mock("next/navigation", async () => ({ ...(await import("../../../support/nextNavigation")).nextNavigationMock, useSearchParams: () => navigation.search }));
beforeEach(() => { clock(); resetRouter(); });
afterEach(() => vi.useRealTimers());
const activities = Array.from({ length: 23 }, (_, i) => ({
  sessionId: `00000000-0000-4000-8000-${String(i + 1).padStart(12, "0")}`,
  date: "2026-10-15", activityType: i % 2 === 0 ? "FREE_TALK" : "PRACTICE", startedAt: `2026-10-15T01:${String(i).padStart(2, "0")}:00Z`,
}));
const row = (i: number) => screen.getByTestId(`report-range-item-${activities[i].sessionId}`);
const count = () => screen.getByTestId("report-selection-count");
const next = () => fireEvent.click(within(screen.getByRole("navigation", { name: "대화 목록 페이지" })).getByRole("button", { name: "다음" }));
const previous = () => fireEvent.click(within(screen.getByRole("navigation", { name: "대화 목록 페이지" })).getByRole("button", { name: "이전" }));
function render() {
  return renderParent(<ReportRangeScreen />, { handler: path => jsonResponse(200, path === "/api/reports" ? { reportId: REPORT_ID, status: "READY" } : { firstRecordDate: "2026-09-01", activities }) });
}
it("자동 선택 없이 10개씩 표시하고 페이지·종류를 바꿔도 선택한 대화만 요청한다", async () => {
  const h = render(); await screen.findAllByRole("checkbox");
  expect(screen.getAllByRole("checkbox")).toHaveLength(10);
  expect(count()).toHaveTextContent("선택 0개");
  expect(screen.getByTestId("report-range-create")).toBeDisabled();
  fireEvent.click(row(0)); expect(row(0)).toHaveAttribute("aria-checked", "true"); expect(row(0)).not.toHaveTextContent("선택");
  next(); fireEvent.click(row(10));
  next(); expect(screen.getAllByRole("checkbox")).toHaveLength(3); fireEvent.click(row(22));
  expect(count()).toHaveTextContent("선택 3개");
  previous(); expect(row(10)).toHaveAttribute("aria-checked", "true");
  previous(); expect(row(0)).toHaveAttribute("aria-checked", "true");
  fireEvent.change(screen.getByRole("combobox", { name: "대화 종류" }), { target: { value: "PRACTICE" } });
  expect(screen.queryByTestId(`report-range-item-${activities[0].sessionId}`)).toBeNull();
  expect(count()).toHaveTextContent("선택 3개");
  fireEvent.click(screen.getByTestId("report-range-create"));
  await waitFor(() => expect(h.requests("/api/reports")).toHaveLength(1));
  expect(h.requests("/api/reports")[0].body).toMatchObject({ sessionIds: [activities[0].sessionId, activities[10].sessionId, activities[22].sessionId] });
});
it("10개를 넘어 페이지와 종류를 바꿔도 선택을 유지하고 전체 선택 대화를 요청한다", async () => {
  const h = render(); await screen.findAllByRole("checkbox");
  screen.getAllByRole("checkbox").forEach(item => fireEvent.click(item));
  next(); expect(row(10)).toBeEnabled();
  screen.getAllByRole("checkbox").forEach(item => fireEvent.click(item));
  next(); screen.getAllByRole("checkbox").forEach(item => fireEvent.click(item));
  expect(count()).toHaveTextContent("선택 23개");
  previous(); expect(row(10)).toHaveAttribute("aria-checked", "true");
  previous(); expect(row(0)).toHaveAttribute("aria-checked", "true");
  fireEvent.click(row(0)); expect(count()).toHaveTextContent("선택 22개");
  fireEvent.change(screen.getByRole("combobox", { name: "대화 종류" }), { target: { value: "PRACTICE" } });
  expect(count()).toHaveTextContent("선택 22개");
  fireEvent.click(screen.getByTestId("report-range-create"));
  await waitFor(() => expect(h.requests("/api/reports")).toHaveLength(1));
  expect(h.requests("/api/reports")[0].body).toMatchObject({ sessionIds: activities.slice(1).map(a => a.sessionId) });
});
it("10개 넘게 고른 뒤에도 선택 모두 해제로 초기화한다", async () => {
  render(); await screen.findAllByRole("checkbox");
  screen.getAllByRole("checkbox").forEach(item => fireEvent.click(item));
  next(); fireEvent.click(row(10)); expect(count()).toHaveTextContent("선택 11개");
  fireEvent.click(screen.getByRole("button", { name: "선택 모두 해제" }));
  expect(count()).toHaveTextContent("선택 0개"); expect(row(10)).toHaveAttribute("aria-checked", "false");
  expect(screen.getByTestId("report-range-create")).toBeDisabled();
});
it("기간 변경 시 선택과 페이지를 초기화하고 이전 기간 목록으로는 생성하지 않는다", async () => {
  const pending = deferred<Response>();
  renderParent(<ReportRangeScreen />, { handler: path => path.includes("from=2026-10-01") ? pending.promise : jsonResponse(200, { firstRecordDate: "2026-09-01", activities }) });
  await screen.findAllByRole("checkbox"); next(); fireEvent.click(row(10));
  fireEvent.click(screen.getByTestId("report-range-quick-month"));
  expect(count()).toHaveTextContent("선택 0개"); expect(row(0)).toBeDisabled(); expect(screen.getByTestId("report-range-create")).toBeDisabled();
  pending.resolve(jsonResponse(200, { firstRecordDate: "2026-09-01", activities: activities.slice(10, 12) }));
  await waitFor(() => expect(row(10)).toBeEnabled());
  expect(row(10)).toHaveAttribute("aria-checked", "false"); expect(screen.queryByRole("navigation", { name: "대화 목록 페이지" })).toBeNull();
});
it("활동 고르기 줄은 결과 요약이 있을 때만 요약을 보인다", async () => {
  const withSummary = [{ ...activities[0], summary: "블록을 같이 쌓자고 먼저 말했어요." }, { ...activities[1], summary: null }];
  renderParent(<ReportRangeScreen />, { handler: () => jsonResponse(200, { firstRecordDate: "2026-09-01", activities: withSummary }) });
  expect(await screen.findByTestId(`report-range-item-summary-${activities[0].sessionId}`)).toHaveTextContent("블록을 같이 쌓자고 먼저 말했어요.");
  expect(row(0)).toHaveAttribute("role", "checkbox");
  expect(screen.queryByTestId(`report-range-item-summary-${activities[1].sessionId}`)).toBeNull();
});
