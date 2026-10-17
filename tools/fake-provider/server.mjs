#!/usr/bin/env node
// 가짜 공급자 서버 (U1 3.5 Step 45 · J129 · J131 · RD-5 · RD-23 · SD-20 · SD-25 · U11 BR11.10 · contracts/ai-io.md 3절)
//
// realtime(VoiceRelay)이 `MUJOONG_PROVIDER_URL`로 붙는 대역 WS 서버다. 앱 이미지에 넣지 않고 dev · ci Compose 서비스와 host 모드
// 프로세스로만 돈다(J129 · SD-25). 텍스트 프레임 JSON 한 개 = 메시지 한 개(공통 필드 type), 음성은 base64 PCM16LE 24kHz 모노다.
//
// 기록(J131): 표준 출력에 JSON 한 줄씩 남긴다 — 연결(Authorization이 Bearer 형식인지만, 키 값은 남기지 않음), session.start의 키 목록,
// 받은 입력 음성 프레임마다 누적 바이트 수 · 누적 SHA-256, 턴마다 그 턴 입력의 SHA-256 · 보낸 출력 음성의 바이트 수 · SHA-256 ·
// 문장 SHA-256, session.close · 연결 닫힘. 지시문 · 아이 문맥 값 · 전사 원문은 기록하지 않는다(SD-15). C안 시나리오는 이 기록 파일을
// 따라 읽어 "가짜 공급자가 받은 입력 해시 = 시뮬레이터가 보낸 해시", "기기가 받은 0x02 해시 = 가짜 공급자가 보낸 출력 해시"를 단언한다.
// U11(편차 (U11-a), 설계 개정 A1 · A4 · A7): 실호출 3회로 관측한 형식만 말하고 공급자처럼 엄격하다 — 세션 시작은 중첩 session{model, instructions,
// audio{format{type, rate}}}, 출력 음성은 session.output_audio.delta{delta}(응답 id · 응답 끝 사건 없음), 전사는 session.{input,output}_transcript.delta
// {start_ms, end_ms, delta, event_id}(입력 전사 완료 · 말 끝 사건 없음, 출력 전사는 음성보다 앞서 도착), 오류는 error{error{type, code, param}}이다.
// 지원 목록 밖 type에는 error{invalid_request_error, invalid_value, param type}, session.instructions.append에 delegation_id 키가 없으면
// error{missing_required_parameter, param delegation_id}, response.create에는 error{"requires a session with Responses delegation"}로 답한다.
// 올바른 session.instructions.append에는 확인 사건 session.instructions.appended{client_event_id, start_ms, end_ms}를 곧바로 돌려주고, 다음 턴의 새 말은
// 확인 end_ms 뒤에서 시작한다(앞에 낡은 꼬리를 흉내 낸 채움 음성). 지시문은 문장 SHA-256 · 길이만 기록한다. 키(Authorization)는 보지 않고 Bearer 형식
// 여부만 기록한다 — 키가 비면 헤더가 없고 연결은 받는다(BR12.2). 턴 종류 join은 참여 확인 대답("응")의 입력 전사 조각만 보낸다(AI 출력 없음).
//
// 쓰는 법
//   node tools/fake-provider/server.mjs [--host 127.0.0.1] [--port 8090] [--scenario ok] [--turns normal,forbidden]
//        [--frames-per-turn 25] [--started-delay-ms 1500] [--response-delay-ms 120] [--chunk-bytes 4800] [--chunk-interval-ms 20]
//   --help 로 전체 설명. 종료 코드: 0 정상 종료(SIGTERM · SIGINT), 1 기동 실패, 2 인자 오류.
import http from "node:http";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { WebSocketServer } from "ws";
import { INPUT_PAYLOAD_BYTES, RunningHash, sha256Hex } from "../lib/frameCodec.mjs";
import { jsonLine } from "../lib/log.mjs";
import {
  CONNECTION_SCENARIOS,
  DEFAULTS,
  TURN_KINDS,
  SUPPORTED_TYPES,
  ACK_LEAD_MS,
  buildTurn,
  instructionsAppended,
  missingDelegationIdError,
  parseList,
  responseCreateRefused,
  scenarioForConnection,
  textSha256,
  unsupportedTypeError,
} from "./scenarios.mjs";

