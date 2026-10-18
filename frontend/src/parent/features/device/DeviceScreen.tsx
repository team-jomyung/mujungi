"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { useStopExpressionsQuery } from "../../common/api/account";
import { ApiError } from "../../common/api/apiClient";
import { useHomeQuery } from "../../common/api/queries";
import type { DeviceView } from "../../common/api/types";
import { BottomTabBar, type TabKey } from "../../common/components/BottomTabBar";
import { PARENT_ROUTES, goToLogin, isUnauthenticated, lockToastMessage, routeForTab } from "../../common/navigation";
import {
  ACCOUNT_COPY,
  ACCOUNT_PROPOSED_COPY,
  ACCOUNT_TEMP_COPY,
  disconnectsText,
  networkLine,
  quoted,
  registrationDoneLine,
  rttText,
  sinceText,
  stopSectionTitle,
  stopSummary,
} from "@/shared/copy/account";
import { COPY, PROPOSED_COPY, TEMP_COPY, lastCheckedLine, unresponsiveLastLine } from "@/shared/copy/ko";
import { formatHhmm } from "@/shared/copy/timeText";
import { useToast } from "@/shared/toast/ToastProvider";

// 05 우리 집 기기 — 등록 줄 + 네트워크 줄 + 판정 카드 + 종료 표현 구역 (U2 · US2.1 · AC2.1.1 ~ AC2.1.9 · AC2.1.12 ~ AC2.1.14 ·
// mockups S-05 · 9-8 · interaction-spec 3.3 · 4절 05)
// 진입 때 GET /api/home 1회(04와 같은 DeviceView · 아이 이름, U1 합산 응답)와 종료 표현 1회. 두 줄 · 판정은 색 점 + 상태명 + 사유를 함께
// 쓴다(색만이 아님). 판정 값은 U1 · U3가 보고한 값을 그대로 보인다. 하단 탭은 04와 같은 잠김 규칙이고 잠기면 이유 토스트만 보인다.
// 기기 검색 · 페어링 · 해제 · '다시 등록' 단추는 없다(OQ12, report.md).

type Tone = "stable" | "unstable" | "disconnected" | "unresponsive" | "unknown" | "none";

interface DevicePresentation {
  readonly tone: Tone;
  readonly registration: string;
  readonly network: string;
  readonly networkDetail: string | null;
  readonly verdictTitle: string;
  readonly verdictBody: string | null;
}

function hhmm(iso: string | null): string | null {
  return formatHhmm(iso);
}

/** DeviceView → 05 표시 값(AC2.1.1 ~ AC2.1.6). */
export function presentDeviceScreen(device: DeviceView): DevicePresentation {
  const { registration, network } = device;
  if (registration.status === "NONE" || network.state === "NONE") {
    return {
      tone: "none",
      registration: ACCOUNT_COPY.device.registrationNone,
      network: ACCOUNT_COPY.device.networkBeforeRegistration,
      networkDetail: null,
      verdictTitle: ACCOUNT_COPY.device.verdictRegister,
      verdictBody: ACCOUNT_COPY.device.verdictRegisterBody,
    };
  }
  const registered = registrationDoneLine(registration.codePrefix);
  const rtt = network.rttMs === null ? null : rttText(network.rttMs);
  switch (network.state) {
    case "STABLE":
      return {
        tone: "stable",
        registration: registered,
        network: networkLine(ACCOUNT_PROPOSED_COPY.deviceLine.STABLE),
        networkDetail: rtt,
        verdictTitle: ACCOUNT_COPY.device.verdictReady,
        verdictBody: null,
      };
    case "UNSTABLE":
      return {
        tone: "unstable",
        registration: registered,
        network: networkLine(ACCOUNT_PROPOSED_COPY.deviceLine.UNSTABLE),
        networkDetail: [disconnectsText(network.recentDisconnects ?? 0), rtt].filter((part) => part !== null).join(ACCOUNT_TEMP_COPY.separator),
        verdictTitle: ACCOUNT_COPY.device.verdictBlocked,
        verdictBody: ACCOUNT_COPY.device.unstableBody,
      };
    case "DISCONNECTED": {
      const since = hhmm(network.since);
      return {
        tone: "disconnected",
        registration: registered,
        network: networkLine(ACCOUNT_PROPOSED_COPY.deviceLine.DISCONNECTED),
        networkDetail: since === null ? null : sinceText(since),
        verdictTitle: ACCOUNT_COPY.device.verdictBlocked,
        verdictBody: ACCOUNT_COPY.device.disconnectedBody,
      };
    }
    case "UNRESPONSIVE": {
      const last = hhmm(network.lastSeenAt ?? network.lastReportedAt);
      return {
        tone: "unresponsive",
        registration: registered,
        network: networkLine(ACCOUNT_PROPOSED_COPY.deviceLine.UNRESPONSIVE),
        networkDetail: last === null ? null : unresponsiveLastLine(last),
        verdictTitle: ACCOUNT_COPY.device.verdictBlocked,
        verdictBody: ACCOUNT_PROPOSED_COPY.unresponsiveBody,
      };
    }
    default: {
      const last = hhmm(network.lastReportedAt ?? network.lastSeenAt);
      return {
        tone: "unknown",
        registration: registered,
        network: networkLine(ACCOUNT_PROPOSED_COPY.networkUnknownPrefix),
        networkDetail: last === null ? null : unresponsiveLastLine(last),
        verdictTitle: ACCOUNT_COPY.device.verdictBlocked,
        verdictBody: ACCOUNT_TEMP_COPY.networkUnknown,
      };
    }
  }
}

