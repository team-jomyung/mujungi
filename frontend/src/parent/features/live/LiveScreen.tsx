"use client";

import { showsPauseLine, silentMinutes } from "./presentation";
import type { LiveView } from "../../common/api/types";
import { BottomTabBar } from "../../common/components/BottomTabBar";
import { ConfirmDialog } from "../../common/components/ConfirmDialog";
import { NoticeDialog } from "./NoticeDialog";
import { StatusLine } from "./StatusLine";
import { TranscriptList } from "../../common/components/TranscriptList";
import { useLiveScreen } from "./useLiveScreen";
import { PracticeHeader } from "../practice/PracticeHeader";
import { U5B_COPY } from "../practice/copy";
import {
  COPY,
  PROPOSED_COPY,
  liveElapsedLine,
  liveStaleLine,
  liveTalkingLine,
  liveWaitingChildLine,
} from "@/shared/copy/ko";
import { ACTIVITY_COPY, ACTIVITY_PROPOSED_COPY, offlineLine, silentElapsedLine } from "@/shared/copy/activity";
import { elapsedMinuteOrdinal, formatHhmm } from "@/shared/copy/timeText";

// 12 진행(최소) + P1 끝내기 (U1 3.5 Step 40 · BR6.9 · BR6.15 · BR6.16 · PD-12 · RD-21 · J111 · J120 · mockups 9-13 · 9-16 · 9-18)
// - 2초 폴링(useLivePolling). 받은 status에 따라: REQUESTED · PREPARING · DEVICE_ACK · ACTIVE는 머문다, CLOSING · 종료 상태는 17,
//   FAILED는 08 사유 카드(J120). 「이야기 끝내기」는 ACTIVE일 때만 켜진다.
// - stale이면 '기기 상태를 확인할 수 없어요 · 마지막 확인 hh:mm'(observedAt). 연속 3회 실패하면 토스트 + 「다시 시도」, 폴링은 계속.
// - 401이면 시험 로그인으로 가고(01은 U2), 진행 중 세션은 영향받지 않으며 끝내기 요청은 보내지 않는다(BR6.16 · AC1.1.9).
// - P1: 「아니오」 기본 초점. 「네, 끝낼게요」 → 202면 17, 409면 상태를 한 번 다시 읽어 종료 상태 · CLOSING일 때만 17
//   (FAILED면 08, 아직 시작 중이면 머물며 토스트), 503 · 401 · 네트워크 · 시간 초과는 '끝내기 요청을 보내지 못했어요'를 보이고
//   '종료 요청됨'으로 표시하지 않는다. 재시도는 같은 requestId(RD-21 · 표 2.9-C).
// U4a(AC3.2.8 · AC3.2.10 · AC3.2.12 · AC3.3.8 · AC3.4.1 · AC3.5.1 · U4a-J12 · J16 · J17): 공백 경고(경과 줄 + 카드), 끊김 · 미확인 구간
//   점선, 복구 중 줄, 앱 오프라인 줄, 「이야기 끝내기」는 WAITING_CHILD에서도 켜짐, ABORTED · DISCONNECT는 P2 → 17, DECLINED는
//   P3 → 04, 진행 중 서버 재시작 FAILED(SERVER_RESTART)는 17 · 그 밖의 FAILED는 08(사유 코드).
// 요청 · 폴링 · 오류 분기 · 이동은 useLiveScreen 훅이 맡는다(BR2.1, 단계 2).

function subtitleFor(view: LiveView | undefined, childName: string | null): string | null {
  switch (view?.status) {
    case "REQUESTED":
    case "PREPARING":
      return PROPOSED_COPY.live.preparing;
    case "DEVICE_ACK":
      return PROPOSED_COPY.live.deviceAcked;
    case "WAITING_CHILD":
      return childName === null ? null : liveWaitingChildLine(childName);
    case "ACTIVE":
      return childName === null ? null : view.activityType === "PRACTICE" ? U5B_COPY.practicing(childName) : liveTalkingLine(childName);
    default:
      return null;
  }
}

function elapsedLineFor(view: LiveView | undefined): string | null {
  const start = formatHhmm(view?.startedAt);
  const ordinal = elapsedMinuteOrdinal(view?.startedAt, view?.serverTime);
  return start === null || ordinal === null ? null : liveElapsedLine(start, ordinal);
}

