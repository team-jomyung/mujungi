import { fireEvent, screen, waitFor } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import { SavedReports } from "@/parent/common/components/SavedReports";
import { renderParent, jsonResponse, errorResponse, REPORT_ID, router, resetRouter } from "../../../support/resultSupport";
vi.mock("next/navigation", async () => (await import("../../../support/nextNavigation")).nextNavigationMock);
beforeEach(resetRouter);
const item = { reportId: REPORT_ID, periodFrom: "2026-10-01", periodTo: "2026-10-20", activityCount: 3, savedAt: "2026-10-20T01:00:00Z" };
it("저장한 리포트를 기간으로 찾아 기존 본문 화면을 연다", async () => {
  renderParent(<SavedReports />, { handler: () => jsonResponse(200, { items: [item], hasMore: false }) });
  fireEvent.click(await screen.findByRole("button", { name: "리포트 다시 보기 · PDF 다운로드" }));
  expect(router.push).toHaveBeenCalledWith(`/parent/reports/${REPORT_ID}`);
  expect(screen.getByText("2026-10-01 ~ 2026-10-20")).toBeInTheDocument();
});
it("빈 목록과 이전·다음 페이지를 표시한다", async () => {
  const h = renderParent(<SavedReports />, { handler: path => jsonResponse(200, path.endsWith("page=0") ? { items: [item], hasMore: true } : { items: [], hasMore: false }) });
  fireEvent.click(await screen.findByRole("button", { name: "다음" }));
  expect(await screen.findByText("이 페이지에 저장한 리포트가 없어요.")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "다음" })).toBeDisabled();
  expect(h.requests(/page=1/)).toHaveLength(1);
  fireEvent.click(screen.getByRole("button", { name: "이전" }));
  await waitFor(() => expect(screen.getByRole("button", { name: "이전" })).toBeDisabled());
});
it("조회 실패 안내에서 재시도할 수 있다", async () => {
  let failed = true;
  renderParent(<SavedReports />, { handler: () => failed ? errorResponse(500, "INTERNAL_ERROR") : jsonResponse(200, { items: [], hasMore: false }) });
  expect(await screen.findByText("리포트 목록을 불러오지 못했어요.")).toBeInTheDocument();
  failed = false; fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
  expect(await screen.findByText(/아직 저장한 리포트가 없어요/)).toBeInTheDocument();
});
