"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { ApiError } from "../api/apiClient";
import { isTerminalStatus, type LiveView, type SessionStatus } from "../api/types";
import { BottomTabBar, type TabKey } from "../components/BottomTabBar";
import { useLivePolling } from "../hooks/useLivePolling";
import { PARENT_ROUTES, goToLogin, isUnauthenticated, routeForTab } from "../navigation";
import { COPY, PROPOSED_COPY, TEMP_COPY, abortedBody, doneTimeRangeLine, guardianStopDetail } from "@/shared/copy/ko";
import { formatHhmm, minutesBetween } from "@/shared/copy/timeText";
import { messageForErrorCode } from "@/shared/errors/codeToCopy";
import { useToast } from "@/shared/toast/ToastProvider";

// 17 활동 끝(최소) (U1 3.5 Step 40 · BR6.10 · BR6.12 ③ ④ · BR6.15 · AC3.3.3 ~ AC3.3.7 · J120 · mockups 9-19)
// - 마무리 중(CLOSING 등 종료 전): '오늘 자유대화를 마무리하고 있어요' + '무중이 마무리 인사를 하고 있어요' + '기기가 멈추면 기록을 정리해요.'
// - ENDED: '오늘 자유대화가 끝났어요' + 사유 '보호자가 끝냈어요' + '「이야기 끝내기」 · hh:mm'
// - ENDED_UNCONFIRMED: '기기 응답 없이 끝냈어요' + 본문(C.S-17.58), ABORTED: '연결이 끊겨 끝났어요' + 본문(C.S-17.71)
// - 시간 줄 'hh:mm ~ hh:mm · n분', 「홈으로」. 「오늘 기록 보기」 · 정리 상태는 U6이라 감춘다(J120). FAILED면 08 사유 카드(J120).
// - 종료 상태를 받을 때까지 같은 주기로 다시 읽어 카드 글만 바꾼다(마무리 중 → 종료). 마무리 중엔 자유대화 · 연습 탭이 잠긴다.

function keepPollingOnDone(status: SessionStatus): boolean {
  return !isTerminalStatus(status);
}

interface DonePresentation {
  readonly title: string;
  readonly reasonTitle: string | null;
  readonly reasonBody: string | null;
  readonly closing: boolean;
}

function presentDone(view: LiveView | undefined): DonePresentation {
  const endedHhmm = formatHhmm(view?.endedAt);
  switch (view?.status) {
    case "ENDED":
      return {
        title: COPY.done.endedTitle,
        reasonTitle: view.endReason === "GUARDIAN_STOP" ? PROPOSED_COPY.done.guardianStopTitle : null,
        reasonBody: view.endReason === "GUARDIAN_STOP" && endedHhmm !== null ? guardianStopDetail(endedHhmm) : null,
        closing: false,
      };
    case "ENDED_UNCONFIRMED":
      return { title: COPY.done.endedTitle, reasonTitle: COPY.done.unconfirmedTitle, reasonBody: COPY.done.unconfirmedBody, closing: false };
    case "ABORTED":
      return { title: COPY.done.endedTitle, reasonTitle: COPY.done.abortedTitle, reasonBody: abortedBody(endedHhmm), closing: false };
    case "DECLINED":
      return { title: COPY.done.endedTitle, reasonTitle: null, reasonBody: null, closing: false };
    default:
      return { title: COPY.done.closingTitle, reasonTitle: COPY.done.closingBody, reasonBody: COPY.done.closingNote, closing: true };
  }
}

function timeRangeFor(view: LiveView | undefined): string | null {
  const from = formatHhmm(view?.startedAt);
  const endIso = view?.endedAt ?? view?.serverTime ?? null;
  const to = formatHhmm(endIso);
  const minutes = minutesBetween(view?.startedAt, endIso);
  return from === null || to === null || minutes === null ? null : doneTimeRangeLine(from, to, minutes);
}

export interface Done17Props {
  readonly sessionId: string;
}

export function Done17({ sessionId }: Done17Props) {
  const router = useRouter();
  const toast = useToast();
  const polling = useLivePolling(sessionId, { keepPolling: keepPollingOnDone });
  const { view, error, failureStreak, refetch } = polling;
  const status = view?.status;

  useEffect(() => {
    if (status === "FAILED") {
      router.replace(PARENT_ROUTES.freeFailed);
    }
  }, [router, status]);

  useEffect(() => {
    if (isUnauthenticated(error)) {
      goToLogin(router, toast);
    } else if (error instanceof ApiError && error.status === 404) {
      toast.show({ message: messageForErrorCode("NOT_FOUND"), kind: "error" });
      router.replace(PARENT_ROUTES.home);
    }
  }, [error, router, toast]);

  // 읽기 실패는 토스트 + 「다시 시도」(PD-12 표 2.9-A 17행). 폴링은 종료 상태를 받을 때까지 계속한다.
  useEffect(() => {
    if (failureStreak === 1 && !isUnauthenticated(error) && !(error instanceof ApiError && error.status === 404)) {
      toast.show({
        message: TEMP_COPY.networkError,
        kind: "error",
        action: { label: PROPOSED_COPY.retry, onPress: refetch },
      });
    }
  }, [error, failureStreak, refetch, toast]);

  const presentation = presentDone(view);
  const timeRange = timeRangeFor(view);

  const handleNavigate = (tab: TabKey) => {
    router.push(routeForTab(tab));
  };

  return (
    <div className="mj-screen" data-testid="done-view" data-state={presentation.closing ? "CLOSING" : (status ?? "LOADING")}>
      <header className="mj-header mj-header--stacked">
        <p className="mj-header__eyebrow">{COPY.done.header}</p>
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
        {presentation.reasonTitle !== null ? (
          <section className="mj-card mj-reason" role="status" data-testid="done-reason">
            <p className="mj-reason__title">{presentation.reasonTitle}</p>
            {presentation.reasonBody !== null ? <p className="mj-reason__body">{presentation.reasonBody}</p> : null}
          </section>
        ) : null}
        <button type="button" className="mj-button mj-button--primary mj-button--block" data-testid="done-home" onClick={() => router.push(PARENT_ROUTES.home)}>
          {COPY.done.home}
        </button>
      </main>
      <BottomTabBar
        active="free"
        lockMessage={presentation.closing ? PROPOSED_COPY.closingTabToast : null}
        onNavigate={handleNavigate}
        onLockedPress={(message) => toast.show({ message })}
      />
    </div>
  );
}
