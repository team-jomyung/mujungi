"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { useHomeQuery } from "../api/queries";
import { BottomTabBar, type TabKey } from "../components/BottomTabBar";
import { DeviceCard } from "../components/DeviceCard";
import { PARENT_ROUTES, isUnauthenticated, lockToastMessage, routeForTab } from "../navigation";
import { PROPOSED_COPY, TEMP_COPY, homeTitle } from "@/shared/copy/ko";
import { useToast } from "@/shared/toast/ToastProvider";

// 04 홈 — U1 최소: 제목 + 대화 기기 구역 + 하단 탭 잠금 (U1 3.5 Step 40 · BR4.5 · BR4.6 · BR2.9 · J57 · PD-12 · PD-14)
// 진입 때 GET /api/home 1회. 데이터 없이 뼈대를 먼저 그리고 카드 높이를 고정한다. 실패는 토스트 + 「다시 시도」.
// 탭은 시작 가능(canStart)일 때만 열리고, 잠긴 탭을 누르면 이유 토스트(mockups 9-6) 뒤 05로 간다(U1은 05 스텁이 04로 되돌림, J57).
// 아이 구역 · 지금 활동 구역은 U2 · U4가 완성한다.

export function Home04() {
  const router = useRouter();
  const toast = useToast();
  const home = useHomeQuery();
  const { error, errorUpdatedAt, refetch } = home;

  useEffect(() => {
    if (error === null) {
      return;
    }
    if (isUnauthenticated(error)) {
      router.replace(PARENT_ROUTES.devLogin);
      return;
    }
    toast.show({
      message: TEMP_COPY.networkError,
      kind: "error",
      action: { label: PROPOSED_COPY.retry, onPress: () => void refetch() },
    });
  }, [error, errorUpdatedAt, refetch, router, toast]);

  const data = home.data;
  // 홈을 아직 못 읽었으면 판정을 모르므로 잠근다(닫힌 쪽).
  const lockMessage = data === undefined ? TEMP_COPY.lockToastStatusUnknown : data.lock.locked ? lockToastMessage(data.lock.reasonCode) : null;

  const handleNavigate = (tab: TabKey) => {
    if (tab !== "home") {
      router.push(routeForTab(tab));
    }
  };

  const handleLockedPress = (message: string) => {
    toast.show({ message });
    router.push(PARENT_ROUTES.device);
  };

  return (
    <div className="mj-screen" data-testid="home-screen">
      <header className="mj-header">
        <h1 className="mj-header__title" data-testid="home-title">
          {homeTitle(data?.child?.displayName ?? null)}
        </h1>
      </header>
      <main className="mj-screen__body">
        {data !== undefined ? (
          <DeviceCard
            device={data.device}
            onRegister={() => router.push(PARENT_ROUTES.register)}
            onViewStatus={() => router.push(PARENT_ROUTES.device)}
          />
        ) : (
          <section className="mj-card mj-device-card mj-card--placeholder" aria-busy="true" data-testid="home-device-loading" />
        )}
      </main>
      <BottomTabBar active="home" lockMessage={lockMessage} onNavigate={handleNavigate} onLockedPress={handleLockedPress} />
    </div>
  );
}
