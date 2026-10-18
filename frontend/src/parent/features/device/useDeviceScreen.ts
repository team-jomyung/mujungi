"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { useStopExpressionsQuery } from "../child/api";
import { ApiError } from "../../common/api/apiClient";
import { useHomeQuery } from "../home/api";
import type { TabKey } from "../../common/components/BottomTabBar";
import { PARENT_ROUTES, goToLogin, isUnauthenticated, lockToastMessage, routeForTab } from "../../common/navigation";
import { PROPOSED_COPY, TEMP_COPY } from "@/shared/copy/ko";
import { useToast } from "@/shared/toast/ToastProvider";

// 05 우리 집 기기 화면의 요청(GET /api/home · 종료 표현) · 오류 분기 · 화면 이동을 맡는 흐름 훅(BR2.1).
// 동작 규칙은 DeviceScreen에 있던 그대로다(BR1.1).

export function useDeviceScreen() {
  const router = useRouter();
  const toast = useToast();
  const home = useHomeQuery();
  const stops = useStopExpressionsQuery();
  const { error, errorUpdatedAt, refetch } = home;

  useEffect(() => {
    if (error === null) {
      return;
    }
    if (isUnauthenticated(error)) {
      goToLogin(router, toast);
      return;
    }
    toast.show({ message: TEMP_COPY.networkError, kind: "error", action: { label: PROPOSED_COPY.retry, onPress: () => void refetch() } });
  }, [error, errorUpdatedAt, refetch, router, toast]);

  const data = home.data;
  const lockMessage = data === undefined ? TEMP_COPY.lockToastStatusUnknown : data.lock.locked ? lockToastMessage(data.lock.reasonCode) : null;
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
    noChild,
    handleNavigate,
    onLockedPress: (message: string) => toast.show({ message }),
    goToHome: () => router.push(PARENT_ROUTES.home),
    goToRegister: () => router.push(PARENT_ROUTES.register),
    goToStopExpressions: () => router.push(PARENT_ROUTES.stopExpressions),
  };
}
