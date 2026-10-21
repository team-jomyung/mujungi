"use client";

import { useQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { freeFailedRoute, practiceFailedRoute } from "../../common/activity/failure";
import { isStartFailure } from "../../common/activity/livePresentation";
import { ApiError } from "../../common/api/apiClient";
import { queryKeys, useApiClient } from "../../common/api/queries";
import { fetchHome } from "../../common/api/home";
import { isTerminalStatus, type LiveView, type SessionStatus } from "../../common/api/types";
import type { TabKey } from "../../common/components/BottomTabBar";
import { useLivePolling } from "../../common/hooks/useLivePolling";
import { useResultStatusPolling } from "../../common/hooks/useResultStatusPolling";
import { PARENT_ROUTES, goToLogin, isUnauthenticated, routeForTab } from "../../common/navigation";
import { PROPOSED_COPY, TEMP_COPY } from "@/shared/copy/ko";
import { messageForErrorCode } from "@/shared/errors/codeToCopy";
import { useToast } from "@/shared/toast/ToastProvider";

// 17 활동 끝 화면의 폴링 · 오류 분기 · 화면 이동을 맡는 흐름 훅.
// 동작 규칙(종료 상태를 받을 때까지 폴링 · 시작 실패 08(연습은 18) · 401 · 404 · 읽기 실패 토스트)은 DoneScreen에 있던 그대로다.

function keepPollingOnDone(status: SessionStatus): boolean {
  return !isTerminalStatus(status);
}

/**
 * 연습 목표 수(C.S-17.21.1 · C.S-17.21.3). 종료된 세션의 진행 조회에는 practice가 오지 않는다(realtime은 실행기가 끝나면
 * 비우고 core 사본 응답에는 없다). 그래서 12에서 이어받은 조회 값이나 마무리 중에 받은 값 가운데 마지막 것을 기억해 쓴다.
 */
function useGoalCount(sessionId: string, view: LiveView | undefined): number | null {
  const seen = view?.practice?.goalCount ?? null;
  const [kept, setKept] = useState<{ readonly sessionId: string; readonly goalCount: number } | null>(null);
  if (seen !== null && (kept?.sessionId !== sessionId || kept.goalCount !== seen)) {
    setKept({ sessionId, goalCount: seen });
  }
  return seen ?? (kept?.sessionId === sessionId ? kept.goalCount : null);
}

export function useDoneScreen(sessionId: string) {
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
  const resultPolling = useResultStatusPolling(sessionId, status !== undefined && isTerminalStatus(status) && !startFailed);
  const jobStatus = resultPolling.data?.jobStatus;
  const failureCode = view?.failureCode ?? null;
  const isPractice = view?.activityType === "PRACTICE";
  const goalCount = useGoalCount(sessionId, view);

  useEffect(() => {
    if (startFailed) {
      router.replace(isPractice ? practiceFailedRoute(failureCode) : freeFailedRoute(failureCode));
    }
  }, [router, startFailed, failureCode, isPractice]);

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

  const handleNavigate = (tab: TabKey) => {
    router.push(routeForTab(tab));
  };

  return {
    view,
    status,
    childName,
    goalCount,
    jobStatus,
    handleNavigate,
    onLockedPress: (message: string) => toast.show({ message }),
    goToRecordDetail: () => router.push(PARENT_ROUTES.recordDetail(sessionId)),
    goToHome: () => router.push(PARENT_ROUTES.home),
  };
}
