"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { activeTabTarget, liveActivityCard } from "./presentation";
import { useMeQuery } from "../../common/api/account";
import { useHomeQuery } from "../../common/api/home";
import type { TabKey } from "../../common/components/BottomTabBar";
import { PARENT_ROUTES, goToLogin, isUnauthenticated, lockToastMessage, routeForTab } from "../../common/navigation";
import { PROPOSED_COPY, TEMP_COPY } from "@/shared/copy/ko";
import { formatHhmm } from "@/shared/copy/timeText";
import { useToast } from "@/shared/toast/ToastProvider";
import { useNotificationEntry } from "../../common/push/useNotificationEntry";

// 04 홈 화면의 요청 · 앱 시작 점검 · 오류 분기 · 화면 이동을 맡는 흐름 훅(BR2.1).
// 동작 규칙(GET /api/home 1회 · GET /api/me 점검 U2-J9 · 401 → 01 · 잠긴 탭 이유 토스트)은 HomeScreen에 있던 그대로다(BR1.1).

export function useHomeScreen() {
  const router = useRouter();
  const toast = useToast();
  const home = useHomeQuery();
  const me = useMeQuery();
  const checkingAccount = me.isPending || me.isFetching;
  const notification = useNotificationEntry(
    !checkingAccount && me.error === null && me.data?.consentStatus === "GIVEN"
      && me.data.childRegistered && !me.data.deletionInProgress && !isUnauthenticated(home.error),
    checkingAccount,
  );
  const { error, errorUpdatedAt, dataUpdatedAt, refetch } = home;

  useEffect(() => {
    if (error === null) {
      return;
    }
    if (isUnauthenticated(error)) {
      goToLogin(router, toast);
      return;
    }
    toast.show({
      message: TEMP_COPY.networkError,
      kind: "error",
      action: { label: PROPOSED_COPY.retry, onPress: () => void refetch() },
    });
  }, [error, errorUpdatedAt, refetch, router, toast]);

  // 앱 시작 점검(U2-J9 · AC1.1.5 · AC1.2.7): 처음 한 번을 마치지 않았으면 01부터. 약관이 바뀐 보호자(OUTDATED)는 이미 한 번을
  // 마쳤으므로 02 재동의로 보낸다(U2-J8 · 편차 (U2-g) · 리뷰 6, 로그인 직후 분기 routeAfterLogin과 같다)
  const meData = me.data;
  useEffect(() => {
    if (notification.active && isUnauthenticated(me.error)) goToLogin(router, toast);
  }, [notification.active, me.error, router, toast]);
  useEffect(() => {
    if (meData === undefined) {
      return;
    }
    if (meData.deletionInProgress) {
      router.replace(PARENT_ROUTES.loginDeleting);
    } else if (meData.consentStatus === "OUTDATED") {
      router.replace(PARENT_ROUTES.consent);
    } else if (meData.consentStatus !== "GIVEN" || !meData.childRegistered) {
      router.replace(PARENT_ROUTES.login);
    }
  }, [meData, router]);

  const data = home.data;
  const currentSession = data?.currentSession ?? null;
  // 진행 중이면 하단 「자유대화」 · 「연습」이 새 시작 대신 그 활동 화면으로 간다(AC1.4.10, U4a)
  const activeTarget = notification.active ? null : activeTabTarget(currentSession);
  // 홈을 아직 못 읽었으면 판정을 모르므로 잠근다(닫힌 쪽). 진행 중이면 잠그지 않고 활동 화면으로 보낸다.
  const lockMessage =
    data === undefined || notification.pending || notification.unknown
      ? TEMP_COPY.lockToastStatusUnknown
      : activeTarget !== null
        ? null
        : data.lock.locked
          ? lockToastMessage(data.lock.reasonCode)
          : null;
  const childName = data?.child?.displayName ?? null;
  // 지금 활동 칸(U4a · AC1.4.7 · AC1.4.13). 'n분째'는 화면 시계로 센다(U4a-J5)
  const liveCard = liveActivityCard(currentSession, childName, new Date().toISOString());

  const handleNavigate = (tab: TabKey) => {
    if (tab === "home") {
      return;
    }
    if ((tab === "free" || tab === "practice") && activeTarget !== null) {
      router.push(activeTarget);
      return;
    }
    router.push(routeForTab(tab));
  };

  const handleLockedPress = (message: string) => {
    toast.show({ message });
    router.push(PARENT_ROUTES.device);
  };

  // 지금 활동 칸이 '확인 불가'로 보이는 경우(알림 진입이 모르는 상태이거나 홈 조회가 401이 아닌 이유로 실패)
  const activityUnknown = notification.unknown || (error !== null && !isUnauthenticated(error));
  // 마지막 확인 시각 hh:mm(알림 진입이 켜져 있으면 그 시각, 아니면 홈 응답을 받은 시각)
  const lastCheckedHhmm = notification.active
    ? notification.lastCheckedAt > 0 ? formatHhmm(new Date(notification.lastCheckedAt).toISOString()) : null
    : data === undefined ? null : formatHhmm(new Date(dataUpdatedAt).toISOString());

  return {
    data,
    childName,
    notification,
    lockMessage,
    liveCard,
    activityUnknown,
    lastCheckedHhmm,
    handleNavigate,
    handleLockedPress,
    recheckActivity: () => notification.active ? notification.retry() : void refetch(),
    goToChildEdit: () => router.push(PARENT_ROUTES.child),
    goToRegister: () => router.push(PARENT_ROUTES.register),
    goToDevice: () => router.push(PARENT_ROUTES.device),
    goToLiveCard: () => {
      if (liveCard !== null) router.push(liveCard.target);
    },
  };
}
