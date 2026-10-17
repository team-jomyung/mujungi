// C안 끝-끝 한 턴의 단계 C1 ~ C12 (U1 3.5 Step 45 · 계획 ## 검증 명령(C안) C · J131 · J143 · J144 · SEC-07 · SEC-16)
//
// 각 단계는 ctx(실행기가 만든 문맥)를 받아 단언하고, 어긋나면 StepFailure, 기한을 넘으면 StepTimeout을 던진다. 모든 대기에는
// 기한이 있다(SEC-05): HTTP 요청 하나는 deadlines.request, 상태 대기는 deadlines.{registered, stable, active, turn, ended}.
// 비밀 값(쿠키 · 토큰 · CSRF · 등록 코드 · 전사 원문)은 ctx.secrets에 등록만 하고(build/verify/c/secrets.list, 0600) 출력하지 않는다.
// 기기 쪽과 보호자 쪽은 서로 다른 쿠키 저장소를 쓴다(다른 브라우저).
import { randomUUID } from "node:crypto";
import { sha256Hex } from "../lib/frameCodec.mjs";
import { DeviceSimulator, probeUpgrade } from "../device-simulator/simulator.mjs";
import { FORBIDDEN_PATTERN, TURN_SCRIPTS, textSha256 } from "../fake-provider/scenarios.mjs";
import {
  ITEMS_SERVED_KEYS,
  LATENCY_TURN_KEYS,
  StepFailure,
  StepTimeout,
  at,
  check,
  describe,
  expectCookieAttributes,
  expectError,
  expectNoStore,
  expectStatus,
  fail,
  isDateTime,
  isUuid,
  jsonBody,
  missingAttrs,
  missingLatencyAttrs,
  pickAttrs,
  setCookieFrom,
} from "./assertions.mjs";

const REGISTRATION_CODE = /^[2-9A-HJKMNP-Z]{8}$/;
const BOOTSTRAP_COOKIE = "MUJOONG_DEVICE_BOOTSTRAP";
const DEVICE_COOKIE = "MUJOONG_DEVICE";
const SESSION_COOKIE = "MUJOONG_SESSION";
/** CLOSE_TRANSPORT 뒤 일부러 보내는 0x01 프레임 수(R3-8 · SD-17 확인). */
export const AFTER_CLOSE_FRAMES = 10;

const seconds = (ms) => Math.round(ms / 1000);

/** 단계 목록. budget은 단계 전체 기한(ms)을 기한 표에서 계산한다. */
export const STEPS = Object.freeze([
  {
    id: "C1",
    title: "D0 — 기기 확인 401 · 코드 발급 201 · bootstrap 쿠키",
    budget: (d) => 3 * d.request,
    run: stepD0,
  },
  {
    id: "C2",
    title: "시험 로그인 · CSRF · 홈 NO_DEVICE",
    budget: (d) => 3 * d.request,
    run: stepLogin,
  },
  {
    id: "C3",
    title: "05a — CSRF · Origin 거부 뒤 등록 201",
    budget: (d) => 3 * d.request,
    run: stepRegister,
  },
  {
    id: "C4",
    title: "REGISTERED → claim 200 · 기기 쿠키",
    budget: (d) => d.registered + 3 * d.request,
    run: stepClaim,
  },
  {
    id: "C5",
    title: "켜기 — Origin 403 · HELLO_OK · HEARTBEAT",
    budget: (d) => d.hello + 3 * d.request,
    run: stepConnect,
  },
  {
    id: "C6",
    title: "04 안정(STABLE · canStart)",
    budget: (d) => d.stable + d.request,
    run: stepStable,
  },
  { id: "C7", title: "08 자유대화 시작 202", budget: (d) => 2 * d.request, run: stepStart },
  {
    id: "C8",
    title: "START_SESSION → ACK · DEVICE_READY → 시작 안내 · 참여 응답 → ACTIVE",
    budget: (d) => d.active + 2 * d.request,
    run: stepActive,
  },
  {
    id: "C9",
    title: "음성 1턴(정상) — 무변환 중계 · 게이트 통과 · 12 items",
    budget: (d) => d.turn + 2 * d.request,
    run: stepNormalTurn,
  },
  {
    id: "C10",
    title: "게이트 경유(금지 문장) — 출력 0 · WAITING_FILLER 1",
    budget: (d) => d.turn + d.settle + 3 * d.request,
    run: stepForbiddenTurn,
  },
  {
    id: "C11",
    title: "P1 끝내기 — STOP · CLOSING 1회 · CLOSE_TRANSPORT 뒤 중계 없음",
    budget: (d) => d.ended + 2 * d.request,
    run: stepEnd,
  },
  {
    id: "C12",
    title: "ENDED(confirmed) · STANDBY · LATENCY_TURN 키",
    budget: (d) => d.ended + 2 * d.request,
    run: stepEnded,
  },
]);

export const STEP_IDS = Object.freeze(STEPS.map((step) => step.id));

// ------------------------------------------------------------------------------------------------ 공통 대기

/**
 * probe가 {done: true, value}를 돌려줄 때까지 기다린다. 기한을 넘으면 StepTimeout(마지막 관찰 note를 붙인다).
 * HTTP 연결 오류 · 503은 기한 안에서 다시 시도한다.
 */
export async function waitUntil(ctx, probe, { timeoutMs, intervalMs = 500, what }) {
  const limit = ctx.limit(timeoutMs);
  const deadline = Date.now() + limit;
  let note = "관찰 없음";
  for (;;) {
    ctx.throwIfAborted();
    try {
      const result = await probe(Math.max(1, deadline - Date.now()));
      if (result.done) {
        return result.value;
      }
      note = result.note ?? note;
    } catch (error) {
      if (error instanceof StepFailure) {
        throw error;
      }
      note =
        error.name === "HttpTimeoutError"
          ? "요청 제한 시간 초과"
          : `${error.name}(${error.code ?? ""})`;
    }
    const left = deadline - Date.now();
    if (left <= 0) {
      throw new StepTimeout(`${what} (마지막: ${note})`, limit);
    }
    await ctx.sleep(Math.min(intervalMs, left));
  }
}

/** 시뮬레이터 · 기록 대기의 기한 오류를 StepTimeout으로 바꾼다. */
async function bounded(ctx, promise) {
  try {
    return await promise;
  } catch (error) {
    if (error && (error.name === "SimulatorTimeoutError" || error.name === "TailTimeoutError")) {
      throw new StepTimeout(error.message, error.timeoutMs);
    }
    throw error;
  }
}

function requestTimeout(ctx) {
  return ctx.limit(ctx.deadlines.request);
}

