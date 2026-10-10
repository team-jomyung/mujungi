// 기기 시뮬레이터 — 아이 기기 웹(/device)의 WSS 동작을 흉내 내는 시험 도구 (U1 3.5 Step 45 · RD-23 · J129 · J143 · OD-8 · SEC-07)
//
// 계약(contracts/device-ws.asyncapi.yaml · schemas/ws-*.schema.json)대로:
//   - 연결: Cookie(MUJOONG_DEVICE) · Origin 헤더, 자체 서명 CA는 ca 옵션으로만(TLS 검증 유지, 프록시 없음).
//   - HELLO → HELLO_OK. HEARTBEAT는 HELLO_OK 직후 한 번, 그 뒤 heartbeatIntervalMs마다. 송신 직전에 봉투 sentAt을 채운다(J143 · R3-4)
//     · lastRttMs는 직전 HEARTBEAT ↔ HEARTBEAT_ACK 왕복(ms).
//   - opId 명령(START_SESSION · PLAY_APPROVED_CONTENT · STOP_OUTPUT · CLOSE_TRANSPORT)은 받자마자 ACK.
//   - START_SESSION → 승인 음성 미리 받기(GET audioUrl, WAV 형식 확인) → DEVICE_READY{mic, player, prefetch} → 입력 창 열림.
//   - 0x01 입력 프레임은 20ms 간격 965바이트(합성 톤). 프레임 순번은 연결 안에서 1부터 오른다.
//   - OUTPUT_BEGIN 뒤 0x02 → 첫 프레임에 PLAYBACK_MARK STARTED, OUTPUT_END 뒤 재생 길이가 지나면 COMPLETED.
//     PLAY_APPROVED_CONTENT → 미리 받은 WAV 길이만큼 STARTED → COMPLETED(목록에 없는 문구는 FAILED(FETCH_FAILED)).
//   - STOP_OUTPUT → 재생 중인 것을 STOPPED(STOP_OUTPUT). CLOSE_TRANSPORT → 입력 창 닫기 · 재생 멈춤 → VOICE_SESSION_CLOSED
//     (auto.voiceSessionClosed가 거짓이면 호출자가 sendVoiceSessionClosed로 보낸다 — C안이 CLOSE_TRANSPORT 뒤 일부러 0x01을 보낼 때).
//
// 기록: 받은 봉투(계약 형식 문제 목록 포함) · 0x02 바이트 수 · SHA-256(재생 묶음별 · 전체) · OUTPUT_BEGIN 수 · 승인 문구 재생 수 ·
// 닫기 코드. 기기 토큰 · 쿠키 값은 toString · 오류 메시지에 쓰지 않는다. 시각은 기기 시계(Date.now + skewMs)다.
import { randomUUID } from "node:crypto";
import WebSocket from "ws";
import {
  INPUT_FRAME_MS,
  RunningHash,
  decodeFrame,
  encodeInputFrame,
  inspectWav,
  nextFrameSeq,
  pcmDurationMs,
  synthesizeInputPayload,
  FRAME_KIND_OUTPUT,
} from "../lib/frameCodec.mjs";
import {
  OP_ID_COMMANDS,
  ackPayload,
  buildDeviceEnvelope,
  createSeqCounter,
  deviceReadyPayload,
  heartbeatPayload,
  helloPayload,
  parseServerEnvelope,
  playbackMarkPayload,
  voiceSessionClosedPayload,
} from "../lib/envelope.mjs";

/** 업그레이드가 101이 아니었다(예: Origin 403). */
export class HandshakeRefusedError extends Error {
  constructor(status) {
    super(`WSS 업그레이드가 거절됐다(HTTP ${status})`);
    this.name = "HandshakeRefusedError";
    this.status = status;
  }
}

