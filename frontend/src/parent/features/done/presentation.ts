import { isTerminalStatus, type LiveView } from "../../common/api/types";
import type { TabKey } from "../../common/components/BottomTabBar";
import { ACTIVITY_COPY, ACTIVITY_TEMP_COPY, childStopDetail, childStopTitle } from "@/shared/copy/activity";
import {
  COPY,
  PROPOSED_COPY,
  abortedBody,
  donePracticeHeader,
  donePracticeTimeRangeLine,
  doneTimeRangeLine,
  guardianStopDetail,
} from "@/shared/copy/ko";
import { formatHhmm, minutesBetween } from "@/shared/copy/timeText";

// 17 활동 끝 표시 판단 (U4a)
// 종료 사유 → 사유 카드 표(AC3.6.9)를 여기 한 곳에 둔다. 정리 끝 · 정리 실패는 결과 작업 상태(U6)라 종료 상태면 늘 '정리 중'이다.
// DECLINED · CONSENT_WITHDRAWN은 17로 오지 않는다(12가 P3 → 04, 계정 삭제는 01). 그래도 들어오면 U1처럼 제목만 보인다.
// 연습이면 머리 · 제목 · 시간 줄 · 활성 탭만 연습 것(C.S-17.21.x · C.S-17.58.1 ~ 3)으로 바꾸고 사유 카드는 같이 쓴다.
// 활동 종류를 아직 모르거나(첫 읽기 전) 모르는 값이면 지금까지처럼 자유대화로 보인다.

export interface DonePresentation {
  /** 머리 한 줄(활동 종류, 연습이면 목표 수). */
  readonly header: string;
  readonly title: string;
  /** 시간 줄. 시작 · 끝 시각을 모르면 null. */
  readonly timeLine: string | null;
  readonly activeTab: Extract<TabKey, "free" | "practice">;
  readonly reasonTitle: string | null;
  readonly reasonBody: string | null;
  /** 마무리 중(CLOSING · 아직 종료 아님). */
  readonly closing: boolean;
  /** '기록을 정리하고 있어요'(종료 상태, 정리 상태는 U6). */
  readonly cleaningLine: string | null;
  /** 종료 확인 미확인 줄(AC3.4.3, 사유와 따로). */
  readonly unconfirmedLine: string | null;
}

const UNCONFIRMED_STATUSES: ReadonlySet<LiveView["status"]> = new Set(["ENDED_UNCONFIRMED", "ABORTED", "FAILED"]);

interface Reason {
  readonly title: string | null;
  readonly body: string | null;
}

/** 종료 사유 카드(AC3.6.9 표). */
function reasonFor(view: LiveView, childName: string | null): Reason {
  const endedHhmm = formatHhmm(view.endedAt);
  if (view.status === "ENDED_UNCONFIRMED" || view.status === "FAILED") {
    return { title: COPY.done.unconfirmedTitle, body: COPY.done.unconfirmedBody };
  }
  if (view.status === "ABORTED") {
    return { title: COPY.done.abortedTitle, body: abortedBody(endedHhmm) };
  }
  switch (view.endReason) {
    case "GUARDIAN_STOP":
      return { title: PROPOSED_COPY.done.guardianStopTitle, body: endedHhmm === null ? null : guardianStopDetail(endedHhmm) };
    case "CHILD_STOP":
      return { title: childStopTitle(childName), body: childStopDetail(endedHhmm) };
    case "SILENCE":
      return { title: ACTIVITY_COPY.done.silenceTitle, body: ACTIVITY_COPY.done.silenceBody };
    case "DISCONNECT":
      return { title: COPY.done.abortedTitle, body: abortedBody(endedHhmm) };
    default:
      return { title: null, body: null };
  }
}

/** 시간 줄. 끝나기 전에는 서버 시각까지로 잰다. 자유대화는 '· n분'(C.S-17.16.3), 연습은 '· 목표 N개'(C.S-17.21.3). */
function timeLineFor(view: LiveView | undefined, practice: boolean, goalCount: number | null): string | null {
  const from = formatHhmm(view?.startedAt);
  const endIso = view?.endedAt ?? view?.serverTime ?? null;
  const to = formatHhmm(endIso);
  if (from === null || to === null) {
    return null;
  }
  if (practice) {
    return donePracticeTimeRangeLine(from, to, goalCount);
  }
  const minutes = minutesBetween(view?.startedAt, endIso);
  return minutes === null ? null : doneTimeRangeLine(from, to, minutes);
}

/**
 * @param goalCount 이 연습의 목표 수. 종료 뒤 진행 조회에는 목표 수가 오지 않아 화면이 앞서 본 값을 넘긴다. 모르면 null.
 */
export function presentDone(view: LiveView | undefined, childName: string | null, goalCount: number | null = null): DonePresentation {
  const practice = view?.activityType === "PRACTICE";
  const header = practice ? donePracticeHeader(goalCount) : COPY.done.header;
  const timeLine = timeLineFor(view, practice, goalCount);
  const activeTab = practice ? "practice" : "free";
  if (view === undefined || !isTerminalStatus(view.status)) {
    return {
      header,
      title: practice ? PROPOSED_COPY.done.practiceClosingTitle : COPY.done.closingTitle,
      timeLine,
      activeTab,
      reasonTitle: COPY.done.closingBody,
      reasonBody: COPY.done.closingNote,
      closing: true,
      cleaningLine: null,
      unconfirmedLine: null,
    };
  }
  const reason = reasonFor(view, childName);
  return {
    header,
    title: practice ? COPY.done.practiceEndedTitle : COPY.done.endedTitle,
    timeLine,
    activeTab,
    reasonTitle: reason.title,
    reasonBody: reason.body,
    closing: false,
    cleaningLine: ACTIVITY_COPY.done.cleaning,
    // 기기 확인 없이 끝난 상태만(ENDED는 기기 멈춤 확인으로 온다)
    unconfirmedLine: !view.confirmed && UNCONFIRMED_STATUSES.has(view.status) ? ACTIVITY_TEMP_COPY.doneUnconfirmed : null,
  };
}
