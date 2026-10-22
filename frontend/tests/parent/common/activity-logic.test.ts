import { describe, expect, it, vi } from "vitest";
import { presentDone } from "@/parent/features/done/presentation";
import { freeFailedRoute, startFailureReason } from "@/parent/common/activity/failure";
import { activeTabTarget, liveActivityCard } from "@/parent/features/home/presentation";
import { buildTimeline, gapText } from "@/parent/common/components/transcriptTimeline";
import { isStartFailure, liveOutcome, showsPauseLine, silentMinutes } from "@/parent/common/activity/livePresentation";
import {
  fetchCurrentSession,
  isInProgressStatus,
  parseCurrentSession,
  parseLiveExtras,
  parseSessionSummary,
  type SessionSummary,
} from "@/parent/common/api/activity";
import type { ApiClient } from "@/parent/common/api/apiClient";
import { parseHome, parseLiveView, type Bubble, type LiveView } from "@/parent/common/api/types";
import { childStopDetail, childStopTitle, homeActivityLine, homeClosingLine, lastCheckedText, offlineLine } from "@/shared/copy/activity";
import { SESSION_ID, homeBody, liveViewBody } from "../../support/fixtures";

// U4a 화면 판단 단위 시험 (docs/units/u4a/design.md 6절 · AC1.4.7 · AC1.4.13 · AC3.2.8 · AC3.2.10 · AC3.4.3 · AC3.6.9 · U4a-J5 · J12 ~ J17)

function view(status: string, extra: Record<string, unknown> = {}): LiveView {
  return parseLiveView(liveViewBody(status, extra));
}

function summary(status: string, extra: Partial<SessionSummary> = {}): SessionSummary {
  const parsed = parseSessionSummary({ sessionId: SESSION_ID, activityType: "FREE_TALK", status, confirmed: false, requestedAt: "2026-10-15T00:59:58Z", ...extra });
  if (parsed === null) {
    throw new Error("요약을 읽지 못했다");
  }
  return parsed;
}

function bubbleAt(seq: number, startedAt: string): Bubble {
  return { seq, speaker: "CHILD", text: `말 ${seq}`, final: true, uncertain: false, startedAt, endedAt: null };
}

describe("12 상태 → 할 일(liveOutcome · AC3.4.1 · AC3.5.1 · AC3.6.9 · U4a-J12 · J16)", () => {
  it.each([
    ["PREPARING", null, null, "stay"],
    ["WAITING_CHILD", null, null, "stay"],
    ["ACTIVE", null, null, "stay"],
    ["CLOSING", "CHILD_STOP", null, "done"],
    ["ENDED", "SILENCE", null, "done"],
    ["ENDED_UNCONFIRMED", "UNCONFIRMED", null, "done"],
    ["ABORTED", "DISCONNECT", null, "unexpectedEnd"],
    ["ABORTED", null, null, "done"],
    ["DECLINED", "DECLINED", null, "noResponse"],
    ["FAILED", "FAILED", "SERVER_RESTART", "done"],
    ["FAILED", "FAILED", "DEVICE_NO_ACK", "startFailed"],
    ["FAILED", "FAILED", null, "startFailed"],
  ])("%s · %s · %s → %s", (status, endReason, failureCode, kind) => {
    expect(liveOutcome(view(status, { endReason, failureCode })).kind).toBe(kind);
  });

  it("시작 실패는 실패 코드를 함께 넘기고, 서버 재시작만 시작 실패가 아니다", () => {
    expect(liveOutcome(view("FAILED", { failureCode: "PROVIDER_UNAVAILABLE" }))).toEqual({ kind: "startFailed", failureCode: "PROVIDER_UNAVAILABLE" });
    expect(isStartFailure(view("FAILED", { failureCode: "SERVER_RESTART" }))).toBe(false);
    expect(isStartFailure(view("ACTIVE"))).toBe(false);
  });

  it("시작 전(startedAt 없음)에 서버가 재시작된 FAILED는 17이 아니라 08 시작 실패다(AC3.1.9 · AC3.6.9 · 리뷰 R3)", () => {
    const beforeStart = view("FAILED", { endReason: "FAILED", failureCode: "SERVER_RESTART", startedAt: null });
    expect(isStartFailure(beforeStart)).toBe(true);
    expect(liveOutcome(beforeStart)).toEqual({ kind: "startFailed", failureCode: "SERVER_RESTART" });
  });
});

