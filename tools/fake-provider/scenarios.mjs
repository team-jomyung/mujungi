// 가짜 공급자 시나리오 (U1 3.5 Step 45 · J131 · RD-5 · RD-23 · NFR4.2 · SD-17 · U11 BR11.10 · contracts/ai-io.md 3절)
//
// 선 형식은 실호출 3회로 관측한 것만 쓴다(U11 편차 (U11-a), 설계 개정 A1 · A4 · A7): 출력 음성은 session.output_audio.delta{delta}(base64)이고
// 응답 id · 시간 정보 · 응답 끝 사건이 없다. 전사는 session.output_transcript.delta · session.input_transcript.delta{start_ms, end_ms, delta,
// event_id}이고 입력 전사 완료 · 말 끝 사건은 없다 — 아이 말의 끝은 서버(realtime)가 조각을 묶어 정한다. 출력 전사는 음성보다 앞서(약
// 0.6초) 도착한다. 지시 추가(session.instructions.append)에는 확인 사건 session.instructions.appended{client_event_id, start_ms, end_ms}가 오고,
// 지시에 대한 새 말은 확인 end_ms 뒤에서 시작한다(앞에 낡은 꼬리를 흉내 낸 채움 음성). start_ms · end_ms는 이 서버가 센 출력 오디오 시계(보낸 음성
// 바이트 / 48 ms) 위의 값이다.
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
// 지시 하나(session.instructions.append)로 첫 응답을 시작한다 — 가짜 공급자는 받은 사실을 기록하고 확인 사건을 돌려준다).
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

/** 지시에 대한 새 말 앞에 두는 낡은 꼬리 흉내 음성의 길이(realtime new-speech-margin 600ms보다 길다). */
export const FILLER_MS = 700;
/** 일반 통합 시나리오의 채움 길이. contracts/timing.md의 new-speech-margin과 맞춘다(C9 보완). */
export const DEFAULT_INSTRUCTION_FILLER_MS = 600;
/** 지시 확인 end_ms = 지시를 받은 때의 오디오 시계 + 이 값(추정 끝). */
export const ACK_LEAD_MS = 300;
/** 출력 음성 PCM16 24kHz 모노의 ms당 바이트. */
export const PCM_BYTES_PER_MS = BYTES_PER_MS;

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
 * 한 턴의 선 형식 메시지와 앞 지연(ms). 아이 전사 조각들 → (지연) → AI 전사 조각들(음성보다 앞서) → (지시에 대한 응답이면 낡은 꼬리 흉내 채움 음성) →
 * 출력 음성 조각들. 입력 전사 완료 · 말 끝 · 응답 끝 사건은 없다. 구간 확정은 realtime이 마지막 출력 뒤 800ms 조용으로 한다(U11 BR11.6).
 * @param {number} [clockMs] 이 턴을 보내기 직전의 출력 오디오 시계(ms)
 * @param {number} [pendingAckEndMs] 아직 새 말이 쓰지 않은 지시 확인의 end_ms(없으면 -1) — 있으면 새 말은 그 뒤에서 시작한다
 * @param {number} [instructionFillerMs] 새 말 앞 채움 길이(기본600ms,700ms 악조건은 명시한다)
 * @returns {{script: object, pcm: Buffer, itemId: string, audioMsAdded: number, usedAck: boolean, steps: Array<{delayMs: number, message: object}>}}
 */