export interface LiveScreenProps {
  readonly sessionId: string;
}

export function LiveScreen({ sessionId }: LiveScreenProps) {
  const {
    view,
    status,
    error,
    childName,
    isPractice,
    outcomeKind,
    offline,
    canEnd,
    ending,
    dialogOpen,
    openEndDialog,
    closeEndDialog,
    confirmEnd,
    handleNavigate,
    onLockedPress,
    goToDone,
    goToHome,
  } = useLiveScreen(sessionId);

  const subtitle = subtitleFor(view, childName);
  const baseElapsed = elapsedLineFor(view);
  const silent = view === undefined ? null : silentMinutes(view);
  const elapsed = baseElapsed !== null && silent !== null ? silentElapsedLine(baseElapsed, silent) : baseElapsed;

  return (
    <div className="mj-screen" data-testid="live-screen" data-state={status ?? "LOADING"}>
      <header className="mj-header mj-header--stacked">
        {isPractice && view !== undefined ? <PracticeHeader primary progress={view.practice}
          issue={error ? "fetch" : view.practiceIssue}>
          {subtitle !== null ? <p className="mj-header__subtitle" role="status" data-testid="live-status">{subtitle}</p> : null}
        </PracticeHeader> : <><h1 className="mj-header__title">{COPY.live.header}</h1>
        {subtitle !== null ? (
          <p className="mj-header__subtitle" role="status" data-testid="live-status">
            {subtitle}
          </p>
        ) : null}</>}
        {elapsed !== null ? (
          <p className="mj-header__meta" data-testid="live-elapsed">
            {elapsed}
          </p>
        ) : null}
      </header>
      <main className="mj-screen__body mj-screen__body--live">
        {offline ? (
          <StatusLine tone="warning" testId="live-offline">
            {offlineLine(formatHhmm(view?.serverTime))}
          </StatusLine>
        ) : null}
        {view?.stale === true ? (
          <StatusLine tone="warning" testId="live-stale">
            {liveStaleLine(formatHhmm(view.observedAt))}
          </StatusLine>
        ) : null}
        {view !== undefined && showsPauseLine(view) ? (
          <StatusLine tone="warning" testId="live-recovering">
            {ACTIVITY_PROPOSED_COPY.live.recovering}
          </StatusLine>
        ) : null}
        {silent !== null ? (
          <section className="mj-card mj-warn-card" role="status" data-testid="live-silence-warning">
            <p className="mj-warn-card__title">{ACTIVITY_COPY.live.warnTitle}</p>
            <p className="mj-warn-card__body">{ACTIVITY_COPY.live.warnBody}</p>
          </section>
        ) : null}
        <TranscriptList items={view?.items ?? []} childName={childName} gaps={view?.gaps ?? []} />
        <button
          type="button"
          className="mj-button mj-button--danger mj-button--block"
          data-testid="live-end"
          disabled={!canEnd}
          onClick={openEndDialog}
        >
          {isPractice ? U5B_COPY.endButton : COPY.live.endButton}
        </button>
      </main>
      <BottomTabBar active={isPractice ? "practice" : "free"} lockMessage={null} onNavigate={handleNavigate} onLockedPress={onLockedPress} />
      <ConfirmDialog
        open={dialogOpen}
        title={COPY.endDialog.title}
        confirmLabel={COPY.endDialog.yes}
        cancelLabel={COPY.endDialog.no}
        onConfirm={confirmEnd}
        onCancel={closeEndDialog}
        busy={ending}
        testIdPrefix="p1"
      />
      <NoticeDialog
        open={outcomeKind === "unexpectedEnd"}
        title={ACTIVITY_COPY.unexpectedEnd.title}
        confirmLabel={ACTIVITY_COPY.unexpectedEnd.confirm}
        onConfirm={goToDone}
        testIdPrefix="p2"
      />
      <NoticeDialog
        open={outcomeKind === "noResponse"}
        title={ACTIVITY_COPY.noResponse.title}
        confirmLabel={ACTIVITY_COPY.noResponse.confirm}
        onConfirm={goToHome}
        testIdPrefix="p3"
      />
    </div>
  );
}
