import { encodeInputFrame, nextFrameSeq, type DecodedFrame } from "@/shared/protocol/frameCodec";
import {
  deviceReadyPayload,
  voiceSessionClosedPayload,
  type ApprovedAudio,
  type HelloOkSession,
  type PlaybackMarkPayload,
  type PlaybackStopCause,
  type PrefetchFlag,
  type ReadinessFlag,
  type ServerMessage,
  type SessionView,
} from "@/shared/protocol/messages";
import { toIsoInstant, type DeviceToServerType } from "@/shared/protocol/wsEnvelope";
import type { InterruptedPlayback } from "../audio/player";

// 기기 쪽 세션 처리 (U1 3.5 Step 38 · 계약 4.2 · BR6.8 · BR6.10 · BR7.1 · BR7.6 · BR9.6 · 편차 (h))
//
// START_SESSION → (wsClient가 ACK) → 승인 음성 미리 받기 → DEVICE_READY{mic, player, prefetch}
//   → 입력 창 열림: 0x01 20ms 프레임을 DEVICE_READY 뒤 ~ CLOSE_TRANSPORT 전까지만 보낸다(서버는 ACTIVE 전 프레임을 버린다).
// SESSION_STATE → T19 표시(대기 · 듣는 중 · 생각 중 · 마무리). '말하는 중'은 재생기가 정한다. 지금 세션이 아닌 세션의 STANDBY는 무시한다.
// OUTPUT_BEGIN · 0x02 · OUTPUT_END · PLAY_APPROVED_CONTENT → 재생기(승인된 소리만, BR7.6).
// STOP_OUTPUT → 재생 멈춤(PLAYBACK_MARK STOPPED). CLOSE_TRANSPORT → 입력 창 닫음 · 재생 · 대기열 정리 → VOICE_SESSION_CLOSED.
// WSS는 대기용으로 남는다.
// U3 이어가기(U3-R16 · U3-J4 · J5 · J7): 소켓이 끊기면 입력 · 재생을 멈추되 세션 id는 기억해 다음 HELLO.resume에 싣는다.
//   HELLO_OK.session이 같은 세션이고 resumed면 그 세션으로 입력 창을 다시 열고(DEVICE_READY를 못 보냈으면 보낸다), resumed가 아니면
//   서버가 다시 보낼 START_SESSION을 기다리고, session이 없으면 세션을 잊는다. 재접속 뒤 같은 opId로 다시 온 명령은 ACK만 하고
//   (wsClient) 다시 하지 않는다.

/** 재생기 중 세션 처리가 쓰는 부분. */
export interface SessionPlayer {
  prefetch(items: readonly ApprovedAudio[]): Promise<PrefetchFlag>;
  beginStream(sessionId: string, playbackId: string, interruptible?: boolean): void;
  appendStream(payload: Uint8Array): void;
  endStream(playbackId: string): void;
  playApproved(sessionId: string, playbackId: string, contentCode: string, contentVersionId: string): Promise<void>;
  stopAll(cause: PlaybackStopCause): void;
  /** 끊김: 표식 없이 모두 멈춘다. 멈춘 재생을 돌려주면 이어간 뒤 STOPPED(DISCONNECTED)로 알린다(U3). */
  reset(): readonly InterruptedPlayback[] | void;
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
  /** 승인 음성 미리 받기를 마쳤는지. */
  prefetched: boolean;
  prefetch: PrefetchFlag;
  /** DEVICE_READY를 보냈는지. */
  readySent: boolean;
}

/** 이미 처리한 opId 기억 상한(재전송 무시용, U3-J7). */
const HANDLED_OP_ID_LIMIT = 64;

export class DeviceSession {
  private current: ActiveSession | null = null;
  /** 끊겨서 쉬는 세션(다음 HELLO_OK.session으로 잇거나 잊는다). */
  private suspended: ActiveSession | null = null;
  /** 끊김으로 멈춘 그 세션의 재생(이어가면 알린다). */
  private interrupted: readonly InterruptedPlayback[] = [];
  private readonly handledOpIds: string[] = [];
  /** OUTPUT_BEGIN.text를 재생 번호별로 둔다. 실제 소리가 나는 STARTED 때 꺼내 자막으로 띄운다(소리와 맞추기). */
  private readonly subtitles = new Map<string, string>();

  constructor(private readonly deps: DeviceSessionDependencies) {}

  /** 그 재생의 자막을 한 번 꺼낸다(없으면 null). 끝난 재생의 자막은 남기지 않는다. */
  takeSubtitle(playbackId: string): string | null {
    const text = this.subtitles.get(playbackId) ?? null;
    this.subtitles.delete(playbackId);
    return text;
  }

  get sessionId(): string | null {
    return this.current?.sessionId ?? null;
  }

  /** 다음 HELLO.resume에 실을 세션 id(끊긴 세션 또는 지금 세션). */
  get resumeSessionId(): string | null {
    return this.suspended?.sessionId ?? this.current?.sessionId ?? null;
  }