/** 기다리던 메시지가 기한 안에 오지 않았다. */
export class SimulatorTimeoutError extends Error {
  constructor(what, timeoutMs) {
    super(`${what}: ${timeoutMs}ms 안에 오지 않았다`);
    this.name = "SimulatorTimeoutError";
    this.timeoutMs = timeoutMs;
  }
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * 업그레이드만 시도해 결과를 돌려준다(열리면 바로 닫는다). Origin 거절(403) 확인용(BR3.6 ① · SD-7).
 * @returns {Promise<{status: 'open'} | {status: 'refused', httpStatus: number} | {status: 'error', code: string}>}
 */
export function probeUpgrade({ url, origin = null, cookie = null, ca = null, timeoutMs = 5000 }) {
  return new Promise((resolve) => {
    const headers = {};
    if (cookie) {
      headers.cookie = cookie;
    }
    const ws = new WebSocket(url, {
      ca: ca ?? undefined,
      origin: origin ?? undefined,
      headers,
      perMessageDeflate: false,
      handshakeTimeout: timeoutMs,
    });
    let done = false;
    const finish = (result) => {
      if (!done) {
        done = true;
        resolve(result);
      }
    };
    ws.on("unexpected-response", (req, res) => {
      res.resume();
      req.destroy();
      finish({ status: "refused", httpStatus: res.statusCode });
    });
    ws.on("open", () => {
      ws.terminate();
      finish({ status: "open" });
    });
    ws.on("error", (error) => finish({ status: "error", code: error.code ?? error.name }));
  });
}

export class DeviceSimulator {
  #ws = null;
  #seq = createSeqCounter();
  #frameSeq = 0;
  #inputIndex = 0;
  #hbSeq = 0;
  #hbSentAt = new Map();
  #heartbeatTimer = null;
  #timers = new Set();
  #waiters = new Set();
  #prefetched = new Map();
  #now;

  /**
   * @param {object} options
   * @param {string} options.url wss://host/ws/device
   * @param {string|null} options.origin Origin 헤더
   * @param {string|null} options.cookie Cookie 헤더 값(예: MUJOONG_DEVICE=...)
   * @param {string|Buffer|null} [options.ca]
   * @param {{get: (path: string, extra?: object) => Promise<any>}|null} [options.mediaClient] 승인 음성 미리 받기용 HTTP 클라이언트
   * @param {object} [options.auto] 자동 동작 스위치
   * @param {number} [options.skewMs] 기기 시계 − 실제 시계(ms)
   * @param {number} [options.playbackSpeed] 재생 시간 배율(1 = 실제 길이)
   * @param {string} [options.appVersion]
   */
  constructor({ url, origin, cookie, ca = null, mediaClient = null, auto = {}, skewMs = 0, playbackSpeed = 1, appVersion = "u1-sim-1.0" }) {
    this.url = url;
    this.origin = origin;
    this.ca = ca;
    this.mediaClient = mediaClient;
    this.auto = { ack: true, heartbeat: true, prepare: true, playback: true, voiceSessionClosed: true, ...auto };
    this.skewMs = skewMs;
    this.playbackSpeed = playbackSpeed;
    this.appVersion = appVersion;
    this.#cookie = cookie;
    this.#now = () => Date.now() + this.skewMs;

    /** 받은 봉투 기록: {envelope, problems, recvAtMs, index} */
    this.received = [];
    /** 보낸 봉투 기록(type · seq · sessionId · sentAt만, payload 원문 없음) */
    this.sent = [];
    /** 계약 형식 문제(받은 봉투 · 바이너리) */
    this.protocolErrors = [];
    /** 재생 묶음: playbackId → 기록 */
    this.playbacks = new Map();
    this.counters = {
      outputBegin: 0,
      outputFrames: 0,
      outputBytes: 0,
      orphanOutputBytes: 0,
      approvedPlays: {},
      inputFramesSent: 0,
      errorsReceived: 0,
    };
    this.outputHashAll = new RunningHash();
    this.lastRttMs = null;
    this.heartbeatsWithRtt = 0;
    this.heartbeatIntervalMs = null;
    this.helloOk = null;
    this.session = null;
    this.closeCode = null;
    this.events = [];
  }

