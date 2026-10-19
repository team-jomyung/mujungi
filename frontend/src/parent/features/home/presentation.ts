import { isInProgressStatus, type SessionSummary } from "../../common/api/activity";
import { PARENT_ROUTES } from "../../common/navigation";
import { homeActivityLine, homeClosingLine, startedAtLine } from "@/shared/copy/activity";
import { PROPOSED_COPY } from "@/shared/copy/ko";
import { elapsedMinuteOrdinal, formatHhmm } from "@/shared/copy/timeText";

// 04 지금 활동 칸 판단 (U4a · AC1.4.7 · AC1.4.10 · AC1.4.13 · B1)
// REQUESTED ~ ACTIVE → 진행 중(→ 12), CLOSING → 종료 처리 중(→ 17), 종료 · 없음 → 없음(U2 틀). 경과 'n분째'는 화면 시계로 센다.

export interface LiveActivityCard {
  readonly state: "live" | "closing";
  readonly line: string;
  /** 'hh:mm 시작'(시작 전이면 null). */
  readonly startedLine: string | null;
  /** 「진행 화면 보기 ›」의 목적지. */
  readonly target: string;
}

/** 지금 활동 칸(진행 중이 아니면 null — U2의 '없음' · '잠김' 틀을 쓴다). */
export function liveActivityCard(session: SessionSummary | null, childName: string | null, nowIso: string): LiveActivityCard | null {
  if (session === null || !isInProgressStatus(session.status)) {
    return null;
  }
  if (session.status === "CLOSING") {
    return { state: "closing", line: homeClosingLine(childName), startedLine: null, target: PARENT_ROUTES.sessionDone(session.sessionId) };
  }
  const ordinal = elapsedMinuteOrdinal(session.startedAt, nowIso);
  const started = formatHhmm(session.startedAt);
  return {
    state: "live",
    line: ordinal === null ? PROPOSED_COPY.live.preparing : homeActivityLine(childName, ordinal),
    startedLine: started === null ? null : startedAtLine(started),
    target: PARENT_ROUTES.session(session.sessionId),
  };
}

/** 진행 중이면 하단 「자유대화」 · 「연습」이 가는 곳(AC1.4.10), 아니면 null(평소 탭 이동). */
export function activeTabTarget(session: SessionSummary | null): string | null {
  if (session === null || !isInProgressStatus(session.status)) {
    return null;
  }
  return session.status === "CLOSING" ? PARENT_ROUTES.sessionDone(session.sessionId) : PARENT_ROUTES.session(session.sessionId);
}