/** 12 진행 보기(GET /api/sessions/{id}?sinceSeq=0). */
async function liveView(ctx, timeoutMs) {
  const res = await ctx.parent.get(`/api/sessions/${ctx.state.sessionId}?sinceSeq=0`, {
    timeoutMs: Math.min(timeoutMs ?? requestTimeout(ctx), requestTimeout(ctx)),
  });
  if (res.status !== 200) {
    return { res, view: null };
  }
  return { res, view: res.json() };
}

function itemTexts(view, speaker) {
  return (view?.items ?? []).filter((item) => item.speaker === speaker).map((item) => item);
}

// ------------------------------------------------------------------------------------------------ C1 ~ C4

async function stepD0(ctx) {
  const { device, deadlines } = ctx;
  const me = await device.get("/api/device/me", { timeoutMs: requestTimeout(ctx) });
  expectError(me, 401, "DEVICE_NOT_REGISTERED", "GET /api/device/me(쿠키 없음)");
  const status = await device.get("/api/device/bootstrap/status", {
    timeoutMs: requestTimeout(ctx),
  });
  expectError(status, 401, "BOOTSTRAP_NOT_FOUND", "GET /api/device/bootstrap/status(쿠키 없음)");

  const issued = await device.post("/api/device/bootstrap", undefined, {
    timeoutMs: requestTimeout(ctx),
  });
  expectStatus(issued, 201, "POST /api/device/bootstrap");
  const body = jsonBody(issued, "POST /api/device/bootstrap");
  check(
    REGISTRATION_CODE.test(body.code ?? ""),
    "POST /api/device/bootstrap: code가 발급 알파벳 8자가 아니다",
  );
  check(isDateTime(body.expiresAt), "POST /api/device/bootstrap: expiresAt이 DateTime이 아니다");
  ctx.secrets.addCode(body.code);
  const expiresIn = Date.parse(body.expiresAt) - Date.now();
  check(
    Math.abs(expiresIn - deadlines.codeTtl) <= 120_000,
    `POST /api/device/bootstrap: expiresAt이 지금 + code-ttl(${seconds(deadlines.codeTtl)}초)과 2분 넘게 다르다`,
  );
  const cookie = setCookieFrom(issued, BOOTSTRAP_COOKIE, "POST /api/device/bootstrap");
  ctx.secrets.addToken("bootstrap 비밀", cookie.value);
  expectCookieAttributes(
    cookie,
    { sameSite: "Strict", path: "/api/device", maxAgeSeconds: seconds(deadlines.codeTtl) },
    "POST /api/device/bootstrap",
  );
  check(
    ctx.deviceJar.get(BOOTSTRAP_COOKIE) !== null,
    "bootstrap 쿠키가 기기 쿠키 저장소에 들어가지 않았다",
  );
  ctx.state.code = body.code;
}

async function stepLogin(ctx) {
  const { parent, deadlines } = ctx;
  const login = await parent.post("/api/dev/login", undefined, { timeoutMs: requestTimeout(ctx) });
  expectStatus(login, 200, "POST /api/dev/login");
  const body = jsonBody(login, "POST /api/dev/login");
  check(isUuid(body.guardianId), "POST /api/dev/login: guardianId가 UUID가 아니다");
  const cookie = setCookieFrom(login, SESSION_COOKIE, "POST /api/dev/login");
  ctx.secrets.addToken("세션 쿠키", cookie.value);
  ctx.secrets.addDecodedSessionId(cookie.value);
  expectCookieAttributes(
    cookie,
    { sameSite: "Lax", path: "/", maxAgeSeconds: seconds(deadlines.sessionCookieMaxAge) },
    "POST /api/dev/login",
  );

  const csrf = await parent.get("/api/session/csrf", { timeoutMs: requestTimeout(ctx) });
  expectStatus(csrf, 200, "GET /api/session/csrf");
  expectNoStore(csrf, "GET /api/session/csrf");
  const csrfBody = jsonBody(csrf, "GET /api/session/csrf");
  check(
    typeof csrfBody.token === "string" && csrfBody.token.length >= 16,
    "GET /api/session/csrf: token이 없다",
  );
  check(
    csrfBody.headerName === "X-CSRF-TOKEN",
    "GET /api/session/csrf: headerName이 X-CSRF-TOKEN이 아니다",
  );
  ctx.secrets.addToken("CSRF 토큰", csrfBody.token);
  ctx.state.csrf = csrfBody.token;

  const home = await parent.get("/api/home", { timeoutMs: requestTimeout(ctx) });
  expectStatus(home, 200, "GET /api/home");
  expectNoStore(home, "GET /api/home");
  const view = jsonBody(home, "GET /api/home");
  check(
    at(view, "device.registration.status") === "NONE",
    `GET /api/home: 기기 등록 상태가 NONE이 아니다(${at(view, "device.registration.status")})`,
  );
  check(
    at(view, "device.verdict.canStart") === false,
    "GET /api/home: device.verdict.canStart가 false가 아니다",
  );
  check(
    at(view, "device.verdict.reasonCode") === "NO_DEVICE",
    `GET /api/home: verdict.reasonCode가 NO_DEVICE가 아니다(${at(view, "device.verdict.reasonCode")})`,
  );
  check(at(view, "lock.locked") === true, "GET /api/home: lock.locked가 true가 아니다");
  check(view.currentSession === null, "GET /api/home: currentSession이 null이 아니다(U1 모양)");
}

function csrfHeaders(ctx) {
  return { "X-CSRF-TOKEN": ctx.state.csrf };
}

async function stepRegister(ctx) {
  const { parent, opts } = ctx;
  const code = ctx.state.code;
  const noToken = await parent.post(
    "/api/device/register",
    { code },
    { timeoutMs: requestTimeout(ctx) },
  );
  expectError(noToken, 403, "CSRF_TOKEN_INVALID", "POST /api/device/register(X-CSRF-TOKEN 없음)");
  const evil = await parent.post(
    "/api/device/register",
    { code },
    {
      headers: csrfHeaders(ctx),
      origin: opts.evilOrigin,
      timeoutMs: requestTimeout(ctx),
    },
  );
  expectError(
    evil,
    403,
    "CSRF_TOKEN_INVALID",
    `POST /api/device/register(Origin ${opts.evilOrigin})`,
  );
  const registered = await parent.post(
    "/api/device/register",
    { code },
    { headers: csrfHeaders(ctx), timeoutMs: requestTimeout(ctx) },
  );
  expectStatus(registered, 201, "POST /api/device/register");
  const body = jsonBody(registered, "POST /api/device/register");
  check(isUuid(body.deviceId), "POST /api/device/register: deviceId가 UUID가 아니다");
  check(
    body.codePrefix === code.slice(0, 4),
    "POST /api/device/register: codePrefix가 코드 앞 4자가 아니다",
  );
  check(
    isDateTime(body.registeredAt),
    "POST /api/device/register: registeredAt이 DateTime이 아니다",
  );
  ctx.state.deviceId = body.deviceId;
}

