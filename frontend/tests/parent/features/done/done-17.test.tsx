import { onlineManager } from "@tanstack/react-query";
import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { queryKeys } from "@/parent/common/api/queries";
import { parseLiveView } from "@/parent/common/api/types";
import { DoneScreen } from "@/parent/features/done/DoneScreen";
import { SESSION_ID, errorResponse, jsonResponse, liveViewBody } from "../../../support/fixtures";
import { resetRouter, router } from "../../../support/nextNavigation";
import { renderParent, type ParentHarness } from "../../../support/parentHarness";

vi.mock("next/navigation", async () => (await import("../../../support/nextNavigation")).nextNavigationMock);

// 17 활동 끝 시험 (U1 3.5 Step 42 · BR6.10 · BR6.15 · AC3.3.4 ~ AC3.3.7 · J120 · mockups 9-19 · C.S-17)
// 마무리 중 → (같은 주기로 다시 읽어) 종료 카드, 종료 사유 3종 문구, 시간 줄 'hh:mm ~ hh:mm · n분', 「홈으로」,
// 「오늘 기록 보기」는 감춤(U6), FAILED는 08 사유 카드, 마무리 중엔 자유대화 · 연습 탭이 잠긴다.
// 연습이면 머리 · 제목 · 시간 줄 · 활성 탭이 C.S-17.21.x이고 시작 실패는 18로 간다(docs/done-screen-practice.md).

const POLL_MS = 40;
const ENDED_AT = "2026-10-15T01:21:00Z"; // 10:21

function renderDone(
  read: (index: number) => Response | Promise<Response>,
  prepare?: (queryClient: ParentHarness["queryClient"]) => void,
): ParentHarness {
  let index = 0;
  return renderParent(<DoneScreen sessionId={SESSION_ID} />, {
    timing: { parentPollIntervalMs: POLL_MS },
    handler: () => {
      index += 1;
      return read(index);
    },
    prepare,
  });
}

const pause = (ms: number) => act(() => new Promise<void>((resolve) => setTimeout(resolve, ms)));

beforeEach(() => {
  resetRouter();
});

afterEach(() => {
  // 오프라인 시험이 바꾼 TanStack 온라인 상태(모듈 단일 객체)를 되돌린다.
  onlineManager.setOnline(true);
});

describe("17 — 마무리 중 → 종료(AC3.3.4 · AC3.3.5)", () => {
  it("CLOSING이면 마무리 카드를 보이고, 종료를 받으면 카드 글만 바꾼 뒤 읽기를 멈춘다", async () => {
    const harness = renderDone((index) =>
      jsonResponse(
        200,
        index <= 2
          ? liveViewBody("CLOSING", { serverTime: "2026-10-15T01:20:30Z" })
          : liveViewBody("ENDED", { endReason: "GUARDIAN_STOP", confirmed: true, endedAt: ENDED_AT }),
      ),
    );
    expect(await screen.findByText("오늘 자유대화를 마무리하고 있어요")).toBeInTheDocument();
    expect(screen.getByTestId("done-reason")).toHaveTextContent("무중이 마무리 인사를 하고 있어요");
    expect(screen.getByTestId("done-reason")).toHaveTextContent("기기가 멈추면 기록을 정리해요.");
    expect(screen.getByTestId("done-view")).toHaveAttribute("data-state", "CLOSING");
    await waitFor(() => expect(screen.getByTestId("done-title")).toHaveTextContent("오늘 자유대화가 끝났어요"));
    expect(screen.getByTestId("done-reason")).toHaveTextContent("보호자가 끝냈어요");
    expect(screen.getByTestId("done-reason")).toHaveTextContent("「이야기 끝내기」 · 10:21");
    expect(screen.getByTestId("done-time")).toHaveTextContent("10:00 ~ 10:21 · 21분");
    const reads = harness.requests().filter((request) => request.path.includes("sinceSeq")).length;
    await pause(POLL_MS * 4);
    expect(harness.requests().filter((request) => request.path.includes("sinceSeq")).length).toBe(reads);
  });

  it("마무리 중에는 자유대화 · 연습 탭이 잠겨 '끝나는 중이에요' 토스트를 보인다", async () => {
    renderDone(() => jsonResponse(200, liveViewBody("CLOSING")));
    await screen.findByText("오늘 자유대화를 마무리하고 있어요");
    expect(screen.getByTestId("tab-free")).toHaveAttribute("aria-disabled", "true");
    fireEvent.click(screen.getByTestId("tab-practice"));
    expect(screen.getByTestId("toast")).toHaveTextContent("끝나는 중이에요");
    expect(router.push).not.toHaveBeenCalled();
  });

  it("끝나기 전 시간 줄은 서버 시각까지로 보인다", async () => {
    renderDone(() => jsonResponse(200, liveViewBody("CLOSING", { serverTime: "2026-10-15T01:05:00Z" })));
    expect(await screen.findByTestId("done-time")).toHaveTextContent("10:00 ~ 10:05 · 5분");
  });
});

