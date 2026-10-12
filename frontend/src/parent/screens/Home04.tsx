"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { useMeQuery } from "../api/account";
import { useHomeQuery } from "../api/queries";
import type { LockReasonCode } from "../api/types";
import { BottomTabBar, type TabKey } from "../components/BottomTabBar";
import { DeviceCard } from "../components/DeviceCard";
import { SettingsSheet } from "../components/SettingsSheet";
import { PARENT_ROUTES, goToLogin, isUnauthenticated, lockToastMessage, routeForTab } from "../navigation";
import { ACCOUNT_COPY, ACCOUNT_PROPOSED_COPY, ACCOUNT_TEMP_COPY, childNameAgeLine } from "@/shared/copy/account";
import { PROPOSED_COPY, TEMP_COPY, homeTitle } from "@/shared/copy/ko";
import { useToast } from "@/shared/toast/ToastProvider";

// 04 홈 — 아이 · 대화 기기 · 지금 활동 세 구역 + 「⚙」 설정 (U1 3.5 Step 40 · BR4.5 · BR4.6 · BR2.9 · J57 · PD-12 · PD-14,
// U2 · US1.4 · US1.5 · AC1.4.1 ~ AC1.4.6 · AC1.4.11 · AC1.4.12 · AC1.4.14 · AC1.4.15 · U2-J9 · U2-J15)
// 진입 때 GET /api/home 1회. 데이터 없이 뼈대를 먼저 그리고 카드 높이를 고정한다. 실패는 토스트 + 「다시 시도」.
// 탭은 시작 가능(canStart)일 때만 열리고, 잠긴 탭을 누르면 이유 토스트(mockups 9-6) 뒤 05로 간다.
// 앱 시작 점검(U2-J9): GET /api/me로 동의 · 아이가 없으면 01(다음 실행은 01부터), 삭제 처리 중이면 01 안내. 이 응답을 못 읽으면 막지 않는다.
// 지금 활동 구역은 틀만(J18): 진행 중 · 미확인 · 재조회는 U4가 채운다. 401은 안내 토스트 뒤 01이다(AC1.1.9).

function lockedReason(reason: LockReasonCode | null): string {
  switch (reason) {
    case "NO_DEVICE":
      return ACCOUNT_COPY.home.lockedNoDevice;
    case "DEVICE_UNSTABLE":
      return ACCOUNT_COPY.home.lockedUnstable;
    case "DEVICE_DISCONNECTED":
      return ACCOUNT_PROPOSED_COPY.lockedDisconnected;
    case "DEVICE_UNRESPONSIVE":
      return ACCOUNT_PROPOSED_COPY.lockedUnresponsive;
    default:
      return TEMP_COPY.lockToastStatusUnknown;
  }
}

export function Home04() {
  const router = useRouter();
  const toast = useToast();
  const home = useHomeQuery();
  const me = useMeQuery();
  const [settingsOpen, setSettingsOpen] = useState(false);
  const { error, errorUpdatedAt, refetch } = home;

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

  // 앱 시작 점검(U2-J9 · AC1.1.5 · AC1.2.7): 처음 한 번을 마치지 않았으면 01부터
  const meData = me.data;
  useEffect(() => {
    if (meData === undefined) {
      return;
    }
    if (meData.deletionInProgress) {
      router.replace(PARENT_ROUTES.loginDeleting);
    } else if (meData.consentStatus !== "GIVEN" || !meData.childRegistered) {
      router.replace(PARENT_ROUTES.login);
    }
  }, [meData, router]);

  const data = home.data;
  // 홈을 아직 못 읽었으면 판정을 모르므로 잠근다(닫힌 쪽).
  const lockMessage = data === undefined ? TEMP_COPY.lockToastStatusUnknown : data.lock.locked ? lockToastMessage(data.lock.reasonCode) : null;
  const childName = data?.child?.displayName ?? null;

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
      <header className="mj-header mj-header--with-action">
        <h1 className="mj-header__title" data-testid="home-title">
          {homeTitle(childName)}
        </h1>
        <button
          type="button"
          className="mj-header__action"
          aria-label={ACCOUNT_TEMP_COPY.settingsButton}
          data-testid="home-settings"
          onClick={() => setSettingsOpen(true)}
        >
          {ACCOUNT_TEMP_COPY.settingsIcon}
        </button>
      </header>
      <main className="mj-screen__body">
        {data?.child !== null && data?.child !== undefined ? (
          <section className="mj-card mj-child-section" data-testid="home-child-card" aria-labelledby="home-child-heading">
            <h2 className="mj-card__heading" id="home-child-heading">
              {ACCOUNT_COPY.home.childSection}
            </h2>
            <p className="mj-child-section__line">{childNameAgeLine(data.child.displayName, data.child.age)}</p>
            <button type="button" className="mj-link-button" data-testid="home-child-edit" onClick={() => router.push(PARENT_ROUTES.child)}>
              {ACCOUNT_COPY.home.childEdit}
            </button>
          </section>
        ) : null}
        {data !== undefined ? (
          <DeviceCard
            device={data.device}
            onRegister={() => router.push(PARENT_ROUTES.register)}
            onViewStatus={() => router.push(PARENT_ROUTES.device)}
          />
        ) : (
          <section className="mj-card mj-device-card mj-card--placeholder" aria-busy="true" data-testid="home-device-loading" />
        )}
        {data !== undefined ? (
          <section
            className="mj-card mj-activity-card"
            data-testid="home-activity-card"
            data-state={data.lock.locked ? "locked" : "none"}
            aria-labelledby="home-activity-heading"
          >
            <h2 className="mj-card__heading" id="home-activity-heading">
              {ACCOUNT_COPY.home.activitySection}
            </h2>
            {data.lock.locked ? (
              <>
                <p className="mj-activity-card__status" data-testid="home-activity-status">
                  {ACCOUNT_COPY.home.activityLocked}
                </p>
                <p className="mj-activity-card__reason">{lockedReason(data.lock.reasonCode)}</p>
              </>
            ) : (
              <>
                <p className="mj-activity-card__status" data-testid="home-activity-status">
                  {ACCOUNT_COPY.home.activityNone}
                </p>
                <p className="mj-activity-card__reason">{ACCOUNT_COPY.home.activityNoneBody}</p>
              </>
            )}
          </section>
        ) : null}
      </main>
      <BottomTabBar active="home" lockMessage={lockMessage} onNavigate={handleNavigate} onLockedPress={handleLockedPress} />
      <SettingsSheet open={settingsOpen} childName={childName} onClose={() => setSettingsOpen(false)} />
    </div>
  );
}