async function stepClaim(ctx) {
  const { device, deadlines } = ctx;
  await waitUntil(
    ctx,
    async (left) => {
      const res = await device.get("/api/device/bootstrap/status", {
        timeoutMs: Math.min(left, requestTimeout(ctx)),
      });
      if (res.status !== 200) {
        return { done: false, note: describe(res) };
      }
      const status = res.json()?.status;
      return { done: status === "REGISTERED", value: status, note: `status ${status}` };
    },
    { timeoutMs: deadlines.registered, intervalMs: 500, what: "bootstrap status REGISTERED 대기" },
  );
  const claim = await device.post("/api/device/bootstrap/claim", undefined, {
    timeoutMs: requestTimeout(ctx),
  });
  expectStatus(claim, 200, "POST /api/device/bootstrap/claim");
  const body = jsonBody(claim, "POST /api/device/bootstrap/claim");
  check(
    body.deviceId === ctx.state.deviceId,
    "POST /api/device/bootstrap/claim: deviceId가 등록한 기기와 다르다",
  );
  const deviceCookie = setCookieFrom(claim, DEVICE_COOKIE, "POST /api/device/bootstrap/claim");
  ctx.secrets.addToken("기기 토큰", deviceCookie.value);
  expectCookieAttributes(
    deviceCookie,
    { sameSite: "Strict", path: "/", maxAgeSeconds: seconds(deadlines.deviceCookieMaxAge) },
    "POST /api/device/bootstrap/claim",
  );
  const cleared = setCookieFrom(claim, BOOTSTRAP_COOKIE, "POST /api/device/bootstrap/claim");
  expectCookieAttributes(
    cleared,
    { sameSite: "Strict", path: "/api/device", deleted: true },
    "POST /api/device/bootstrap/claim(bootstrap 쿠키 삭제)",
  );
  check(ctx.deviceJar.get(BOOTSTRAP_COOKIE) === null, "claim 뒤에도 bootstrap 쿠키가 남아 있다");

  const me = await device.get("/api/device/me", { timeoutMs: requestTimeout(ctx) });
  expectStatus(me, 200, "GET /api/device/me(기기 쿠키)");
  expectNoStore(me, "GET /api/device/me");
  const meBody = jsonBody(me, "GET /api/device/me");
  check(meBody.deviceId === ctx.state.deviceId, "GET /api/device/me: deviceId가 다르다");
  const refreshed = setCookieFrom(me, DEVICE_COOKIE, "GET /api/device/me");
  ctx.secrets.addToken("기기 토큰(갱신)", refreshed.value);
  expectCookieAttributes(
    refreshed,
    { sameSite: "Strict", path: "/", maxAgeSeconds: seconds(deadlines.deviceCookieMaxAge) },
    "GET /api/device/me",
  );
}

// ------------------------------------------------------------------------------------------------ C5 ~ C8

async function stepConnect(ctx) {
  const { opts, deadlines } = ctx;
  const cookie = ctx.deviceJar.cookieHeader(opts.wsUrl);
  check(
    cookie !== null && cookie.includes(`${DEVICE_COOKIE}=`),
    "기기 쿠키 저장소에 MUJOONG_DEVICE가 없다",
  );
  const refused = await probeUpgrade({
    url: opts.wsUrl,
    origin: opts.evilOrigin,
    cookie,
    ca: ctx.ca,
    timeoutMs: requestTimeout(ctx),
  });
  check(
    refused.status === "refused" && refused.httpStatus === 403,
    `WSS 업그레이드(Origin ${opts.evilOrigin}): 기대 403, 실제 ${refused.status === "refused" ? refused.httpStatus : refused.status}`,
  );

  const sim = new DeviceSimulator({
    url: opts.wsUrl,
    origin: opts.origin,
    cookie,
    ca: ctx.ca,
    mediaClient: ctx.media,
    auto: { voiceSessionClosed: false },
  });
  ctx.state.sim = sim;
  ctx.closers.push(() => sim.terminate());
  try {
    await sim.open(requestTimeout(ctx));
  } catch (error) {
    fail(
      `WSS 업그레이드(허용 Origin · 기기 쿠키) 실패: ${error.status ? `HTTP ${error.status}` : (error.code ?? error.name)}`,
    );
  }
  const helloOk = await bounded(ctx, sim.hello(ctx.limit(deadlines.hello)));
  check(helloOk.audio === "pcm16le/24000/1", "HELLO_OK.audio가 pcm16le/24000/1이 아니다");
  check(
    helloOk.heartbeatIntervalMs === deadlines.heartbeatInterval,
    `HELLO_OK.heartbeatIntervalMs가 device.heartbeat-interval(${deadlines.heartbeatInterval})과 다르다(${helloOk.heartbeatIntervalMs})`,
  );
  check(
    helloOk.session === null || helloOk.session === undefined,
    "HELLO_OK.session이 null이 아니다(U1은 이어가기 없음)",
  );
  ctx.state.helloOkAt = Date.now();
  // HELLO_OK 직후 HEARTBEAT(sentAt 포함, J143) ↔ HEARTBEAT_ACK(같은 hbSeq)
  await ensureRtt(ctx, deadlines.hello);
  check(
    sim.sent.some((sent) => sent.type === "HEARTBEAT" && isDateTime(sent.sentAt)),
    "HEARTBEAT 봉투에 sentAt이 없다(J143)",
  );
}

/** 왕복을 한 번 재고, 그 값을 실은 HEARTBEAT를 한 번 더 보낸다(LATENCY_TURN rttMs · clockOffsetMs, R3-4). */
async function ensureRtt(ctx, timeoutMs) {
  const sim = ctx.state.sim;
  for (let round = 0; round < 2; round += 1) {
    if (round === 1 && sim.lastRttMs !== null) {
      // 왕복을 잰 뒤 그 값을 실은 HEARTBEAT를 보냈으면 끝
      if (sim.heartbeatsWithRtt > 0) {
        return;
      }
    }
    const from = sim.received.length;
    const hbSeq = sim.heartbeatNow();
    check(hbSeq !== null, "HEARTBEAT를 보낼 연결이 없다");
    const ack = await bounded(
      ctx,
      sim.waitFor(
        (entry) =>
          entry.envelope.type === "HEARTBEAT_ACK" && entry.envelope.payload.hbSeq === hbSeq,
        {
          timeoutMs: ctx.limit(timeoutMs),
          what: `HEARTBEAT_ACK(hbSeq ${hbSeq})`,
          from,
        },
      ),
    );
    check(
      isDateTime(ack.envelope.payload.serverTime),
      "HEARTBEAT_ACK.serverTime이 DateTime이 아니다",
    );
  }
  check(
    sim.lastRttMs !== null && sim.heartbeatsWithRtt > 0,
    "HEARTBEAT 왕복(lastRttMs)을 재지 못했다",
  );
}