  #cookie;

  toString() {
    return `DeviceSimulator[${this.url}, received=${this.received.length}]`;
  }

  get isOpen() {
    return this.#ws !== null && this.#ws.readyState === WebSocket.OPEN;
  }

  /** 연결한다. 101이 아니면 HandshakeRefusedError. */
  open(timeoutMs = 5000) {
    return new Promise((resolve, reject) => {
      const headers = {};
      if (this.#cookie) {
        headers.cookie = this.#cookie;
      }
      const ws = new WebSocket(this.url, {
        ca: this.ca ?? undefined,
        origin: this.origin ?? undefined,
        headers,
        perMessageDeflate: false,
        handshakeTimeout: timeoutMs,
      });
      let settled = false;
      const settle = (fn, value) => {
        if (!settled) {
          settled = true;
          fn(value);
        }
      };
      ws.on("unexpected-response", (req, res) => {
        res.resume();
        req.destroy();
        settle(reject, new HandshakeRefusedError(res.statusCode));
      });
      ws.on("open", () => {
        this.#ws = ws;
        settle(resolve, this);
      });
      ws.on("error", (error) => {
        this.#note("socket-error", { code: error.code ?? error.name });
        settle(reject, error);
      });
      ws.on("message", (data, isBinary) => this.#onMessage(data, isBinary));
      ws.on("close", (code) => {
        this.closeCode = code;
        this.#stopHeartbeat();
        this.#note("closed", { code });
        this.#wake();
      });
    });
  }

  /** HELLO를 보내고 HELLO_OK를 기다린다. 자동 HEARTBEAT를 시작한다. */
  async hello(timeoutMs = 10_000) {
    const from = this.received.length;
    this.#send("HELLO", helloPayload(this.appVersion));
    const entry = await this.waitFor((e) => e.envelope.type === "HELLO_OK", { timeoutMs, what: "HELLO_OK", from });
    this.helloOk = entry.envelope.payload;
    this.heartbeatIntervalMs = entry.envelope.payload.heartbeatIntervalMs;
    if (this.auto.heartbeat) {
      this.heartbeatNow();
      this.#heartbeatTimer = setInterval(() => this.heartbeatNow(), this.heartbeatIntervalMs);
    }
    return entry.envelope.payload;
  }

  /** HEARTBEAT 하나. sentAt은 이 순간 값이다(J143). 보낸 hbSeq. */
  heartbeatNow() {
    if (!this.isOpen) {
      return null;
    }
    this.#hbSeq += 1;
    const hbSeq = this.#hbSeq;
    // 송신 직전 시각: 봉투를 만드는 #send 안에서 sentAt을 찍고 같은 값을 왕복 계산에 쓴다
    const sentAtMs = this.#send("HEARTBEAT", heartbeatPayload(hbSeq, this.lastRttMs));
    this.#hbSentAt.set(hbSeq, sentAtMs);
    if (this.lastRttMs !== null) {
      this.heartbeatsWithRtt += 1;
    }
    return hbSeq;
  }

  /**
   * 0x01 입력 프레임을 count장 20ms 간격으로 보낸다(합성 톤). 이 묶음 본문의 SHA-256을 돌려준다.
   * @returns {Promise<{frames: number, bytes: number, sha256: string, firstSeq: number, lastSeq: number}>}
   */
  async sendInputFrames(count, { intervalMs = INPUT_FRAME_MS } = {}) {
    const hash = new RunningHash();
    let firstSeq = null;
    let lastSeq = null;
    const start = Date.now();
    for (let i = 0; i < count; i += 1) {
      if (!this.isOpen) {
        throw new Error("입력 프레임을 보내는 중 연결이 닫혔다");
      }
      const payload = synthesizeInputPayload(this.#inputIndex);
      this.#inputIndex += 1;
      this.#frameSeq = nextFrameSeq(this.#frameSeq);
      if (firstSeq === null) {
        firstSeq = this.#frameSeq;
      }
      lastSeq = this.#frameSeq;
      this.#ws.send(encodeInputFrame(this.#frameSeq, payload), { binary: true });
      hash.update(payload);
      this.counters.inputFramesSent += 1;
      const target = start + (i + 1) * intervalMs;
      const wait = target - Date.now();
      if (i < count - 1 && wait > 0) {
        await sleep(wait);
      }
    }
    return { frames: count, bytes: hash.bytes, sha256: hash.hex(), firstSeq, lastSeq };
  }

  /** VOICE_SESSION_CLOSED(입력 창 닫힘 · 재생 정리 시각). */
  sendVoiceSessionClosed(sessionId) {
    const closedAt = this.#now();
    this.#send("VOICE_SESSION_CLOSED", voiceSessionClosedPayload(closedAt, closedAt), sessionId);
    this.#note("voice-session-closed", { sessionId });
  }

  /** 기기 「끄기」 등으로 닫는다. */
  close(code = 1000) {
    this.#stopHeartbeat();
    for (const timer of this.#timers) {
      clearTimeout(timer);
    }
    this.#timers.clear();
    if (this.#ws && (this.#ws.readyState === WebSocket.OPEN || this.#ws.readyState === WebSocket.CONNECTING)) {
      this.#ws.close(code);
    }
  }

  /** 즉시 끊는다(정리 경로). */
  terminate() {
    this.#stopHeartbeat();
    for (const timer of this.#timers) {
      clearTimeout(timer);
    }
    this.#timers.clear();
    if (this.#ws) {
      this.#ws.terminate();
    }
    this.#wake();
  }

  /** 닫힐 때까지 기다린다(기한 안에 안 닫히면 null). */
  async waitForClose(timeoutMs) {
    const deadline = Date.now() + timeoutMs;
    while (this.closeCode === null && Date.now() < deadline) {
      await this.#idle(Math.min(50, deadline - Date.now()));
    }
    return this.closeCode;
  }

  /**
   * 받은 봉투 중 조건에 맞는 것을 기다린다.
   * @param {(entry: {envelope: object, problems: string[], recvAtMs: number, index: number}) => boolean} predicate
   * @param {{timeoutMs: number, what: string, from?: number}} options
   */
  async waitFor(predicate, { timeoutMs, what, from = 0 }) {
    if (!(timeoutMs > 0)) {
      throw new RangeError("기다림에는 양수 기한이 있어야 한다(SEC-05)");
    }
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      for (let i = from; i < this.received.length; i += 1) {
        if (predicate(this.received[i])) {
          return this.received[i];
        }
      }
      if (this.closeCode !== null) {
        throw new Error(`${what}: 기다리는 중 연결이 닫혔다(코드 ${this.closeCode})`);
      }
      const left = deadline - Date.now();
      if (left <= 0) {
        throw new SimulatorTimeoutError(what, timeoutMs);
      }
      await this.#idle(Math.min(left, 100));
    }
  }

  /** 시뮬레이터 내부 사건(보낸 표식 등)을 기다린다. */
  async waitForEvent(predicate, { timeoutMs, what, from = 0 }) {
    if (!(timeoutMs > 0)) {
      throw new RangeError("기다림에는 양수 기한이 있어야 한다(SEC-05)");
    }
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      for (let i = from; i < this.events.length; i += 1) {
        if (predicate(this.events[i])) {
          return this.events[i];
        }
      }
      const left = deadline - Date.now();
      if (left <= 0) {
        throw new SimulatorTimeoutError(what, timeoutMs);
      }
      await this.#idle(Math.min(left, 100));
    }
  }

