"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { freeFailedRoute } from "../activity/freeFailure";
import { liveOutcome, showsPauseLine, silentMinutes } from "../activity/livePresentation";
import { ApiError } from "../api/apiClient";
import { endSession, fetchHome, fetchLiveView, mergeLiveView, queryKeys, useApiClient } from "../api/queries";
import { isTerminalStatus, type LiveView, type SessionStatus } from "../api/types";
import { BottomTabBar, type TabKey } from "../components/BottomTabBar";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { NoticeDialog } from "../components/NoticeDialog";
import { StatusLine } from "../components/StatusLine";
import { TranscriptList } from "../components/TranscriptList";
import { useLivePolling } from "../hooks/useLivePolling";
import { PARENT_ROUTES, goToLogin, isUnauthenticated, routeForTab } from "../navigation";
import { PracticeHeader } from "../practice/PracticeHeader";
import { U5B_COPY } from "../practice/u5bCopy";
import {
  COPY,
  PROPOSED_COPY,
  TEMP_COPY,
  liveElapsedLine,
  liveStaleLine,
  liveTalkingLine,
  liveWaitingChildLine,
} from "@/shared/copy/ko";
import { ACTIVITY_COPY, ACTIVITY_PROPOSED_COPY, offlineLine, silentElapsedLine } from "@/shared/copy/activity";
import { elapsedMinuteOrdinal, formatHhmm } from "@/shared/copy/timeText";
import { messageForErrorCode } from "@/shared/errors/codeToCopy";
import { useToast } from "@/shared/toast/ToastProvider";

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

/** 연속 실패가 이 횟수가 되면 토스트를 보인다(PD-12). */
const POLL_FAILURE_TOAST_THRESHOLD = 3;

function keepPollingOnLive(status: SessionStatus): boolean {
  return status !== "CLOSING" && !isTerminalStatus(status);
}

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

export interface Live12Props {
  readonly sessionId: string;
}

