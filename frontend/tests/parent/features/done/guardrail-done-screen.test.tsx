import { act, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { DoneScreen } from "@/parent/features/done/DoneScreen";
import { presentDone } from "@/parent/features/done/presentation";
import { parseLiveView } from "@/parent/common/api/types";
import { renderParent } from "../../../support/parentHarness";
import { bubble, deferred, errorResponse, jsonResponse, liveViewBody, SESSION_ID } from "../../../support/fixtures";
import { resetRouter } from "../../../support/nextNavigation";
vi.mock("next/navigation", async () => (await import("../../../support/nextNavigation")).nextNavigationMock);
const facts = (goodbye: string) => ({ schemaVersion: 1, notice: "COMPLETED", participation: "ACCEPTED", goodbye, confirmedStopBubbleSeq: 7 });
beforeEach(resetRouter);
describe("현재 실제 인사와 종료 화면", () => {
  it("최초 미조회는 로딩이며 인사/정리/정지 완료를 만들지 않는다", () => {
    const pending = deferred<Response>(); renderParent(<DoneScreen sessionId={SESSION_ID} />, { handler: () => pending.promise });
    expect(screen.getByTestId("done-title")).toHaveTextContent("활동 상태를 불러오고 있어요");
    expect(screen.queryByTestId("done-goodbye")).toBeNull(); expect(screen.queryByTestId("done-cleaning")).toBeNull();
  });
  it("CLOSING의 같은 판 PLAYING→STOPPED→null을 즉시 반영한다", async () => {
    const h = renderParent(<DoneScreen sessionId={SESSION_ID} />, { handler: () => jsonResponse(200, liveViewBody("CLOSING", { guardrail: facts("PLAYING") })) });
    expect(await screen.findByTestId("done-goodbye")).toHaveTextContent("무중이 마무리 인사를 하고 있어요");
    for (const guardrail of [facts("STOPPED"), null]) {
      act(() => { h.queryClient.setQueryData(["session", SESSION_ID], parseLiveView(liveViewBody("CLOSING", { guardrail }))); });
      await waitFor(() => expect(screen.queryByTestId("done-goodbye")).toBeNull());
      expect(screen.getByTestId("done-title")).toHaveTextContent("마무리하고 있어요");
    }
  });
  it("null/대기/완료/실패/중단/UNKNOWN 및 다른 모드는 인사 중을 표시하지 않는다", () => {
    for (const goodbye of ["NONE", "PENDING", "COMPLETED", "FAILED", "STOPPED", "UNKNOWN"]) expect(presentDone(parseLiveView(liveViewBody("CLOSING", { guardrail: facts(goodbye) })), null).goodbyeLine).toBeNull();
    for (const extra of [{ guardrail: null }, { stale: true }, { activityType: "PRACTICE" }, { activityType: "UNKNOWN" }, { technicalPause: { kind: "DEVICE", since: "2026-10-15T01:00:00Z" } }, { gaps: [{ kind: "DISCONNECT", from: "2026-10-15T01:00:00Z", to: null }] }]) expect(presentDone(parseLiveView(liveViewBody("CLOSING", { guardrail: facts("PLAYING"), ...extra })), null).goodbyeLine).toBeNull();
  });
  it("조회 오류에서 마지막 PLAYING은 숨기며 기존 다시 시도 경로를 보인다", async () => {
    let fail = false;
    renderParent(<DoneScreen sessionId={SESSION_ID} />, { timing: { parentPollIntervalMs: 40 }, handler: () => fail ? errorResponse(503, "INTERNAL_ERROR") : jsonResponse(200, liveViewBody("CLOSING", { guardrail: facts("PLAYING") })) });
    await screen.findByTestId("done-goodbye"); fail = true;
    await waitFor(() => expect(screen.queryByTestId("done-goodbye")).toBeNull());
    expect(await screen.findByTestId("toast-action")).toHaveTextContent("다시 시도");
  });
  it("브라우저 단절을 알면 마지막 PLAYING을 즉시 숨긴다", async () => {
    renderParent(<DoneScreen sessionId={SESSION_ID} />, { handler: () => jsonResponse(200, liveViewBody("CLOSING", { guardrail: facts("PLAYING") })) });
    await screen.findByTestId("done-goodbye");
    Object.defineProperty(navigator, "onLine", { configurable: true, value: false });
    act(() => window.dispatchEvent(new Event("offline")));
    expect(screen.queryByTestId("done-goodbye")).toBeNull();
    Reflect.deleteProperty(navigator, "onLine"); act(() => window.dispatchEvent(new Event("online")));
  });
  it("아이 종료는 추가 확인0·추가 전사 조회0이며 기기 확인과 결과 정리는 별개다", async () => {
    const h = renderParent(<DoneScreen sessionId={SESSION_ID} />, { handler: path => jsonResponse(200, path.endsWith("/result/status") ? { jobStatus: "REQUESTED", sessionStatus: "ENDED_UNCONFIRMED", confirmed: false } : liveViewBody("ENDED_UNCONFIRMED", { guardrail: facts("PLAYING"), endReason: "CHILD_STOP", items: [bubble({ seq: 7, speaker: "CHILD", text: "여기서 그만" })] })) });
    await screen.findByTestId("done-stop-quote");
    expect(screen.queryByRole("alertdialog")).toBeNull(); expect(screen.queryByTestId("done-goodbye")).toBeNull();
    expect(screen.getByTestId("done-unconfirmed")).toBeInTheDocument(); expect(screen.getByTestId("done-cleaning")).toHaveTextContent("정리하고 있어요");
    expect(h.requests().every(r => r.method === "GET" && (r.path.includes("sinceSeq=") || r.path.endsWith("/result/status") || r.path.endsWith("/csrf")))).toBe(true);
  });
});
