// 기기 WSS 텍스트 프레임 = JSON 제어 봉투 (U1 3.5 Step 45 · 계약 4.2 · J27 · J123 · J143)
// 정본: contracts/schemas/ws-envelope.schema.json · ws-messages.schema.json · contracts/device-ws.asyncapi.yaml
//
// {type, seq, sentAt, opId?, sessionId?, payload}. seq는 연결마다 · 방향마다 1부터 1씩 는다. opId는 확인이 필요한 서버 명령
// 4종에만 붙고 기기는 ACK.ackOf로 돌려준다. sentAt은 보낸 쪽 시계 값이고 기기(시뮬레이터)는 HEARTBEAT 송신 직전에 채운다
// (OD-8 · R3-4 · J143). 이 모듈은 기기 쪽(보내는 봉투 만들기)과 서버 쪽 봉투 확인(시뮬레이터가 받은 봉투가 계약 형식인지)을 맡는다.
// JSON Schema 검증기를 쓰지 않고 스키마의 required · enum · additionalProperties를 손으로 옮겼다(tools 의존성을 늘리지 않음).

export const DEVICE_TO_SERVER_TYPES = Object.freeze([
  "HELLO",
  "HEARTBEAT",
  "ACK",
  "DEVICE_READY",
  "PLAYBACK_MARK",
  "VOICE_SESSION_CLOSED",
  "DEVICE_STATE",
]);

export const SERVER_TO_DEVICE_TYPES = Object.freeze([
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
]);

/** opId가 필수인 서버 명령(기기는 받자마자 ACK). */
export const OP_ID_COMMANDS = Object.freeze([
  "START_SESSION",
  "PLAY_APPROVED_CONTENT",
  "STOP_OUTPUT",
  "CLOSE_TRANSPORT",
]);
/** 봉투 sessionId가 필수인 type(보낸 쪽 기준, ws-envelope.schema.json의 if-then). */
export const SESSION_ID_REQUIRED = Object.freeze([
  "START_SESSION",
  "OUTPUT_BEGIN",
  "PLAY_APPROVED_CONTENT",
  "STOP_OUTPUT",
  "CLOSE_TRANSPORT",
  "DEVICE_READY",
  "PLAYBACK_MARK",
  "VOICE_SESSION_CLOSED",
]);

export const VIEWS = Object.freeze(["STANDBY", "LISTENING", "THINKING", "CLOSING"]);
export const STOP_OUTPUT_REASONS = Object.freeze([
  "GATE_CANCEL",
  "GUARDIAN_STOP",
  "CHILD_STOP",
  "SILENCE",
  "BEFORE_CLOSING",
  "SERVER_BARGE_IN",
  "TECHNICAL",
]);
export const CLOSE_TRANSPORT_REASONS = Object.freeze([
  "ENDED",
  "GUARDIAN_STOP",
  "CHILD_STOP",
  "SILENCE",
  "DISCONNECT",
  "CONSENT_WITHDRAWN",
  "DECLINED",
  "FAILED",
]);
export const PLAYBACK_STATUSES = Object.freeze([
  "STARTED",
  "PROGRESS",
  "COMPLETED",
  "STOPPED",
  "FAILED",
]);
export const STOP_CAUSES = Object.freeze([
  "LOCAL_VOICE_ACTIVITY",
  "STOP_OUTPUT",
  "CLOSE_TRANSPORT",
  "DISCONNECTED",
]);
export const FAILURE_REASONS = Object.freeze([
  "DECODE_ERROR",
  "AUDIO_DEVICE_ERROR",
  "QUEUE_OVERFLOW",
  "FETCH_FAILED",
]);

/** WSS 닫기 코드(device-ws.asyncapi.yaml x-close-codes). */
export const CLOSE_CODES = Object.freeze({
  DEVICE_OFF: 1000,
  GOING_AWAY: 1001,
  ABNORMAL_CLOSURE: 1006,
  SERVER_ERROR: 1011,
  TRY_AGAIN_LATER: 1013,
  REPLACED: 4001,
  PROTOCOL_ERROR: 4400,
  UNAUTHORIZED: 4401,
  HELLO_TIMEOUT: 4408,
});

