"use client";

import { useState } from "react";
import { useHomeScreen } from "./useHomeScreen";
import type { LockReasonCode } from "../../common/api/types";
import { BottomTabBar } from "../../common/components/BottomTabBar";
import { DeviceCard } from "./DeviceCard";
import { SettingsSheet } from "./SettingsSheet";
import { ACCOUNT_COPY, ACCOUNT_PROPOSED_COPY, ACCOUNT_TEMP_COPY, childNameAgeLine } from "@/shared/copy/account";
import { ACTIVITY_COPY, ACTIVITY_PROPOSED_COPY, lastCheckedText } from "@/shared/copy/activity";
import { TEMP_COPY, homeTitle } from "@/shared/copy/ko";

// 04 홈 — 아이 · 대화 기기 · 지금 활동 세 구역 + 「⚙」 설정 (U1 3.5 Step 40 · BR4.5 · BR4.6 · BR2.9 · J57 · PD-12 · PD-14,
// U2 · US1.4 · US1.5 · AC1.4.1 ~ AC1.4.6 · AC1.4.11 · AC1.4.12 · AC1.4.14 · AC1.4.15 · U2-J9 · U2-J15)
// 진입 때 GET /api/home 1회. 데이터 없이 뼈대를 먼저 그리고 카드 높이를 고정한다. 실패는 토스트 + 「다시 시도」.
// 탭은 시작 가능(canStart)일 때만 열리고, 잠긴 탭을 누르면 이유 토스트(mockups 9-6) 뒤 05로 간다.
// 앱 시작 점검(U2-J9): GET /api/me로 동의 · 아이가 없으면 01(다음 실행은 01부터), 삭제 처리 중이면 01 안내. 이 응답을 못 읽으면 막지 않는다.
// 지금 활동 구역(U4a · AC1.4.7 · AC1.4.8 · AC1.4.10 · AC1.4.13 · AC1.1.6): 진행 중 '{아이} · 자유대화 n분째' + 'hh:mm 시작' +
// 「진행 화면 보기 ›」 → 12, 종료 처리 중(CLOSING) → 17, 조회 실패면 '지금 상태를 불러오지 못했어요' · 마지막 확인 · 「다시 확인」만
// (진행 중 · 없음 라벨을 보이지 않는다), 그 밖은 U2 틀(없음 · 잠김). 401은 안내 토스트 뒤 01이다(AC1.1.9).
// 요청 · 점검 · 오류 분기 · 이동은 useHomeScreen 훅이 맡는다(BR2.1, 단계 2).

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

export function HomeScreen() {
  const {
    data,
    childName,
    notification,
    lockMessage,
    liveCard,
    activityUnknown,
    lastCheckedHhmm,
    handleNavigate,
    handleLockedPress,
    recheckActivity,
    goToChildEdit,
    goToRegister,
    goToDevice,
    goToLiveCard,
  } = useHomeScreen();
  const [settingsOpen, setSettingsOpen] = useState(false);

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
            <button type="button" className="mj-link-button" data-testid="home-child-edit" onClick={goToChildEdit}>
              {ACCOUNT_COPY.home.childEdit}
            </button>
          </section>
        ) : null}
        {data !== undefined ? (
          <DeviceCard
            device={data.device}
            onRegister={goToRegister}
            onViewStatus={goToDevice}
          />
        ) : (
          <section className="mj-card mj-device-card mj-card--placeholder" aria-busy="true" data-testid="home-device-loading" />
        )}
        {activityUnknown ? (
          <section
            className="mj-card mj-activity-card"
            data-testid="home-activity-card"
            data-state="unknown"
            aria-labelledby="home-activity-heading"
          >
            <h2 className="mj-card__heading" id="home-activity-heading">
              {ACCOUNT_COPY.home.activitySection}
            </h2>
            <p className="mj-activity-card__status" data-testid="home-activity-status">
              {ACTIVITY_PROPOSED_COPY.home.unknown}
            </p>
            <p className="mj-activity-card__reason" data-testid="home-activity-checked">
              {lastCheckedText(lastCheckedHhmm)}
            </p>
            <button type="button" className="mj-link-button" data-testid="home-activity-recheck"
              onClick={recheckActivity}>
              {ACTIVITY_PROPOSED_COPY.home.recheck}
            </button>
          </section>
        ) : notification.pending ? (
          <section className="mj-card mj-activity-card mj-card--placeholder" aria-busy="true" data-testid="home-activity-loading" />
        ) : liveCard !== null ? (
          <section
            className="mj-card mj-activity-card"
            data-testid="home-activity-card"
            data-state={liveCard.state}
            aria-labelledby="home-activity-heading"
          >
            <h2 className="mj-card__heading" id="home-activity-heading">
              {ACCOUNT_COPY.home.activitySection}
            </h2>
            <p className="mj-activity-card__status" data-testid="home-activity-status">
              {liveCard.line}
            </p>
            {liveCard.startedLine !== null ? <p className="mj-activity-card__reason">{liveCard.startedLine}</p> : null}
            <button type="button" className="mj-link-button" data-testid="home-activity-open" onClick={goToLiveCard}>
              {ACTIVITY_COPY.home.viewLive}
            </button>
          </section>
        ) : data !== undefined ? (
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