export function buildTurn({
  connection,
  turn,
  kind,
  responseDelayMs = DEFAULTS.responseDelayMs,
  chunkBytes = DEFAULTS.chunkBytes,
  chunkIntervalMs = DEFAULTS.chunkIntervalMs,
  clockMs = 0,
  pendingAckEndMs = -1,
  instructionFillerMs = DEFAULT_INSTRUCTION_FILLER_MS,
}) {
  const script = TURN_SCRIPTS[kind];
  if (!script) {
    throw new RangeError(`모르는 턴 종류: ${kind}`);
  }
  if (!Number.isInteger(chunkBytes) || chunkBytes <= 0 || chunkBytes % SAMPLE_BYTES !== 0) {
    throw new RangeError("출력 조각 크기는 2바이트 배수 양수여야 한다");
  }
  if (!Number.isSafeInteger(instructionFillerMs) || instructionFillerMs < 0) {
    throw new RangeError("지시 채움 길이는 0 이상 안전한 정수여야 한다");
  }
  // itemId는 기록용 식별자일 뿐 선 형식에는 싣지 않는다(공급자는 입력 전사 항목 id를 주지 않는다)
  const itemId = `item-${connection}-${turn}`;
  const pcm = outputPcmFor(turn, kind);
  const [child1, child2] = splitInTwo(script.childText);
  const timed = (type, delta, startMs, endMs) => ({
    type,
    start_ms: startMs,
    end_ms: endMs,
    delta,
    event_id: `fake-${type.includes("input") ? "i" : "t"}-${connection}-${turn}-${delta.length}`,
  });
  const childSteps = [{ delayMs: 0, message: timed("session.input_transcript.delta", child1, clockMs, clockMs) }];
  if (child2 !== "") {
    childSteps.push({ delayMs: 0, message: timed("session.input_transcript.delta", child2, clockMs, clockMs) });
  }
  if (script.aiText === null) {
    // 참여 확인 대답(U4b): 아이 전사 조각만 보내고 AI 출력은 없다
    return { script, pcm, itemId, audioMsAdded: 0, usedAck: false, steps: childSteps };
  }
  const usedAck = pendingAckEndMs >= 0;
  const startMs = usedAck ? Math.max(clockMs, pendingAckEndMs) : clockMs;
  const outputMs = Math.round(pcm.length / PCM_BYTES_PER_MS);
  const fillerMs = usedAck ? startMs - clockMs + instructionFillerMs : 0;
  const [ai1, ai2] = splitInTwo(script.aiText);
  const steps = [
    ...childSteps,
    {
      delayMs: responseDelayMs,
      message: timed("session.output_transcript.delta", ai1, startMs, startMs + Math.round(outputMs / 2)),
    },
    {
      delayMs: 0,
      message: timed("session.output_transcript.delta", ai2, startMs + Math.round(outputMs / 2), startMs + outputMs),
    },
  ];
  const audioChunks = [];
  if (fillerMs > 0) {
    const filler = Buffer.alloc(Math.round(fillerMs * PCM_BYTES_PER_MS) - (Math.round(fillerMs * PCM_BYTES_PER_MS) % 2));
    for (let offset = 0; offset < filler.length; offset += chunkBytes) {
      audioChunks.push(filler.subarray(offset, Math.min(offset + chunkBytes, filler.length)));
    }
  }
  for (let offset = 0; offset < pcm.length; offset += chunkBytes) {
    audioChunks.push(pcm.subarray(offset, Math.min(offset + chunkBytes, pcm.length)));
  }
  audioChunks.forEach((chunk, index) => {
    steps.push({
      delayMs: index === 0 ? 0 : chunkIntervalMs,
      message: { type: "session.output_audio.delta", delta: chunk.toString("base64") },
    });
  });
  const audioMsAdded = audioChunks.reduce((sum, chunk) => sum + chunk.length, 0) / PCM_BYTES_PER_MS;
  return { script, pcm, itemId, audioMsAdded, usedAck, steps };
}

/** 실호출 오류 문장에서 확인한 공급자 지원 type 전부(U11-a). 이 밖의 type에는 error 사건으로 답한다. */
export const SUPPORTED_TYPES = Object.freeze([
  "session.start",
  "session.update",
  "session.close",
  "session.input_audio.append",
  "session.input_audio.mute",
  "session.input_audio.unmute",
  "session.instructions.append",
  "session.thinking.append",
  "session.commentary.append",
  "session.output_audio.fixture.play",
  "response.create",
  "response.item.create",
  "decision.create",
  "decision.item.create",
  "decision.item.delete",
]);

/** 지시 추가에 {@code delegation_id} 키가 없을 때의 오류 사건(실호출 관측: missing_required_parameter, param delegation_id). */
export function missingDelegationIdError() {
  return {
    type: "error",
    error: {
      type: "invalid_request_error",
      code: "missing_required_parameter",
      message: "Missing required parameter",
      param: "delegation_id",
    },
  };
}

/** response.create는 Responses 위임 세션에서만 받는다(client 위임 세션은 거절, 실호출 관측). */
export function responseCreateRefused() {
  return {
    type: "error",
    error: {
      type: "invalid_request_error",
      code: "invalid_request",
      message: "response.create requires a session with Responses delegation",
      param: null,
    },
  };
}

/** 지시 확인 사건(실호출 관측: client_event_id · start_ms · end_ms · event_id). */
export function instructionsAppended(clientEventId, startMs, endMs, eventId) {
  return { type: "session.instructions.appended", event_id: eventId, client_event_id: clientEventId, start_ms: startMs, end_ms: endMs };
}

/** 지원 목록 밖 type에 대한 오류 사건(실호출 관측 모양, message는 고정 문장). */
export function unsupportedTypeError() {
  return {
    type: "error",
    error: { type: "invalid_request_error", code: "invalid_value", message: "Invalid value", param: "type" },
  };
}
