import {
  deviceStatePayload,
  type DeviceStatePayload,
  type MicState,
  type PlayerState,
  type VisibilityState,
  type WakeLockState,
} from "@/shared/protocol/messages";

// 기기 상태 보고 (U3 L5 · U3-R17 · FR-F18 · AC6.2.7 · 계약 DEVICE_STATE)
//
// 마이크 트랙 · 재생 장치 · 탭 보임 · 화면 켜짐 유지(Wake Lock)의 지금 값을 들고 있다가, 연결이 준비되면(HELLO_OK) 정상 상태(마이크 OK ·
// 재생 OK · 보임 · 켜짐 유지)와 다른 필드만 보내고, 그 뒤로는 바뀐 필드만 보낸다(계약 '바뀐 필드만 실어도 된다'). 모두 정상이면 아무것도
// 보내지 않는다. 연결이 없을 때 바뀐 값은 다음 연결의 첫 보고에 담긴다. 서버는 연결 객체에 최신값을 두고 지표로 센다.

export interface DeviceStateSnapshot {
  readonly mic: MicState;
  readonly player: PlayerState;
  readonly visibility: VisibilityState;
  readonly wakeLock: WakeLockState;
}

const FIELDS = ["mic", "player", "visibility", "wakeLock"] as const;

/** 정상 상태(서버가 보고를 받기 전에 전제하는 값). */
export const NORMAL_DEVICE_STATE: DeviceStateSnapshot = { mic: "OK", player: "OK", visibility: "VISIBLE", wakeLock: "HELD" };

export class DeviceStateReporter {
  private current: DeviceStateSnapshot;
  /** 이 연결에서 서버가 아는 값(연결이 없으면 null). */
  private reported: DeviceStateSnapshot | null = null;

  constructor(
    initial: DeviceStateSnapshot,
    private readonly send: (payload: DeviceStatePayload) => void,
  ) {
    this.current = initial;
  }

  get snapshot(): DeviceStateSnapshot {
    return this.current;
  }

  /** 값이 바뀌었다. 연결이 있으면 서버가 아는 값과 다른 필드만 보낸다. */
  update(fields: Partial<DeviceStateSnapshot>): void {
    this.current = { ...this.current, ...fields };
    if (this.reported === null) {
      return;
    }
    const changed: { -readonly [K in keyof DeviceStateSnapshot]?: DeviceStateSnapshot[K] } = {};
    for (const field of FIELDS) {
      if (this.current[field] !== this.reported[field]) {
        (changed as Record<string, string>)[field] = this.current[field];
      }
    }
    this.flush(changed);
  }

  /** 연결이 준비됐다: 정상 상태와 다른 필드만 보낸다. */
  connected(): void {
    this.reported = NORMAL_DEVICE_STATE;
    this.update({});
  }

  /** 연결이 끊겼다: 다음 연결에서 다시 모두 보낸다. */
  disconnected(): void {
    this.reported = null;
  }

  private flush(fields: DeviceStatePayload): void {
    const payload = deviceStatePayload(fields);
    if (payload === null) {
      return;
    }
    this.send(payload);
    this.reported = this.current;
  }
}
