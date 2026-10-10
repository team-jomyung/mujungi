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
  readonly resume: null;
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

/** HELLO. 토큰 · 비밀을 넣지 않는다(J29 · BR3.6). resume은 U3가 완성한다. */
export function helloPayload(appVersion: string): HelloPayload {
  return { appVersion, audio: [AUDIO_FORMAT], resume: null };
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

export interface HelloOkMessage {
  readonly type: "HELLO_OK";
  readonly connectionId: string;
  readonly serverTime: string;
  readonly heartbeatIntervalMs: number;
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

/** 서버 → 기기 봉투를 type별 메시지로 읽는다. 모르는 type · 틀린 payload는 null. */
export function parseServerMessage(envelope: InboundEnvelope): ServerMessage | null {
  const { payload, opId, sessionId } = envelope;
  switch (envelope.type) {
    case "HELLO_OK": {
      const { connectionId, serverTime, heartbeatIntervalMs } = payload;
      if (!isUuid(connectionId) || typeof serverTime !== "string" || !isPositiveInteger(heartbeatIntervalMs)) {
        return null;
      }
      return { type: "HELLO_OK", connectionId, serverTime, heartbeatIntervalMs };
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
      return { type: "OUTPUT_BEGIN", sessionId, playbackId };
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