const TONE_ICON: Readonly<Record<Tone, string>> = {
  stable: "✓",
  unstable: "!",
  disconnected: "✕",
  unresponsive: "?",
  unknown: "?",
  none: "–",
};

export function DeviceScreen() {
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
  const view = data === undefined ? null : presentDeviceScreen(data.device);
  const lockMessage = data === undefined ? TEMP_COPY.lockToastStatusUnknown : data.lock.locked ? lockToastMessage(data.lock.reasonCode) : null;
  const lastChecked = data === undefined ? null : hhmm(data.device.network.lastReportedAt ?? data.device.network.lastSeenAt);
  const childName = data?.child?.displayName ?? null;
  const stopItems = stops.data?.items ?? [];
  const noChild = stops.error instanceof ApiError && stops.error.code === "CHILD_NOT_REGISTERED";

  const handleNavigate = (tab: TabKey) => {
    router.push(routeForTab(tab));
  };

  return (
    <div className="mj-screen" data-testid="device-screen">
      <header className="mj-header mj-header--with-back mj-header--stacked">
        <button
          type="button"
          className="mj-header__back"
          aria-label={TEMP_COPY.back}
          data-testid="device-back"
          onClick={() => router.push(PARENT_ROUTES.home)}
        >
          {COPY.register.backArrow}
        </button>
        <h1 className="mj-header__title">{ACCOUNT_COPY.device.title}</h1>
        {lastChecked !== null ? (
          <p className="mj-header__meta" data-testid="device-last-checked">
            {lastCheckedLine(lastChecked)}
          </p>
        ) : null}
      </header>
      <main className="mj-screen__body">
        {view === null ? (
          <section className="mj-card mj-card--placeholder" aria-busy="true" data-testid="device-loading" />
        ) : (
          <>
            <section className="mj-card mj-device-rows" data-testid="device-rows" data-state={view.tone}>
              <p className="mj-device-row" data-testid="device-registration">
                {view.registration}
              </p>
              <p className="mj-device-row" data-testid="device-network">
                <span className={`mj-status-dot mj-status-dot--${view.tone}`} aria-hidden="true">
                  {TONE_ICON[view.tone]}
                </span>
                <span>{view.network}</span>
              </p>
              {view.networkDetail !== null ? (
                <p className="mj-device-row mj-device-row--detail" data-testid="device-network-detail">
                  {view.networkDetail}
                </p>
              ) : null}
            </section>
            <section className="mj-card mj-verdict-card" role="status" data-testid="device-verdict" data-state={view.tone}>
              <p className="mj-verdict-card__title">{view.verdictTitle}</p>
              {view.verdictBody !== null ? <p className="mj-verdict-card__body">{view.verdictBody}</p> : null}
              {view.tone === "none" ? (
                <button
                  type="button"
                  className="mj-button mj-button--primary"
                  data-testid="device-register"
                  onClick={() => router.push(PARENT_ROUTES.register)}
                >
                  {ACCOUNT_COPY.device.register}
                </button>
              ) : null}
            </section>
          </>
        )}
        {childName !== null && !noChild ? (
          <section className="mj-card mj-stop-section" data-testid="device-stop-section" aria-labelledby="device-stop-heading">
            <h2 className="mj-card__heading" id="device-stop-heading">
              {stopSectionTitle(childName)}
            </h2>
            <p className="mj-stop-section__default">{quoted(ACCOUNT_COPY.stopExpressions.defaultText)}</p>
            {stopItems.length > 0 ? (
              <p className="mj-stop-section__summary" data-testid="device-stop-summary">
                {stopSummary(stopItems.map((item) => item.text))}
              </p>
            ) : null}
            <button
              type="button"
              className="mj-link-button"
              data-testid="device-stop-settings"
              onClick={() => router.push(PARENT_ROUTES.stopExpressions)}
            >
              {ACCOUNT_COPY.device.stopSettings}
            </button>
          </section>
        ) : null}
      </main>
      <BottomTabBar active="home" lockMessage={lockMessage} onNavigate={handleNavigate} onLockedPress={(message) => toast.show({ message })} />
    </div>
  );
}
