import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { NoticeDialog } from "@/parent/features/live/NoticeDialog";
import { ConfirmDialog } from "@/parent/common/components/ConfirmDialog";
import { nonParticipationTitle } from "@/parent/common/activity/livePresentation";
import { DoneScreen } from "@/parent/features/done/DoneScreen";
import { parseLiveView } from "@/parent/common/api/types";
import { renderParent } from "../../../support/parentHarness";
import { bubble, jsonResponse, liveViewBody, SESSION_ID } from "../../../support/fixtures";
vi.mock("next/navigation", async () => (await import("../../../support/nextNavigation")).nextNavigationMock);
describe("변경 표면 접근성과 연습 경계", () => {
  it("P-03은 접근 가능한 제목·확인 초점과 양방향 Tab 가두기를 유지한다", () => {
    const confirm = vi.fn(); render(<NoticeDialog open title="이야기를 시작하지 않았어요" confirmLabel="확인" onConfirm={confirm} testIdPrefix="p3" />);
    const dialog = screen.getByRole("alertdialog", { name: "이야기를 시작하지 않았어요" }); const button = screen.getByTestId("p3-confirm");
    for (const shiftKey of [false, true]) { fireEvent.keyDown(dialog, { key: "Tab", shiftKey }); expect(button).toHaveFocus(); }
    fireEvent.keyDown(dialog, { key: "Escape" }); expect(confirm).not.toHaveBeenCalled(); fireEvent.click(button); expect(confirm).toHaveBeenCalledTimes(1);
  });
  it("동일 제목의 반복 폴링은 DOM 재생성/재초점/추가 live 영역을 만들지 않는다", () => {
    const props = { open: true, title: "이야기를 시작하지 않았어요", confirmLabel: "확인", onConfirm: vi.fn(), testIdPrefix: "p3" };
    const h = render(<NoticeDialog {...props} />); const dialog = screen.getByTestId("p3-dialog"); const focus = vi.spyOn(screen.getByTestId("p3-confirm"), "focus");
    h.rerender(<NoticeDialog {...props} />); expect(screen.getByTestId("p3-dialog")).toBe(dialog); expect(focus).not.toHaveBeenCalled(); expect(dialog.querySelector("[aria-live]")).toBeNull();
  });
  it("P-01은 취소 기본 초점·복귀와 기존 확인 동작을 유지한다", () => {
    const confirm = vi.fn(); const cancel = vi.fn(); const trigger = document.createElement("button"); document.body.append(trigger); trigger.focus();
    const props = { title: "끝낼까요", confirmLabel: "끝내기", cancelLabel: "아니오", onConfirm: confirm, onCancel: cancel, testIdPrefix: "p1" };
    const h = render(<ConfirmDialog open {...props} />); expect(screen.getByTestId("p1-no")).toHaveFocus(); fireEvent.click(screen.getByTestId("p1-no")); expect(cancel).toHaveBeenCalledTimes(1);
    h.rerender(<ConfirmDialog open={false} {...props} />); expect(trigger).toHaveFocus(); trigger.remove(); expect(confirm).not.toHaveBeenCalled();
  });
  it("긴 실제 인용은 HTML 실행 없이 원문/줄바꿈으로 보이고 자동 낭독 영역 밖이다", async () => {
    const text = '<img src=x onerror=alert(1)> "한글" \\ \n' + "긴문구".repeat(100);
    const h = renderParent(<DoneScreen sessionId={SESSION_ID} />, { handler: () => jsonResponse(200, liveViewBody("ENDED", { endReason: "CHILD_STOP", guardrail: { schemaVersion: 1, notice: "COMPLETED", participation: "ACCEPTED", goodbye: "COMPLETED", confirmedStopBubbleSeq: 7 }, items: [bubble({ seq: 7, speaker: "CHILD", text })] })) });
    const quote = await screen.findByTestId("done-stop-quote"); expect(quote.textContent).toContain(text); expect(quote.querySelector("img")).toBeNull();
    expect(quote.closest('[role="status"], [aria-live]')).toBeNull(); expect(quote).toHaveStyle({ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }); expect(h.requests(/transcript/)).toHaveLength(0);
  });
  it("자유대화 facts를 연습/미확인 모드의 참여 사유로 사용하지 않는다", () => {
    for (const activityType of ["PRACTICE", "FUTURE"]) expect(nonParticipationTitle(parseLiveView(liveViewBody("DECLINED", { activityType, guardrail: { schemaVersion: 1, notice: "COMPLETED", participation: "REFUSED", goodbye: "PLAYING", confirmedStopBubbleSeq: null } })))).toBe("이야기를 시작하지 않았어요");
  });
});
