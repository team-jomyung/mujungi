import { AUDIO_FORMAT } from "./frameCodec";
import { isUuid, type InboundEnvelope } from "./wsEnvelope";

// 기기 WSS 제어 메시지 payload 17종 (U1 3.5 Step 37 · 계약 4.2 · contracts/schemas/ws-messages.schema.json)
// - 기기 → 서버: 보내는 쪽 형식을 지키는 만들기 함수(스키마의 additionalProperties: false와 같은 필드만).
// - 서버 → 기기: 받은 봉투를 type별로 읽는다. 모르는 type · 틀린 payload는 null(기기는 버리고 연결을 유지한다).
//   모르는 enum 값은 'UNKNOWN'으로 읽어 오류로 끊지 않는다(계약 10절).

// ───────────── 공통 값 ─────────────

export type SessionView = "STANDBY" | "LISTENING" | "THINKING" | "CLOSING";
export const SESSION_VIEWS: readonly SessionView[] = ["STANDBY", "LISTENING", "THINKING", "CLOSING"];

export type PlaybackStatus = "STARTED" | "PROGRESS" | "COMPLETED" | "STOPPED" | "FAILED";
export type PlaybackStopCause = "LOCAL_VOICE_ACTIVITY" | "STOP_OUTPUT" | "CLOSE_TRANSPORT" | "DISCONNECTED";
export type PlaybackFailureReason = "DECODE_ERROR" | "AUDIO_DEVICE_ERROR" | "QUEUE_OVERFLOW" | "FETCH_FAILED";
export type ReadinessFlag = "OK" | "UNAVAILABLE";
export type PrefetchFlag = "OK" | "PARTIAL";

/** 승인 음성 정적 경로(같은 origin 상대 경로, 개인정보 없음). 이 형식 밖의 주소는 받지 않는다(BR7.6). */
export const APPROVED_AUDIO_URL_PATTERN = /^\/media\/approved\/[A-Za-z0-9._-]+\.wav$/;
const CONTENT_CODE_PATTERN = /^[A-Z][A-Z0-9_]*$/;

// ───────────── 기기 → 서버 payload ─────────────

export interface HelloPayload {
  readonly appVersion: string;
  readonly audio: readonly string[];
  /** U3 이어가기: 끊기기 전 세션(U3-R16). 없으면 null. */
  readonly resume: { readonly sessionId: string } | null;
}

export interface HeartbeatPayload {
  readonly hbSeq: number;
  readonly lastRttMs: number | null;
}

export interface AckPayload {
  readonly ackOf: string;
}

export interface DeviceReadyPayload {
  readonly mic: ReadinessFlag;
  readonly player: ReadinessFlag;
  readonly prefetch: PrefetchFlag;
}

export interface PlaybackMarkPayload {
  readonly playbackId: string;
  readonly status: PlaybackStatus;
  readonly playedRange: { readonly fromMs: number; readonly toMs: number } | null;
  readonly observedAt: string;
  readonly stopCause: PlaybackStopCause | null;
  readonly failureReason: PlaybackFailureReason | null;
}

export interface VoiceSessionClosedPayload {
  readonly outputStoppedAt: string;
  readonly micClosedAt: string;
}

/** HELLO. 토큰 · 비밀을 넣지 않는다(J29 · BR3.6). U3: 끊기기 전 세션이 있으면 resume으로 싣는다(U3-R16). */
export function helloPayload(appVersion: string, resumeSessionId: string | null = null): HelloPayload {
  return { appVersion, audio: [AUDIO_FORMAT], resume: resumeSessionId === null ? null : { sessionId: resumeSessionId } };
}

// U3 DEVICE_STATE(U3-R17): 바뀐 필드만 싣는다(스키마 minProperties 1 · 값은 null이 아님)
export type MicState = "OK" | "LOST";
export type PlayerState = "OK" | "ERROR";
export type VisibilityState = "VISIBLE" | "HIDDEN";
export type WakeLockState = "HELD" | "RELEASED";

export interface DeviceStatePayload {
  readonly mic?: MicState;
  readonly player?: PlayerState;
  readonly visibility?: VisibilityState;
  readonly wakeLock?: WakeLockState;
}

/** 정해진 필드만 담은 DEVICE_STATE. 담을 것이 없으면 null(보내지 않는다). */
export function deviceStatePayload(fields: DeviceStatePayload): DeviceStatePayload | null {
  const payload: { mic?: MicState; player?: PlayerState; visibility?: VisibilityState; wakeLock?: WakeLockState } = {};
  if (fields.mic !== undefined) {
    payload.mic = fields.mic;
  }
  if (fields.player !== undefined) {
    payload.player = fields.player;
  }
  if (fields.visibility !== undefined) {
    payload.visibility = fields.visibility;
  }
  if (fields.wakeLock !== undefined) {
    payload.wakeLock = fields.wakeLock;
  }
  return Object.keys(payload).length === 0 ? null : payload;
}