describe("17 — 종료 사유 3종(AC3.3.6 · AC3.3.7 · C.S-17.58 · C.S-17.71)", () => {
  it("ENDED_UNCONFIRMED는 '기기 응답 없이 끝냈어요'와 본문이다", async () => {
    renderDone(() => jsonResponse(200, liveViewBody("ENDED_UNCONFIRMED", { endReason: "UNCONFIRMED", endedAt: ENDED_AT })));
    await screen.findByText("기기 응답 없이 끝냈어요");
    const reason = screen.getByTestId("done-reason");
    expect(reason).toHaveTextContent("기기가 30초 동안 멈춤을 알리지 않아 끝낸 것으로 처리했어요. 다음 시작 전에 기기 상태를 다시 확인해요.");
    expect(screen.getByTestId("done-title")).toHaveTextContent("오늘 자유대화가 끝났어요");
  });

  it("ABORTED는 '연결이 끊겨 끝났어요'와 끊긴 시각 본문이고 시각이 없으면 앞을 뺀다", async () => {
    const first = renderDone(() => jsonResponse(200, liveViewBody("ABORTED", { endReason: "DISCONNECT", endedAt: ENDED_AT })));
    await screen.findByText("연결이 끊겨 끝났어요");
    expect(screen.getByTestId("done-reason")).toHaveTextContent("10:21 기기 연결이 끊겨 다시 잇지 못했어요. 끊기기 전까지의 기록은 남아요.");
    first.view.unmount();
    renderDone(() => jsonResponse(200, liveViewBody("ABORTED", { endReason: "DISCONNECT", endedAt: null })));
    await screen.findByText("연결이 끊겨 끝났어요");
    expect(screen.getByText("기기 연결이 끊겨 다시 잇지 못했어요. 끊기기 전까지의 기록은 남아요.")).toBeInTheDocument();
  });

  // U4a(AC3.6.9): SILENCE · CHILD_STOP은 사유 카드가 생겼다(tests/u4a/parent/done-17-u4a.test.tsx). 사유가 없는 ENDED로 U1 단언을 둔다
  it("ENDED인데 사유가 없으면 사유 카드 없이 제목만, DECLINED도 제목만 보인다", async () => {
    const first = renderDone(() => jsonResponse(200, liveViewBody("ENDED", { endReason: null, endedAt: ENDED_AT })));
    expect(await screen.findByText("오늘 자유대화가 끝났어요")).toBeInTheDocument();
    expect(screen.queryByTestId("done-reason")).toBeNull();
    first.view.unmount();
    renderDone(() => jsonResponse(200, liveViewBody("DECLINED", { endReason: "DECLINED", endedAt: ENDED_AT })));
    expect(await screen.findByText("오늘 자유대화가 끝났어요")).toBeInTheDocument();
    expect(screen.queryByTestId("done-reason")).toBeNull();
    expect(screen.getByTestId("done-view")).toHaveAttribute("data-state", "DECLINED");
  });

  it("정리 끝(DONE)에서 「오늘 기록 보기」를 보이고 「홈으로」는 04로 간다(U6)", async () => {
    renderParent(<DoneScreen sessionId={SESSION_ID} />, { handler: path => jsonResponse(200, path.endsWith("/result/status") ? { jobStatus: "DONE", sessionStatus: "ENDED", confirmed: true } : liveViewBody("ENDED", { endReason: "GUARDIAN_STOP", endedAt: ENDED_AT })) });
    await screen.findByText("보호자가 끝냈어요");
    expect(await screen.findByTestId("done-view-record")).toHaveTextContent("오늘 기록 보기");
    fireEvent.click(screen.getByTestId("done-home"));
    expect(router.push).toHaveBeenCalledWith("/parent");
    fireEvent.click(screen.getByTestId("tab-free"));
    expect(router.push).toHaveBeenLastCalledWith("/parent/free");
  });
});