export const USAGE = `가짜 공급자 서버 (실호출 관측 선 형식, 시험 전용)

쓰는 법: node tools/fake-provider/server.mjs [옵션]

  --host <주소>             들을 주소 (기본 127.0.0.1, Compose 서비스는 0.0.0.0)
  --port <번호>             들을 포트 (기본 8090, 0이면 빈 포트)
  --scenario <목록>         연결 시나리오, 쉼표 목록이면 n번째 연결에 n번째 값(끝 값 반복)
                            ok | no-started | delayed | refuse | silent (기본 ok)
  --turns <목록>            입력 프레임 묶음마다 낼 턴 normal | forbidden | join (기본 normal,forbidden)
                            join은 참여 확인 대답("응")만 보낸다(U4b, AI 출력 없음)
  --frames-per-turn <n>     턴 하나를 여는 입력 프레임 수 (기본 ${DEFAULTS.framesPerTurn})
  --started-delay-ms <ms>   delayed 시나리오의 session.started 지연 (기본 ${DEFAULTS.startedDelayMs})
  --response-delay-ms <ms>  아이 전사 확정 뒤 AI 출력까지 지연 (기본 ${DEFAULTS.responseDelayMs})
  --chunk-bytes <n>         출력 음성 조각 크기, 2바이트 배수 (기본 ${DEFAULTS.chunkBytes})
  --chunk-interval-ms <ms>  출력 음성 조각 간격 (기본 ${DEFAULTS.chunkIntervalMs})
  --help                    이 설명

기록: 표준 출력에 JSON 한 줄씩({"ev": ...}). 키 · 지시문 · 전사 원문은 쓰지 않는다.
상태: GET /healthz → 200 {"status":"UP"}
종료 코드: 0 정상 종료, 1 기동 실패, 2 인자 오류
`;

/**
 * 가짜 공급자를 띄운다.
 * @param {object} [options]
 * @param {string} [options.host]
 * @param {number} [options.port]
 * @param {string[]} [options.scenarios]
 * @param {string[]} [options.turns]
 * @param {number} [options.framesPerTurn]
 * @param {number} [options.startedDelayMs]
 * @param {number} [options.responseDelayMs]
 * @param {number} [options.chunkBytes]
 * @param {number} [options.chunkIntervalMs]
 * @param {(entry: object) => void} [options.onRecord] 기록 한 건(기본: 표준 출력 JSON 한 줄)
 */