export function heartbeatPayload(hbSeq: number, lastRttMs: number | null): HeartbeatPayload {
  return { hbSeq, lastRttMs };
}

export function ackPayload(opId: string): AckPayload {
  return { ackOf: opId };
}

export function deviceReadyPayload(mic: ReadinessFlag, player: ReadinessFlag, prefetch: PrefetchFlag): DeviceReadyPayload {
  return { mic, player, prefetch };
}

export function voiceSessionClosedPayload(outputStoppedAt: string, micClosedAt: string): VoiceSessionClosedPayload {
  return { outputStoppedAt, micClosedAt };
}

// ───────────── 서버 → 기기 메시지 ─────────────

export interface ApprovedAudio {
  readonly contentCode: string;
  readonly contentVersionId: string;
  readonly audioUrl: string;
}

/** U3 이어가기 결과(HELLO_OK.session): 진행 중 세션과 표시 상태, 세션을 그대로 이었는지(U3-J4 · J5). */
export interface HelloOkSession {
  readonly sessionId: string;
  readonly view: SessionView | "UNKNOWN";
  readonly resumed: boolean;
}

export interface HelloOkMessage {
  readonly type: "HELLO_OK";
  readonly connectionId: string;
  readonly serverTime: string;
  readonly heartbeatIntervalMs: number;
  /** 진행 중 세션이 있을 때만 있다(없으면 속성 자체가 없다). */
  readonly session?: HelloOkSession;
}

export interface HeartbeatAckMessage {
  readonly type: "HEARTBEAT_ACK";
  readonly hbSeq: number;
  readonly serverTime: string;
}

export interface StartSessionMessage {
  readonly type: "START_SESSION";
  readonly opId: string;
  readonly sessionId: string;
  readonly approvedAudio: readonly ApprovedAudio[];
}

export interface SessionStateMessage {
  readonly type: "SESSION_STATE";
  readonly sessionId: string | null;
  readonly view: SessionView | "UNKNOWN";
}

export interface OutputBeginMessage {
  readonly type: "OUTPUT_BEGIN";
  readonly sessionId: string;
  readonly playbackId: string;
  /** 게이트를 통과한 이 말의 확정 글(기기 자막, 선택). 없거나 형식이 틀리면 null이고 자막 없이 재생한다. */
  readonly text?: string | null;
  /** false면 아이 소리로 이 묶음 재생을 끊지 않는다(연습 첫 대본, FT-1030-b). 필드가 없거나 false가 아니면 true. */
  readonly interruptible?: boolean;
}

/** OUTPUT_BEGIN.text 최대 글자 수(ws-messages.schema.json). */
export const OUTPUT_TEXT_MAX_CHARS = 500;

function readSubtitle(value: unknown): string | null {
  if (typeof value !== "string") {
    return null;
  }
  const text = value.trim();
  return text.length > 0 && [...text].length <= OUTPUT_TEXT_MAX_CHARS ? text : null;
}

export interface OutputEndMessage {
  readonly type: "OUTPUT_END";
  readonly sessionId: string | null;
  readonly playbackId: string;
}

export interface PlayApprovedContentMessage {
  readonly type: "PLAY_APPROVED_CONTENT";
  readonly opId: string;
  readonly sessionId: string;
  readonly playbackId: string;
  readonly contentCode: string;
  readonly contentVersionId: string;
}

export interface StopOutputMessage {
  readonly type: "STOP_OUTPUT";
  readonly opId: string;
  readonly sessionId: string;
  readonly reason: string;
}

export interface CloseTransportMessage {
  readonly type: "CLOSE_TRANSPORT";
  readonly opId: string;
  readonly sessionId: string;
  readonly reason: string;
}

export interface ErrorMessage {
  readonly type: "ERROR";
  readonly code: string;
  readonly refSeq: number | null;
}

export type ServerMessage =
  | HelloOkMessage
  | HeartbeatAckMessage
  | StartSessionMessage
  | SessionStateMessage
  | OutputBeginMessage
  | OutputEndMessage
  | PlayApprovedContentMessage
  | StopOutputMessage
  | CloseTransportMessage
  | ErrorMessage;

function isPositiveInteger(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value) && value >= 1;
}

function isNonNegativeInteger(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value) && value >= 0;
}

function readView(value: unknown): SessionView | "UNKNOWN" {
  return SESSION_VIEWS.find((view) => view === value) ?? "UNKNOWN";
}

