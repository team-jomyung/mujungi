"use client";

import { useQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { presentDone } from "../activity/donePresentation";
import { freeFailedRoute } from "../activity/freeFailure";
import { isStartFailure } from "../activity/livePresentation";
import { ApiError } from "../api/apiClient";
import { fetchHome, queryKeys, useApiClient } from "../api/queries";
import { isTerminalStatus, type LiveView, type SessionStatus } from "../api/types";
import { BottomTabBar, type TabKey } from "../components/BottomTabBar";
import { useLivePolling } from "../hooks/useLivePolling";
import { PARENT_ROUTES, goToLogin, isUnauthenticated, routeForTab } from "../navigation";
import { COPY, PROPOSED_COPY, TEMP_COPY, doneTimeRangeLine } from "@/shared/copy/ko";
import { formatHhmm, minutesBetween } from "@/shared/copy/timeText";
import { messageForErrorCode } from "@/shared/errors/codeToCopy";
import { useToast } from "@/shared/toast/ToastProvider";

// 17 활동 끝 (U1 3.5 Step 40 · BR6.10 · BR6.12 ③ ④ · BR6.15 · AC3.3.3 ~ AC3.3.7 · J120 · mockups 9-19, U4a · AC3.4.3 · AC3.6.1 ·
// AC3.6.9 · AC3.6.10 · U4a-J13 ~ J16)
// - 마무리 중(CLOSING 등 종료 전): '오늘 자유대화를 마무리하고 있어요' + '무중이 마무리 인사를 하고 있어요' + '기기가 멈추면 기록을 정리해요.'
// - 종료: '오늘 자유대화가 끝났어요' + 사유 카드(AC3.6.9 표 — 보호자 끝냄 · 아이가 끝냄 · 자동 종료 · 연결 끊김 · 기기 응답 없이(UNCONFIRMED ·
//   서버 재시작 FAILED), activity/donePresentation.ts) + '기록을 정리하고 있어요'(정리 끝 · 실패는 U6) + 종료 확인 미확인 줄(AC3.4.3).
// - 시간 줄 'hh:mm ~ hh:mm · n분', 「홈으로」. 「오늘 기록 보기」는 정리 상태(U6)와 27이 생길 때까지 감춘다(J120 · U4a-J14).
// - 시작 실패 FAILED면 08 사유 카드(J120). 종료 상태를 받을 때까지 같은 주기로 다시 읽는다. 마무리 중엔 자유대화 · 연습 탭이 잠긴다.

function keepPollingOnDone(status: SessionStatus): boolean {
  return !isTerminalStatus(status);
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
  const api = useApiClient();
  const polling = useLivePolling(sessionId, { keepPolling: keepPollingOnDone });
  // 아이 이름(아이가 끝냄 사유)은 04에서 받아 둔 홈 응답을 쓴다(17은 따로 부르지 않는다).
  const homeCache = useQuery({ queryKey: queryKeys.home, queryFn: ({ signal }) => fetchHome(api, signal), enabled: false });
  const childName = homeCache.data?.child?.displayName ?? null;
  const { view, error, failureStreak, refetch } = polling;
  const status = view?.status;
  const startFailed = view !== undefined && isStartFailure(view);
  const failureCode = view?.failureCode ?? null;

  useEffect(() => {
    if (startFailed) {
      router.replace(freeFailedRoute(failureCode));
    }
  }, [router, startFailed, failureCode]);

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

  const presentation = presentDone(view, childName);
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
        {presentation.unconfirmedLine !== null ? (
          <p className="mj-done__unconfirmed" data-testid="done-unconfirmed">
            {presentation.unconfirmedLine}
          </p>
        ) : null}
        {presentation.cleaningLine !== null ? (
          <p className="mj-done__cleaning" role="status" data-testid="done-cleaning">
            {presentation.cleaningLine}
          </p>
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