describe("17 — 실패 · 이동", () => {
  it("FAILED면 08 사유 카드로 간다(J120)", async () => {
    renderDone(() => jsonResponse(200, liveViewBody("FAILED", { failureCode: "DEVICE_NO_ACK" })));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/free?failed=1"));
  });

  it("읽기 실패는 토스트 + 「다시 시도」를 한 번 보이고 다시 시도하면 읽는다", async () => {
    let fail = true;
    renderDone(() => (fail ? errorResponse(500, "INTERNAL_ERROR") : jsonResponse(200, liveViewBody("ENDED", { endReason: "GUARDIAN_STOP", endedAt: ENDED_AT }))));
    expect(await screen.findByTestId("toast")).toHaveTextContent("잠시 문제가 생겼어요. 다시 시도해 주세요.");
    fail = false;
    fireEvent.click(screen.getByTestId("toast-action"));
    expect(await screen.findByText("보호자가 끝냈어요")).toBeInTheDocument();
  });

  it("오프라인인 채 들어와도 첫 읽기 실패에 토스트 + 「다시 시도」이고, 온라인이 되면 다시 읽어 종료 카드를 보인다(PD-12 · J361)", async () => {
    // 같은 앱 안에서 이미 offline 사건을 받은 상태(예: 12에서 끊긴 뒤 17로 옴).
    onlineManager.setOnline(false);
    let online = false;
    renderDone(() => {
      if (!online) {
        throw new TypeError("Failed to fetch");
      }
      return jsonResponse(200, liveViewBody("ENDED", { endReason: "GUARDIAN_STOP", endedAt: ENDED_AT }));
    });
    expect(await screen.findByTestId("toast")).toHaveTextContent("잠시 문제가 생겼어요. 다시 시도해 주세요.");
    expect(screen.getByTestId("toast-action")).toHaveTextContent("다시 시도");
    online = true;
    act(() => window.dispatchEvent(new Event("online")));
    expect(await screen.findByText("보호자가 끝냈어요")).toBeInTheDocument();
  });

  it("401은 안내 토스트 뒤 01(U2 · AC1.1.9), 404는 '찾을 수 없어요' 뒤 홈이다", async () => {
    const first = renderDone(() => errorResponse(401, "UNAUTHENTICATED"));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/login"));
    expect(screen.getByTestId("toast")).toHaveTextContent("로그인이 필요해요.");
    first.view.unmount();
    resetRouter();
    renderDone(() => errorResponse(404, "NOT_FOUND"));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent"));
    expect(screen.getByTestId("toast")).toHaveTextContent("찾을 수 없어요.");
  });
});

