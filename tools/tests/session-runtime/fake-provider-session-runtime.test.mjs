// 가짜 공급자 U4b 추가분 시험 (FR2.1 · FR2.3 · BR2.6 · U4b-J3 · 편차 (U4b-a) → (U11-a) · contracts/ai-io.md 3절)
// 참여 확인 턴(join)이 아이 대답 한 마디만 보내고 AI 출력이 없는지, response.create를 받은 사실만 기록하는지 본다.
import { randomBytes } from "node:crypto";
import { afterEach, describe, expect, it } from "vitest";
import WebSocket from "ws";
import { synthesizeInputPayload } from "../../lib/frameCodec.mjs";
import { parseArgs, startFakeProvider } from "../../fake-provider/server.mjs";
import { TURN_KINDS, buildTurn } from "../../fake-provider/scenarios.mjs";

const providers = [];
const sockets = [];

afterEach(async () => {
  for (const socket of sockets.splice(0)) {
    socket.terminate();
  }
  for (const provider of providers.splice(0)) {
    await provider.close();
  }
});

async function start(options = {}) {
  const provider = await startFakeProvider({
    port: 0,
    framesPerTurn: 3,
    responseDelayMs: 5,
    chunkIntervalMs: 1,
    onRecord: () => {},
    ...options,
  });
  providers.push(provider);
  return provider;
}

async function waitUntil(condition, timeoutMs = 3000) {
  const deadline = Date.now() + timeoutMs;
  while (!condition()) {
    if (Date.now() > deadline) {
      throw new Error("기다린 조건이 오지 않았다");
    }
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
}

async function startedSession(provider) {
  const ws = new WebSocket(provider.url, { headers: { authorization: `Bearer ${randomBytes(16).toString("base64url")}` } });
  sockets.push(ws);
  const messages = [];
  ws.on("message", (data) => messages.push(JSON.parse(data.toString("utf8"))));
  ws.on("error", () => {});
  await new Promise((resolve, reject) => {
    ws.once("open", resolve);
    ws.once("error", reject);
  });
  ws.send(JSON.stringify({ type: "session.start", session: { model: "m", instructions: "i", audio: { format: { type: "audio/pcm", rate: 24000 } } } }));
  await waitUntil(() => messages.some((message) => message.type === "session.started"));
  return { ws, messages };
}

function sendFrames(ws, count) {
  for (let index = 0; index < count; index += 1) {
    ws.send(JSON.stringify({ type: "session.input_audio.append", audio: synthesizeInputPayload(index).toString("base64") }));
  }
}

describe("가짜 공급자 — 참여 확인 턴(U4b FR2.1)", () => {
  it("join 턴은 아이 대답 '응'만 보내고 AI 출력이 없다", async () => {
    const provider = await start({ turns: ["join", "normal"] });
    const client = await startedSession(provider);
    sendFrames(client.ws, 3);
    await waitUntil(() => client.messages.some((message) => message.type === "session.input_transcript.delta"));
    await new Promise((resolve) => setTimeout(resolve, 50));

    // 입력 전사 완료 사건은 없다 — 조각 하나뿐이고 서버가 조용해진 뒤 확정한다(설계 개정 A7)
    expect(client.messages.some((message) => message.type === "session.input_transcript.done")).toBe(false);
    const deltas = client.messages.filter((message) => message.type === "session.input_transcript.delta");
    expect(deltas).toHaveLength(1);
    expect(deltas[0].delta).toBe("응");
    expect(client.messages.some((message) => message.type.startsWith("session.output"))).toBe(false);
    const turn = provider.records.find((entry) => entry.ev === "turn");
    expect(turn).toMatchObject({ kind: "join", outputBytes: 0, aiTextSha256: null });

    // 다음 프레임 묶음은 평소 턴(AI 출력 포함)을 연다
    sendFrames(client.ws, 3);
    await waitUntil(() => client.messages.some((message) => message.type === "session.output_audio.delta"));
  });

  it("join 턴의 메시지는 한 글자 대답이라 조각이 하나다", () => {
    const built = buildTurn({ connection: 1, turn: 1, kind: "join" });
    expect(built.steps.map((step) => step.message.type)).toEqual(["session.input_transcript.delta"]);
    expect(built.pcm).toHaveLength(0);
  });

  it("턴 인자는 join을 받고 모르는 값은 거절한다", () => {
    expect(TURN_KINDS).toContain("join");
    expect(parseArgs(["--turns", "join,normal"]).turns).toEqual(["join", "normal"]);
    expect(() => parseArgs(["--turns", "join,hello"])).toThrow(RangeError);
  });
});

describe("가짜 공급자 — 응답 생성 요청은 거절(U4b-J3 · 편차 (U4b-a) → (U11-a), 설계 개정 A1)", () => {
  it("response.create는 받은 사실을 기록하고 Responses 위임 전용 오류로 거절하며 출력을 만들지 않는다", async () => {
    const provider = await start({ turns: ["join"] });
    const client = await startedSession(provider);
    client.ws.send(JSON.stringify({ type: "response.create" }));
    client.ws.send(JSON.stringify({ type: "response.create" }));
    await waitUntil(() => provider.records.filter((entry) => entry.ev === "response.create").length === 2);
    await waitUntil(() => client.messages.filter((message) => message.type === "error").length === 2);

    expect(provider.records.filter((entry) => entry.ev === "recv-unsupported")).toHaveLength(0);
    expect(provider.records.find((entry) => entry.ev === "response.create")).toEqual(
      expect.objectContaining({ ev: "response.create", conn: 1 }),
    );
    expect(client.messages.find((message) => message.type === "error").error.message).toContain(
      "requires a session with Responses delegation",
    );
    expect(client.messages.some((message) => message.type.startsWith("session.output"))).toBe(false);
  });
});