async function stepStable(ctx) {
  const { parent, deadlines } = ctx;
  const view = await waitUntil(
    ctx,
    async (left) => {
      const res = await parent.get("/api/home", { timeoutMs: Math.min(left, requestTimeout(ctx)) });
      if (res.status !== 200) {
        return { done: false, note: describe(res) };
      }
      const home = res.json();
      const done =
        at(home, "device.network.state") === "STABLE" &&
        at(home, "device.verdict.canStart") === true &&
        at(home, "lock.locked") === false;
      return {
        done,
        value: home,
        note: `network ${at(home, "device.network.state")} · canStart ${at(home, "device.verdict.canStart")} · locked ${at(home, "lock.locked")}`,
      };
    },
    { timeoutMs: deadlines.stable, intervalMs: 500, what: "04 기기 STABLE · canStart 대기" },
  );
  check(
    at(view, "device.registration.status") === "REGISTERED",
    "GET /api/home: 기기 등록 상태가 REGISTERED가 아니다",
  );
  check(
    at(view, "device.registration.codePrefix") === ctx.state.code.slice(0, 4),
    "GET /api/home: codePrefix가 다르다",
  );
  ctx.measurements.stableReachedMs = Date.now() - ctx.state.helloOkAt;
}

async function stepStart(ctx) {
  const { parent } = ctx;
  ctx.provider.poll();
  ctx.state.providerMark = ctx.provider.entries.length;
  ctx.state.simMarkBeforeStart = ctx.state.sim.snapshot();
  const idempotencyKey = randomUUID();
  const res = await parent.post(
    "/api/activities/free",
    { idempotencyKey, context: { situationType: "FRIEND", sourceType: "SEEN" } },
    { headers: csrfHeaders(ctx), timeoutMs: requestTimeout(ctx) },
  );
  expectStatus(res, 202, "POST /api/activities/free");
  const body = jsonBody(res, "POST /api/activities/free");
  check(isUuid(body.sessionId), "POST /api/activities/free: sessionId가 UUID가 아니다");
  check(
    body.status === "REQUESTED",
    `POST /api/activities/free: status가 REQUESTED가 아니다(${body.status})`,
  );
  ctx.state.sessionId = body.sessionId;
  ctx.state.startRequestedAt = Date.now();
}

async function stepActive(ctx) {
  const { deadlines } = ctx;
  const sim = ctx.state.sim;
  const sessionId = ctx.state.sessionId;
  const from = ctx.state.simMarkBeforeStart.receivedIndex;
  const start = await bounded(
    ctx,
    sim.waitFor((e) => e.envelope.type === "START_SESSION" && e.envelope.sessionId === sessionId, {
      timeoutMs: ctx.limit(deadlines.active),
      what: "START_SESSION",
      from,
    }),
  );
  const codes = (start.envelope.payload.approvedAudio ?? []).map((item) => item.contentCode).sort();
  check(
    codes.includes("CLOSING") && codes.includes("WAITING_FILLER"),
    `START_SESSION.approvedAudio에 CLOSING · WAITING_FILLER가 없다(${codes.join(",")})`,
  );
  ctx.state.approvedAudio = start.envelope.payload.approvedAudio;
  await bounded(
    ctx,
    sim.waitForEvent((e) => e.kind === "ack-sent" && e.ackOf === start.envelope.opId, {
      timeoutMs: ctx.limit(deadlines.active),
      what: "START_SESSION ACK 송신",
    }),
  );
  const ready = await bounded(
    ctx,
    sim.waitForEvent((e) => e.kind === "device-ready-sent" && e.sessionId === sessionId, {
      timeoutMs: ctx.limit(deadlines.active),
      what: "승인 음성 미리 받기 · DEVICE_READY 송신",
    }),
  );
  check(ready.prefetch === "OK", "승인 음성 미리 받기가 OK가 아니다(/media/approved/*.wav)");

  // 가짜 공급자: 이 세션의 연결 · session.start(키가 비면 Authorization 헤더 없음, U11 BR12.2) · session.started
  const mark = ctx.state.providerMark;
  const connect = await bounded(
    ctx,
    ctx.provider.waitFor((e) => e.ev === "connect", {
      timeoutMs: ctx.limit(deadlines.active),
      what: "가짜 공급자 연결",
      from: mark,
    }),
  );
  check(connect.bearer === false, "검증 환경은 GPT-Live 키를 두지 않는데 공급자 연결에 Authorization Bearer가 실렸다(U11 BR12.2)");
  ctx.state.providerConn = connect.conn;
  const started = await bounded(
    ctx,
    ctx.provider.waitFor((e) => e.ev === "session.start" && e.conn === connect.conn, {
      timeoutMs: ctx.limit(deadlines.active),
      what: "가짜 공급자 session.start 수신",
      from: mark,
    }),
  );
  const allowedKeys = ["session", "type"];
  check(
    started.keys.every((key) => allowedKeys.includes(key)),
    `session.start에 허용 목록 밖 필드가 있다(SD-20): ${started.keys.filter((key) => !allowedKeys.includes(key)).join(",")}`,
  );
  const allowedSessionKeys = ["audio", "instructions", "model"];
  check(
    started.sessionKeys.every((key) => allowedSessionKeys.includes(key)),
    "session.start.session에 model · instructions · audio 밖 값이 있다(SD-20, 아이 정보는 지시문 문장 안에만)",
  );

  await bounded(
    ctx,
    sim.waitFor(
      (e) => e.envelope.type === "SESSION_STATE" && e.envelope.payload.view === "LISTENING",
      {
        timeoutMs: ctx.limit(deadlines.active),
        what: "SESSION_STATE LISTENING(ACTIVE)",
        from,
      },
    ),
  );
  await stepJoin(ctx);
  ctx.measurements.activeReachedMs = Date.now() - ctx.state.startRequestedAt;
  const view = await waitUntil(
    ctx,
    async (left) => {
      const { res, view: live } = await liveView(ctx, left);
      if (live === null) {
        return { done: false, note: describe(res) };
      }
      return { done: live.status === "ACTIVE", value: live, note: `status ${live.status}` };
    },
    { timeoutMs: deadlines.active, intervalMs: 500, what: "12 진행 보기 ACTIVE" },
  );
  check(isDateTime(view.startedAt), "12 진행 보기: startedAt이 없다");
  check(
    view.sessionId === sessionId && view.activityType === "FREE_TALK",
    "12 진행 보기: sessionId · activityType이 다르다",
  );
  // U4b: 참여 전의 AI 줄(승인된 시작 안내)은 뒤 턴의 AI 말 수에서 뺀다
  ctx.state.preTurnAiSeqs = new Set(itemTexts(view, "AI").map((item) => item.seq));
}

