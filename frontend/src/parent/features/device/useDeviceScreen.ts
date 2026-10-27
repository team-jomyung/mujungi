"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef } from "react";
import { useStopExpressionsQuery } from "../../common/api/child";
import { ApiError } from "../../common/api/apiClient";
import { useHomeQuery } from "../../common/api/home";
import type { TabKey } from "../../common/components/BottomTabBar";
import { PARENT_ROUTES, goToLogin, isUnauthenticated, lockToastMessage, routeForTab } from "../../common/navigation";
import { PROPOSED_COPY, TEMP_COPY } from "@/shared/copy/ko";
import { useToast } from "@/shared/toast/ToastProvider";

// 05 우리 집 기기 화면의 요청(GET /api/home · 종료 표현) · 오류 분기 · 화면 이동을 맡는 흐름 훅.
// 홈과 같은 SSE 알림·재조회·장애 복구를 사용한다.

export function useDeviceScreen() {
  const router = useRouter();
  const toast = useToast();
  const home = useHomeQuery(true);
  const stops = useStopExpressionsQuery();
  const { error, errorUpdatedAt, refetch } = home;
  const errorShown = useRef(false);

  useEffect(() => {
    if (error === null) {
      errorShown.current = false;
      return;
    }
    if (isUnauthenticated(error)) {
      goToLogin(router, toast);
      return;
    }
    if (errorShown.current) return;
    errorShown.current = true;
    toast.show({ message: TEMP_COPY.networkError, kind: "error", action: { label: PROPOSED_COPY.retry, onPress: () => void refetch() } });
  }, [error, errorUpdatedAt, refetch, router, toast]);

  const data = home.data;
  const lockMessage = data === undefined || error !== null ? TEMP_COPY.lockToastStatusUnknown : data.lock.locked ? lockToastMessage(data.lock.reasonCode) : null;
  const childName = data?.child?.displayName ?? null;
  const stopItems = stops.data?.items ?? [];
  const noChild = stops.error instanceof ApiError && stops.error.code === "CHILD_NOT_REGISTERED";

  const handleNavigate = (tab: TabKey) => {
    router.push(routeForTab(tab));
  };

  return {
    data,
    lockMessage,
    childName,
    stopItems,
    stopLoading: stops.isPending,
    stopReadFailed: stops.isError,
    noChild,
    handleNavigate,
    onLockedPress: (message: string) => toast.show({ message }),
    goToHome: () => router.push(PARENT_ROUTES.home),
    goToRegister: () => router.push(PARENT_ROUTES.register),
    goToStopExpressions: () => router.push(PARENT_ROUTES.stopExpressions),
  };
}