export async function startFakeProvider({
  host = "127.0.0.1",
  port = 8090,
  scenarios = ["ok"],
  turns = ["normal", "forbidden"],
  framesPerTurn = DEFAULTS.framesPerTurn,
  startedDelayMs = DEFAULTS.startedDelayMs,
  responseDelayMs = DEFAULTS.responseDelayMs,
  chunkBytes = DEFAULTS.chunkBytes,
  chunkIntervalMs = DEFAULTS.chunkIntervalMs,
  onRecord = (entry) => process.stdout.write(jsonLine(entry)),
} = {}) {
  for (const scenario of scenarios) {
    if (!CONNECTION_SCENARIOS.includes(scenario)) {
      throw new RangeError(`모르는 연결 시나리오: ${scenario}`);
    }
  }
  for (const kind of turns) {
    if (!TURN_KINDS.includes(kind)) {
      throw new RangeError(`모르는 턴 종류: ${kind}`);
    }
  }
  if (!Number.isInteger(framesPerTurn) || framesPerTurn < 1) {
    throw new RangeError("frames-per-turn은 1 이상 정수여야 한다");
  }

  const records = [];
  const record = (entry) => {
    const stamped = { ts: new Date().toISOString(), ...entry };
    records.push(stamped);
    onRecord(stamped);
  };
  const held = new Set();
  const timers = new Set();
  let connectionCount = 0;

  const server = http.createServer((req, res) => {
    if (req.method === "GET" && req.url === "/healthz") {
      res.writeHead(200, { "content-type": "application/json" });
      res.end('{"status":"UP"}');
      return;
    }
    res.writeHead(404, { "content-type": "application/json" });
    res.end('{"status":"NOT_FOUND"}');
  });
  const wss = new WebSocketServer({ noServer: true, perMessageDeflate: false });

  server.on("upgrade", (req, socket, head) => {
    connectionCount += 1;
    const conn = connectionCount;
    const scenario = scenarioForConnection(scenarios, conn);
    socket.on("error", () => {});
    const authorization = req.headers.authorization ?? "";
    const bearer = /^Bearer \S+/.test(authorization);
    const requestPath = (req.url ?? "/").split("?")[0];
    if (scenario === "refuse") {
      record({ ev: "refused", conn, path: requestPath });
      socket.end(
        "HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\nContent-Length: 0\r\n\r\n",
      );
      return;
    }
    if (scenario === "silent") {
      record({ ev: "silent", conn, path: requestPath });
      held.add(socket);
      socket.on("close", () => held.delete(socket));
      return;
    }
    // U11 BR12.2: 키가 비면 Authorization 헤더가 없다. 가짜 공급자는 헤더를 보지 않고 연결을 받는다(Bearer 형식인지만 기록)
    wss.handleUpgrade(req, socket, head, (ws) => {
      record({ ev: "connect", conn, path: requestPath, bearer, scenario });
      attach(ws, conn, scenario);
    });
  });

  function later(ms, fn) {
    const timer = setTimeout(() => {
      timers.delete(timer);
      fn();
    }, ms);
    timers.add(timer);
  }

  function attach(ws, conn, scenario) {
    const state = {
      started: false,
      inputFrames: 0,
      inputBytes: 0,
      cumulative: new RunningHash(),
      turnHash: new RunningHash(),
      turnFrames: 0,
      turnIndex: 0,
      closed: false,
      // 이 연결에서 보낸 출력 오디오의 누적 ms(세션 오디오 시계)와, 아직 새 말이 쓰지 않은 지시 확인의 end_ms
      audioMs: 0,
      pendingAckEndMs: -1,
      ackSeq: 0,
    };
    const send = (message) => {
      if (!state.closed && ws.readyState === ws.OPEN) {
        ws.send(JSON.stringify(message));
        return true;
      }
      return false;
    };

    ws.on("message", (data, isBinary) => {
      if (isBinary) {
        record({ ev: "unexpected-binary", conn });
        return;
      }
      let message;
      try {
        message = JSON.parse(data.toString("utf8"));
      } catch {
        record({ ev: "bad-message", conn });
        return;
      }
      if (message === null || typeof message !== "object" || typeof message.type !== "string") {
        record({ ev: "bad-message", conn });
        return;
      }
      switch (message.type) {
        case "session.start":
          onSessionStart(message);
          break;
        case "session.input_audio.append":
          onInputAudio(message);
          break;
        case "session.instructions.append":
          onInstructionsAppend(message);
          break;
        case "response.create":
          // client 위임 세션은 거절한다(실호출 관측). 받은 사실만 기록한다
          record({ ev: "response.create", conn });
          send(responseCreateRefused());
          break;
        case "session.close":
          record({
            ev: "session.close",
            conn,
            inputFrames: state.inputFrames,
            inputBytes: state.inputBytes,
          });
          later(0, () => ws.close(1000));
          break;
        default:
          if (SUPPORTED_TYPES.includes(message.type)) {
            // 지원 목록 안이지만 이 대역이 쓰지 않는 type: 조용히 받는다
            record({ ev: "recv-ignored", conn, type: message.type });
          } else {
            // 지원 목록 밖(예: 응답 취소): 실공급자처럼 error 사건으로 답한다(type 이름은 기록에 64자까지만)
            record({ ev: "recv-unsupported", conn, type: message.type.slice(0, 64) });
            send(unsupportedTypeError());
          }
      }
    });

    ws.on("close", (code) => {
      state.closed = true;
      record({
        ev: "close",
        conn,
        code,
        inputFrames: state.inputFrames,
        inputBytes: state.inputBytes,
        inputSha256: state.cumulative.hex(),
      });
    });
    ws.on("error", () => {});

    function onInstructionsAppend(message) {
      const hasDelegationId = Object.prototype.hasOwnProperty.call(message, "delegation_id");
      const content = typeof message.content === "string" ? message.content : null;
      // 지시문 원문은 남기지 않는다(문장 SHA-256 · 길이만)
      record({
        ev: "session.instructions.append",
        conn,
        keys: Object.keys(message).sort(),
        delegationIdPresent: hasDelegationId,
        delegationIdNull: hasDelegationId && message.delegation_id === null,
        eventId: typeof message.event_id === "string" ? message.event_id.slice(0, 64) : null,
        textSha256: content === null ? null : textSha256(content),
        textLength: content === null ? 0 : content.length,
      });
      if (!hasDelegationId) {
        send(missingDelegationIdError());
        return;
      }
      if (content === null || content.trim() === "") {
        send({ ...missingDelegationIdError(), error: { ...missingDelegationIdError().error, param: "content" } });
        return;
      }
      state.ackSeq += 1;
      const ackEnd = state.audioMs + ACK_LEAD_MS;
      state.pendingAckEndMs = ackEnd;
      send(
        instructionsAppended(
          typeof message.event_id === "string" ? message.event_id : "",
          state.audioMs,
          ackEnd,
          `fake-a-${conn}-${state.ackSeq}`,
        ),
      );
    }

    function onSessionStart(message) {
      const body = message.session && typeof message.session === "object" ? message.session : {};
      const format =
        body.audio && typeof body.audio === "object" && body.audio.format && typeof body.audio.format === "object"
          ? body.audio.format
          : null;
      // 지시문(아이 정보가 든 문장)은 기록하지 않는다(키 목록만, SD-20 허용 필드 대조용)
      record({
        ev: "session.start",
        conn,
        keys: Object.keys(message).sort(),
        sessionKeys: Object.keys(body).sort(),
        model: typeof body.model === "string" ? body.model.slice(0, 64) : null,
        audioFormat: format ? { type: format.type, rate: format.rate } : null,
      });
      const started = { type: "session.started", session: { id: `fake-provider-${conn}` } };
      if (scenario === "ok") {
        state.started = true;
        send(started);
        record({ ev: "session.started", conn });
      } else if (scenario === "delayed") {
        later(startedDelayMs, () => {
          state.started = true;
          if (send(started)) {
            record({ ev: "session.started", conn, delayedMs: startedDelayMs });
          }
        });
      } else {
        record({ ev: "session.started.withheld", conn });
      }
    }

    function onInputAudio(message) {
      let audio;
      try {
        audio = Buffer.from(typeof message.audio === "string" ? message.audio : "", "base64");
      } catch {
        audio = Buffer.alloc(0);
      }
      state.inputFrames += 1;
      state.inputBytes += audio.length;
      state.cumulative.update(audio);
      state.turnHash.update(audio);
      state.turnFrames += 1;
      const entry = {
        ev: "input",
        conn,
        frames: state.inputFrames,
        bytes: state.inputBytes,
        sha256: state.cumulative.hex(),
      };
      if (audio.length !== INPUT_PAYLOAD_BYTES) {
        entry.badLength = audio.length;
      }
      record(entry);
      if (state.turnIndex < turns.length && state.turnFrames >= framesPerTurn) {
        triggerTurn();
      }
    }

    function triggerTurn() {
      const turn = state.turnIndex + 1;
      const kind = turns[state.turnIndex];
      state.turnIndex = turn;
      const built = buildTurn({
        connection: conn,
        turn,
        kind,
        responseDelayMs,
        chunkBytes,
        chunkIntervalMs,
        clockMs: state.audioMs,
        pendingAckEndMs: state.pendingAckEndMs,
      });
      state.audioMs += built.audioMsAdded;
      if (built.usedAck) {
        state.pendingAckEndMs = -1;
      }
      record({
        ev: "turn",
        conn,
        turn,
        kind,
        frames: state.turnFrames,
        inputBytes: state.turnHash.bytes,
        inputSha256: state.turnHash.hex(),
        itemId: built.itemId,
        outputBytes: built.pcm.length,
        outputSha256: sha256Hex(built.pcm),
        childTextSha256: textSha256(built.script.childText),
        aiTextSha256: built.script.aiText === null ? null : textSha256(built.script.aiText),
      });
      state.turnHash = new RunningHash();
      state.turnFrames = 0;
      emitSteps(built.steps, 0, turn);
    }

    function emitSteps(steps, index, turn) {
      if (index >= steps.length) {
        record({ ev: "turn-emitted", conn, turn });
        return;
      }
      const step = steps[index];
      const run = () => {
        if (!send(step.message)) {
          record({ ev: "turn-aborted", conn, turn, at: index });
          return;
        }
        emitSteps(steps, index + 1, turn);
      };
      if (step.delayMs > 0) {
        later(step.delayMs, run);
      } else {
        run();
      }
    }
  }

  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(port, host, () => {
      server.off("error", reject);
      resolve();
    });
  });
  const address = server.address();
  const boundPort = typeof address === "object" && address ? address.port : port;
  record({ ev: "listening", host, port: boundPort, scenarios, turns, framesPerTurn });

  async function close() {
    for (const timer of timers) {
      clearTimeout(timer);
    }
    timers.clear();
    for (const socket of held) {
      socket.destroy();
    }
    held.clear();
    for (const client of wss.clients) {
      client.terminate();
    }
    await new Promise((resolve) => wss.close(() => resolve()));
    await new Promise((resolve) => server.close(() => resolve()));
  }

  return {
    host,
    port: boundPort,
    url: `ws://${host === "0.0.0.0" ? "127.0.0.1" : host}:${boundPort}/v1/live/sessions`,
    records,
    close,
  };
}