/**
 * U4b 참여 확인(FR2.1 · BR2.1 · U4b-J3): DEVICE_READY 뒤 WAITING_CHILD에서 시작 안내(INTRO_FREE_TALK, 시드 003의 승인 문구)를 재생하고, 재생
 * 완료 뒤 참여 창에서 아이가 "응"이라고 답하면 ACTIVE가 되고 Realtime이 첫 응답 지시(session.instructions.append)를 한 번 보낸다 —
 * 응답 요청 type(response.create)은 없다(설계 개정 A1). 지시는 delegation_id를 null로 싣는다(키가 있어야 한다). 가짜 공급자 턴 계획의 첫
 * 턴은 join이다(--turns join,normal,forbidden). 아이 "응"은 입력 전사 조각뿐이고 서버가 조용해진 뒤 확정한다(설계 개정 A7).
 */
async function stepJoin(ctx) {
  const { deadlines } = ctx;
  const sim = ctx.state.sim;
  const sessionId = ctx.state.sessionId;
  const before = ctx.state.simMarkBeforeStart;
  const intro = await bounded(
    ctx,
    sim.waitFor(
      (e) =>
        e.envelope.type === "PLAY_APPROVED_CONTENT" &&
        e.envelope.payload.contentCode === "INTRO_FREE_TALK" &&
        e.envelope.sessionId === sessionId,
      {
        timeoutMs: ctx.limit(deadlines.active),
        what: "PLAY_APPROVED_CONTENT(INTRO_FREE_TALK) — 시드 003 승인 문구",
        from: before.receivedIndex,
      },
    ),
  );
  const introId = intro.envelope.payload.playbackId;
  await bounded(
    ctx,
    sim.waitForEvent(
      (e) => e.kind === "mark-sent" && e.playbackId === introId && e.status === "COMPLETED",
      {
        timeoutMs: ctx.limit(deadlines.active),
        what: "INTRO_FREE_TALK PLAYBACK_MARK COMPLETED 송신",
        from: before.eventIndex,
      },
    ),
  );
  const listening = await framesPerTurn(ctx);
  check(
    listening.turns?.[0] === "join",
    "가짜 공급자 턴 계획이 join으로 시작하지 않는다(--turns join,normal,forbidden 필요)",
  );
  const sent = await sim.sendInputFrames(listening.framesPerTurn);
  const turn = await bounded(
    ctx,
    ctx.provider.waitFor(
      (e) => e.ev === "turn" && e.conn === ctx.state.providerConn && e.turn === 1,
      {
        timeoutMs: ctx.limit(deadlines.turn),
        what: "가짜 공급자 참여 응답 턴 시작",
        from: ctx.state.providerMark,
      },
    ),
  );
  check(turn.kind === "join", `가짜 공급자 턴 1 종류가 join이 아니다(${turn.kind})`);
  check(
    turn.frames === sent.frames && turn.inputSha256 === sent.sha256,
    "참여 응답 턴: 공급자가 받은 입력이 보낸 프레임과 다르다(무변환 중계 실패)",
  );
  check(
    turn.childTextSha256 === textSha256(TURN_SCRIPTS.join.childText) && turn.aiTextSha256 === null,
    "참여 응답 턴: 가짜 공급자 문장이 scenarios.mjs와 다르다(같은 판의 tools를 써야 한다)",
  );
  const append = await bounded(
    ctx,
    ctx.provider.waitFor(
      (e) => e.ev === "session.instructions.append" && e.conn === ctx.state.providerConn,
      {
        timeoutMs: ctx.limit(deadlines.turn),
        what: "참여 확인 뒤 첫 응답 지시(session.instructions.append) 수신",
        from: ctx.state.providerMark,
      },
    ),
  );
  check(
    append.delegationIdPresent === true && append.delegationIdNull === true,
    "첫 응답 지시: delegation_id 키가 null로 실려 있어야 한다(공급자는 키가 없으면 거절한다)",
  );
  check(
    !ctx.provider.entries.some((e) => e.ev === "response.create" && e.conn === ctx.state.providerConn),
    "응답 요청 type(response.create)은 보내지 않는다(client 위임 세션은 거절한다)",
  );
}

// ------------------------------------------------------------------------------------------------ C9 · C10 턴

async function framesPerTurn(ctx) {
  const listening = await bounded(
    ctx,
    ctx.provider.waitFor((e) => e.ev === "listening", {
      timeoutMs: ctx.limit(ctx.deadlines.request),
      what: "가짜 공급자 listening 기록",
    }),
  );
  check(
    Number.isInteger(listening.framesPerTurn) && listening.framesPerTurn > 0,
    "가짜 공급자 기록에 framesPerTurn이 없다",
  );
  return listening;
}

async function runTurn(ctx, turnNumber, kind) {
  const { deadlines } = ctx;
  const sim = ctx.state.sim;
  const listening = await framesPerTurn(ctx);
  check(
    listening.turns?.[turnNumber - 1] === kind,
    `가짜 공급자 턴 ${turnNumber}이 ${kind}가 아니다(--turns join,normal,forbidden 필요)`,
  );
  const snapshot = sim.snapshot();
  const sent = await sim.sendInputFrames(listening.framesPerTurn);
  const turn = await bounded(
    ctx,
    ctx.provider.waitFor(
      (e) => e.ev === "turn" && e.conn === ctx.state.providerConn && e.turn === turnNumber,
      {
        timeoutMs: ctx.limit(deadlines.turn),
        what: `가짜 공급자 턴 ${turnNumber} 시작`,
        from: ctx.state.providerMark,
      },
    ),
  );
  check(turn.kind === kind, `가짜 공급자 턴 ${turnNumber} 종류가 ${kind}가 아니다(${turn.kind})`);
  // J131 · SEC-07: 무변환 중계 — 공급자가 받은 이 턴 입력 = 시뮬레이터가 보낸 프레임 본문
  check(
    turn.frames === sent.frames && turn.inputBytes === sent.bytes,
    `턴 ${turnNumber}: 공급자가 받은 입력 프레임 수 · 바이트가 보낸 것과 다르다(${turn.frames}/${sent.frames})`,
  );
  check(
    turn.inputSha256 === sent.sha256,
    `턴 ${turnNumber}: 공급자가 받은 입력 음성 해시가 보낸 해시와 다르다(무변환 중계 실패)`,
  );
  const script = TURN_SCRIPTS[kind];
  check(
    turn.childTextSha256 === textSha256(script.childText) &&
      turn.aiTextSha256 === textSha256(script.aiText),
    `턴 ${turnNumber}: 가짜 공급자 문장이 scenarios.mjs와 다르다(같은 판의 tools를 써야 한다)`,
  );
  return { snapshot, sent, turn, script };
}

