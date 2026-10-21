import { isTerminalStatus, type LiveView } from "../../common/api/types";
import { ACTIVITY_COPY, ACTIVITY_TEMP_COPY, childStopDetail, childStopTitle } from "@/shared/copy/activity";
import { COPY, PROPOSED_COPY, abortedBody, guardianStopDetail } from "@/shared/copy/ko";
import { formatHhmm } from "@/shared/copy/timeText";

// 17 활동 끝 표시 판단 (U4a)
// 종료 사유 → 사유 카드 표(AC3.6.9)를 여기 한 곳에 둔다. 정리 끝 · 정리 실패는 결과 작업 상태(U6)라 종료 상태면 늘 '정리 중'이다.
// DECLINED · CONSENT_WITHDRAWN은 17로 오지 않는다(12가 P3 → 04, 계정 삭제는 01). 그래도 들어오면 U1처럼 제목만 보인다.

export interface DonePresentation {
  readonly goodbyeLine: string | null;
  readonly stopQuote: string | null;
  readonly title: string;
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
      return { title: childStopTitle(childName), body: null };
    case "SILENCE":
      return { title: ACTIVITY_COPY.done.silenceTitle, body: ACTIVITY_COPY.done.silenceBody };
    case "DISCONNECT":
      return { title: COPY.done.abortedTitle, body: abortedBody(endedHhmm) };
    default:
      return { title: null, body: null };
  }
}

/** 같은 세션 공개 items의 정확한 최종 CHILD 참조 외에는 인용하지 않는다. */
export function confirmedStopQuote(view: LiveView | undefined): string | null {
  if (view?.activityType !== "FREE_TALK" || view.endReason !== "CHILD_STOP") return null;
  const seq = view.guardrail?.confirmedStopBubbleSeq;
  if (seq === null || seq === undefined) return null;
  const row = view.items.find((item) => item.seq === seq);
  if (!row || row.speaker !== "CHILD" || !row.final || row.uncertain || row.text.trim().length === 0) return null;
  return childStopDetail(row.text, formatHhmm(row.startedAt));
}

export function presentDone(view: LiveView | undefined, childName: string | null, trusted = true): DonePresentation {
  if (view === undefined || !isTerminalStatus(view.status)) {
    const closing = view?.status === "CLOSING";
    const goodbye = trusted && closing && view?.activityType === "FREE_TALK" && !view.stale && view.technicalPause === null &&
      !view.gaps.some((gap) => gap.to === null) && view.guardrail?.goodbye === "PLAYING";
    return {
      goodbyeLine: goodbye ? COPY.done.closingBody : null,
      stopQuote: confirmedStopQuote(view),
      title: view === undefined ? (trusted ? ACTIVITY_COPY.done.loading : ACTIVITY_COPY.done.loadFailed) : closing ? COPY.done.closingTitle : ACTIVITY_COPY.done.unknown,
      reasonTitle: null,
      reasonBody: closing ? COPY.done.closingNote : null,
      closing,
      cleaningLine: null,
      unconfirmedLine: null,
    };
  }
  const reason = reasonFor(view, childName);
  return {
    goodbyeLine: null,
    stopQuote: confirmedStopQuote(view),
    title: COPY.done.endedTitle,
    reasonTitle: reason.title,
    reasonBody: reason.body,
    closing: false,
    cleaningLine: ACTIVITY_COPY.done.cleaning,
    // 기기 확인 없이 끝난 상태만(ENDED는 기기 멈춤 확인으로 온다)
    unconfirmedLine: !view.confirmed && UNCONFIRMED_STATUSES.has(view.status) ? ACTIVITY_TEMP_COPY.doneUnconfirmed : null,
  };
}