describe("17 — 연습 변형(C.S-17.21.x · C.S-17.58.1 ~ 5)", () => {
  const PRACTICE_STARTED_AT = "2026-10-15T01:40:00Z"; // 10:40
  const PRACTICE_ENDED_AT = "2026-10-15T01:52:00Z"; // 10:52
  const progress = { goalIndex: 2, goalCount: 2, goalName: "목표 이름", stage: "NEW_SCENARIO_RESPONSE" };

  function practiceBody(status: string, extra: Record<string, unknown> = {}): Record<string, unknown> {
    return liveViewBody(status, { activityType: "PRACTICE", startedAt: PRACTICE_STARTED_AT, ...extra });
  }

  /** 머리 · 제목 · 시간 줄만 본다(탭 이름 '자유대화'는 설계에 있다). */
  function expectNoFreeTalkInHeader() {
    for (const id of ["done-header", "done-title", "done-time"]) {
      expect(screen.getByTestId(id)).not.toHaveTextContent("자유대화");
    }
  }

  it("마무리 중에 받은 목표 수를 종료 뒤에도 써서 '연습 · 목표 2개' · '오늘 연습이 끝났어요' · '10:40 ~ 10:52 · 목표 2개'를 보인다", async () => {
    renderDone((index) =>
      jsonResponse(
        200,
        index <= 2
          ? practiceBody("CLOSING", { practice: progress, serverTime: "2026-10-15T01:51:30Z", statusVersion: 5 })
          // 종료된 세션의 진행 조회에는 practice가 오지 않는다(realtime 종료 · core 사본 응답).
          : practiceBody("ENDED", { endReason: "GUARDIAN_STOP", confirmed: true, endedAt: PRACTICE_ENDED_AT, statusVersion: 6 }),
      ),
    );
    expect(await screen.findByText("오늘 연습을 마무리하고 있어요")).toBeInTheDocument();
    expect(screen.getByTestId("done-header")).toHaveTextContent("연습 · 목표 2개");
    expect(screen.getByTestId("done-reason")).toHaveTextContent("무중이 마무리 인사를 하고 있어요");
    expect(screen.getByTestId("tab-practice")).toHaveAttribute("aria-selected", "true");
    await waitFor(() => expect(screen.getByTestId("done-title")).toHaveTextContent("오늘 연습이 끝났어요"));
    expect(screen.getByTestId("done-header")).toHaveTextContent("연습 · 목표 2개");
    expect(screen.getByTestId("done-time")).toHaveTextContent("10:40 ~ 10:52 · 목표 2개");
    expect(screen.getByTestId("done-reason")).toHaveTextContent("보호자가 끝냈어요");
    expectNoFreeTalkInHeader();
    expect(screen.getByTestId("tab-practice")).toHaveAttribute("aria-selected", "true");
    expect(screen.getByTestId("tab-free")).toHaveAttribute("aria-selected", "false");
    // 정리 중 · 끝에는 탭이 열린다(S-17 분기: 자유대화 · 연습 → 08 · 18).
    fireEvent.click(screen.getByTestId("tab-practice"));
    expect(router.push).toHaveBeenLastCalledWith("/parent/practice");
  });

  it("연습 마무리 중에도 자유대화 · 연습 탭은 잠겨 '끝나는 중이에요' 토스트를 보인다", async () => {
    renderDone(() => jsonResponse(200, practiceBody("CLOSING", { practice: progress })));
    await screen.findByText("오늘 연습을 마무리하고 있어요");
    expect(screen.getByTestId("tab-free")).toHaveAttribute("aria-disabled", "true");
    expect(screen.getByTestId("tab-practice")).toHaveAttribute("aria-disabled", "true");
    fireEvent.click(screen.getByTestId("tab-practice"));
    expect(screen.getByTestId("toast")).toHaveTextContent("끝나는 중이에요");
    expect(router.push).not.toHaveBeenCalled();
  });

  // 12는 2초마다 읽으므로 마무리가 그 사이에 끝나면 CLOSING을 못 보고 종료를 받아 바로 17로 온다.
  // 실행 중 · 마무리 중 서버 재시작 FAILED(SERVER_RESTART)도 12에서 바로 17로 온다. 둘 다 종료 응답에는 practice가 없다.
  it.each([
    ["종료(ENDED)", { status: "ENDED", extra: { endReason: "GUARDIAN_STOP", confirmed: true }, reason: "보호자가 끝냈어요" }],
    ["실행 중 서버 재시작(FAILED)", { status: "FAILED", extra: { failureCode: "SERVER_RESTART" }, reason: "기기 응답 없이 끝냈어요" }],
  ] as const)("12가 캐시에 남긴 목표 수를 17이 마무리 중 없이 바로 받은 %s에도 쓴다", async (_label, { status, extra, reason }) => {
    renderDone(
      () => jsonResponse(200, practiceBody(status, { ...extra, endedAt: PRACTICE_ENDED_AT, statusVersion: 6 })),
      // 12가 마지막으로 받아 둔 진행 중 응답(같은 조회 캐시 queryKeys.session).
      (queryClient) => queryClient.setQueryData(queryKeys.session(SESSION_ID),
        parseLiveView(practiceBody("ACTIVE", { practice: progress, statusVersion: 4 }))),
    );
    await waitFor(() => expect(screen.getByTestId("done-title")).toHaveTextContent("오늘 연습이 끝났어요"));
    expect(screen.getByTestId("done-view")).toHaveAttribute("data-state", status);
    expect(screen.getByTestId("done-header")).toHaveTextContent("연습 · 목표 2개");
    expect(screen.getByTestId("done-time")).toHaveTextContent("10:40 ~ 10:52 · 목표 2개");
    expect(screen.getByTestId("done-reason")).toHaveTextContent(reason);
    expectNoFreeTalkInHeader();
    expect(router.replace).not.toHaveBeenCalled();
  });

  it("기기 응답 없이 끝낸 연습은 C.S-17.58.1 ~ 5다", async () => {
    renderDone((index) =>
      jsonResponse(
        200,
        index <= 1
          ? practiceBody("CLOSING", { practice: progress, statusVersion: 5 })
          : practiceBody("ENDED_UNCONFIRMED", { endReason: "UNCONFIRMED", endedAt: "2026-10-15T01:55:00Z", statusVersion: 6 }),
      ),
    );
    await screen.findByText("기기 응답 없이 끝냈어요");
    expect(screen.getByTestId("done-header")).toHaveTextContent("연습 · 목표 2개");
    expect(screen.getByTestId("done-title")).toHaveTextContent("오늘 연습이 끝났어요");
    expect(screen.getByTestId("done-time")).toHaveTextContent("10:40 ~ 10:55 · 목표 2개");
    expect(screen.getByTestId("done-reason")).toHaveTextContent(
      "기기가 30초 동안 멈춤을 알리지 않아 끝낸 것으로 처리했어요. 다음 시작 전에 기기 상태를 다시 확인해요.",
    );
    expectNoFreeTalkInHeader();
  });

  it("목표 수를 한 번도 받지 못했으면(종료 뒤 처음 들어옴) 머리는 '연습', 시간 줄은 'hh:mm ~ hh:mm'만이다", async () => {
    renderDone(() => jsonResponse(200, practiceBody("ENDED", { endReason: "CHILD_STOP", confirmed: true, endedAt: PRACTICE_ENDED_AT })));
    await waitFor(() => expect(screen.getByTestId("done-title")).toHaveTextContent("오늘 연습이 끝났어요"));
    expect(screen.getByTestId("done-header").textContent).toBe("연습");
    expect(screen.getByTestId("done-time").textContent).toBe("10:40 ~ 10:52");
    expect(screen.getByTestId("done-reason")).toHaveTextContent("아이가 끝내자고 했어요");
    expect(screen.getByTestId("tab-practice")).toHaveAttribute("aria-selected", "true");
  });

  it("연습 시작 실패는 12와 같이 18로 사유를 싣고 가고, 사유 코드가 없으면 기본 사유다", async () => {
    const first = renderDone(() => jsonResponse(200, practiceBody("FAILED", { failureCode: "SCENARIO_GEN_FAILED", startedAt: null })));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/practice?failed=1&reason=SCENARIO_GEN_FAILED"));
    first.view.unmount();
    resetRouter();
    renderDone(() => jsonResponse(200, practiceBody("FAILED", { failureCode: null, startedAt: null })));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/practice?failed=1&reason=DEVICE_NO_ACK"));
    expect(router.replace).not.toHaveBeenCalledWith(expect.stringContaining("/parent/free"));
  });

  it("자유대화 17은 머리 '자유대화' · 'n분' 시간 줄 · 자유대화 탭 그대로다", async () => {
    renderDone(() => jsonResponse(200, liveViewBody("ENDED", { endReason: "GUARDIAN_STOP", confirmed: true, endedAt: ENDED_AT })));
    await waitFor(() => expect(screen.getByTestId("done-title")).toHaveTextContent("오늘 자유대화가 끝났어요"));
    expect(screen.getByTestId("done-header").textContent).toBe("자유대화");
    expect(screen.getByTestId("done-time")).toHaveTextContent("10:00 ~ 10:21 · 21분");
    expect(screen.getByTestId("tab-free")).toHaveAttribute("aria-selected", "true");
    expect(screen.getByTestId("tab-practice")).toHaveAttribute("aria-selected", "false");
  });
});
