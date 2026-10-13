// 가짜 공급자 시나리오 (U1 3.5 Step 45 · J110 · J131 · RD-5 · RD-23 · NFR4.2 · SD-17 · contracts/ai-io.md 3절)
//
// 연결 시나리오(연결마다 하나): ok · no-started · delayed · refuse · silent
//   ok         session.start에 곧바로 session.started
//   no-started session.started를 보내지 않는다(편차 (h): 20초 뒤 FAILED(PROVIDER_UNAVAILABLE))
//   delayed    startedDelayMs 뒤에 session.started(지연)
//   refuse     WS 업그레이드를 503으로 거절(연결 거부)
//   silent     TCP · 업그레이드 요청은 받되 아무 응답도 하지 않는다(연결 단계 무응답 → connect-timeout)
//
// 턴 시나리오(입력 프레임 framesPerTurn장을 받을 때마다 다음 턴 하나): normal · forbidden
//   normal     아이 전사(조각 2 → 말 끝 → 확정) → AI 전사 · 출력 음성(게이트 통과 문장)
//   forbidden  같은 순서이되 AI 문장에 시드 사전 SCORE 패턴 '비정상'이 있다(게이트 폐기 → WAITING_FILLER, BR7.4 · A5 · J101)
//
// 문장은 모두 합성 값이고 개인정보가 없다. C안 시나리오(run-c.mjs)는 같은 상수를 읽어 12 items · DB 행 · 로그 위생 목록을 만든다.
// 출력 음성은 턴 번호와 종류로 정해지는 톤이라 같은 입력이면 같은 바이트다.
import { BYTES_PER_MS, SAMPLE_BYTES, sha256Hex, synthesizeTone } from "../lib/frameCodec.mjs";

export const CONNECTION_SCENARIOS = Object.freeze([
  "ok",
  "no-started",
  "delayed",
  "refuse",
  "silent",
]);
// U4b(FR2.1 · U4b-J3): join은 참여 확인 대답 한 마디("응")만 보내고 AI 출력은 없다(참여 전에는 서버가 응답을 막고, 참여가 확인되면
// session.response.create를 한 번 보낸다 — 가짜 공급자는 받은 사실만 기록한다).
export const TURN_KINDS = Object.freeze(["normal", "forbidden", "join"]);
/** 시드 사전(gate/seed-dictionary.json) SCORE 항목(J101 · 사람 결정 12). */
export const FORBIDDEN_PATTERN = "비정상";

/** 턴 종류별 문장 · 출력 길이 · 톤. 문장은 시드 사전 7항목(정규화 뒤 부분 문자열)을 normal에서 피하고 forbidden에서만 건드린다. */
export const TURN_SCRIPTS = Object.freeze({
  normal: Object.freeze({
    childText: "오늘 학교에서 공룡 그림을 그렸어",
    aiText: "우와, 공룡 그림을 그렸구나! 어떤 공룡이었어?",
    outputMs: 600,
    freq: 330,
  }),
  forbidden: Object.freeze({
    childText: "친구가 내 그림을 보고 웃었어",
    aiText: "친구가 그렇게 웃는 건 비정상이야",
    outputMs: 500,
    freq: 440,
  }),
  join: Object.freeze({
    childText: "응",
    aiText: null,
    outputMs: 0,
    freq: 0,
  }),
});

export const DEFAULTS = Object.freeze({
  framesPerTurn: 25,
  startedDelayMs: 1500,
  responseDelayMs: 120,
  chunkBytes: 4800,
  chunkIntervalMs: 20,
});

/** 쉼표 목록을 읽어 허용 값인지 본다. */
export function parseList(text, allowed, label) {
  const items = String(text ?? "")
    .split(",")
    .map((item) => item.trim())
    .filter((item) => item.length > 0);
  if (items.length === 0) {
    throw new RangeError(`${label} 목록이 비어 있다`);
  }
  for (const item of items) {
    if (!allowed.includes(item)) {
      throw new RangeError(`${label} 값이 틀렸다: ${item} (허용: ${allowed.join(", ")})`);
    }
  }
  return items;
}

/** n번째 연결(1부터)의 시나리오. 목록보다 뒤의 연결은 마지막 값을 쓴다. */
export function scenarioForConnection(scenarios, connectionIndex) {
  return scenarios[Math.min(connectionIndex, scenarios.length) - 1];
}

