import { encodeInputFrame, nextFrameSeq, type DecodedFrame } from "@/shared/protocol/frameCodec";
import {
  deviceReadyPayload,
  voiceSessionClosedPayload,
  type ApprovedAudio,
  type PlaybackMarkPayload,
  type PlaybackStopCause,
  type PrefetchFlag,
  type ReadinessFlag,
  type ServerMessage,
  type SessionView,
} from "@/shared/protocol/messages";
import { toIsoInstant, type DeviceToServerType } from "@/shared/protocol/wsEnvelope";

// 기기 쪽 세션 처리 (U1 3.5 Step 38 · 계약 4.2 · BR6.8 · BR6.10 · BR7.1 · BR7.6 · BR9.6 · 편차 (h))
//
// START_SESSION → (wsClient가 ACK) → 승인 음성 미리 받기 → DEVICE_READY{mic, player, prefetch}
//   → 입력 창 열림: 0x01 20ms 프레임을 DEVICE_READY 뒤 ~ CLOSE_TRANSPORT 전까지만 보낸다(서버는 ACTIVE 전 프레임을 버린다).
// SESSION_STATE → T19 표시(대기 · 듣는 중 · 생각 중 · 마무리). '말하는 중'은 재생기가 정한다.
// OUTPUT_BEGIN · 0x02 · OUTPUT_END · PLAY_APPROVED_CONTENT → 재생기(승인된 소리만, BR7.6).
// STOP_OUTPUT → 재생 멈춤(PLAYBACK_MARK STOPPED). CLOSE_TRANSPORT → 입력 창 닫음 · 재생 · 대기열 정리 → VOICE_SESSION_CLOSED.
// WSS는 대기용으로 남는다. 소켓이 끊기면 입력 · 재생을 멈추고 세션을 잊는다(이어가기는 U3).

/** 재생기 중 세션 처리가 쓰는 부분. */
export interface SessionPlayer {
  prefetch(items: readonly ApprovedAudio[]): Promise<PrefetchFlag>;
  beginStream(sessionId: string, playbackId: string): void;
  appendStream(payload: Uint8Array): void;
  endStream(playbackId: string): void;
  playApproved(sessionId: string, playbackId: string, contentCode: string, contentVersionId: string): Promise<void>;
  stopAll(cause: PlaybackStopCause): void;
  reset(): void;
}

export interface DeviceSessionDependencies {
  readonly send: (type: DeviceToServerType, payload: object, sessionId?: string) => void;
  readonly sendBinary: (frame: Uint8Array) => void;
  readonly player: SessionPlayer;
  /** 지금 마이크 · 재생 준비 상태(DEVICE_READY). */
  readonly readiness: () => { readonly mic: ReadinessFlag; readonly player: ReadinessFlag };
  /** 입력 창이 열리고 닫힐 때(캡처 버퍼를 비우는 데 쓴다). */
  readonly onInputWindowChange?: (open: boolean) => void;
  readonly onView: (view: SessionView) => void;
  readonly nowMs: () => number;
}

interface ActiveSession {
  readonly sessionId: string;
  inputOpen: boolean;
  frameSeq: number;
}

export class DeviceSession {
  private current: ActiveSession | null = null;

  constructor(private readonly deps: DeviceSessionDependencies) {}

  get sessionId(): string | null {
    return this.current?.sessionId ?? null;
  }

  get isInputOpen(): boolean {
    return this.current?.inputOpen ?? false;
  }

  /** 재생기의 PLAYBACK_MARK를 서버로 보낸다. */
  sendPlaybackMark(sessionId: string, mark: PlaybackMarkPayload): void {
    this.deps.send("PLAYBACK_MARK", mark, sessionId);
  }

