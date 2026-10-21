import { describe, expect, it } from "vitest";
import { presentDone } from "@/parent/features/done/presentation";
import { parseLiveView, type LiveView } from "@/parent/common/api/types";
import { liveViewBody } from "../../../support/fixtures";

// 17 활동 끝 표시 판단 — 연습 변형 (화면 설계 v1.5j C.S-17.21.x · C.S-17.58.1 ~ 5, docs/done-screen-practice.md)
// 연습이면 머리 · 제목 · 시간 줄 · 활성 탭만 연습 것으로 바뀌고 사유 카드는 자유대화와 같다. 자유대화 표시는 그대로다.

const STARTED_AT = "2026-10-15T01:40:00Z"; // 10:40
const ENDED_AT = "2026-10-15T01:52:00Z"; // 10:52

function view(status: string, extra: Record<string, unknown> = {}): LiveView {
  return parseLiveView(liveViewBody(status, { startedAt: STARTED_AT, ...extra }));
}

function practiceView(status: string, extra: Record<string, unknown> = {}): LiveView {
  return view(status, { activityType: "PRACTICE", ...extra });
}

describe("17 연습 — 머리 · 제목 · 시간 줄(C.S-17.21.1 ~ 3)", () => {
  it("ENDED는 '연습 · 목표 2개' · '오늘 연습이 끝났어요' · '10:40 ~ 10:52 · 목표 2개'이고 탭은 연습이다", () => {
    const presentation = presentDone(practiceView("ENDED", { endReason: "GUARDIAN_STOP", confirmed: true, endedAt: ENDED_AT }), null, 2);
    expect(presentation.header).toBe("연습 · 목표 2개");
    expect(presentation.title).toBe("오늘 연습이 끝났어요");
    expect(presentation.timeLine).toBe("10:40 ~ 10:52 · 목표 2개");
    expect(presentation.activeTab).toBe("practice");
    expect(presentation.closing).toBe(false);
    expect(presentation.cleaningLine).toBe("기록을 정리하고 있어요");
    for (const text of [presentation.header, presentation.title, presentation.timeLine]) {
      expect(text).not.toContain("자유대화");
    }
  });

  it("사유 카드는 자유대화와 같다(보호자 끝냄 · 아이가 끝냄 · 자동 종료 · 연결 끊김)", () => {
    const cases: ReadonlyArray<readonly [string, string, string]> = [
      ["ENDED", "GUARDIAN_STOP", "보호자가 끝냈어요"],
      ["ENDED", "CHILD_STOP", "하늘이 끝내자고 했어요"],
      ["ENDED", "SILENCE", "말이 없어 자동으로 끝났어요"],
      ["ABORTED", "DISCONNECT", "연결이 끊겨 끝났어요"],
    ];
    for (const [status, endReason, reasonTitle] of cases) {
      const free = presentDone(view(status, { endReason, endedAt: ENDED_AT }), "하늘");
      const practice = presentDone(practiceView(status, { endReason, endedAt: ENDED_AT }), "하늘", 2);
      expect(practice.reasonTitle).toBe(reasonTitle);
      expect(practice.reasonTitle).toBe(free.reasonTitle);
      expect(practice.reasonBody).toBe(free.reasonBody);
      expect(practice.unconfirmedLine).toBe(free.unconfirmedLine);
      expect(practice.title).toBe("오늘 연습이 끝났어요");
    }
  });

  it("기기 응답 없이 끝낸 연습은 C.S-17.58.1 ~ 5다", () => {
    const presentation = presentDone(
      practiceView("ENDED_UNCONFIRMED", { endReason: "UNCONFIRMED", endedAt: "2026-10-15T01:55:00Z" }),
      null,
      2,
    );
    expect(presentation.header).toBe("연습 · 목표 2개");
    expect(presentation.title).toBe("오늘 연습이 끝났어요");
    expect(presentation.timeLine).toBe("10:40 ~ 10:55 · 목표 2개");
    expect(presentation.reasonTitle).toBe("기기 응답 없이 끝냈어요");
    expect(presentation.reasonBody).toBe(
      "기기가 30초 동안 멈춤을 알리지 않아 끝낸 것으로 처리했어요. 다음 시작 전에 기기 상태를 다시 확인해요.",
    );
    expect(presentation.unconfirmedLine).toBe("기기 종료 확인 · 확인 안 됨");
  });
});

describe("17 연습 — 마무리 중 · 목표 수 없음", () => {
  it("CLOSING은 제안 제목 '오늘 연습을 마무리하고 있어요'와 같은 마무리 카드이고 시간 줄은 서버 시각까지다", () => {
    const presentation = presentDone(practiceView("CLOSING", { serverTime: "2026-10-15T01:50:30Z" }), null, 3);
    expect(presentation.closing).toBe(true);
    expect(presentation.header).toBe("연습 · 목표 3개");
    expect(presentation.title).toBe("오늘 연습을 마무리하고 있어요");
    expect(presentation.reasonTitle).toBe("무중이 마무리 인사를 하고 있어요");
    expect(presentation.reasonBody).toBe("기기가 멈추면 기록을 정리해요.");
    expect(presentation.timeLine).toBe("10:40 ~ 10:50 · 목표 3개");
    expect(presentation.activeTab).toBe("practice");
    expect(presentation.cleaningLine).toBeNull();
  });

  it("목표 수를 모르면 머리는 '연습', 시간 줄은 'hh:mm ~ hh:mm'만이다", () => {
    const presentation = presentDone(practiceView("ENDED", { endReason: "GUARDIAN_STOP", endedAt: ENDED_AT }), null, null);
    expect(presentation.header).toBe("연습");
    expect(presentation.timeLine).toBe("10:40 ~ 10:52");
    expect(presentation.title).toBe("오늘 연습이 끝났어요");
    expect(presentation.activeTab).toBe("practice");
  });

  it("시작 시각을 모르면 시간 줄이 없다", () => {
    expect(presentDone(practiceView("ENDED", { startedAt: null, endedAt: ENDED_AT }), null, 2).timeLine).toBeNull();
    expect(presentDone(view("ENDED", { startedAt: null, endedAt: ENDED_AT }), null).timeLine).toBeNull();
  });
});

describe("17 자유대화 — 회귀 없음(C.S-17.16.x · C.S-17.73.x)", () => {
  it("자유대화는 목표 수를 받아도 '자유대화' 머리 · 'n분' 시간 줄 · 자유대화 탭이다", () => {
    const presentation = presentDone(view("ENDED", { endReason: "GUARDIAN_STOP", endedAt: ENDED_AT }), null, 2);
    expect(presentation.header).toBe("자유대화");
    expect(presentation.title).toBe("오늘 자유대화가 끝났어요");
    expect(presentation.timeLine).toBe("10:40 ~ 10:52 · 12분");
    expect(presentation.activeTab).toBe("free");
  });

  it("활동 종류를 아직 모르거나(첫 읽기 전) 모르는 값이면 지금까지처럼 자유대화로 보인다", () => {
    const loading = presentDone(undefined, null, null);
    expect(loading.header).toBe("자유대화");
    expect(loading.title).toBe("오늘 자유대화를 마무리하고 있어요");
    expect(loading.timeLine).toBeNull();
    expect(loading.activeTab).toBe("free");
    const unknown = presentDone(view("ENDED", { activityType: "QUIZ", endedAt: ENDED_AT }), null, 2);
    expect(unknown.header).toBe("자유대화");
    expect(unknown.title).toBe("오늘 자유대화가 끝났어요");
    expect(unknown.activeTab).toBe("free");
  });
});