  /** 지금 계수 묶음(턴 경계 표시용). */
  snapshot() {
    return {
      at: Date.now(),
      receivedIndex: this.received.length,
      eventIndex: this.events.length,
      outputBegin: this.counters.outputBegin,
      outputFrames: this.counters.outputFrames,
      outputBytes: this.counters.outputBytes,
      orphanOutputBytes: this.counters.orphanOutputBytes,
      approvedPlays: { ...this.counters.approvedPlays },
      errorsReceived: this.counters.errorsReceived,
    };
  }

  /** snapshot 이후 늘어난 양. */
  since(snapshot) {
    const approved = {};
    for (const [code, count] of Object.entries(this.counters.approvedPlays)) {
      approved[code] = count - (snapshot.approvedPlays[code] ?? 0);
    }
    return {
      outputBegin: this.counters.outputBegin - snapshot.outputBegin,
      outputFrames: this.counters.outputFrames - snapshot.outputFrames,
      outputBytes: this.counters.outputBytes - snapshot.outputBytes,
      orphanOutputBytes: this.counters.orphanOutputBytes - snapshot.orphanOutputBytes,
      approvedPlays: approved,
      errorsReceived: this.counters.errorsReceived - snapshot.errorsReceived,
      received: this.received.slice(snapshot.receivedIndex),
    };
  }