function readApprovedAudio(value: unknown): ApprovedAudio[] | null {
  if (!Array.isArray(value)) {
    return null;
  }
  const items: ApprovedAudio[] = [];
  for (const entry of value) {
    if (typeof entry !== "object" || entry === null) {
      continue;
    }
    const { contentCode, contentVersionId, audioUrl } = entry as Record<string, unknown>;
    // 형식이 틀린 항목은 재생 대상에서 뺀다(승인된 소리만, BR7.6).
    if (
      typeof contentCode === "string" &&
      CONTENT_CODE_PATTERN.test(contentCode) &&
      isUuid(contentVersionId) &&
      typeof audioUrl === "string" &&
      APPROVED_AUDIO_URL_PATTERN.test(audioUrl)
    ) {
      items.push({ contentCode, contentVersionId, audioUrl });
    }
  }
  return items;
}

/** HELLO_OK.session(U3). 형식이 틀리면 없는 것으로 본다(세션을 이어 가지 않는다). */
function readHelloOkSession(value: unknown): HelloOkSession | null {
  if (typeof value !== "object" || value === null) {
    return null;
  }
  const { sessionId, view, resumed } = value as Record<string, unknown>;
  if (!isUuid(sessionId) || typeof resumed !== "boolean") {
    return null;
  }
  return { sessionId, view: readView(view), resumed };
}

/** 서버 → 기기 봉투를 type별 메시지로 읽는다. 모르는 type · 틀린 payload는 null. */
export function parseServerMessage(envelope: InboundEnvelope): ServerMessage | null {
  const { payload, opId, sessionId } = envelope;
  switch (envelope.type) {
    case "HELLO_OK": {
      const { connectionId, serverTime, heartbeatIntervalMs } = payload;
      if (!isUuid(connectionId) || typeof serverTime !== "string" || !isPositiveInteger(heartbeatIntervalMs)) {
        return null;
      }
      const session = readHelloOkSession(payload.session);
      return session === null
        ? { type: "HELLO_OK", connectionId, serverTime, heartbeatIntervalMs }
        : { type: "HELLO_OK", connectionId, serverTime, heartbeatIntervalMs, session };
    }
    case "HEARTBEAT_ACK": {
      const { hbSeq, serverTime } = payload;
      if (!isNonNegativeInteger(hbSeq) || typeof serverTime !== "string") {
        return null;
      }
      return { type: "HEARTBEAT_ACK", hbSeq, serverTime };
    }
    case "START_SESSION": {
      const approvedAudio = readApprovedAudio(payload.approvedAudio);
      if (opId === null || sessionId === null || approvedAudio === null) {
        return null;
      }
      return { type: "START_SESSION", opId, sessionId, approvedAudio };
    }
    case "SESSION_STATE":
      return { type: "SESSION_STATE", sessionId, view: readView(payload.view) };
    case "OUTPUT_BEGIN": {
      const { playbackId } = payload;
      if (sessionId === null || !isUuid(playbackId)) {
        return null;
      }
      return {
        type: "OUTPUT_BEGIN",
        sessionId,
        playbackId,
        text: readSubtitle(payload.text),
        interruptible: payload.interruptible !== false,
      };
    }
    case "OUTPUT_END": {
      const { playbackId } = payload;
      if (!isUuid(playbackId)) {
        return null;
      }
      return { type: "OUTPUT_END", sessionId, playbackId };
    }
    case "PLAY_APPROVED_CONTENT": {
      const { playbackId, contentCode, contentVersionId } = payload;
      if (
        opId === null ||
        sessionId === null ||
        !isUuid(playbackId) ||
        typeof contentCode !== "string" ||
        !isUuid(contentVersionId)
      ) {
        return null;
      }
      return { type: "PLAY_APPROVED_CONTENT", opId, sessionId, playbackId, contentCode, contentVersionId };
    }
    case "STOP_OUTPUT":
    case "CLOSE_TRANSPORT": {
      const reason = typeof payload.reason === "string" ? payload.reason : "UNKNOWN";
      if (opId === null || sessionId === null) {
        return null;
      }
      return envelope.type === "STOP_OUTPUT"
        ? { type: "STOP_OUTPUT", opId, sessionId, reason }
        : { type: "CLOSE_TRANSPORT", opId, sessionId, reason };
    }
    case "ERROR": {
      const code = typeof payload.code === "string" ? payload.code : "UNKNOWN";
      const refSeq = isPositiveInteger(payload.refSeq) ? payload.refSeq : null;
      return { type: "ERROR", code, refSeq };
    }
    default:
      return null;
  }
}