async function stepNormalTurn(ctx) {
  const { deadlines } = ctx;
  const sim = ctx.state.sim;
  const sessionId = ctx.state.sessionId;
  await ensureRtt(ctx, deadlines.hello);
  // U4b: 턴 1은 참여 응답(join)이라 정상 턴은 2번째다
  const { snapshot, turn, script } = await runTurn(ctx, 2, "normal");
  ctx.secrets.addText(script.childText);
  ctx.secrets.addText(script.aiText);
  const begin = await bounded(
    ctx,
    sim.waitFor((e) => e.envelope.type === "OUTPUT_BEGIN" && e.envelope.sessionId === sessionId, {
      timeoutMs: ctx.limit(deadlines.turn),
      what: "OUTPUT_BEGIN(게이트 통과)",
      from: snapshot.receivedIndex,
    }),
  );
  check(begin.envelope.payload.source === "GPT_LIVE", "OUTPUT_BEGIN.source가 GPT_LIVE가 아니다");
  const playbackId = begin.envelope.payload.playbackId;
  await bounded(
    ctx,
    sim.waitFor(
      (e) => e.envelope.type === "OUTPUT_END" && e.envelope.payload.playbackId === playbackId,
      {
        timeoutMs: ctx.limit(deadlines.turn),
        what: "OUTPUT_END",
        from: snapshot.receivedIndex,
      },
    ),
  );
  await bounded(
    ctx,
    sim.waitForEvent(
      (e) => e.kind === "mark-sent" && e.playbackId === playbackId && e.status === "COMPLETED",
      {
        timeoutMs: ctx.limit(deadlines.turn),
        what: "PLAYBACK_MARK COMPLETED 송신",
        from: snapshot.eventIndex,
      },
    ),
  );
  const playback = sim.playbacks.get(playbackId);
  check(
    playback.bytes === turn.outputBytes,
    `0x02 출력 바이트가 공급자가 보낸 것과 다르다(${playback.bytes}/${turn.outputBytes})`,
  );
  check(
    playback.hash.hex() === turn.outputSha256,
    "기기가 받은 0x02 해시가 공급자가 보낸 출력 해시와 다르다(게이트 뒤 무변환 실패, SEC-07)",
  );
  check(sim.since(snapshot).orphanOutputBytes === 0, "OUTPUT_BEGIN 없이 온 0x02가 있다");
  ctx.state.turn1PlaybackId = playbackId;
  ctx.measurements.turn1 = {
    inputFrames: turn.frames,
    inputSha256Match: true,
    outputBytes: playback.bytes,
    outputSha256Match: true,
  };

  const view = await waitUntil(
    ctx,
    async (left) => {
      const { res, view: live } = await liveView(ctx, left);
      if (live === null) {
        return { done: false, note: describe(res) };
      }
      const child = itemTexts(live, "CHILD").some(
        (item) => item.text === script.childText && item.final === true,
      );
      const ai = itemTexts(live, "AI").some(
        (item) => item.text === script.aiText && item.final === true,
      );
      return {
        done: child && ai,
        value: live,
        note: `items ${live.items?.length ?? 0}(아이 ${child} · AI ${ai})`,
      };
    },
    { timeoutMs: deadlines.turn, intervalMs: 500, what: "12 items에 아이 전사 · 승인된 AI 말" },
  );
  check(view.status === "ACTIVE", `12 진행 보기: 턴 뒤 상태가 ACTIVE가 아니다(${view.status})`);
}

async function stepForbiddenTurn(ctx) {
  const { deadlines } = ctx;
  const sim = ctx.state.sim;
  const sessionId = ctx.state.sessionId;
  const { snapshot, turn, script } = await runTurn(ctx, 3, "forbidden");
  ctx.secrets.addText(script.childText);
  ctx.secrets.addText(script.aiText);
  check(
    script.aiText.includes(FORBIDDEN_PATTERN),
    "금지 턴 문장에 시드 사전 패턴이 없다(scenarios.mjs)",
  );
  await bounded(
    ctx,
    ctx.provider.waitFor(
      (e) => e.ev === "turn-emitted" && e.conn === ctx.state.providerConn && e.turn === 3,
      {
        timeoutMs: ctx.limit(deadlines.turn),
        what: "가짜 공급자 금지 턴 송신 완료",
        from: ctx.state.providerMark,
      },
    ),
  );
  const filler = await bounded(
    ctx,
    sim.waitFor(
      (e) =>
        e.envelope.type === "PLAY_APPROVED_CONTENT" &&
        e.envelope.payload.contentCode === "WAITING_FILLER" &&
        e.envelope.sessionId === sessionId,
      {
        timeoutMs: ctx.limit(deadlines.turn),
        what: "PLAY_APPROVED_CONTENT(WAITING_FILLER)",
        from: snapshot.receivedIndex,
      },
    ),
  );
  const fillerId = filler.envelope.payload.playbackId;
  await bounded(
    ctx,
    sim.waitForEvent(
      (e) => e.kind === "mark-sent" && e.playbackId === fillerId && e.status === "COMPLETED",
      {
        timeoutMs: ctx.limit(deadlines.turn),
        what: "WAITING_FILLER PLAYBACK_MARK COMPLETED 송신",
        from: snapshot.eventIndex,
      },
    ),
  );
  // 늦게 오는 출력이 없는지 조용 시간 + 1초 더 본다
  await ctx.sleep(ctx.limit(deadlines.settle));
  const delta = sim.since(snapshot);
  check(
    delta.outputBegin === 0,
    `금지 턴 동안 OUTPUT_BEGIN이 ${delta.outputBegin}건 왔다(게이트 우회, NFR1.1 · SD-17)`,
  );
  check(
    delta.outputBytes === 0,
    `금지 턴 동안 0x02가 ${delta.outputBytes}바이트 왔다(게이트 우회)`,
  );
  check(
    (delta.approvedPlays.WAITING_FILLER ?? 0) === 1,
    `WAITING_FILLER가 정확히 1건이 아니다(${delta.approvedPlays.WAITING_FILLER ?? 0})`,
  );
  ctx.state.fillerPlaybackId = fillerId;
  ctx.measurements.turn2 = {
    inputFrames: turn.frames,
    outputBeginReceived: 0,
    outputBytesReceived: 0,
    waitingFiller: 1,
  };

  const view = await waitUntil(
    ctx,
    async (left) => {
      const { res, view: live } = await liveView(ctx, left);
      if (live === null) {
        return { done: false, note: describe(res) };
      }
      const child = itemTexts(live, "CHILD").some(
        (item) => item.text === script.childText && item.final === true,
      );
      return { done: child, value: live, note: `items ${live.items?.length ?? 0}` };
    },
    { timeoutMs: deadlines.turn, intervalMs: 500, what: "12 items에 두 번째 아이 전사" },
  );
  check(
    !(view.items ?? []).some(
      (item) => item.text.includes(FORBIDDEN_PATTERN) || item.text === script.aiText,
    ),
    "12 items에 금지 문장이 있다(BR7.5)",
  );
  // U4b: 참여 전의 AI 줄(승인된 시작 안내)은 세지 않는다
  const turnAi = itemTexts(view, "AI").filter((item) => !ctx.state.preTurnAiSeqs?.has(item.seq));
  check(
    turnAi.length === 1,
    `12 items의 AI 말이 1건(승인된 첫 턴)이 아니다(${turnAi.length})`,
  );

  // realtime_transcript: 폐기된 AI 행은 approved_for_display false, 통과한 행은 true(BR7.4 · BR7.5 · A5). 문장 원문 대신 해시로 묻는다.
  const forbiddenHash = sha256Hex(Buffer.from(script.aiText, "utf8"));
  const passedHash = sha256Hex(Buffer.from(TURN_SCRIPTS.normal.aiText, "utf8"));
  const rows = await waitUntil(
    ctx,
    async (left) => {
      const result = await ctx.sql(
        "realtime",
        `SELECT approved_for_display, encode(sha256(convert_to(text, 'UTF8')), 'hex') FROM realtime.realtime_transcript ` +
          `WHERE session_id = '${sessionId}' AND speaker = 'AI' AND assembly_status = 'FINAL' ORDER BY sequence_no;`,
        { timeoutMs: Math.min(left, requestTimeout(ctx)) },
      );
      const found = result.some((row) => row[1] === forbiddenHash);
      return { done: found, value: result, note: `AI FINAL 행 ${result.length}` };
    },
    { timeoutMs: deadlines.turn, intervalMs: 500, what: "realtime_transcript 폐기 AI 행" },
  );
  const forbiddenRow = rows.find((row) => row[1] === forbiddenHash);
  check(
    forbiddenRow[0] === "f",
    "realtime_transcript: 폐기된 AI 행의 approved_for_display가 false가 아니다",
  );
  const passedRow = rows.find((row) => row[1] === passedHash);
  check(
    passedRow !== undefined && passedRow[0] === "t",
    "realtime_transcript: 통과한 AI 행의 approved_for_display가 true가 아니다",
  );
}