/** 인자를 읽는다. 틀리면 RangeError. */
export function parseArgs(argv) {
  const options = {};
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    const value = () => {
      const next = argv[i + 1];
      if (next === undefined || next.startsWith("--")) {
        throw new RangeError(`${arg}에 값이 없다`);
      }
      i += 1;
      return next;
    };
    const integer = (min) => {
      const text = value();
      if (!/^[0-9]+$/.test(text) || Number(text) < min) {
        throw new RangeError(`${arg} 값은 ${min} 이상 정수여야 한다`);
      }
      return Number(text);
    };
    switch (arg) {
      case "--help":
      case "-h":
        options.help = true;
        break;
      case "--host":
        options.host = value();
        break;
      case "--port":
        options.port = integer(0);
        break;
      case "--scenario":
        options.scenarios = parseList(value(), CONNECTION_SCENARIOS, "시나리오");
        break;
      case "--turns":
        options.turns = parseList(value(), TURN_KINDS, "턴");
        break;
      case "--frames-per-turn":
        options.framesPerTurn = integer(1);
        break;
      case "--started-delay-ms":
        options.startedDelayMs = integer(0);
        break;
      case "--response-delay-ms":
        options.responseDelayMs = integer(0);
        break;
      case "--chunk-bytes": {
        const bytes = integer(2);
        if (bytes % 2 !== 0) {
          throw new RangeError("--chunk-bytes는 2바이트 배수여야 한다");
        }
        options.chunkBytes = bytes;
        break;
      }
      case "--chunk-interval-ms":
        options.chunkIntervalMs = integer(0);
        break;
      default:
        throw new RangeError(`모르는 인자: ${arg}`);
    }
  }
  return options;
}

export async function main(
  argv = process.argv.slice(2),
  io = { stdout: process.stdout, stderr: process.stderr },
) {
  let options;
  try {
    options = parseArgs(argv);
  } catch (error) {
    io.stderr.write(`가짜 공급자: ${error.message}\n${USAGE}`);
    return { exitCode: 2 };
  }
  if (options.help) {
    io.stdout.write(USAGE);
    return { exitCode: 0 };
  }
  const { help: _help, ...rest } = options;
  try {
    const provider = await startFakeProvider({
      ...rest,
      onRecord: (entry) => io.stdout.write(jsonLine(entry)),
    });
    return { exitCode: null, provider };
  } catch (error) {
    io.stderr.write(`가짜 공급자 기동 실패: ${error.code ?? error.message}\n`);
    return { exitCode: 1 };
  }
}

const isEntryPoint =
  process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url;
if (isEntryPoint) {
  const result = await main();
  if (result.exitCode !== null) {
    process.exitCode = result.exitCode;
  } else {
    const stop = async () => {
      await result.provider.close();
      process.exit(0);
    };
    process.on("SIGTERM", stop);
    process.on("SIGINT", stop);
  }
}

export const MODULE_PATH = fileURLToPath(import.meta.url);
