// 기기 WSS 텍스트 프레임 = JSON 제어 봉투 (U1 3.5 Step 37 · 계약 4.2 · J27 · contracts/schemas/ws-envelope.schema.json)
// {type, seq, sentAt, opId?, sessionId?, payload}. seq는 연결마다 · 방향마다 1부터 1씩 는다. opId는 확인이 필요한 서버 명령
// 4종에만 붙고 기기는 ACK.ackOf로 돌려준다. sentAt은 보낸 쪽 시계 값이고 기기는 HEARTBEAT 송신 직전에 채운다(OD-8 · R3-4).
// 받는 쪽은 같은 seq를 한 번만 처리하고 모르는 type · 필드는 무시한다(계약 10절 '더하기만 안전').

export const DEVICE_TO_SERVER_TYPES = [
  "HELLO",
  "HEARTBEAT",
  "ACK",
  "DEVICE_READY",
  "PLAYBACK_MARK",
  "VOICE_SESSION_CLOSED",
  "DEVICE_STATE",
] as const;

export const SERVER_TO_DEVICE_TYPES = [
  "HELLO_OK",
  "HEARTBEAT_ACK",
  "START_SESSION",
  "SESSION_STATE",
  "OUTPUT_BEGIN",
  "OUTPUT_END",
  "PLAY_APPROVED_CONTENT",
  "STOP_OUTPUT",
  "CLOSE_TRANSPORT",
  "ERROR",
] as const;

export type DeviceToServerType = (typeof DEVICE_TO_SERVER_TYPES)[number];
export type ServerToDeviceType = (typeof SERVER_TO_DEVICE_TYPES)[number];

/** opId가 붙어 오는 서버 명령(기기는 받자마자 ACK한다). */
export const OP_ID_COMMANDS: readonly ServerToDeviceType[] = [
  "START_SESSION",
  "PLAY_APPROVED_CONTENT",
  "STOP_OUTPUT",
  "CLOSE_TRANSPORT",
];

/** 봉투 seq 상한(uint32). */
export const MAX_ENVELOPE_SEQ = 0xffff_ffff;

export const UUID_PATTERN = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

export function isUuid(value: unknown): value is string {
  return typeof value === "string" && UUID_PATTERN.test(value);
}

/** 보내는 봉투. */
export interface OutboundEnvelope<P extends object = object> {
  readonly type: DeviceToServerType;
  readonly seq: number;
  readonly sentAt: string;
  readonly sessionId?: string;
  readonly payload: P;
}

/** 받은 봉투(형식만 확인한 상태, payload는 messages.ts가 type별로 읽는다). */
export interface InboundEnvelope {
  readonly type: string;
  readonly seq: number;
  readonly sentAt: string;
  readonly opId: string | null;
  readonly sessionId: string | null;
  readonly payload: Readonly<Record<string, unknown>>;
}

/** 방향별 보내는 seq 계수기(1부터). uint32를 넘으면 1로 돌아간다. */
export function createSeqCounter(): () => number {
  let last = 0;
  return () => {
    last = last >= MAX_ENVELOPE_SEQ ? 1 : last + 1;
    return last;
  };
}

/** 시각을 계약 형식(ISO 8601 UTC, 밀리초, 끝 Z)으로 쓴다. */
export function toIsoInstant(epochMs: number): string {
  return new Date(epochMs).toISOString();
}

export function buildEnvelope<P extends object>(
  type: DeviceToServerType,
  seq: number,
  sentAtMs: number,
  payload: P,
  sessionId?: string,
): OutboundEnvelope<P> {
  const base = { type, seq, sentAt: toIsoInstant(sentAtMs), payload };
  return sessionId === undefined ? base : { ...base, sessionId };
}

export function serializeEnvelope(envelope: OutboundEnvelope): string {
  return JSON.stringify(envelope);
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/**
 * 텍스트 프레임을 봉투로 읽는다. JSON이 아니거나 필수 필드 형식이 틀리면 null(받는 쪽은 버린다).
 * 모르는 필드는 무시한다.
 */
export function parseInboundEnvelope(text: string): InboundEnvelope | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return null;
  }
  if (!isPlainObject(parsed)) {
    return null;
  }
  const { type, seq, sentAt, opId, sessionId, payload } = parsed;
  if (typeof type !== "string" || type.length === 0) {
    return null;
  }
  if (typeof seq !== "number" || !Number.isInteger(seq) || seq < 1 || seq > MAX_ENVELOPE_SEQ) {
    return null;
  }
  if (typeof sentAt !== "string" || !isPlainObject(payload)) {
    return null;
  }
  if (opId !== undefined && opId !== null && !isUuid(opId)) {
    return null;
  }
  if (sessionId !== undefined && sessionId !== null && !isUuid(sessionId)) {
    return null;
  }
  return {
    type,
    seq,
    sentAt,
    opId: typeof opId === "string" ? opId : null,
    sessionId: typeof sessionId === "string" ? sessionId : null,
    payload,
  };
}

/**
 * 받은 seq 중복 거르개. 처음 보는 seq면 true. 최근 capacity개만 기억한다(연결마다 새로 만든다).
 */
export function createInboundDeduper(capacity = 256): (seq: number) => boolean {
  const seen = new Set<number>();
  const order: number[] = [];
  return (seq: number) => {
    if (seen.has(seq)) {
      return false;
    }
    seen.add(seq);
    order.push(seq);
    if (order.length > capacity) {
      const oldest = order.shift();
      if (oldest !== undefined) {
        seen.delete(oldest);
      }
    }
    return true;
  };
}