export function Live12({ sessionId }: Live12Props) {
  const router = useRouter();
  const toast = useToast();
  const api = useApiClient();
  const queryClient = useQueryClient();
  const polling = useLivePolling(sessionId, { keepPolling: keepPollingOnLive });
  // 아이 이름은 04에서 받아 둔 홈 응답을 쓴다(12는 진행 조회 말고 따로 부르지 않는다).
  const homeCache = useQuery({ queryKey: queryKeys.home, queryFn: ({ signal }) => fetchHome(api, signal), enabled: false });
  const childName = homeCache.data?.child?.displayName ?? null;
  const [dialogOpen, setDialogOpen] = useState(false);
  const [ending, setEnding] = useState(false);
  const requestIdRef = useRef<string | null>(null);

  const view = polling.view;
  const status = view?.status;
  const { error, failureStreak, refetch } = polling;

  // 상태에 따른 화면 이동(functional-spec 4절 표 · J120, U4a: 연결 끊김 종료는 P2 → 17, 무응답은 P3 → 04, 서버 재시작 FAILED는 17 — U4a-J12 · J16).
  const outcome = view === undefined ? null : liveOutcome(view);
  const outcomeKind = outcome?.kind;
  const failedCode = outcome?.kind === "startFailed" ? outcome.failureCode : null;
  const isPractice = view?.activityType === "PRACTICE";
  useEffect(() => {
    if (outcomeKind === "startFailed") {
      router.replace(isPractice ? `${PARENT_ROUTES.practice}?failed=1&reason=${encodeURIComponent(failedCode ?? "DEVICE_NO_ACK")}` : freeFailedRoute(failedCode));
    } else if (outcomeKind === "done") {
      router.replace(PARENT_ROUTES.sessionDone(sessionId));
    }
  }, [router, sessionId, outcomeKind, failedCode, isPractice]);

  // 401 → 안내 토스트 뒤 01(AC1.1.9, 세션은 계속), 404(남의 · 없는 세션) → 홈.
  useEffect(() => {
    if (isUnauthenticated(error)) {
      goToLogin(router, toast);
    } else if (error instanceof ApiError && error.status === 404) {
      toast.show({ message: messageForErrorCode("NOT_FOUND"), kind: "error" });
      router.replace(PARENT_ROUTES.home);
    }
  }, [error, router, toast]);

  // 연속 3회 실패하면 한 번 알린다(PD-12, 폴링은 계속).
  useEffect(() => {
    if (failureStreak === POLL_FAILURE_TOAST_THRESHOLD) {
      toast.show({
        message: PROPOSED_COPY.live.pollingTrouble,
        kind: "error",
        action: { label: PROPOSED_COPY.retry, onPress: refetch },
      });
    }
  }, [failureStreak, refetch, toast]);

  const rereadAfterConflict = async () => {
    const previous = queryClient.getQueryData<LiveView>(queryKeys.session(sessionId));
    const fresh = await fetchLiveView(api, sessionId, previous?.nextSeq ?? 0);
    queryClient.setQueryData<LiveView>(queryKeys.session(sessionId), mergeLiveView(previous, fresh));
    // 이동은 위 효과(liveOutcome)가 한다. 아직 시작 중이면 머물며 알린다
    if (liveOutcome(fresh).kind === "stay") {
      toast.show({ message: TEMP_COPY.livePreparingEndToast });
    }
  };

  const confirmEnd = async () => {
    if (ending) {
      return;
    }
    // 같은 P1 안의 재시도는 같은 requestId(BR6.9 · RD-21).
    const requestId = requestIdRef.current ?? crypto.randomUUID();
    requestIdRef.current = requestId;
    setEnding(true);
    try {
      await endSession(api, sessionId, requestId);
      requestIdRef.current = null;
      setDialogOpen(false);
      router.push(PARENT_ROUTES.sessionDone(sessionId));
    } catch (failure) {
      setDialogOpen(false);
      if (failure instanceof ApiError && failure.status === 409 && failure.code === "SESSION_NOT_ACTIVE") {
        requestIdRef.current = null;
        try {
          await rereadAfterConflict();
        } catch {
          toast.show({ message: PROPOSED_COPY.endSendFailed, kind: "error" });
        }
        return;
      }
      if (failure instanceof ApiError && failure.status === 404) {
        toast.show({ message: messageForErrorCode("NOT_FOUND"), kind: "error" });
        router.replace(PARENT_ROUTES.home);
        return;
      }
      // 503 · 401 · 403 · 네트워크 · 시간 초과: 전송하지 못함(종료 요청됨으로 표시하지 않음, 세션은 그대로).
      toast.show({ message: PROPOSED_COPY.endSendFailed, kind: "error" });
      if (isUnauthenticated(failure)) {
        // '전송하지 못함' 토스트를 남긴 채 01로(AC1.1.9 · AC3.3.6)
        router.replace(PARENT_ROUTES.login);
      }
    } finally {
      setEnding(false);
    }
  };

  const handleNavigate = (tab: TabKey) => {
    // 진행 중에는 자유대화 · 연습 탭을 눌러도 12에 머문다(mockups S-12 TABS).
    if (tab === "home" || tab === "records") {
      router.push(routeForTab(tab));
    }
  };

  const subtitle = subtitleFor(view, childName);
  const baseElapsed = elapsedLineFor(view);
  const silent = view === undefined ? null : silentMinutes(view);
  const elapsed = baseElapsed !== null && silent !== null ? silentElapsedLine(baseElapsed, silent) : baseElapsed;
  // 앱만 오프라인: 마지막 폴링이 응답 없음으로 실패(U4a-J17 · AC3.2.12). 마지막으로 받은 시각을 보인다
  const offline = error instanceof ApiError && error.isDeliveryUnknown && failureStreak > 0;
  // 종료 가능 상태는 WAITING_CHILD · ACTIVE(AC3.3.8 · C.S-12.11.5 참여 기다리는 중 그림의 「이야기 끝내기」 · U4b 3.2 표)
  const canEnd = (status === "ACTIVE" || status === "WAITING_CHILD") && !ending;

  return (
    <div className="mj-screen" data-testid="live-screen" data-state={status ?? "LOADING"}>
      <header className="mj-header mj-header--stacked">
        {isPractice ? <PracticeHeader primary progress={view.practice}
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
          onClick={() => setDialogOpen(true)}
        >
          {isPractice ? U5B_COPY.endButton : COPY.live.endButton}
        </button>
      </main>
      <BottomTabBar active={isPractice ? "practice" : "free"} lockMessage={null} onNavigate={handleNavigate} onLockedPress={(message) => toast.show({ message })} />
      <ConfirmDialog
        open={dialogOpen}
        title={COPY.endDialog.title}
        confirmLabel={COPY.endDialog.yes}
        cancelLabel={COPY.endDialog.no}
        onConfirm={() => void confirmEnd()}
        onCancel={() => setDialogOpen(false)}
        busy={ending}
        testIdPrefix="p1"
      />
      <NoticeDialog
        open={outcomeKind === "unexpectedEnd"}
        title={ACTIVITY_COPY.unexpectedEnd.title}
        confirmLabel={ACTIVITY_COPY.unexpectedEnd.confirm}
        onConfirm={() => router.replace(PARENT_ROUTES.sessionDone(sessionId))}
        testIdPrefix="p2"
      />
      <NoticeDialog
        open={outcomeKind === "noResponse"}
        title={ACTIVITY_COPY.noResponse.title}
        confirmLabel={ACTIVITY_COPY.noResponse.confirm}
        onConfirm={() => router.replace(PARENT_ROUTES.home)}
        testIdPrefix="p3"
      />
    </div>
  );
}