// ------------------------------------------------------------------------------------------------ C11 · C12

async function stepEnd(ctx) {
  const { parent, deadlines } = ctx;
  const sim = ctx.state.sim;
  const sessionId = ctx.state.sessionId;
  const snapshot = sim.snapshot();
  const res = await parent.post(
    `/api/sessions/${sessionId}/end`,
    { requestId: randomUUID() },
    {
      headers: csrfHeaders(ctx),
      timeoutMs: requestTimeout(ctx),
    },
  );
  expectStatus(res, 202, "POST /api/sessions/{id}/end");
  check(
    jsonBody(res, "POST /api/sessions/{id}/end").status === "CLOSING",
    "POST /api/sessions/{id}/end: status가 CLOSING이 아니다",
  );

  const stop = await bounded(
    ctx,
    sim.waitFor((e) => e.envelope.type === "STOP_OUTPUT" && e.envelope.sessionId === sessionId, {
      timeoutMs: ctx.limit(deadlines.ended),
      what: "STOP_OUTPUT",
      from: snapshot.receivedIndex,
    }),
  );
  check(
    stop.envelope.payload.reason === "GUARDIAN_STOP",
    `STOP_OUTPUT.reason이 GUARDIAN_STOP이 아니다(${stop.envelope.payload.reason})`,
  );
  const afterStop = { ...sim.snapshot(), receivedIndex: stop.index + 1 };
  const outputBytesAtStop = sim.counters.outputBytes;
  await bounded(
    ctx,
    sim.waitFor(
      (e) => e.envelope.type === "SESSION_STATE" && e.envelope.payload.view === "CLOSING",
      {
        timeoutMs: ctx.limit(deadlines.ended),
        what: "SESSION_STATE CLOSING",
        from: snapshot.receivedIndex,
      },
    ),
  );
  const closing = await bounded(
    ctx,
    sim.waitFor(
      (e) =>
        e.envelope.type === "PLAY_APPROVED_CONTENT" && e.envelope.payload.contentCode === "CLOSING",
      {
        timeoutMs: ctx.limit(deadlines.ended),
        what: "PLAY_APPROVED_CONTENT(CLOSING)",
        from: snapshot.receivedIndex,
      },
    ),
  );
  const closingId = closing.envelope.payload.playbackId;
  check(
    closingId !== ctx.state.fillerPlaybackId && closingId !== ctx.state.turn1PlaybackId,
    "CLOSING 재생 playbackId가 앞 재생과 같다(새 playbackId여야 한다)",
  );
  const completed = await bounded(
    ctx,
    sim.waitForEvent(
      (e) => e.kind === "mark-sent" && e.playbackId === closingId && e.status === "COMPLETED",
      {
        timeoutMs: ctx.limit(deadlines.ended),
        what: "CLOSING PLAYBACK_MARK COMPLETED 송신",
        from: snapshot.eventIndex,
      },
    ),
  );
  const closeTransport = await bounded(
    ctx,
    sim.waitFor(
      (e) => e.envelope.type === "CLOSE_TRANSPORT" && e.envelope.sessionId === sessionId,
      {
        timeoutMs: ctx.limit(deadlines.ended),
        what: "CLOSE_TRANSPORT",
        from: snapshot.receivedIndex,
      },
    ),
  );
  check(
    closeTransport.envelope.payload.reason === "GUARDIAN_STOP",
    "CLOSE_TRANSPORT.reason이 GUARDIAN_STOP이 아니다",
  );
  check(
    closeTransport.recvAtMs >= completed.atMs,
    "CLOSE_TRANSPORT가 CLOSING 재생 끝 표식보다 먼저 왔다(BR6.10)",
  );
  const sinceStart = sim.since(snapshot);
  check(
    (sinceStart.approvedPlays.CLOSING ?? 0) === 1,
    `CLOSING 승인 음성이 정확히 1회가 아니다(${sinceStart.approvedPlays.CLOSING ?? 0})`,
  );
  check(sim.counters.outputBytes === outputBytesAtStop, "STOP_OUTPUT 뒤 0x02가 왔다(R3-8 · SD-17)");
  check(afterStop.outputBegin === sim.counters.outputBegin, "STOP_OUTPUT 뒤 OUTPUT_BEGIN이 왔다");

  // CLOSE_TRANSPORT 뒤 일부러 보낸 0x01이 공급자에 닿지 않는다(R3-8 · SD-17 · SEC-07). 공급자 연결은 아직 열려 있어야 의미가 있다.
  await ctx.sleep(ctx.limit(Math.min(500, deadlines.settle)));
  const before = latestProviderInput(ctx);
  const closedEarly =
    ctx.provider.find((e) => e.ev === "close" && e.conn === ctx.state.providerConn).length > 0;
  check(
    !closedEarly,
    "VOICE_SESSION_CLOSED 전에 공급자 연결이 닫혔다(CLOSE_TRANSPORT 뒤 중계 확인 불가)",
  );
  await sim.sendInputFrames(AFTER_CLOSE_FRAMES);
  await ctx.sleep(ctx.limit(deadlines.settle));
  const after = latestProviderInput(ctx);
  check(
    after.frames === before.frames && after.bytes === before.bytes,
    `CLOSE_TRANSPORT 뒤 공급자 수신이 늘었다(${before.frames} → ${after.frames})`,
  );
  ctx.measurements.afterCloseTransport = {
    framesSentByDevice: AFTER_CLOSE_FRAMES,
    providerFramesIncrease: after.frames - before.frames,
  };
  ctx.state.vscFrom = sim.received.length;
  sim.sendVoiceSessionClosed(sessionId);
}

