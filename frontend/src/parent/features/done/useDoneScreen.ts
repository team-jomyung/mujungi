"use client";

import { useQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { freeFailedRoute } from "../free/failure";
import { isStartFailure } from "../live/presentation";
import { ApiError } from "../../common/api/apiClient";
import { queryKeys, useApiClient } from "../../common/api/queries";
import { fetchHome } from "../home/api";
import { isTerminalStatus, type SessionStatus } from "../../common/api/types";
import type { TabKey } from "../../common/components/BottomTabBar";
import { useLivePolling } from "../live/useLivePolling";
import { useResultStatusPolling } from "./useResultStatusPolling";
import { PARENT_ROUTES, goToLogin, isUnauthenticated, routeForTab } from "../../common/navigation";
import { PROPOSED_COPY, TEMP_COPY } from "@/shared/copy/ko";
import { messageForErrorCode } from "@/shared/errors/codeToCopy";
import { useToast } from "@/shared/toast/ToastProvider";

// 17 활동 끝 화면의 폴링 · 오류 분기 · 화면 이동을 맡는 흐름 훅(BR2.1).
// 동작 규칙(종료 상태를 받을 때까지 폴링 · 시작 실패 08 · 401 · 404 · 읽기 실패 토스트)은 DoneScreen에 있던 그대로다(BR1.1).

function keepPollingOnDone(status: SessionStatus): boolean {
  return !isTerminalStatus(status);
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

  const handleNavigate = (tab: TabKey) => {
    router.push(routeForTab(tab));
  };

  return {
    view,
    status,
    childName,
    jobStatus,
    handleNavigate,
    onLockedPress: (message: string) => toast.show({ message }),
    goToRecordDetail: () => router.push(PARENT_ROUTES.recordDetail(sessionId)),
    goToHome: () => router.push(PARENT_ROUTES.home),
  };
}