describe("12 공백 경고 · 복구 중 · 흐름(AC3.2.8 · AC3.2.10 · U4a-J19)", () => {
  it("공백 경고 분은 경고 시작부터 서버 시각까지이고 4분 경계에서 4, 경고가 없거나 시각을 모르면 null", () => {
    const warned = view("ACTIVE", { serverTime: "2026-10-15T01:06:00Z", silenceWarning: { silentSince: "2026-10-15T01:02:00Z", endsAt: "2026-10-15T01:08:00Z" } });
    expect(silentMinutes(warned)).toBe(4);
    expect(silentMinutes(view("ACTIVE"))).toBeNull();
    expect(silentMinutes({ ...warned, serverTime: null })).toBeNull();
    expect(silentMinutes({ ...warned, silenceWarning: { silentSince: "x", endsAt: "y" } })).toBeNull();
    expect(silentMinutes({ ...warned, serverTime: "2026-10-15T01:02:10Z" })).toBe(1);
  });

  it("구간은 그보다 늦게 시작한 첫 말풍선 앞에 끼고 남은 구간은 끝에 붙는다", () => {
    const items = [bubbleAt(1, "2026-10-15T01:00:00Z"), bubbleAt(2, ""), bubbleAt(3, "2026-10-15T01:05:00Z")];
    const entries = buildTimeline(items, [
      { from: "2026-10-15T01:06:00Z", to: null, kind: "DISCONNECT" },
      { from: "2026-10-15T01:01:00Z", to: "2026-10-15T01:02:00Z", kind: "DISCONNECT" },
    ]);
    expect(entries.map((entry) => (entry.kind === "bubble" ? `b${entry.bubble.seq}` : `g${entry.gap.from.slice(14, 16)}`))).toEqual([
      "b1",
      "b2",
      "g01",
      "b3",
      "g06",
    ]);
  });

  it("구간 글: 끝난 끊김 · 끝난 미확인 · 진행 중", () => {
    expect(gapText({ from: "2026-10-15T01:33:00Z", to: "2026-10-15T01:34:00Z", kind: "DISCONNECT" })).toBe("10:33 ~ 10:34 잠깐 끊김");
    expect(gapText({ from: "2026-10-15T01:33:00Z", to: "2026-10-15T01:34:00Z", kind: "UNCONFIRMED" })).toBe("10:33 ~ 10:34 확인되지 않은 구간");
    expect(gapText({ from: "2026-10-15T01:33:00Z", to: null, kind: "DISCONNECT" })).toBe("대화 연결을 다시 잇고 있어요");
  });

  it("복구 중 줄은 일시 정지가 있고 흐름에 진행 중 구간이 아직 없을 때만(같은 글을 두 번 보이지 않음)", () => {
    const pause = { kind: "DEVICE" as const, since: "2026-10-15T01:04:50Z" };
    expect(showsPauseLine({ technicalPause: pause, gaps: [] })).toBe(true);
    expect(showsPauseLine({ technicalPause: pause, gaps: [{ from: "2026-10-15T01:04:50Z", to: null, kind: "DISCONNECT" }] })).toBe(false);
    expect(showsPauseLine({ technicalPause: null, gaps: [] })).toBe(false);
  });
});

describe("17 사유 표(AC3.6.9 · AC3.4.3 · U4a-J13 ~ J15)", () => {
  it.each([
    ["ENDED", "GUARDIAN_STOP", true, "보호자가 끝냈어요", "「이야기 끝내기」 · 10:21"],
    ["ENDED", "CHILD_STOP", true, "하늘이 끝내자고 했어요", null],
    ["ENDED", "SILENCE", true, "말이 없어 자동으로 끝났어요", "공백 6분 · 6분 동안 말소리가 없어 활동을 끝냈어요."],
    ["ENDED_UNCONFIRMED", "UNCONFIRMED", false, "기기 응답 없이 끝냈어요", "기기가 30초 동안"],
    ["FAILED", "FAILED", false, "기기 응답 없이 끝냈어요", "기기가 30초 동안"],
    ["ABORTED", "DISCONNECT", false, "연결이 끊겨 끝났어요", "10:21 기기 연결이 끊겨"],
  ])("%s · %s → 사유 카드", (status, endReason, confirmed, title, body) => {
    const presentation = presentDone(view(status, { endReason, confirmed, endedAt: "2026-10-15T01:21:00Z" }), "하늘");
    expect(presentation.title).toBe("오늘 자유대화가 끝났어요");
    expect(presentation.reasonTitle).toBe(title);
    if (body === null) { expect(presentation.reasonBody).toBeNull(); expect(presentation.stopQuote).toBeNull(); }
    else expect(presentation.reasonBody).toContain(body);
    expect(presentation.cleaningLine).toBe("기록을 정리하고 있어요");
    expect(presentation.unconfirmedLine).toBe(confirmed ? null : "기기 종료 확인 · 확인 안 됨");
  });

  it("마무리 중(CLOSING) · 아직 못 읽음은 마무리 카드이고 정리 줄 · 미확인 줄이 없다", () => {
    for (const current of [view("CLOSING", { endReason: "CHILD_STOP" }), undefined]) {
      const presentation = presentDone(current, null);
      expect(presentation.closing).toBe(current !== undefined);
      expect(presentation.title).toBe(current === undefined ? "활동 상태를 불러오고 있어요" : "오늘 자유대화를 마무리하고 있어요");
      expect(presentation.goodbyeLine).toBeNull();
      expect(presentation.cleaningLine).toBeNull();
      expect(presentation.unconfirmedLine).toBeNull();
    }
  });

  it("DECLINED는 제목만이고 미확인 줄이 없다, 시각 · 이름이 없으면 줄을 줄인다", () => {
    const declined = presentDone(view("DECLINED", { endReason: "DECLINED" }), null);
    expect(declined.reasonTitle).toBeNull();
    expect(declined.unconfirmedLine).toBeNull();
    const noTime = presentDone(view("ENDED", { endReason: "GUARDIAN_STOP", confirmed: true, endedAt: null }), null);
    expect(noTime.reasonBody).toBeNull();
    expect(childStopTitle(null)).toBe("아이가 끝내자고 했어요");
    expect(childStopDetail("오늘은 그만", null)).toBe("\"오늘은 그만\"");
  });
});