function latestProviderInput(ctx) {
  const inputs = ctx.provider.find((e) => e.ev === "input" && e.conn === ctx.state.providerConn);
  const last = inputs[inputs.length - 1];
  return last ? { frames: last.frames, bytes: last.bytes } : { frames: 0, bytes: 0 };
}

async function stepEnded(ctx) {
  const { deadlines } = ctx;
  const sim = ctx.state.sim;
  const sessionId = ctx.state.sessionId;
  await bounded(
    ctx,
    sim.waitFor(
      (e) => e.envelope.type === "SESSION_STATE" && e.envelope.payload.view === "STANDBY",
      {
        timeoutMs: ctx.limit(deadlines.ended),
        what: "SESSION_STATE STANDBY",
        from: ctx.state.vscFrom,
      },
    ),
  );
  const view = await waitUntil(
    ctx,
    async (left) => {
      const { res, view: live } = await liveView(ctx, left);
      if (live === null) {
        return { done: false, note: describe(res) };
      }
      return {
        done: live.status === "ENDED" && live.confirmed === true,
        value: live,
        note: `status ${live.status} · confirmed ${live.confirmed}`,
      };
    },
    { timeoutMs: deadlines.ended, intervalMs: 500, what: "12 진행 보기 ENDED(confirmed)" },
  );
  check(
    view.endReason === "GUARDIAN_STOP",
    `12 진행 보기: endReason이 GUARDIAN_STOP이 아니다(${view.endReason})`,
  );
  check(isDateTime(view.endedAt), "12 진행 보기: endedAt이 없다");
  // Core 사본(core.core_activity)도 ENDED · confirmed · GUARDIAN_STOP(BR6.14)
  await waitUntil(
    ctx,
    async (left) => {
      const rows = await ctx.sql(
        "core",
        `SELECT status, confirmed, end_reason FROM core.core_activity WHERE session_id = '${sessionId}';`,
        { timeoutMs: Math.min(left, requestTimeout(ctx)) },
      );
      const row = rows[0];
      return {
        done:
          row !== undefined && row[0] === "ENDED" && row[1] === "t" && row[2] === "GUARDIAN_STOP",
        note: row ? row.join("|") : "행 없음",
      };
    },
    {
      timeoutMs: deadlines.ended,
      intervalMs: 500,
      what: "Core 사본 ENDED(confirmed · GUARDIAN_STOP)",
    },
  );
  // 공급자 세션 정리(BR6.10 ④)
  await bounded(
    ctx,
    ctx.provider.waitFor((e) => e.ev === "session.close" && e.conn === ctx.state.providerConn, {
      timeoutMs: ctx.limit(deadlines.ended),
      what: "가짜 공급자 session.close 수신",
      from: ctx.state.providerMark,
    }),
  );

  // OD-8 · J143: LATENCY_TURN 한 줄에 시각 키 8종과 출처 · 오프셋 · 왕복 · 응답 · 조용 시간, SESSION_ITEMS_SERVED에 두 시각(값은 기록만)
  const latency = await bounded(
    ctx,
    ctx.realtimeLog.waitFor(
      (e) => e.event === "LATENCY_TURN" && missingLatencyAttrs(e).length === 0,
      {
        timeoutMs: ctx.limit(deadlines.ended),
        what: latencyNote(ctx),
      },
    ),
  ).catch((error) => {
    if (error instanceof StepTimeout) {
      throw new StepTimeout(latencyNote(ctx), error.timeoutMs);
    }
    throw error;
  });
  const served = await bounded(
    ctx,
    ctx.realtimeLog.waitFor(
      (e) => e.event === "SESSION_ITEMS_SERVED" && missingAttrs(e, ITEMS_SERVED_KEYS).length === 0,
      {
        timeoutMs: ctx.limit(deadlines.request),
        what: "SESSION_ITEMS_SERVED(tViewItemReady · tViewItemFirstServed)",
      },
    ),
  );
  ctx.measurements.latencyTurn = pickAttrs(latency, [
    ...LATENCY_TURN_KEYS,
    "relayInMs",
    "relayOutMs",
    "turnSeq",
    "unobservedReason",
  ]);
  ctx.measurements.itemsServed = pickAttrs(served, [
    "seq",
    "speaker",
    ...ITEMS_SERVED_KEYS,
    "tTranscriptFinal",
  ]);

  check(
    sim.protocolErrors.length === 0,
    `서버가 보낸 봉투 ${sim.protocolErrors.length}건이 계약 형식과 다르다: ${sim.protocolErrors[0]?.problems?.[0] ?? ""}`,
  );
  check(
    sim.counters.errorsReceived === 0,
    `서버가 ERROR를 ${sim.counters.errorsReceived}건 보냈다`,
  );
  check(sim.isOpen, "ENDED 뒤 기기 WSS가 대기용으로 남아 있지 않다");
  sim.close(1000);
}

function latencyNote(ctx) {
  const lines = ctx.realtimeLog.find((e) => e.event === "LATENCY_TURN");
  if (lines.length === 0) {
    return "LATENCY_TURN 줄 없음";
  }
  const best = lines
    .map((line) => missingLatencyAttrs(line))
    .sort((a, b) => a.length - b.length)[0];
  return `LATENCY_TURN ${lines.length}줄, 가장 가까운 줄에 빠진 키: ${best.join(",")}`;
}