export const MAX_ENVELOPE_SEQ = 0xffff_ffff;
export const UUID_PATTERN =
  /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;
/** ISO 8601 UTC(끝 Z), 소수 초 1 ~ 9자리(common.schema.json DateTime). */
export const DATE_TIME_PATTERN =
  /^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,9})?Z$/;
const CONTENT_CODE_PATTERN = /^[A-Z][A-Z0-9_]*$/;
const APPROVED_AUDIO_URL_PATTERN = /^\/media\/approved\/[A-Za-z0-9._-]+\.wav$/;
const ENVELOPE_KEYS = new Set(["type", "seq", "sentAt", "opId", "sessionId", "payload"]);

export function isUuid(value) {
  return typeof value === "string" && UUID_PATTERN.test(value);
}

export function isDateTime(value) {
  return typeof value === "string" && DATE_TIME_PATTERN.test(value);
}

/** 시각을 계약 형식(ISO 8601 UTC, 밀리초, 끝 Z)으로. */
export function toIsoInstant(epochMs) {
  return new Date(epochMs).toISOString();
}

/** 방향별 보내는 seq 계수기(1부터, uint32를 넘으면 1로). */
export function createSeqCounter() {
  let last = 0;
  return () => {
    last = last >= MAX_ENVELOPE_SEQ ? 1 : last + 1;
    return last;
  };
}

/**
 * 기기 → 서버 봉투를 만든다. sentAt은 호출한 순간 값이어야 하므로(J143) 호출자가 송신 직전에 부른다.
 * @param {string} type DEVICE_TO_SERVER_TYPES 중 하나
 * @param {object} payload type별 payload
 * @param {{seq: number, sentAtMs: number, sessionId?: string|null}} meta
 */
export function buildDeviceEnvelope(type, payload, { seq, sentAtMs, sessionId = null }) {
  if (!DEVICE_TO_SERVER_TYPES.includes(type)) {
    throw new TypeError(`기기가 보낼 수 없는 type이다: ${type}`);
  }
  if (!Number.isInteger(seq) || seq < 1 || seq > MAX_ENVELOPE_SEQ) {
    throw new RangeError("봉투 seq는 1 ~ uint32여야 한다");
  }
  if (payload === null || typeof payload !== "object" || Array.isArray(payload)) {
    throw new TypeError("payload는 객체여야 한다");
  }
  if (SESSION_ID_REQUIRED.includes(type) && !isUuid(sessionId)) {
    throw new TypeError(`${type}에는 sessionId(UUID)가 필요하다`);
  }
  const envelope = { type, seq, sentAt: toIsoInstant(sentAtMs) };
  if (sessionId !== null && sessionId !== undefined) {
    envelope.sessionId = sessionId;
  }
  envelope.payload = payload;
  return envelope;
}

function problem(list, text) {
  list.push(text);
}

function checkKeys(problems, label, object, allowed) {
  for (const key of Object.keys(object)) {
    if (!allowed.includes(key)) {
      problem(problems, `${label}.${key}: 계약에 없는 필드`);
    }
  }
}

function requireField(problems, label, object, key, test, description) {
  if (!(key in object)) {
    problem(problems, `${label}.${key}: 없음`);
    return;
  }
  if (!test(object[key])) {
    problem(problems, `${label}.${key}: ${description}가 아님`);
  }
}

function optionalField(problems, label, object, key, test, description) {
  if (key in object && !test(object[key])) {
    problem(problems, `${label}.${key}: ${description}가 아님`);
  }
}

const isInt = (min) => (value) => Number.isInteger(value) && value >= min;
const isEnum = (values) => (value) => values.includes(value);
const isNullable = (test) => (value) => value === null || test(value);