describe("04 지금 활동(AC1.4.7 · AC1.4.10 · AC1.4.13 · U4a-J5)", () => {
  const now = "2026-10-15T01:04:30Z";

  it.each(["REQUESTED", "PREPARING", "DEVICE_ACK", "WAITING_CHILD", "ACTIVE"])("%s는 진행 중 → 12", (status) => {
    const card = liveActivityCard(summary(status, { startedAt: "2026-10-15T01:00:00Z" }), "하늘", now);
    expect(card).toEqual({ state: "live", line: "하늘 · 자유대화 5분째", startedLine: "10:00 시작", target: `/parent/sessions/${SESSION_ID}` });
    expect(activeTabTarget(summary(status))).toBe(`/parent/sessions/${SESSION_ID}`);
  });

  it("시작 전(startedAt 없음)은 '준비하고 있어요', CLOSING은 종료 처리 중 → 17", () => {
    expect(liveActivityCard(summary("PREPARING"), "하늘", now)).toMatchObject({ line: "준비하고 있어요", startedLine: null });
    expect(liveActivityCard(summary("CLOSING"), "하늘", now)).toEqual({
      state: "closing",
      line: "하늘 · 자유대화 종료 처리 중",
      startedLine: null,
      target: `/parent/sessions/${SESSION_ID}/done`,
    });
    expect(activeTabTarget(summary("CLOSING"))).toBe(`/parent/sessions/${SESSION_ID}/done`);
    expect(homeClosingLine(null)).toBe("자유대화 종료 처리 중");
    expect(homeActivityLine(null, 2)).toBe("자유대화 2분째");
  });

  it.each(["ENDED", "ENDED_UNCONFIRMED", "ABORTED", "DECLINED", "FAILED"])("%s는 '없음'(U2 틀)이다", (status) => {
    expect(liveActivityCard(summary(status), "하늘", now)).toBeNull();
    expect(activeTabTarget(summary(status))).toBeNull();
  });

  it("없으면 null, 조회 실패 · 오프라인 줄은 마지막 확인 시각을 붙인다", () => {
    expect(liveActivityCard(null, "하늘", now)).toBeNull();
    expect(activeTabTarget(null)).toBeNull();
    expect(lastCheckedText("10:32")).toBe("마지막 확인 10:32");
    expect(lastCheckedText(null)).toBe("마지막 확인");
    expect(offlineLine("10:32")).toBe("실시간 상태를 불러오지 못하고 있어요 · 마지막 확인 10:32");
    expect(offlineLine(null)).toBe("실시간 상태를 불러오지 못하고 있어요");
  });
});

describe("08 시작 실패 사유(AC3.1.9 · AC3.2.4 · AC3.2.5 · U4a-J16)", () => {
  it("코드별 문구와 경로(기본 사유는 U1과 같은 ?failed=1)", () => {
    expect(startFailureReason(null)).toBe("기기가 응답하지 않아 시작하지 못했어요. 기기를 확인하고 다시 시도해 주세요.");
    expect(startFailureReason("DEVICE_NO_ACK")).toBe(startFailureReason(null));
    expect(startFailureReason("PROVIDER_UNAVAILABLE")).toBe("지금은 시작하지 못했어요. 잠시 뒤 다시 시도해 주세요.");
    expect(startFailureReason("SCENARIO_GEN_FAILED")).toBe("연습 상황을 만들지 못했어요. 잠시 뒤 다시 시도해 주세요.");
    expect(freeFailedRoute(null)).toBe("/parent/free?failed=1");
    expect(freeFailedRoute("DEVICE_NO_ACK")).toBe("/parent/free?failed=1");
    expect(freeFailedRoute("PROVIDER_UNAVAILABLE")).toBe("/parent/free?failed=1&reason=PROVIDER_UNAVAILABLE");
    expect(freeFailedRoute("<script>")).toBe("/parent/free?failed=1");
  });
});