  handleMessage(message: ServerMessage): void {
    switch (message.type) {
      case "START_SESSION":
        void this.startSession(message.sessionId, message.approvedAudio);
        return;
      case "SESSION_STATE":
        if (message.view !== "UNKNOWN") {
          this.deps.onView(message.view);
        }
        return;
      case "OUTPUT_BEGIN":
        if (this.isCurrent(message.sessionId)) {
          this.deps.player.beginStream(message.sessionId, message.playbackId);
        }
        return;
      case "OUTPUT_END":
        this.deps.player.endStream(message.playbackId);
        return;
      case "PLAY_APPROVED_CONTENT":
        if (this.isCurrent(message.sessionId)) {
          void this.deps.player.playApproved(
            message.sessionId,
            message.playbackId,
            message.contentCode,
            message.contentVersionId,
          );
        }
        return;
      case "STOP_OUTPUT":
        this.deps.player.stopAll("STOP_OUTPUT");
        return;
      case "CLOSE_TRANSPORT":
        this.closeTransport(message.sessionId);
        return;
      default:
        // ERROR 등은 화면 동작이 없다(기술 문구를 보이지 않는다, BR9.4).
        return;
    }
  }

  /** 0x02: 지금 세션이 있을 때만 재생기에 넘긴다(재생기는 OUTPUT_BEGIN 없이 온 프레임을 버린다). */
  handleOutputFrame(frame: DecodedFrame): void {
    if (this.current !== null) {
      this.deps.player.appendStream(frame.payload);
    }
  }

  /** 캡처가 만든 480표본 프레임. 입력 창이 열려 있을 때만 0x01로 보낸다(BR7.1). */
  handleInputFrame(samples: Int16Array): void {
    const session = this.current;
    if (session === null || !session.inputOpen) {
      return;
    }
    session.frameSeq = nextFrameSeq(session.frameSeq);
    this.deps.sendBinary(encodeInputFrame(session.frameSeq, samples));
  }

  /** 소켓이 끊겼을 때: 입력 · 재생을 멈추고 세션을 잊는다. */
  handleDisconnect(): void {
    this.setInputOpen(false);
    this.current = null;
    this.deps.player.reset();
  }

  private isCurrent(sessionId: string): boolean {
    return this.current !== null && this.current.sessionId === sessionId;
  }

  private setInputOpen(open: boolean): void {
    const session = this.current;
    if (session === null || session.inputOpen === open) {
      return;
    }
    session.inputOpen = open;
    this.deps.onInputWindowChange?.(open);
  }

  private async startSession(sessionId: string, approvedAudio: readonly ApprovedAudio[]): Promise<void> {
    if (this.current !== null && this.current.sessionId === sessionId) {
      return;
    }
    if (this.current !== null) {
      // 앞 세션의 남은 소리 · 입력을 정리한다(서버가 새 세션을 시작했다).
      this.setInputOpen(false);
      this.deps.player.stopAll("CLOSE_TRANSPORT");
    }
    const session: ActiveSession = { sessionId, inputOpen: false, frameSeq: 0 };
    this.current = session;
    const prefetch = await this.deps.player.prefetch(approvedAudio);
    if (this.current !== session) {
      return;
    }
    const readiness = this.deps.readiness();
    this.deps.send("DEVICE_READY", deviceReadyPayload(readiness.mic, readiness.player, prefetch), sessionId);
    if (readiness.mic === "OK") {
      this.setInputOpen(true);
    }
  }

  private closeTransport(sessionId: string): void {
    // 입력 송신을 먼저 멈추고(마이크 닫힘 시각), 재생 · 대기열을 정리한 뒤(출력 멈춤 시각) 확인을 보낸다.
    if (this.isCurrent(sessionId)) {
      this.setInputOpen(false);
    }
    const micClosedAt = toIsoInstant(this.deps.nowMs());
    this.deps.player.stopAll("CLOSE_TRANSPORT");
    const outputStoppedAt = toIsoInstant(this.deps.nowMs());
    if (this.isCurrent(sessionId)) {
      this.current = null;
    }
    this.deps.send("VOICE_SESSION_CLOSED", voiceSessionClosedPayload(outputStoppedAt, micClosedAt), sessionId);
  }
}
