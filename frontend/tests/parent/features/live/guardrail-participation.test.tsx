import { fireEvent, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { LiveScreen } from "@/parent/features/live/LiveScreen";
import { parseLiveView } from "@/parent/common/api/types";
import { renderParent } from "../../../support/parentHarness";
import { jsonResponse, liveViewBody, SESSION_ID } from "../../../support/fixtures";
import { resetRouter, router } from "../../../support/nextNavigation";
vi.mock("next/navigation", async () => (await import("../../../support/nextNavigation")).nextNavigationMock);
const facts = (participation: string) => ({ schemaVersion: 1, notice: "COMPLETED", participation, goodbye: "NONE", confirmedStopBubbleSeq: null });
beforeEach(resetRouter);
describe("서버 미참여 표시", () => {
  it.each([
    ["REFUSED", "아이가 지금은 이야기하지 않기로 했어요"],
    ["NO_RESPONSE", "아이가 대답하지 않아 시작하지 못했어요"],
    ["FUTURE", "이야기를 시작하지 않았어요"],
  ])("%s 사유의 P-03 확인은 홈 이동만 한다", async (reason, title) => {
    const harness = renderParent(<LiveScreen sessionId={SESSION_ID} />, { handler: () => jsonResponse(200, liveViewBody("DECLINED", { guardrail: facts(reason) })) });
    expect(await screen.findByRole("alertdialog", { name: title })).toBeInTheDocument();
    expect(screen.getByTestId("p3-confirm")).toHaveFocus(); fireEvent.click(screen.getByTestId("p3-confirm"));
    expect(router.replace).toHaveBeenCalledWith("/parent");
    expect(harness.requests().filter(r => r.method !== "GET")).toHaveLength(0);
  });
  it("사실 없는 구버전은 무응답으로 추정하지 않는다", async () => {
    renderParent(<LiveScreen sessionId={SESSION_ID} />, { handler: () => jsonResponse(200, liveViewBody("DECLINED")) });
    expect(await screen.findByRole("alertdialog", { name: "이야기를 시작하지 않았어요" })).toBeInTheDocument();
  });
  it("기술 FAILED는 미참여 팝업 없이 기존 실패 경로다", async () => {
    const h = renderParent(<LiveScreen sessionId={SESSION_ID} />, { handler: () => jsonResponse(200, liveViewBody("FAILED", { failureCode: "PROVIDER_UNAVAILABLE", guardrail: facts("REFUSED") })) });
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/free?failed=1&reason=PROVIDER_UNAVAILABLE"));
    expect(screen.queryByTestId("p3-dialog")).toBeNull(); expect(h.requests(/\/end$/)).toHaveLength(0);
  });
  it("더 최신 제목은 열린 팝업을 유지하고 초점을 다시 옮기지 않는다", async () => {
    const h = renderParent(<LiveScreen sessionId={SESSION_ID} />, { handler: () => jsonResponse(200, liveViewBody("DECLINED", { guardrail: facts("REFUSED") })) });
    const dialog = await screen.findByTestId("p3-dialog"); const focus = vi.spyOn(screen.getByTestId("p3-confirm"), "focus");
    h.queryClient.setQueryData(["session", SESSION_ID], parseLiveView(liveViewBody("DECLINED", { guardrail: facts("NO_RESPONSE") })));
    await waitFor(() => expect(dialog).toHaveAccessibleName("아이가 대답하지 않아 시작하지 못했어요"));
    expect(focus).not.toHaveBeenCalled(); expect(screen.getByTestId("p3-dialog")).toBe(dialog);
  });
});
