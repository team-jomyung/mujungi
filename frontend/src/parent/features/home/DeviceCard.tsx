import { COPY, PROPOSED_COPY, lastCheckedLine, unresponsiveLastLine } from "@/shared/copy/ko";
import { formatHhmm } from "@/shared/copy/timeText";
import type { DeviceView } from "../../common/api/types";

// 04 대화 기기 구역 (U1 3.5 Step 39 · interaction-spec 3.2 · mockups S-04)
// 안정 · 불안정 · 끊김 · 응답 없음 · 확인 불가 · 기기 없음 여섯 상태를 색 점 + 아이콘 + 상태명 + 사유로 함께 보인다
// (색만으로 구분하지 않음). 「기기 등록」은 등록이 없을 때만, 「기기 상태 보기 ›」는 등록된 기기면 어느 상태든 보인다.
// 두 단추 모두 아이 · 지금 활동 구역과 같은 글자 단추로 그린다(홈 카드 생김새 통일).

export type DeviceTone = "stable" | "unstable" | "disconnected" | "unresponsive" | "unknown" | "none";

interface DevicePresentation {
  readonly tone: DeviceTone;
  readonly name: string;
  readonly reason: string | null;
  readonly timeLine: string | null;
}

const TONE_ICON: Readonly<Record<DeviceTone, string>> = {
  stable: "✓",
  unstable: "!",
  disconnected: "✕",
  unresponsive: "?",
  unknown: "?",
  none: "–",
};

function lastChecked(iso: string | null): string | null {
  const hhmm = formatHhmm(iso);
  return hhmm === null ? null : lastCheckedLine(hhmm);
}

/** DeviceView → 표시 값(표). */
export function presentDevice(device: DeviceView): DevicePresentation {
  const { network, registration } = device;
  if (registration.status === "NONE" || network.state === "NONE") {
    return { tone: "none", name: COPY.home.noDevice, reason: COPY.home.noDeviceBody, timeLine: null };
  }
  switch (network.state) {
    case "STABLE":
      return {
        tone: "stable",
        name: COPY.home.stable,
        reason: null,
        timeLine: lastChecked(network.lastReportedAt ?? network.lastSeenAt),
      };
    case "UNSTABLE":
      return { tone: "unstable", name: COPY.home.unstable, reason: COPY.home.unstableReason, timeLine: null };
    case "DISCONNECTED":
      return {
        tone: "disconnected",
        name: PROPOSED_COPY.home.disconnected,
        reason: PROPOSED_COPY.home.disconnectedReason,
        timeLine: null,
      };
    case "UNRESPONSIVE": {
      const hhmm = formatHhmm(network.lastSeenAt ?? network.lastReportedAt);
      return {
        tone: "unresponsive",
        name: PROPOSED_COPY.home.unresponsive,
        reason: PROPOSED_COPY.home.unresponsiveReason,
        timeLine: hhmm === null ? null : unresponsiveLastLine(hhmm),
      };
    }
    default:
      return {
        tone: "unknown",
        name: PROPOSED_COPY.home.unknown,
        reason: null,
        timeLine: lastChecked(network.lastReportedAt ?? network.lastSeenAt),
      };
  }
}

export interface DeviceCardProps {
  readonly device: DeviceView;
  readonly onRegister: () => void;
  readonly onViewStatus: () => void;
}

export function DeviceCard({ device, onRegister, onViewStatus }: DeviceCardProps) {
  const view = presentDevice(device);
  return (
    <section className="mj-card mj-device-card" data-testid="home-device-card" data-state={view.tone} aria-labelledby="home-device-heading">
      <h2 className="mj-card__heading" id="home-device-heading">
        {COPY.home.deviceSection}
      </h2>
      <p className="mj-device-card__status" aria-live="polite">
        <span className={`mj-status-dot mj-status-dot--${view.tone}`} aria-hidden="true">
          {TONE_ICON[view.tone]}
        </span>
        <span className="mj-device-card__name" data-testid="home-device-state-name">
          {view.name}
        </span>
      </p>
      {view.reason !== null ? <p className="mj-device-card__reason">{view.reason}</p> : null}
      {view.timeLine !== null ? <p className="mj-device-card__time">{view.timeLine}</p> : null}
      {view.tone === "none" ? (
        <button type="button" className="mj-link-button" data-testid="home-device-register" onClick={onRegister}>
          {COPY.home.registerDevice}
        </button>
      ) : (
        <button type="button" className="mj-link-button" data-testid="home-device-status" onClick={onViewStatus}>
          {COPY.home.viewDeviceStatus}
        </button>
      )}
    </section>
  );
}