  // -------------------------------------------------------------------------------------------- 내부

  /** 봉투를 만들어 바로 보낸다. sentAt은 만드는 순간(= 송신 직전) 값이다. 보낸 시각(ms). */
  #send(type, payload, sessionId = null) {
    if (!this.isOpen) {
      throw new Error(`${type}: 연결이 열려 있지 않다`);
    }
    const sentAtMs = this.#now();
    const envelope = buildDeviceEnvelope(type, payload, { seq: this.#seq(), sentAtMs, sessionId });
    this.#ws.send(JSON.stringify(envelope));
    this.sent.push({ type, seq: envelope.seq, sessionId, sentAt: envelope.sentAt });
    return sentAtMs;
  }

  #note(kind, detail = {}) {
    // kind · atMs는 detail이 덮어쓰지 못하게 뒤에 둔다
    this.events.push({ ...detail, kind, atMs: Date.now() });
    this.#wake();
  }

  #idle(ms) {
    return new Promise((resolve) => {
      const waiter = { resolve, timer: null };
      waiter.timer = setTimeout(() => {
        this.#waiters.delete(waiter);
        resolve();
      }, Math.max(1, ms));
      this.#waiters.add(waiter);
    });
  }

  #wake() {
    for (const waiter of this.#waiters) {
      clearTimeout(waiter.timer);
      waiter.resolve();
    }
    this.#waiters.clear();
  }

  #later(ms, fn) {
    const timer = setTimeout(() => {
      this.#timers.delete(timer);
      try {
        fn();
      } catch (error) {
        this.#note("timer-error", { message: error.message });
      }
    }, Math.max(0, ms));
    this.#timers.add(timer);
  }

  #stopHeartbeat() {
    if (this.#heartbeatTimer !== null) {
      clearInterval(this.#heartbeatTimer);
      this.#heartbeatTimer = null;
    }
  }

  #onMessage(data, isBinary) {
    if (isBinary) {
      this.#onBinary(data);
      return;
    }
    const { envelope, problems } = parseServerEnvelope(data);
    const entry = { envelope: envelope ?? { type: null, payload: {} }, problems, recvAtMs: Date.now(), index: this.received.length };
    if (problems.length > 0) {
      this.protocolErrors.push({ type: envelope?.type ?? null, problems });
    }
    this.received.push(entry);
    if (envelope !== null) {
      try {
        this.#handle(envelope);
      } catch (error) {
        this.#note("handler-error", { type: envelope.type, message: error.message });
      }
    }
    this.#wake();
  }

  #onBinary(data) {
    const decoded = decodeFrame(data);
    if (!decoded.ok || decoded.frame.kind !== FRAME_KIND_OUTPUT) {
      this.protocolErrors.push({ type: "BINARY", problems: [decoded.ok ? "서버가 0x02가 아닌 프레임을 보냈다" : decoded.reason] });
      this.#wake();
      return;
    }
    const payload = decoded.frame.payload;
    this.counters.outputFrames += 1;
    this.counters.outputBytes += payload.length;
    this.outputHashAll.update(payload);
    const stream = this.#activeStream();
    if (stream === null) {
      // OUTPUT_BEGIN 없이 온 0x02는 버린다(BR7.6)
      this.counters.orphanOutputBytes += payload.length;
      this.#wake();
      return;
    }
    stream.hash.update(payload);
    stream.bytes += payload.length;
    stream.frames += 1;
    if (!stream.startedSent && this.auto.playback) {
      stream.startedSent = true;
      stream.startedAtMs = Date.now();
      this.#mark(stream, "STARTED");
    }
    this.#wake();
  }

  #activeStream() {
    for (const playback of this.playbacks.values()) {
      if (playback.kind === "stream" && playback.state === "receiving") {
        return playback;
      }
    }
    return null;
  }

  #handle(envelope) {
    const { type, payload, opId, sessionId } = envelope;
    if (OP_ID_COMMANDS.includes(type) && opId && this.auto.ack) {
      this.#send("ACK", ackPayload(opId), sessionId);
      this.#note("ack-sent", { ackOf: opId, of: type });
    }
    switch (type) {
      case "HEARTBEAT_ACK": {
        const sentAt = this.#hbSentAt.get(payload.hbSeq);
        if (sentAt !== undefined) {
          this.lastRttMs = Math.max(0, this.#now() - sentAt);
          this.#hbSentAt.delete(payload.hbSeq);
        }
        break;
      }
      case "START_SESSION":
        if (this.auto.prepare) {
          void this.#prepare(sessionId, payload.approvedAudio ?? []);
        }
        break;
      case "SESSION_STATE":
        this.#note("view", { view: payload.view, sessionId });
        break;
      case "OUTPUT_BEGIN": {
        this.counters.outputBegin += 1;
        this.playbacks.set(payload.playbackId, {
          kind: "stream",
          playbackId: payload.playbackId,
          sessionId,
          state: "receiving",
          hash: new RunningHash(),
          bytes: 0,
          frames: 0,
          startedSent: false,
          startedAtMs: null,
          beganAtMs: Date.now(),
        });
        break;
      }
      case "OUTPUT_END": {
        const stream = this.playbacks.get(payload.playbackId);
        if (stream && stream.kind === "stream" && stream.state === "receiving") {
          stream.state = "playing";
          stream.totalMs = payload.totalMs ?? null;
          if (this.auto.playback) {
            const durationMs = pcmDurationMs(stream.bytes) / this.playbackSpeed;
            const elapsed = stream.startedAtMs === null ? 0 : Date.now() - stream.startedAtMs;
            if (!stream.startedSent) {
              stream.startedSent = true;
              stream.startedAtMs = Date.now();
              this.#mark(stream, "STARTED");
            }
            this.#later(Math.max(10, durationMs - elapsed), () => this.#complete(stream));
          }
        }
        break;
      }
      case "PLAY_APPROVED_CONTENT": {
        const code = payload.contentCode;
        this.counters.approvedPlays[code] = (this.counters.approvedPlays[code] ?? 0) + 1;
        const audio = this.#prefetched.get(code);
        const playback = {
          kind: "approved",
          playbackId: payload.playbackId,
          sessionId,
          contentCode: code,
          contentVersionId: payload.contentVersionId,
          state: "playing",
          startedSent: false,
          startedAtMs: null,
        };
        this.playbacks.set(payload.playbackId, playback);
        if (this.auto.playback) {
          if (!audio || audio.contentVersionId !== payload.contentVersionId) {
            playback.state = "failed";
            this.#mark(playback, "FAILED", { failureReason: "FETCH_FAILED" });
          } else {
            playback.startedSent = true;
            playback.startedAtMs = Date.now();
            this.#mark(playback, "STARTED");
            this.#later(Math.max(10, audio.durationMs / this.playbackSpeed), () => this.#complete(playback));
          }
        }
        break;
      }
      case "STOP_OUTPUT":
        this.#note("stop-output", { reason: payload.reason, sessionId });
        this.#stopAll("STOP_OUTPUT");
        break;
      case "CLOSE_TRANSPORT": {
        const micClosedAt = this.#now();
        this.session = this.session && this.session.sessionId === sessionId ? { ...this.session, inputOpen: false } : this.session;
        this.#stopAll("CLOSE_TRANSPORT");
        this.#note("close-transport", { reason: payload.reason, sessionId, micClosedAt });
        if (this.auto.voiceSessionClosed) {
          this.sendVoiceSessionClosed(sessionId);
        }
        break;
      }
      case "ERROR":
        this.counters.errorsReceived += 1;
        this.#note("server-error", { code: payload.code, refSeq: payload.refSeq ?? null });
        break;
      default:
        break;
    }
  }

  async #prepare(sessionId, approvedAudio) {
    this.session = { sessionId, inputOpen: false };
    let prefetch = "OK";
    for (const item of approvedAudio) {
      try {
        if (!this.mediaClient) {
          throw new Error("미리 받기 클라이언트 없음");
        }
        const res = await this.mediaClient.get(item.audioUrl, { cookies: false, headers: { accept: "audio/wav" } });
        const wav = res.status === 200 ? inspectWav(res.body) : { ok: false, reason: `HTTP_${res.status}` };
        if (!wav.ok) {
          prefetch = "PARTIAL";
          this.#note("prefetch-failed", { contentCode: item.contentCode, reason: wav.reason });
          continue;
        }
        this.#prefetched.set(item.contentCode, { contentVersionId: item.contentVersionId, durationMs: wav.durationMs, bytes: wav.dataBytes });
        this.#note("prefetched", { contentCode: item.contentCode, durationMs: wav.durationMs });
      } catch (error) {
        prefetch = "PARTIAL";
        this.#note("prefetch-failed", { contentCode: item.contentCode, reason: error.code ?? error.name });
      }
    }
    if (!this.isOpen || this.session?.sessionId !== sessionId) {
      return;
    }
    this.#send("DEVICE_READY", deviceReadyPayload("OK", "OK", prefetch), sessionId);
    this.session = { sessionId, inputOpen: true };
    this.#note("device-ready-sent", { sessionId, prefetch });
  }

  #mark(playback, status, extra = {}) {
    if (!this.isOpen) {
      return;
    }
    this.#send(
      "PLAYBACK_MARK",
      playbackMarkPayload({ playbackId: playback.playbackId, status, observedAtMs: this.#now(), ...extra }),
      playback.sessionId,
    );
    this.#note("mark-sent", {
      playbackId: playback.playbackId,
      status,
      playbackKind: playback.kind,
      contentCode: playback.contentCode ?? null,
    });
  }

  #complete(playback) {
    if (playback.state !== "playing") {
      return;
    }
    playback.state = "completed";
    this.#mark(playback, "COMPLETED");
  }

  #stopAll(cause) {
    for (const playback of this.playbacks.values()) {
      if (playback.state === "receiving" || playback.state === "playing") {
        const wasStarted = playback.startedSent;
        playback.state = "stopped";
        if (wasStarted && this.auto.playback) {
          this.#mark(playback, "STOPPED", { stopCause: cause });
        }
      }
    }
  }
}

/** 재생 묶음의 SHA-256(없으면 null). */
export function playbackSha256(simulator, playbackId) {
  const playback = simulator.playbacks.get(playbackId);
  return playback && playback.kind === "stream" ? playback.hash.hex() : null;
}

/** 새 opId 같은 무작위 UUID(시험 편의). */
export const newUuid = () => randomUUID();
