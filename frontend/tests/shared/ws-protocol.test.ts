import { describe, expect, it } from "vitest";
import { CLOSE_CODES, actionForClose } from "@/shared/protocol/closeCodes";
import {
  ackPayload,
  deviceReadyPayload,
  heartbeatPayload,
  helloPayload,
  parseServerMessage,
  voiceSessionClosedPayload,
} from "@/shared/protocol/messages";
import {
  MAX_ENVELOPE_SEQ,
  buildEnvelope,
  createInboundDeduper,
  createSeqCounter,
  parseInboundEnvelope,
  serializeEnvelope,
  toIsoInstant,
  type InboundEnvelope,
} from "@/shared/protocol/wsEnvelope";
import { CONNECTION_ID, CONTENT_VERSION_ID, OP_ID, PLAYBACK_ID, SESSION_ID } from "../support/fixtures";

// 기기 WSS 제어 봉투 · 메시지 · 닫기 코드 시험 (U1 3.5 Step 41 · 계약 4.2 · 10절 · BR3.7 · BR9.7 · J29)

function envelope(type: string, payload: Record<string, unknown>, extra: Partial<InboundEnvelope> = {}): InboundEnvelope {
  return { type, seq: 1, sentAt: "2026-10-15T01:00:00.000Z", opId: null, sessionId: null, payload, ...extra };
}

describe("wsEnvelope — 봉투 쓰기 · 읽기", () => {
  it("보내는 seq는 방향마다 1부터 1씩 늘고 sentAt은 ISO 8601 UTC 밀리초다", () => {
    const next = createSeqCounter();
    expect([next(), next(), next()]).toEqual([1, 2, 3]);
    const built = buildEnvelope("HEARTBEAT", 4, Date.UTC(2026, 9, 15, 1, 0, 0, 7), heartbeatPayload(1, null));
    expect(built).toEqual({ type: "HEARTBEAT", seq: 4, sentAt: "2026-10-15T01:00:00.007Z", payload: { hbSeq: 1, lastRttMs: null } });
    expect("sessionId" in built).toBe(false);
    const withSession = buildEnvelope("DEVICE_READY", 5, 0, deviceReadyPayload("OK", "OK", "PARTIAL"), SESSION_ID);
    expect(JSON.parse(serializeEnvelope(withSession))).toMatchObject({ sessionId: SESSION_ID, payload: { prefetch: "PARTIAL" } });
    expect(toIsoInstant(0)).toBe("1970-01-01T00:00:00.000Z");
  });

  it("형식이 맞는 봉투를 읽고 모르는 필드는 무시한다", () => {
    const parsed = parseInboundEnvelope(
      JSON.stringify({ type: "STOP_OUTPUT", seq: 9, sentAt: "x", opId: OP_ID, sessionId: SESSION_ID, payload: { reason: "R" }, extra: 1 }),
    );
    expect(parsed).toEqual({ type: "STOP_OUTPUT", seq: 9, sentAt: "x", opId: OP_ID, sessionId: SESSION_ID, payload: { reason: "R" } });
    expect(parseInboundEnvelope(JSON.stringify({ type: "X", seq: 1, sentAt: "x", opId: null, payload: {} }))).toMatchObject({
      opId: null,
      sessionId: null,
    });
  });

  it.each([
    ["JSON 아님", "{"],
    ["배열", "[]"],
    ["type 없음", JSON.stringify({ seq: 1, sentAt: "x", payload: {} })],
    ["빈 type", JSON.stringify({ type: "", seq: 1, sentAt: "x", payload: {} })],
    ["seq 0", JSON.stringify({ type: "A", seq: 0, sentAt: "x", payload: {} })],
    ["seq 소수", JSON.stringify({ type: "A", seq: 1.5, sentAt: "x", payload: {} })],
    ["seq uint32 초과", JSON.stringify({ type: "A", seq: MAX_ENVELOPE_SEQ + 1, sentAt: "x", payload: {} })],
    ["sentAt 숫자", JSON.stringify({ type: "A", seq: 1, sentAt: 1, payload: {} })],
    ["payload 배열", JSON.stringify({ type: "A", seq: 1, sentAt: "x", payload: [] })],
    ["opId 형식", JSON.stringify({ type: "A", seq: 1, sentAt: "x", opId: "op-1", payload: {} })],
    ["sessionId 형식", JSON.stringify({ type: "A", seq: 1, sentAt: "x", sessionId: "s", payload: {} })],
  ])("틀린 봉투(%s)는 버린다", (_, text) => {
    expect(parseInboundEnvelope(text)).toBeNull();
  });

  it("같은 seq는 한 번만 처리하고 기억은 최근 capacity개까지다", () => {
    const isNew = createInboundDeduper(2);
    expect(isNew(1)).toBe(true);
    expect(isNew(1)).toBe(false);
    expect(isNew(2)).toBe(true);
    expect(isNew(3)).toBe(true);
    // 가장 오래된 1은 잊었다.
    expect(isNew(1)).toBe(true);
    expect(isNew(3)).toBe(false);
  });
});