/** 턴의 출력 음성(PCM16LE 24kHz 모노). turn은 1부터. */
export function outputPcmFor(turn, kind) {
  const script = TURN_SCRIPTS[kind];
  if (!script) {
    throw new RangeError(`모르는 턴 종류: ${kind}`);
  }
  const bytes = Math.round(script.outputMs * BYTES_PER_MS);
  if (bytes === 0) {
    return Buffer.alloc(0);
  }
  return synthesizeTone(bytes / SAMPLE_BYTES, {
    freq: script.freq + (turn - 1) * 10,
    amplitude: 0.2,
  });
}

/** 문자열을 두 조각으로(코드 포인트 경계). */
export function splitInTwo(text) {
  const chars = Array.from(text);
  const half = Math.ceil(chars.length / 2);
  return [chars.slice(0, half).join(""), chars.slice(half).join("")];
}

/** 문장 해시(기록에는 원문 대신 이 값을 쓴다). */
export function textSha256(text) {
  return sha256Hex(Buffer.from(text, "utf8"));
}

/**
 * 한 턴의 선 형식 메시지와 앞 지연(ms). J110: 아이 전사 조각 → 말 끝 → 아이 전사 확정 → (지연) → AI 전사 조각 →
 * 출력 음성 조각들 → 출력 끝. 구간 확정은 realtime이 마지막 출력 뒤 800ms 조용으로 한다(BR7.3).
 * @returns {{script: object, pcm: Buffer, itemId: string, responseId: string, steps: Array<{delayMs: number, message: object}>}}
 */
export function buildTurn({
  connection,
  turn,
  kind,
  responseDelayMs = DEFAULTS.responseDelayMs,
  chunkBytes = DEFAULTS.chunkBytes,
  chunkIntervalMs = DEFAULTS.chunkIntervalMs,
  confidence = null,
}) {
  const script = TURN_SCRIPTS[kind];
  if (!script) {
    throw new RangeError(`모르는 턴 종류: ${kind}`);
  }
  if (!Number.isInteger(chunkBytes) || chunkBytes <= 0 || chunkBytes % SAMPLE_BYTES !== 0) {
    throw new RangeError("출력 조각 크기는 2바이트 배수 양수여야 한다");
  }
  const itemId = `item-${connection}-${turn}`;
  const responseId = script.aiText === null ? null : `resp-${connection}-${turn}`;
  const pcm = outputPcmFor(turn, kind);
  const [child1, child2] = splitInTwo(script.childText);
  const childSteps = [
    { delayMs: 0, message: { type: "session.input_transcript.delta", itemId, delta: child1 } },
    ...(child2 === "" ? [] : [{ delayMs: 0, message: { type: "session.input_transcript.delta", itemId, delta: child2 } }]),
    { delayMs: 0, message: { type: "session.input_audio.speech_stopped" } },
    {
      delayMs: 0,
      message:
        confidence === null
          ? { type: "session.input_transcript.done", itemId, transcript: script.childText }
          : { type: "session.input_transcript.done", itemId, transcript: script.childText, confidence },
    },
  ];
  if (script.aiText === null) {
    // 참여 확인 대답(U4b): 아이 전사만 보내고 AI 출력은 없다
    return { script, pcm, itemId, responseId, steps: childSteps };
  }
  const [ai1, ai2] = splitInTwo(script.aiText);
  const steps = [
    { delayMs: 0, message: { type: "session.input_transcript.delta", itemId, delta: child1 } },
    { delayMs: 0, message: { type: "session.input_transcript.delta", itemId, delta: child2 } },
    { delayMs: 0, message: { type: "session.input_audio.speech_stopped" } },
    {
      delayMs: 0,
      message:
        confidence === null
          ? { type: "session.input_transcript.done", itemId, transcript: script.childText }
          : { type: "session.input_transcript.done", itemId, transcript: script.childText, confidence },
    },
    {
      delayMs: responseDelayMs,
      message: { type: "session.output_transcript.delta", responseId, delta: ai1 },
    },
    { delayMs: 0, message: { type: "session.output_transcript.delta", responseId, delta: ai2 } },
  ];
  for (let offset = 0; offset < pcm.length; offset += chunkBytes) {
    const chunk = pcm.subarray(offset, Math.min(offset + chunkBytes, pcm.length));
    steps.push({
      delayMs: offset === 0 ? 0 : chunkIntervalMs,
      message: { type: "session.output_audio.delta", responseId, audio: chunk.toString("base64") },
    });
  }
  steps.push({ delayMs: 0, message: { type: "session.output.done", responseId } });
  return { script, pcm, itemId, responseId, steps };
}
