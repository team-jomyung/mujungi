#!/usr/bin/env node
// 가짜 공급자 서버 (U1 3.5 Step 45 · J110 선 형식 · J129 · J131 · RD-5 · RD-23 · SD-20 · SD-25 · contracts/ai-io.md 3절)
//
// realtime(VoiceRelay)이 `MUJOONG_PROVIDER_URL`로 붙는 대역 WS 서버다. 앱 이미지에 넣지 않고 dev · ci Compose 서비스와 host 모드
// 프로세스로만 돈다(J129 · SD-25). 텍스트 프레임 JSON 한 개 = 메시지 한 개(공통 필드 type), 음성은 base64 PCM16LE 24kHz 모노다.
//
// 기록(J131): 표준 출력에 JSON 한 줄씩 남긴다 — 연결(Authorization이 Bearer 형식인지만, 키 값은 남기지 않음), session.start의 키 목록,
// 받은 입력 음성 프레임마다 누적 바이트 수 · 누적 SHA-256, 턴마다 그 턴 입력의 SHA-256 · 보낸 출력 음성의 바이트 수 · SHA-256 ·
// 문장 SHA-256, session.close · 연결 닫힘. 지시문 · 아이 문맥 값 · 전사 원문은 기록하지 않는다(SD-15). C안 시나리오는 이 기록 파일을
// 따라 읽어 "가짜 공급자가 받은 입력 해시 = 시뮬레이터가 보낸 해시", "기기가 받은 0x02 해시 = 가짜 공급자가 보낸 출력 해시"를 단언한다.
// U3(편차 (aa)): session.response.cancel이 오면 그 응답의 남은 출력 조각을 보내지 않고 기록하며(끼어들기 · 종료 의도), 
// session.instructions.correct는 문장 SHA-256 · 길이만 기록한다. --confidence를 주면 아이 전사 확정에 confidence를 싣는다(U3-J17).
// U4b(편차 (U4b-a)): session.response.create는 받은 사실만 기록하고, 턴 종류 join은 참여 확인 대답("응")만 보낸다(AI 출력 없음).
//
// 쓰는 법
//   node tools/fake-provider/server.mjs [--host 127.0.0.1] [--port 8090] [--scenario ok] [--turns normal,forbidden]
//        [--frames-per-turn 25] [--started-delay-ms 1500] [--response-delay-ms 120] [--chunk-bytes 4800] [--chunk-interval-ms 20]
//        [--confidence 0.4]
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
  buildTurn,
  parseList,
  scenarioForConnection,
  textSha256,
} from "./scenarios.mjs";

export const USAGE = `가짜 공급자 서버 (J110 선 형식, 시험 전용)

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
  --confidence <0~1>        아이 전사 확정(session.input_transcript.done)에 실을 신뢰도 (기본 없음, U3)
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
 * @param {number | null} [options.confidence] 아이 전사 확정에 실을 신뢰도(0 ~ 1, 없으면 싣지 않는다, U3)
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
  confidence = null,
  onRecord = (entry) => process.stdout.write(jsonLine(entry)),
} = {}) {
  if (confidence !== null && !(typeof confidence === "number" && confidence >= 0 && confidence <= 1)) {
    throw new RangeError("confidence는 0 ~ 1이어야 한다");
  }
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
    if (!bearer) {
      // 키가 없는 연결은 받지 않는다(실공급자와 같은 모양, 키 값은 보지 않는다)
      record({ ev: "unauthorized", conn, path: requestPath });
      socket.end("HTTP/1.1 401 Unauthorized\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
      return;
    }
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
      /** 지금 내보내는 응답 id(취소 요청에 id가 없을 때 대상, U3). */
      currentResponseId: null,
      /** 취소된 응답 id(U3-R5). */
      cancelled: new Set(),
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
        case "session.response.cancel": {
          // 끼어들기 · 종료 의도(U3-R5 · U3-J11): 그 응답(없으면 지금 응답)의 남은 출력을 보내지 않는다
          const target = typeof message.responseId === "string" ? message.responseId : state.currentResponseId;
          if (target !== null) {
            state.cancelled.add(target);
          }
          record({ ev: "session.response.cancel", conn, hasResponseId: typeof message.responseId === "string" });
          break;
        }
        case "session.instructions.correct":
          // 고정 교정 문장(U3-J12): 원문은 남기지 않는다
          record({
            ev: "session.instructions.correct",
            conn,
            textSha256: typeof message.text === "string" ? textSha256(message.text) : null,
            textLength: typeof message.text === "string" ? message.text.length : 0,
          });
          break;
        case "session.response.create":
          // U4b(편차 (U4b-a) · U4b-J3): 참여 확인 · 확인 질문 뒤 발언권 반환 — 받은 사실만 기록한다
          record({ ev: "session.response.create", conn });
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
          record({ ev: "recv-unknown", conn, type: message.type.slice(0, 64) });
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

    function onSessionStart(message) {
      const childContext =
        message.childContext && typeof message.childContext === "object"
          ? message.childContext
          : {};
      // 지시문 · 아이 문맥 값은 기록하지 않는다(키 목록만, SD-20 허용 필드 대조용)
      record({
        ev: "session.start",
        conn,
        keys: Object.keys(message).sort(),
        childContextKeys: Object.keys(childContext).sort(),
        model: typeof message.model === "string" ? message.model.slice(0, 64) : null,
        audio:
          message.audio && typeof message.audio === "object"
            ? { input: message.audio.input, output: message.audio.output }
            : null,
      });
      const started = { type: "session.started", providerSessionId: `fake-provider-${conn}` };
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
        confidence,
      });
      state.currentResponseId = built.responseId;
      record({
        ev: "turn",
        conn,
        turn,
        kind,
        frames: state.turnFrames,
        inputBytes: state.turnHash.bytes,
        inputSha256: state.turnHash.hex(),
        itemId: built.itemId,
        responseId: built.responseId,
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
        const responseId = step.message.responseId;
        if (typeof responseId === "string" && state.cancelled.has(responseId)) {
          // 취소된 응답: 남은 출력 조각 · 끝 신호를 보내지 않는다
          record({ ev: "turn-cancelled", conn, turn, at: index });
          return;
        }
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
      case "--confidence": {
        const text = value();
        const parsed = Number(text);
        if (!/^(0(\.[0-9]+)?|1(\.0+)?)$/.test(text) || Number.isNaN(parsed)) {
          throw new RangeError(`${arg} 값은 0 ~ 1이어야 한다`);
        }
        options.confidence = parsed;
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