/** 서버 → 기기 payload 확인(ws-messages.schema.json). 문제 목록(빈 배열이면 통과). */
export function checkServerPayload(type, payload) {
  const problems = [];
  const p = payload;
  const label = `${type}.payload`;
  switch (type) {
    case "HELLO_OK":
      checkKeys(problems, label, p, [
        "connectionId",
        "serverTime",
        "heartbeatIntervalMs",
        "audio",
        "session",
      ]);
      requireField(problems, label, p, "connectionId", isUuid, "UUID");
      requireField(problems, label, p, "serverTime", isDateTime, "DateTime");
      requireField(problems, label, p, "heartbeatIntervalMs", isInt(1), "1 이상 정수");
      requireField(problems, label, p, "audio", isEnum(["pcm16le/24000/1"]), "pcm16le/24000/1");
      optionalField(
        problems,
        label,
        p,
        "session",
        isNullable(
          (s) =>
            typeof s === "object" &&
            isUuid(s.sessionId) &&
            VIEWS.includes(s.view) &&
            typeof s.resumed === "boolean",
        ),
        "null 또는 {sessionId, view, resumed}",
      );
      break;
    case "HEARTBEAT_ACK":
      checkKeys(problems, label, p, ["hbSeq", "serverTime"]);
      requireField(problems, label, p, "hbSeq", isInt(0), "0 이상 정수");
      requireField(problems, label, p, "serverTime", isDateTime, "DateTime");
      break;
    case "START_SESSION":
      checkKeys(problems, label, p, ["approvedAudio"]);
      requireField(
        problems,
        label,
        p,
        "approvedAudio",
        (list) =>
          Array.isArray(list) &&
          list.every(
            (item) =>
              item !== null &&
              typeof item === "object" &&
              Object.keys(item).every((key) =>
                ["contentCode", "contentVersionId", "audioUrl"].includes(key),
              ) &&
              CONTENT_CODE_PATTERN.test(item.contentCode ?? "") &&
              isUuid(item.contentVersionId) &&
              APPROVED_AUDIO_URL_PATTERN.test(item.audioUrl ?? ""),
          ),
        "승인 음성 목록",
      );
      break;
    case "SESSION_STATE":
      checkKeys(problems, label, p, ["view"]);
      requireField(problems, label, p, "view", isEnum(VIEWS), "View");
      break;
    case "OUTPUT_BEGIN":
      checkKeys(problems, label, p, ["playbackId", "source"]);
      requireField(problems, label, p, "playbackId", isUuid, "UUID");
      requireField(
        problems,
        label,
        p,
        "source",
        isEnum(["GPT_LIVE", "SCENARIO_SCRIPT"]),
        "출력 출처",
      );
      break;
    case "OUTPUT_END":
      checkKeys(problems, label, p, ["playbackId", "totalMs"]);
      requireField(problems, label, p, "playbackId", isUuid, "UUID");
      optionalField(problems, label, p, "totalMs", isInt(0), "0 이상 정수");
      break;
    case "PLAY_APPROVED_CONTENT":
      checkKeys(problems, label, p, ["playbackId", "contentCode", "contentVersionId"]);
      requireField(problems, label, p, "playbackId", isUuid, "UUID");
      requireField(
        problems,
        label,
        p,
        "contentCode",
        (v) => typeof v === "string" && CONTENT_CODE_PATTERN.test(v),
        "contentCode",
      );
      requireField(problems, label, p, "contentVersionId", isUuid, "UUID");
      break;
    case "STOP_OUTPUT":
      checkKeys(problems, label, p, ["reason"]);
      requireField(problems, label, p, "reason", isEnum(STOP_OUTPUT_REASONS), "STOP_OUTPUT 사유");
      break;
    case "CLOSE_TRANSPORT":
      checkKeys(problems, label, p, ["reason"]);
      requireField(
        problems,
        label,
        p,
        "reason",
        isEnum(CLOSE_TRANSPORT_REASONS),
        "CLOSE_TRANSPORT 사유",
      );
      break;
    case "ERROR":
      checkKeys(problems, label, p, ["code", "refSeq"]);
      requireField(
        problems,
        label,
        p,
        "code",
        isEnum(["UNKNOWN_TYPE", "BAD_PAYLOAD"]),
        "ERROR 코드",
      );
      optionalField(problems, label, p, "refSeq", isNullable(isInt(1)), "null 또는 1 이상 정수");
      break;
    default:
      problem(problems, `${type}: 서버가 보낼 수 없는 type`);
  }
  return problems;
}