describe("messages — 기기 → 서버 payload", () => {
  it("HELLO에는 앱 판 · 음성 형식 · resume만 있고 토큰 · 비밀이 없다(J29 · BR3.6)", () => {
    const hello = helloPayload("mujoong-device-web/u1");
    expect(Object.keys(hello).sort()).toEqual(["appVersion", "audio", "resume"]);
    expect(hello).toEqual({ appVersion: "mujoong-device-web/u1", audio: ["pcm16le/24000/1"], resume: null });
  });

  it("ACK · VOICE_SESSION_CLOSED는 스키마 필드만 담는다", () => {
    expect(ackPayload(OP_ID)).toEqual({ ackOf: OP_ID });
    expect(voiceSessionClosedPayload("a", "b")).toEqual({ outputStoppedAt: "a", micClosedAt: "b" });
  });
});

describe("messages — 서버 → 기기 읽기", () => {
  it("HELLO_OK · HEARTBEAT_ACK를 읽고 틀린 값은 버린다", () => {
    expect(
      parseServerMessage(envelope("HELLO_OK", { connectionId: CONNECTION_ID, serverTime: "t", heartbeatIntervalMs: 5000 })),
    ).toEqual({ type: "HELLO_OK", connectionId: CONNECTION_ID, serverTime: "t", heartbeatIntervalMs: 5000 });
    expect(parseServerMessage(envelope("HELLO_OK", { connectionId: "c", serverTime: "t", heartbeatIntervalMs: 5000 }))).toBeNull();
    expect(parseServerMessage(envelope("HELLO_OK", { connectionId: CONNECTION_ID, serverTime: "t", heartbeatIntervalMs: 0 }))).toBeNull();
    expect(parseServerMessage(envelope("HEARTBEAT_ACK", { hbSeq: 0, serverTime: "t" }))).toEqual({
      type: "HEARTBEAT_ACK",
      hbSeq: 0,
      serverTime: "t",
    });
    expect(parseServerMessage(envelope("HEARTBEAT_ACK", { hbSeq: -1, serverTime: "t" }))).toBeNull();
  });

  it("START_SESSION의 승인 음성은 같은 origin /media/approved/*.wav만 남긴다(BR7.6)", () => {
    const good = { contentCode: "CLOSING", contentVersionId: CONTENT_VERSION_ID, audioUrl: "/media/approved/closing-v1.wav" };
    const message = parseServerMessage(
      envelope(
        "START_SESSION",
        {
          approvedAudio: [
            good,
            { ...good, audioUrl: "https://evil.invalid/a.wav" },
            { ...good, audioUrl: "/media/approved/../x.wav" },
            { ...good, contentCode: "closing" },
            { ...good, contentVersionId: "v1" },
            null,
          ],
        },
        { opId: OP_ID, sessionId: SESSION_ID },
      ),
    );
    expect(message).toEqual({ type: "START_SESSION", opId: OP_ID, sessionId: SESSION_ID, approvedAudio: [good] });
    expect(parseServerMessage(envelope("START_SESSION", { approvedAudio: {} }, { opId: OP_ID, sessionId: SESSION_ID }))).toBeNull();
    expect(parseServerMessage(envelope("START_SESSION", { approvedAudio: [] }, { sessionId: SESSION_ID }))).toBeNull();
  });

  it("SESSION_STATE의 모르는 view는 UNKNOWN으로 읽는다(계약 10절)", () => {
    expect(parseServerMessage(envelope("SESSION_STATE", { view: "LISTENING" }, { sessionId: SESSION_ID }))).toEqual({
      type: "SESSION_STATE",
      sessionId: SESSION_ID,
      view: "LISTENING",
    });
    expect(parseServerMessage(envelope("SESSION_STATE", { view: "DANCING" }))).toEqual({
      type: "SESSION_STATE",
      sessionId: null,
      view: "UNKNOWN",
    });
  });

  it("OUTPUT_BEGIN · OUTPUT_END · PLAY_APPROVED_CONTENT는 playbackId UUID가 있어야 한다", () => {
    expect(parseServerMessage(envelope("OUTPUT_BEGIN", { playbackId: PLAYBACK_ID }, { sessionId: SESSION_ID }))).toEqual({
      type: "OUTPUT_BEGIN",
      sessionId: SESSION_ID,
      playbackId: PLAYBACK_ID,
    });
    expect(parseServerMessage(envelope("OUTPUT_BEGIN", { playbackId: PLAYBACK_ID }))).toBeNull();
    expect(parseServerMessage(envelope("OUTPUT_END", { playbackId: PLAYBACK_ID }))).toMatchObject({ type: "OUTPUT_END", sessionId: null });
    expect(parseServerMessage(envelope("OUTPUT_END", { playbackId: "p" }))).toBeNull();
    const play = { playbackId: PLAYBACK_ID, contentCode: "WAITING_FILLER", contentVersionId: CONTENT_VERSION_ID };
    expect(parseServerMessage(envelope("PLAY_APPROVED_CONTENT", play, { opId: OP_ID, sessionId: SESSION_ID }))).toEqual({
      type: "PLAY_APPROVED_CONTENT",
      opId: OP_ID,
      sessionId: SESSION_ID,
      ...play,
    });
    expect(parseServerMessage(envelope("PLAY_APPROVED_CONTENT", { ...play, contentVersionId: 1 }, { opId: OP_ID, sessionId: SESSION_ID }))).toBeNull();
  });

  it("STOP_OUTPUT · CLOSE_TRANSPORT · ERROR를 읽고 모르는 type은 null이다", () => {
    expect(parseServerMessage(envelope("STOP_OUTPUT", { reason: "GUARDIAN_STOP" }, { opId: OP_ID, sessionId: SESSION_ID }))).toEqual({
      type: "STOP_OUTPUT",
      opId: OP_ID,
      sessionId: SESSION_ID,
      reason: "GUARDIAN_STOP",
    });
    expect(parseServerMessage(envelope("CLOSE_TRANSPORT", {}, { opId: OP_ID, sessionId: SESSION_ID }))).toEqual({
      type: "CLOSE_TRANSPORT",
      opId: OP_ID,
      sessionId: SESSION_ID,
      reason: "UNKNOWN",
    });
    expect(parseServerMessage(envelope("CLOSE_TRANSPORT", {}, { sessionId: SESSION_ID }))).toBeNull();
    expect(parseServerMessage(envelope("ERROR", { code: "UNKNOWN_TYPE", refSeq: 3 }))).toEqual({ type: "ERROR", code: "UNKNOWN_TYPE", refSeq: 3 });
    expect(parseServerMessage(envelope("ERROR", { refSeq: 0 }))).toEqual({ type: "ERROR", code: "UNKNOWN", refSeq: null });
    expect(parseServerMessage(envelope("SOMETHING_NEW", {}))).toBeNull();
  });
});

describe("closeCodes — 닫기 코드별 기기 웹 동작(BR9.7 · BR3.7)", () => {
  it("기기가 1000으로 닫은 것과 4001(대체됨)은 꺼짐이다", () => {
    expect(actionForClose(CLOSE_CODES.DEVICE_OFF, true)).toBe("OFF");
    expect(actionForClose(CLOSE_CODES.REPLACED, false)).toBe("OFF");
  });

  it("4401은 /api/device/me 확인이다", () => {
    expect(actionForClose(CLOSE_CODES.UNAUTHORIZED, false)).toBe("RECHECK_ME");
  });

  it.each([1000, 1001, 1006, 1011, 1013, 4400, 4408, 4999])("서버 쪽 닫기 %i은 켜짐이면 재접속이다", (code) => {
    expect(actionForClose(code, false)).toBe("RECONNECT");
  });
});
