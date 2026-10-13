import { describe, expect, it } from "vitest";
import { deviceStatePayload, helloPayload, parseServerMessage } from "@/shared/protocol/messages";
import type { InboundEnvelope } from "@/shared/protocol/wsEnvelope";
import { readTimingFromEnv } from "@/shared/timing/readTimingFromEnv";
import { TimingConfigError, defaultTiming, parseDurationList, resolveTiming } from "@/shared/timing/timing";
import { CONNECTION_ID, SESSION_ID } from "../../u1/support/fixtures";

// U3 프로토콜 · 시간 키 시험 (U3-R16 · U3-R17 · U3-J10 · U3-J20 · contracts/timing.md 1절 · ws-messages 스키마)

function envelope(type: string, payload: Record<string, unknown>): InboundEnvelope {
  return { type, seq: 1, sentAt: "2026-10-15T01:00:00.000Z", opId: null, sessionId: null, payload };
}

describe("U3 기기 → 서버 payload", () => {
  it("HELLO는 끊기기 전 세션을 resume으로 싣고, 없으면 null이다(U3-R16)", () => {
    expect(helloPayload("app", SESSION_ID)).toEqual({
      appVersion: "app",
      audio: ["pcm16le/24000/1"],
      resume: { sessionId: SESSION_ID },
    });
    expect(helloPayload("app", null).resume).toBeNull();
    expect(helloPayload("app").resume).toBeNull();
  });

  it("DEVICE_STATE는 정한 필드만 싣고 담을 것이 없으면 보내지 않는다(스키마 minProperties 1)", () => {
    expect(deviceStatePayload({ mic: "LOST" })).toEqual({ mic: "LOST" });
    expect(deviceStatePayload({ player: "ERROR", visibility: "HIDDEN", wakeLock: "RELEASED" })).toEqual({
      player: "ERROR",
      visibility: "HIDDEN",
      wakeLock: "RELEASED",
    });
    expect(Object.keys(deviceStatePayload({ mic: "OK", player: "OK", visibility: "VISIBLE", wakeLock: "HELD" }) ?? {})).toEqual([
      "mic",
      "player",
      "visibility",
      "wakeLock",
    ]);
    expect(deviceStatePayload({})).toBeNull();
  });
});

describe("U3 HELLO_OK.session(이어가기 결과)", () => {
  const base = { connectionId: CONNECTION_ID, serverTime: "t", heartbeatIntervalMs: 5000, audio: "pcm16le/24000/1" };

  it("세션 id · 표시 상태 · 그대로 이었는지를 읽는다", () => {
    expect(
      parseServerMessage(envelope("HELLO_OK", { ...base, session: { sessionId: SESSION_ID, view: "LISTENING", resumed: true } })),
    ).toEqual({
      type: "HELLO_OK",
      connectionId: CONNECTION_ID,
      serverTime: "t",
      heartbeatIntervalMs: 5000,
      session: { sessionId: SESSION_ID, view: "LISTENING", resumed: true },
    });
  });

  it("모르는 view는 UNKNOWN이고, 틀린 session · null은 세션이 없는 것으로 본다", () => {
    const unknownView = parseServerMessage(
      envelope("HELLO_OK", { ...base, session: { sessionId: SESSION_ID, view: "DANCING", resumed: false } }),
    );
    expect(unknownView?.type === "HELLO_OK" ? unknownView.session : undefined).toEqual({
      sessionId: SESSION_ID,
      view: "UNKNOWN",
      resumed: false,
    });
    for (const session of [null, "x", { sessionId: "nope", view: "LISTENING", resumed: true }, { sessionId: SESSION_ID, view: "LISTENING" }]) {
      const parsed = parseServerMessage(envelope("HELLO_OK", { ...base, session }));
      expect(parsed?.type).toBe("HELLO_OK");
      expect(parsed !== null && "session" in parsed).toBe(false);
    }
  });
});

describe("U3 시간 키(contracts/timing.md 1절)", () => {
  it("기본값은 재접속 1 · 2 · 4초, PROGRESS 1초, 끼어들기 500ms · 최소 음성 200ms다", () => {
    const timing = defaultTiming();
    expect(timing.deviceReconnectBackoffMs).toEqual([1_000, 2_000, 4_000]);
    expect(timing.devicePlaybackMarkIntervalMs).toBe(1_000);
    expect(timing.gateBargeInStopMs).toBe(500);
    expect(timing.gateLocalVoiceMinMs).toBe(200);
  });

  it("시간 목록은 계약 표기 [1s, 2s, 4s]와 Spring 목록 표기 1s,2s,4s를 읽고 틀리면 null이다", () => {
    expect(parseDurationList("[1s, 2s, 4s]")).toEqual([1_000, 2_000, 4_000]);
    expect(parseDurationList("500ms,1s")).toEqual([500, 1_000]);
    expect(parseDurationList(" [ 3s ] ")).toEqual([3_000]);
    for (const wrong of ["", "[]", "[1s,,2s]", "1s, x", "[0s]", "1s;2s"]) {
      expect(parseDurationList(wrong), wrong).toBeNull();
    }
  });

  it("환경 변수로 목록 · 끼어들기 값을 덮어쓰고, 틀린 목록은 멈춘다(fail fast)", () => {
    const timing = readTimingFromEnv({
      MUJOONG_TIMING_DEVICE_RECONNECTBACKOFF: "500ms,1s",
      MUJOONG_TIMING_GATE_BARGEINSTOP: "800ms",
      MUJOONG_TIMING_GATE_LOCALVOICEMIN: "100ms",
      MUJOONG_TIMING_DEVICE_PLAYBACKMARKINTERVAL: "2s",
    });
    expect(timing.deviceReconnectBackoffMs).toEqual([500, 1_000]);
    expect(timing.gateBargeInStopMs).toBe(800);
    expect(timing.gateLocalVoiceMinMs).toBe(100);
    expect(timing.devicePlaybackMarkIntervalMs).toBe(2_000);
    expect(() => resolveTiming({ MUJOONG_TIMING_DEVICE_RECONNECTBACKOFF: "1s,,2s" })).toThrow(TimingConfigError);
  });
});
