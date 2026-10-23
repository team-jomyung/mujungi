"use client";

import { presentDone } from "./presentation";
import { isTerminalStatus, type LiveView } from "../../common/api/types";
import { BottomTabBar } from "../../common/components/BottomTabBar";
import { useDoneScreen } from "./useDoneScreen";
import { RESULT_TEMP_COPY as RESULT_COPY } from "@/shared/copy/result";
import { COPY, PROPOSED_COPY, doneTimeRangeLine } from "@/shared/copy/ko";
import { formatHhmm, minutesBetween } from "@/shared/copy/timeText";

// 17 활동 끝 (U1 3.5 Step 40 · ③ ④ · mockups 9-19, U4a · AC3.4.3 · AC3.6.1 ·
// )
// - 마무리 중(CLOSING 등 종료 전): '오늘 자유대화를 마무리하고 있어요' + '무중이 마무리 인사를 하고 있어요' + '기기가 멈추면 기록을 정리해요.'
// - 종료: '오늘 자유대화가 끝났어요' + 사유 카드(AC3.6.9 표 — 보호자 끝냄 · 아이가 끝냄 · 자동 종료 · 연결 끊김 · 기기 응답 없이(UNCONFIRMED ·
//   서버 재시작 FAILED), activity/donePresentation.ts) + '기록을 정리하고 있어요'(정리 끝 · 실패는 U6) + 종료 확인 미확인 줄(AC3.4.3).
// - 시간 줄 'hh:mm ~ hh:mm · n분', 「홈으로」. 「오늘 기록 보기」는 정리 상태(U6)와 27이 생길 때까지 감춘다.
// - 시작 실패 FAILED면 08 사유 카드. 종료 상태를 받을 때까지 같은 주기로 다시 읽는다. 마무리 중엔 자유대화 · 연습 탭이 잠긴다.
// 폴링 · 오류 분기 · 이동은 useDoneScreen 훅이 맡는다(단계 2).

function timeRangeFor(view: LiveView | undefined): string | null {
  const from = formatHhmm(view?.startedAt);
  const endIso = view?.endedAt ?? view?.serverTime ?? null;
  const to = formatHhmm(endIso);
  const minutes = minutesBetween(view?.startedAt, endIso);
  return from === null || to === null || minutes === null ? null : doneTimeRangeLine(from, to, minutes);
}

export interface DoneScreenProps {
  readonly sessionId: string;
}

export function DoneScreen({ sessionId }: DoneScreenProps) {
  const { view, status, childName, trusted, jobStatus, handleNavigate, onLockedPress, goToRecordDetail, goToHome } = useDoneScreen(sessionId);
  const presentation = presentDone(view, childName, trusted);
  const timeRange = timeRangeFor(view);

  return (
    <div className="mj-screen" data-testid="done-view" data-state={presentation.closing ? "CLOSING" : (status ?? "LOADING")}>
      <header className="mj-header mj-header--stacked">
        <p className="mj-header__eyebrow">{view?.activityType === "PRACTICE" ? "연습" : COPY.done.header}</p>
        <h1 className="mj-header__title" data-testid="done-title">
          {presentation.title}
        </h1>
        {timeRange !== null ? (
          <p className="mj-header__meta" data-testid="done-time">
            {timeRange}
          </p>
        ) : null}
      </header>
      <main className="mj-screen__body">
        {presentation.goodbyeLine !== null ? <p role="status" data-testid="done-goodbye">{presentation.goodbyeLine}</p> : null}
        {presentation.closing ? <p data-testid="done-closing-note">{presentation.reasonBody}</p> : null}
        {presentation.stopQuote !== null ? <p className="mj-reason__body" style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }} data-testid="done-stop-quote">{presentation.stopQuote}</p> : null}
        {presentation.reasonTitle !== null ? (
          <section className="mj-card mj-reason" role="status" data-testid="done-reason">
            <p className="mj-reason__title">{presentation.reasonTitle}</p>
            {presentation.reasonBody !== null ? <p className="mj-reason__body">{presentation.reasonBody}</p> : null}
          </section>
        ) : null}
        {presentation.unconfirmedLine !== null ? (
          <p className="mj-done__unconfirmed" data-testid="done-unconfirmed">
            {presentation.unconfirmedLine}
          </p>
        ) : null}
        {status !== undefined && isTerminalStatus(status) && jobStatus !== "NONE" ? (
          <p className="mj-done__cleaning" role="status" aria-live="polite" data-testid="done-cleaning">
            {jobStatus === "DONE" ? RESULT_COPY.done : jobStatus === "FAILED" ? RESULT_COPY.failed : RESULT_COPY.cleaning}
          </p>
        ) : null}
        {jobStatus === "DONE" ? <button type="button" className="mj-button mj-button--block" data-testid="done-view-record" onClick={goToRecordDetail}>{RESULT_COPY.viewRecord}</button> : null}
        {jobStatus === "FAILED" ? <button type="button" className="mj-button mj-button--block" data-testid="done-retry-in-record" onClick={goToRecordDetail}>{RESULT_COPY.retryInRecord}</button> : null}
        <button type="button" className="mj-button mj-button--primary mj-button--block" data-testid="done-home" onClick={goToHome}>
          {COPY.done.home}
        </button>
      </main>
      <BottomTabBar
        active={view?.activityType === "PRACTICE" ? "practice" : "free"}
        lockMessage={presentation.closing ? PROPOSED_COPY.closingTabToast : null}
        onNavigate={handleNavigate}
        onLockedPress={onLockedPress}
      />
    </div>
  );
}