/**
 * 서버가 보낸 텍스트 프레임을 읽고 계약 형식인지 본다. 형식이 틀려도 읽을 수 있으면 envelope를 함께 돌려준다
 * (시뮬레이터는 기록하고 시나리오가 문제 0건을 단언한다).
 * @returns {{envelope: object|null, problems: string[]}}
 */
export function parseServerEnvelope(text) {
  let value;
  try {
    value = JSON.parse(typeof text === "string" ? text : Buffer.from(text).toString("utf8"));
  } catch {
    return { envelope: null, problems: ["JSON이 아니다"] };
  }
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    return { envelope: null, problems: ["봉투가 객체가 아니다"] };
  }
  const problems = [];
  for (const key of Object.keys(value)) {
    if (!ENVELOPE_KEYS.has(key)) {
      problem(problems, `envelope.${key}: 계약에 없는 필드`);
    }
  }
  if (!SERVER_TO_DEVICE_TYPES.includes(value.type)) {
    problem(problems, `type: 서버 → 기기 type이 아니다(${String(value.type).slice(0, 40)})`);
  }
  if (!Number.isInteger(value.seq) || value.seq < 1 || value.seq > MAX_ENVELOPE_SEQ) {
    problem(problems, "seq: 1 ~ uint32 정수가 아니다");
  }
  if (!isDateTime(value.sentAt)) {
    problem(problems, "sentAt: DateTime이 아니다");
  }
  if (value.payload === null || typeof value.payload !== "object" || Array.isArray(value.payload)) {
    problem(problems, "payload: 객체가 아니다");
  }
  const type = value.type;
  if (OP_ID_COMMANDS.includes(type)) {
    if (!isUuid(value.opId)) {
      problem(problems, `${type}: opId(UUID)가 없다`);
    }
  } else if ("opId" in value) {
    problem(problems, `${type}: opId가 붙으면 안 된다`);
  }
  if (SESSION_ID_REQUIRED.includes(type) && !isUuid(value.sessionId)) {
    problem(problems, `${type}: sessionId(UUID)가 없다`);
  } else if ("sessionId" in value && value.sessionId !== null && !isUuid(value.sessionId)) {
    problem(problems, `${type}: sessionId가 UUID · null이 아니다`);
  }
  if (
    SERVER_TO_DEVICE_TYPES.includes(type) &&
    value.payload &&
    typeof value.payload === "object" &&
    !Array.isArray(value.payload)
  ) {
    problems.push(...checkServerPayload(type, value.payload));
  }
  const envelope = {
    type: value.type,
    seq: value.seq,
    sentAt: value.sentAt,
    opId: value.opId ?? null,
    sessionId: value.sessionId ?? null,
    payload: value.payload ?? {},
  };
  return { envelope, problems };
}

// ---------------------------------------------------------------------------- 기기 → 서버 payload 만들기

export function helloPayload(appVersion = "u1-sim-1.0") {
  return { appVersion, audio: ["pcm16le/24000/1"], resume: null };
}

export function heartbeatPayload(hbSeq, lastRttMs) {
  return { hbSeq, lastRttMs: lastRttMs ?? null };
}

export function ackPayload(ackOf) {
  if (!isUuid(ackOf)) {
    throw new TypeError("ackOf는 UUID여야 한다");
  }
  return { ackOf };
}

export function deviceReadyPayload(mic = "OK", player = "OK", prefetch = "OK") {
  return { mic, player, prefetch };
}

export function playbackMarkPayload({
  playbackId,
  status,
  observedAtMs,
  stopCause = null,
  failureReason = null,
  playedRange = null,
}) {
  if (!PLAYBACK_STATUSES.includes(status)) {
    throw new TypeError(`PLAYBACK_MARK status가 틀렸다: ${status}`);
  }
  return {
    playbackId,
    status,
    playedRange,
    observedAt: toIsoInstant(observedAtMs),
    stopCause,
    failureReason,
  };
}

export function voiceSessionClosedPayload(outputStoppedAtMs, micClosedAtMs) {
  return {
    outputStoppedAt: toIsoInstant(outputStoppedAtMs),
    micClosedAt: toIsoInstant(micClosedAtMs),
  };
}
