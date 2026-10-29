import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, afterEach, expect, it, vi } from "vitest";
import { RecordsScreen } from "@/parent/features/records/RecordsScreen";
import { renderParent, jsonResponse, navigation, clock, SESSION_ID, router, resetRouter } from "../../../support/resultSupport";
vi.mock("next/navigation", async () => ({ ...(await import("../../../support/nextNavigation")).nextNavigationMock, useSearchParams: () => navigation.search }));
beforeEach(() => { clock(); resetRouter(); }); afterEach(() => vi.useRealTimers());
const item = (extra = {}) => ({ sessionId: SESSION_ID, activityType: "FREE_TALK", startedAt: "2026-10-15T01:00:00Z", endedAt: "2026-10-15T01:03:00Z", finalStatus: "ENDED", linkable: true, goalCount: 0, interrupted: false, ...extra });
function render(items = [item()], days = [{ date: "2026-10-15", count: 1 }]) { return renderParent(<RecordsScreen />, { handler: path => jsonResponse(200, path.includes("month=") ? { days } : { items }) }); }
it("자유대화 시간과 분을 표시하고 상세로 간다", async () => { render(); const row = await screen.findByTestId(`records-item-${SESSION_ID}`); expect(row).toHaveTextContent("10:00 자유대화 · 3분"); fireEvent.click(row); expect(router.push).toHaveBeenCalledWith(`/parent/records/${SESSION_ID}`); });
it("콤보박스로 전체·자유대화·연습대화를 구분하고 필터가 비면 안내한다", async () => {
  const practiceId = "00000000-0000-4000-8000-000000000088";
  render([item(), item({ sessionId: practiceId, activityType: "PRACTICE" })]);
  await screen.findByTestId(`records-item-${SESSION_ID}`);
  const filter = screen.getByRole("combobox", { name: "대화 종류" });
  fireEvent.change(filter, { target: { value: "FREE_TALK" } });
  expect(screen.getByTestId(`records-item-${SESSION_ID}`)).toBeInTheDocument();
  expect(screen.queryByTestId(`records-item-${practiceId}`)).toBeNull();
  fireEvent.change(filter, { target: { value: "PRACTICE" } });
  expect(screen.queryByTestId(`records-item-${SESSION_ID}`)).toBeNull();
  expect(screen.getByTestId(`records-item-${practiceId}`)).toBeInTheDocument();
  fireEvent.change(filter, { target: { value: "ALL" } });
  expect(screen.getByTestId(`records-item-${SESSION_ID}`)).toBeInTheDocument();
});
it("대화 유형이 없는 경우 빈 날짜와 구분해 표시한다", async () => {
  render(); await screen.findByTestId(`records-item-${SESSION_ID}`);
  fireEvent.change(screen.getByRole("combobox", { name: "대화 종류" }), { target: { value: "PRACTICE" } });
  expect(screen.getByText("선택한 종류의 대화가 없어요.")).toBeInTheDocument();
  expect(screen.queryByTestId("records-empty-day")).toBeNull();
});
it("연습은 목표 수 없이 연습대화 · 분과 중단을 표시한다", async () => { render([item({ activityType: "PRACTICE", goalCount: 2, interrupted: true })]); const row = await screen.findByTestId(`records-item-${SESSION_ID}`); expect(row).toHaveTextContent("연습대화 · 3분 · 중간에 끊김"); expect(row).not.toHaveTextContent("목표"); });
it("결과 요약이 있으면 줄 아래에 보이고 없으면 요약 자리를 두지 않는다", async () => {
  const otherId = "00000000-0000-4000-8000-000000000089";
  render([item({ summary: "블록을 같이 쌓자고 먼저 말했어요." }), item({ sessionId: otherId, summary: null })]);
  expect(await screen.findByTestId(`records-item-summary-${SESSION_ID}`)).toHaveTextContent("블록을 같이 쌓자고 먼저 말했어요.");
  expect(screen.getByTestId(`records-item-${SESSION_ID}`)).toHaveTextContent("10:00 자유대화 · 3분");
  expect(screen.queryByTestId(`records-item-summary-${otherId}`)).toBeNull();
});
it("재시작 FAILED는 중간에 끊김이다", async () => { render([item({ finalStatus: "FAILED" })]); expect(await screen.findByTestId(`records-item-${SESSION_ID}`)).toHaveTextContent("중간에 끊김"); });
it("DECLINED 카드는 키보드 이동이나 클릭으로 상세를 열지 않는다", async () => { render([item({ finalStatus: "DECLINED", linkable: false })]); const row = await screen.findByTestId(`records-item-declined-${SESSION_ID}`); expect(row).not.toHaveAttribute("tabindex"); fireEvent.click(row); expect(router.push).not.toHaveBeenCalled(); });
it("시각이 없는 시작 실패 기록은 종류·상태·시간 안내를 나누고 성과를 표시하지 않는다", async () => {
  render([item({ finalStatus: "DECLINED", linkable: false, startedAt: null, endedAt: null })]);
  const card = await screen.findByRole("article", { name: "자유대화" });
  expect(within(card).getByRole("heading", { name: "자유대화" })).toBeInTheDocument();
  expect(within(card).getByText("시작하지 못함")).toBeInTheDocument();
  expect(within(card).getByText("시작 시각을 확인할 수 없어요")).toBeInTheDocument();
  expect(card).not.toHaveTextContent(/분|목표/);
  expect(within(card).queryByRole("button")).toBeNull();
  fireEvent.click(card);
  expect(router.push).not.toHaveBeenCalled();
});
it("연습 시작 실패 카드에 한국 시각을 표시하고 종류 필터를 적용한다", async () => {
  const practiceId = "00000000-0000-4000-8000-000000000088";
  render([item(), item({ sessionId: practiceId, activityType: "PRACTICE", finalStatus: "DECLINED", linkable: false, goalCount: 2 })]);
  const card = await screen.findByRole("article", { name: "연습대화" });
  expect(within(card).getByText("시작 시각")).toHaveTextContent("시작 시각 10:00");
  expect(within(card).getByText("10:00")).toHaveAttribute("datetime", "2026-10-15T01:00:00Z");
  expect(card).not.toHaveTextContent(/분|목표/);
  fireEvent.change(screen.getByRole("combobox", { name: "대화 종류" }), { target: { value: "FREE_TALK" } });
  expect(screen.queryByRole("article", { name: "연습대화" })).toBeNull();
  fireEvent.change(screen.getByRole("combobox", { name: "대화 종류" }), { target: { value: "PRACTICE" } });
  expect(screen.getByRole("article", { name: "연습대화" })).toBeInTheDocument();
  expect(screen.queryByTestId(`records-item-${SESSION_ID}`)).toBeNull();
});
it("상세 이동이 불가능한 기록도 비활성 카드로 유지한다", async () => {
  render([item({ finalStatus: "FAILED", linkable: false })]);
  const card = await screen.findByRole("article", { name: "자유대화" });
  expect(within(card).getByText("시작하지 못함")).toBeInTheDocument();
  fireEvent.click(card);
  expect(router.push).not.toHaveBeenCalled();
});
it("빈 기록과 활동 없는 날을 안내한다", async () => { render([], []); expect(await screen.findByTestId("records-empty")).toHaveTextContent("아직 기록이 없어요"); expect(screen.getByTestId("records-empty-day")).toHaveTextContent("활동이 없어요"); });
it("상세에서 돌아온 날짜를 복원하고 다른 날을 선택한다", async () => { navigation.search = new URLSearchParams("date=2026-10-10"); const h = render(); expect(await screen.findByTestId("records-day-title")).toHaveTextContent("10월 10일"); await waitFor(() => expect(h.requests(/date=2026-10-10/)).toHaveLength(1)); fireEvent.click(screen.getByTestId("records-day-2026-10-12")); expect(router.replace).toHaveBeenCalledWith("/parent/records?date=2026-10-12"); });
it("달력 점은 읽을 수 있고 리포트와 기록 탭이 이동한다", async () => { render(); await waitFor(() => expect(screen.getByTestId("records-day-2026-10-15")).toHaveAccessibleName("10월 15일 · 활동 1개")); expect(screen.getByTestId("records-day-2026-10-16")).toBeDisabled(); fireEvent.click(screen.getByTestId("records-report")); expect(router.push).toHaveBeenCalledWith("/parent/reports/new"); fireEvent.click(screen.getByTestId("records-prev")); expect(screen.getByTestId("records-month")).toHaveTextContent("9월"); });