  /**
   * HELLO_OK를 받았다(U3-R16): 같은 세션을 그대로 이었으면 입력 창을 다시 열고 표시 상태를 맞춘다. 서버가 세션을 모르는 기기로 보거나(resumed
   * false) 진행 중 세션이 없으면 끊긴 세션을 잊는다(START_SESSION이 다시 오면 처음부터 한다).
   */
  handleReady(session: HelloOkSession | undefined): void {
    const waiting = this.suspended;
    this.suspended = null;
    if (waiting === null) {
      return;
    }
    const interrupted = this.interrupted;
    this.interrupted = [];
    if (session === undefined || !session.resumed || session.sessionId !== waiting.sessionId) {
      this.handledOpIds.length = 0;
      return;
    }
    this.current = waiting;
    if (session.view !== "UNKNOWN") {
      this.deps.onView(session.view);
    }
    // 끊김으로 멈춘 재생의 끝을 알린다(서버의 마무리 흐름 · 재생 추적이 기다리지 않게)
    for (const playback of interrupted) {
      this.sendPlaybackMark(playback.sessionId, {
        playbackId: playback.playbackId,
        status: "STOPPED",
        playedRange: playback.playedRange,
        observedAt: toIsoInstant(this.deps.nowMs()),
        stopCause: "DISCONNECTED",
        failureReason: null,
      });
    }
    if (waiting.prefetched) {
      this.sendReadyAndOpen(waiting);
    }
  }

  get isInputOpen(): boolean {
    return this.current?.inputOpen ?? false;
  }

  /** 재생기의 PLAYBACK_MARK를 서버로 보낸다. */
  sendPlaybackMark(sessionId: string, mark: PlaybackMarkPayload): void {
    if (!this.isCurrent(sessionId)) return;
    this.deps.send("PLAYBACK_MARK", mark, sessionId);
  }

  handleMessage(message: ServerMessage): void {
    if (message.type !== "START_SESSION" && "sessionId" in message && this.current !== null && message.sessionId !== this.current.sessionId) return;
    if ("opId" in message && !this.firstTime(`${message.type}:${message.sessionId}:${message.opId}`)) {
      // 재접속 뒤 같은 명령 · 같은 opId로 다시 온 것: wsClient가 ACK를 다시 보냈고 실행은 한 번뿐이다(U3-J7)
      return;
    }
    switch (message.type) {
      case "START_SESSION":
        void this.startSession(message.sessionId, message.approvedAudio);
        return;
      case "SESSION_STATE":
        if (this.suspended !== null || (this.current !== null && message.sessionId !== this.current.sessionId)) {
          // 3차 리뷰 4: 끝난 다른 세션을 대기로 돌려보내는 뒤늦은 명령이 지금 세션의 표시를 덮지 않는다
          return;
        }
        if (message.view !== "UNKNOWN") {
          this.deps.onView(message.view);
        }
        return;
      case "OUTPUT_BEGIN":
        if (this.isCurrent(message.sessionId)) {
          if (typeof message.text === "string") this.subtitles.set(message.playbackId, message.text);
          this.deps.player.beginStream(message.sessionId, message.playbackId, message.interruptible !== false);
        }
        return;
      case "OUTPUT_END":
        if (this.isCurrent(message.sessionId)) this.deps.player.endStream(message.playbackId);
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
        if (this.isCurrent(message.sessionId)) this.deps.player.stopAll("STOP_OUTPUT");
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

  /** 소켓이 끊겼을 때: 입력 · 재생을 멈추고 세션은 이어가기용으로 기억한다(U3-R16). */
  handleDisconnect(): void {
    this.setInputOpen(false);
    const active = this.current;
    if (active !== null) {
      this.suspended = active;
    }
    this.current = null;
    const stopped = this.deps.player.reset() ?? [];
    if (active !== null) {
      this.interrupted = [...this.interrupted, ...stopped.filter((playback) => playback.sessionId === active.sessionId)];
    }
  }

  /** 「끄기」 · 꺼짐: 세션을 기억하지 않고 모두 잊는다. */
  forget(): void {
    this.handleDisconnect();
    this.suspended = null;
    this.interrupted = [];
    this.handledOpIds.length = 0;
  }

  private firstTime(key: string): boolean {
    if (this.handledOpIds.includes(key)) {
      return false;
    }
    this.handledOpIds.push(key);
    if (this.handledOpIds.length > HANDLED_OP_ID_LIMIT) {
      this.handledOpIds.shift();
    }
    return true;
  }

  private isCurrent(sessionId: string | null): boolean {
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
    const session: ActiveSession = {
      sessionId,
      inputOpen: false,
      frameSeq: 0,
      prefetched: false,
      prefetch: "OK",
      readySent: false,
    };
    this.current = session;
    this.suspended = null;
    const prefetch = await this.deps.player.prefetch(approvedAudio);
    session.prefetched = true;
    session.prefetch = prefetch;
    if (this.current !== session) {
      // 미리 받는 사이 끊겼다 — 이어 가면 handleReady가 DEVICE_READY를 보낸다
      return;
    }
    this.sendReadyAndOpen(session);
  }

  /** DEVICE_READY(아직 안 보냈으면)와 입력 창 열기. 이어가기 뒤에도 같은 길을 쓴다. */
  private sendReadyAndOpen(session: ActiveSession): void {
    const readiness = this.deps.readiness();
    if (!session.readySent) {
      session.readySent = true;
      this.deps.send("DEVICE_READY", deviceReadyPayload(readiness.mic, readiness.player, session.prefetch), session.sessionId);
    }
    if (readiness.mic === "OK") {
      this.setInputOpen(true);
    }
  }

  private closeTransport(sessionId: string): void {
    if (this.current !== null && !this.isCurrent(sessionId)) return;
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
