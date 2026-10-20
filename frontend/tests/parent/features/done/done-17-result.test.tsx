import { fireEvent, screen, waitFor } from "@testing-library/react";
import { beforeEach, afterEach, expect, it, vi } from "vitest";
import { DoneScreen } from "@/parent/features/done/DoneScreen";
import { liveViewBody } from "../../../support/fixtures";
import { renderParent, jsonResponse, errorResponse, status, SESSION_ID, router, resetRouter } from "../../../support/resultSupport";
vi.mock("next/navigation", async () => (await import("../../../support/nextNavigation")).nextNavigationMock);
beforeEach(resetRouter); afterEach(() => vi.useRealTimers());
function render(job: string, fail = false) { return renderParent(<DoneScreen sessionId={SESSION_ID} />, { timing: { parentPollIntervalMs: 25 }, handler: path => path.endsWith("/result/status") ? fail ? errorResponse(500, "INTERNAL_ERROR") : jsonResponse(200, status(job)) : jsonResponse(200, liveViewBody("ENDED", { confirmed: true })) }); }
it("정리 중에는 상태 글만 보인다", async () => { render("RUNNING"); expect(await screen.findByTestId("done-cleaning")).toHaveTextContent("기록을 정리하고 있어요"); expect(screen.queryByTestId("done-view-record")).toBeNull(); });
it("DONE에서만 오늘 기록 보기로 그 세션에 간다", async () => { render("DONE"); fireEvent.click(await screen.findByTestId("done-view-record")); expect(router.push).toHaveBeenCalledWith(`/parent/records/${SESSION_ID}`); expect(screen.getByTestId("done-cleaning")).toHaveTextContent("정리가 끝났어요"); });
it("FAILED는 기록에서 다시 시도한다", async () => { render("FAILED"); fireEvent.click(await screen.findByTestId("done-retry-in-record")); expect(router.push).toHaveBeenCalledWith(`/parent/records/${SESSION_ID}`); expect(screen.queryByTestId("done-view-record")).toBeNull(); });
it("NONE은 정리 줄 없이 홈 단추만 남는다", async () => { const h = render("NONE"); await waitFor(() => expect(h.requests(/result\/status/)).toHaveLength(1)); await waitFor(() => expect(screen.queryByTestId("done-cleaning")).toBeNull()); expect(screen.getByTestId("done-home")).toBeInTheDocument(); });
it("상태 조회 실패는 정리 중을 유지하며 다음 주기에 다시 묻는다", async () => { const h = render("RUNNING", true); await waitFor(() => expect(h.requests(/result\/status/).length).toBeGreaterThan(1)); expect(screen.getByTestId("done-cleaning")).toHaveTextContent("정리하고 있어요"); });
it("마무리 중에는 결과 상태를 조회하지 않는다", async () => { const h = renderParent(<DoneScreen sessionId={SESSION_ID} />, { handler: () => jsonResponse(200, liveViewBody("CLOSING")) }); await screen.findByTestId("done-title"); expect(h.requests(/result\/status/)).toHaveLength(0); });