describe("응답 읽기(계약 3절 · 편차 (U4a-a))", () => {
  it("홈의 currentSession과 /sessions/current의 {session}을 읽고 모르는 값은 UNKNOWN · null로", () => {
    const home = parseHome({ ...homeBody("STABLE"), currentSession: { sessionId: SESSION_ID, activityType: "FREE_TALK", status: "ACTIVE", confirmed: false, requestedAt: "t" } });
    expect(home.currentSession).toMatchObject({ sessionId: SESSION_ID, status: "ACTIVE", activityType: "FREE_TALK", endReason: null });
    expect(parseHome(homeBody("STABLE")).currentSession).toBeNull();
    expect(parseCurrentSession({ session: null })).toBeNull();
    expect(parseCurrentSession("x")).toBeNull();
    expect(parseCurrentSession({ session: { sessionId: SESSION_ID, status: "WHAT", activityType: "X", endReason: "NEW_REASON", confirmed: true } })).toMatchObject({
      status: "UNKNOWN",
      activityType: "UNKNOWN",
      endReason: "UNKNOWN",
      confirmed: true,
    });
    expect(parseSessionSummary({ status: "ACTIVE" })).toBeNull();
    expect(isInProgressStatus("CLOSING")).toBe(true);
    expect(isInProgressStatus("ENDED")).toBe(false);
  });

  it("12 덧붙임은 계약 모양만 남긴다", () => {
    const extras = parseLiveExtras({
      gaps: [
        { from: "2026-10-15T01:00:00Z", to: "2026-10-15T01:00:30Z", kind: "DISCONNECT" },
        { from: "2026-10-15T01:01:00Z", to: null, kind: "UNCONFIRMED" },
        { from: "2026-10-15T01:01:00Z", kind: "OTHER" },
        { from: "bad", kind: "DISCONNECT" },
        { from: "2026-10-15T01:01:00Z", to: "bad", kind: "DISCONNECT" },
        "x",
      ],
      technicalPause: { kind: "PROVIDER", since: "2026-10-15T01:00:00Z" },
      silenceWarning: { silentSince: "2026-10-15T01:00:00Z", endsAt: "2026-10-15T01:02:00Z" },
    });
    expect(extras.gaps).toHaveLength(2);
    expect(extras.technicalPause).toEqual({ kind: "PROVIDER", since: "2026-10-15T01:00:00Z" });
    expect(extras.silenceWarning).not.toBeNull();
    expect(parseLiveExtras({ gaps: "x", technicalPause: { kind: "X", since: "t" }, silenceWarning: { silentSince: "t" } })).toEqual({
      gaps: [],
      technicalPause: null,
      silenceWarning: null,
    });
  });

  it("fetchCurrentSession은 CSRF 없이 GET /api/sessions/current를 부른다", async () => {
    const request = vi.fn().mockResolvedValue(null);
    await fetchCurrentSession({ request } as unknown as ApiClient);
    expect(request).toHaveBeenCalledWith(expect.objectContaining({ method: "GET", path: "/api/sessions/current", csrf: false }));
  });
});

describe("연습 종료 사유 분리", () => {
  it("연습 완료를 아이의 중단 발화로 바꾸지 않는다", () => {
    const result = presentDone(view("ENDED", { activityType: "PRACTICE", endReason: "CHILD_STOP", endDetail: "PRACTICE_COMPLETED" }), "하늘");
    expect(result.title).toBe("연습이 종료됐어요");
    expect(result.reasonTitle).toBe("이번 연습을 마쳤어요");
    expect(result.reasonBody).toBeNull();
  });
  it("도입 재생 실패는 완료도 아이의 종료 요청도 아니다", () => {
    const result = presentDone(view("ENDED", { activityType: "PRACTICE", endReason: "DISCONNECT", endDetail: "PRACTICE_OUTPUT_FAILED" }), "하늘");
    expect(result.reasonTitle).toBe("연습 음성을 재생하지 못했어요");
    expect(result.reasonBody).toContain("완료된 것은 아니에요");
  });
});

it("연습의 상세 종료 사유가 없는 대체 응답도 아이 발화를 추정하지 않는다", () => {
  const result = presentDone(view("ENDED", { activityType: "PRACTICE", endReason: "CHILD_STOP" }), "하늘");
  expect(result.reasonTitle).toBe("연습이 종료됐어요");
  expect(result.reasonBody).toBeNull();
});
