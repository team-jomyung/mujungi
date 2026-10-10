#!/usr/bin/env node
// host 모드 Nginx 대역 — 검증 명령 host 모드의 입구 (U1 3.5 Step 45 · J133 · BR8.9 · SD-3 · OD-7 · R4-17 · 편차 (r)(t)(y))
//
// Docker 데몬을 쓸 수 없을 때(사람 결정 11) scripts/verify/b-host.sh가 Nginx 대신 이 프로세스를 127.0.0.1:8443에 띄운다.
// infra/nginx 설정(Step 44)의 경로 · 헤더 규칙 가운데 C안 · B 단언이 기대는 부분을 Node https로 흉내 낸다. Nginx 설정 자체
// (렌더 결과 · nginx -t · 망 격리)는 확인하지 못하므로 report.txt에 NOT_VERIFIED(host)로 남긴다(J133).
//
//   경로      = /ws/device → realtime(업그레이드 중계, 75초 무신호면 끊음) · ^~ /api/ → core-api · ^~ /media/approved/ → 승인 음성
//             정적 파일(.wav GET · HEAD) · ^~ /internal/ · ^~ /actuator/ → 403 · 그 밖(/ · /_next/static/) → web
//   원시 URI  경로에 ; · \ · %2e · %2f · %5c(대소문자 무관)가 있으면 400(/api/..;/internal/… 우회 방지). 점 구간 · 겹 슬래시는
//             정규화한 경로로 길을 고른다(Nginx location 판정과 같음)
//   헤더      X-Forwarded-For · X-Real-IP = 접속 주소로 덮어씀(앞 값 이어 붙이지 않음), X-Trace-Id = 새 32자 16진(위조 값 버림),
//             X-Forwarded-Proto = https. 응답에 HSTS · nosniff · Referrer-Policy · Permissions-Policy, /api/ · /media/approved/ ·
//             대역이 만든 오류에는 CSP default-src 'none'; frame-ancestors 'none'
//   한도      본문 64KB(65,536바이트) 초과 → 400 VALIDATION_FAILED 봉투(편차 (r)), = /api/device/bootstrap은 IP당 30r/m burst 20
//             nodelay → 429 RATE_LIMITED, 업스트림 연결 실패 → 502 · 시간 초과 → 504 UPSTREAM_UNAVAILABLE(편차 (y), 상태 유지).
//             앱이 낸 503은 그대로 통과한다(proxy_intercept_errors off)
//   TLS       TLS 1.2 이상, 세션 티켓 끔. SNI · Host가 SERVICE_HOST가 아니면 핸드셰이크 거절 · 연결 닫기(ssl_reject_handshake · 444)
//   접근 로그 표준 출력 JSON 한 줄(쿼리 · 쿠키 · Referer 없음). 폴링(D0 상태 · 12 진행) 2xx · 304는 남기지 않는다
//
// 쓰는 법: node tools/dev-edge/edge.mjs --cert <PEM> --key <PEM> [--listen 127.0.0.1:8443] [--service-host localhost]
//          [--core http://127.0.0.1:8080] [--realtime http://127.0.0.1:8081] [--web http://127.0.0.1:3000] [--media-dir infra/seed-audio]
//   준비되면 표준 출력에 {"event":"EDGE_READY",...} 한 줄. 종료 코드: 0 정상 종료(SIGTERM · SIGINT), 1 기동 실패, 2 인자 오류.
import { randomBytes } from "node:crypto";
import { constants as cryptoConstants } from "node:crypto";
import { readFileSync } from "node:fs";
import { readFile, stat } from "node:fs/promises";
import http from "node:http";
import https from "node:https";
import net from "node:net";
import path from "node:path";
import tls from "node:tls";
import { fileURLToPath, pathToFileURL } from "node:url";
import { jsonLine } from "../lib/log.mjs";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
export const REPO_ROOT = path.resolve(scriptDir, "..", "..");

/** 대역이 만드는 오류 봉투의 고정 문장(contracts/errors.md 표와 같아야 한다 — dev-edge.test.mjs가 대조). */
export const EDGE_ERRORS = Object.freeze({
  VALIDATION_FAILED: "요청 형식이 올바르지 않아요.",
  RATE_LIMITED: "잠시 뒤 다시 시도해 주세요.",
  UPSTREAM_UNAVAILABLE: "지금은 연결할 수 없어요. 잠시 뒤 다시 시도해 주세요.",
});

export const DEFAULTS = Object.freeze({
  listen: "127.0.0.1:8443",
  serviceHost: "localhost",
  core: "http://127.0.0.1:8080",
  realtime: "http://127.0.0.1:8081",
  web: "http://127.0.0.1:3000",
  hstsMaxAge: 31_536_000,
  upstreamTimeoutMs: 60_000,
  wsIdleTimeoutMs: 75_000,
  bodyLimitBytes: 65_536,
  headerTimeoutMs: 10_000,
  bootstrapRatePerMinute: 30,
  bootstrapBurst: 20,
});

export const USAGE = `host 모드 Nginx 대역 (J133, 검증 명령 host 모드 전용)

쓰는 법: node tools/dev-edge/edge.mjs --cert <PEM> --key <PEM> [옵션]

  --cert <파일>               서버 인증서 PEM(SERVICE_HOST용, 자체 서명 CA가 서명)
  --key <파일>                서버 키 PEM
  --listen <주소:포트>        들을 곳 (기본 ${DEFAULTS.listen})
  --service-host <이름>       SNI · Host로 받을 이름 (기본 환경 변수 SERVICE_HOST 또는 ${DEFAULTS.serviceHost})
  --core <url>                core-api 업스트림 (기본 ${DEFAULTS.core})
  --realtime <url>            realtime 업스트림 (기본 ${DEFAULTS.realtime})
  --web <url>                 web 업스트림 (기본 ${DEFAULTS.web})
  --media-dir <디렉터리>      /media/approved/ 원본 (기본 <저장소>/infra/seed-audio)
  --hsts-max-age <초>         HSTS max-age (기본 ${DEFAULTS.hstsMaxAge})
  --upstream-timeout-ms <ms>  업스트림 응답 시간 제한, 넘으면 504 (기본 ${DEFAULTS.upstreamTimeoutMs})
  --ws-idle-timeout-ms <ms>   /ws/device 무신호 끊기 (기본 ${DEFAULTS.wsIdleTimeoutMs})
  --help                      이 설명

출력: 준비되면 {"event":"EDGE_READY",...}, 요청마다 {"event":"EDGE_ACCESS",...}(폴링 2xx · 304 제외)
종료 코드: 0 정상 종료, 1 기동 실패, 2 인자 오류
`;

const HOP_BY_HOP = new Set([
  "connection",
  "keep-alive",
  "proxy-connection",
  "proxy-authenticate",
  "proxy-authorization",
  "te",
  "trailer",
  "transfer-encoding",
  "upgrade",
]);
/** 클라이언트가 보낸 값을 버리고 대역이 다시 쓰는 헤더. */
const OVERWRITTEN = new Set([
  "x-forwarded-for",
  "x-real-ip",
  "x-forwarded-proto",
  "x-forwarded-host",
  "x-trace-id",
  "forwarded",
]);
const RAW_URI_FORBIDDEN = /[;\\]|%2e|%2f|%5c/i;
const POLLING_PATHS = [/^\/api\/device\/bootstrap\/status$/, /^\/api\/sessions\/[^/]+$/];
const MEDIA_FILE = /^[A-Za-z0-9._-]+\.wav$/;
const API_CSP = "default-src 'none'; frame-ancestors 'none'";

/** 32자 소문자 16진(Nginx $request_id와 같은 모양, OD-7). */
export function newTraceId() {
  return randomBytes(16).toString("hex");
}

/**
 * 원시 요청 URI를 검사하고 길 고르기용 경로를 만든다.
 * @returns {{ok: true, path: string, forwardPath: string, query: string} | {ok: false}}
 */
export function normalizeRequestTarget(rawUrl) {
  if (typeof rawUrl !== "string" || !rawUrl.startsWith("/")) {
    return { ok: false };
  }
  const at = rawUrl.indexOf("?");
  const rawPath = at < 0 ? rawUrl : rawUrl.slice(0, at);
  const query = at < 0 ? "" : rawUrl.slice(at);
  if (RAW_URI_FORBIDDEN.test(rawPath)) {
    return { ok: false };
  }
  let decoded;
  try {
    decoded = decodeURIComponent(rawPath);
  } catch {
    return { ok: false };
  }
  if (decoded.includes("\0")) {
    return { ok: false };
  }
  const normalized = path.posix.normalize(decoded);
  const forwardPath =
    normalized === decoded
      ? rawPath
      : encodeURI(normalized).replace(/\?/g, "%3F").replace(/#/g, "%23");
  return { ok: true, path: normalized, forwardPath, query };
}

/** 길 고르기(Nginx location 순서와 같은 결과). */
export function routeFor(normalizedPath) {
  if (normalizedPath.startsWith("/internal/") || normalizedPath.startsWith("/actuator/")) {
    return "forbidden";
  }
  if (normalizedPath === "/ws/device") {
    return "realtime";
  }
  if (normalizedPath === "/api/device/bootstrap") {
    return "bootstrap";
  }
  if (normalizedPath.startsWith("/api/")) {
    return "core";
  }
  if (normalizedPath.startsWith("/media/approved/")) {
    return "media";
  }
  return "web";
}

/** IP별 누출 버킷(limit_req rate · burst nodelay). */
export class LeakyBucket {
  #excess = new Map();

  constructor({ ratePerMinute, burst, now = () => Date.now() }) {
    this.ratePerMs = ratePerMinute / 60_000;
    this.burst = burst;
    this.now = now;
  }

  /** 받아도 되면 참. */
  take(key) {
    const t = this.now();
    const prev = this.#excess.get(key) ?? { excess: 0, at: t };
    const drained = Math.max(0, prev.excess - (t - prev.at) * this.ratePerMs);
    if (drained + 1 > this.burst + 1) {
      this.#excess.set(key, { excess: drained, at: t });
      return false;
    }
    this.#excess.set(key, { excess: drained + 1, at: t });
    return true;
  }
}

function clientAddress(socket) {
  const address = socket.remoteAddress ?? "";
  return address.startsWith("::ffff:") ? address.slice(7) : address;
}

function hostnameOf(hostHeader) {
  if (typeof hostHeader !== "string" || hostHeader.length === 0) {
    return null;
  }
  if (hostHeader.startsWith("[")) {
    const end = hostHeader.indexOf("]");
    return end < 0 ? null : hostHeader.slice(1, end).toLowerCase();
  }
  return hostHeader.split(":")[0].toLowerCase();
}

/** 업스트림으로 보낼 헤더(원시 헤더 순서 유지, hop-by-hop · 덮어쓸 헤더 제외). */
function forwardHeaders(rawHeaders, extra) {
  const out = [];
  for (let i = 0; i < rawHeaders.length; i += 2) {
    const name = rawHeaders[i];
    const lower = name.toLowerCase();
    if (HOP_BY_HOP.has(lower) || OVERWRITTEN.has(lower)) {
      continue;
    }
    out.push([name, rawHeaders[i + 1]]);
  }
  for (const [name, value] of Object.entries(extra)) {
    out.push([name, value]);
  }
  return out;
}

/**
 * 대역을 띄운다.
 * @returns {Promise<{host: string, port: number, close: () => Promise<void>}>}
 */
export async function startEdge({
  listen = DEFAULTS.listen,
  cert,
  key,
  serviceHost = DEFAULTS.serviceHost,
  core = DEFAULTS.core,
  realtime = DEFAULTS.realtime,
  web = DEFAULTS.web,
  mediaDir = path.join(REPO_ROOT, "infra", "seed-audio"),
  hstsMaxAge = DEFAULTS.hstsMaxAge,
  upstreamTimeoutMs = DEFAULTS.upstreamTimeoutMs,
  wsIdleTimeoutMs = DEFAULTS.wsIdleTimeoutMs,
  bodyLimitBytes = DEFAULTS.bodyLimitBytes,
  headerTimeoutMs = DEFAULTS.headerTimeoutMs,
  bootstrapRatePerMinute = DEFAULTS.bootstrapRatePerMinute,
  bootstrapBurst = DEFAULTS.bootstrapBurst,
  log = (entry) => process.stdout.write(jsonLine(entry)),
} = {}) {
  if (!cert || !key) {
    throw new TypeError("인증서와 키가 필요하다");
  }
  const [listenHost, listenPortText] = splitHostPort(listen);
  const upstreams = { core: new URL(core), realtime: new URL(realtime), web: new URL(web) };
  const agents = {
    core: new http.Agent({ keepAlive: true, maxSockets: 64 }),
    web: new http.Agent({ keepAlive: true, maxSockets: 64 }),
    realtime: new http.Agent({ keepAlive: true, maxSockets: 16 }),
  };
  const host = serviceHost.toLowerCase();
  const bucket = new LeakyBucket({ ratePerMinute: bootstrapRatePerMinute, burst: bootstrapBurst });
  const context = tls.createSecureContext({ cert, key, minVersion: "TLSv1.2" });
  const sockets = new Set();

  const securityHeaders = (route) => {
    const headers = {
      "strict-transport-security": `max-age=${hstsMaxAge}`,
      "x-content-type-options": "nosniff",
      "referrer-policy": "no-referrer",
      "permissions-policy": "microphone=(self), camera=(), geolocation=()",
    };
    if (route !== "web") {
      headers["content-security-policy"] = API_CSP;
    }
    return headers;
  };

  const access = (entry) => {
    if (
      entry.method === "GET" &&
      (entry.status === 304 || (entry.status >= 200 && entry.status < 300)) &&
      POLLING_PATHS.some((re) => re.test(entry.path))
    ) {
      return;
    }
    log({ ts: new Date().toISOString(), event: "EDGE_ACCESS", ...entry });
  };

  const sendEnvelope = (res, status, code, traceId, route, extraHeaders = {}) => {
    const body = Buffer.from(JSON.stringify({ code, message: EDGE_ERRORS[code], traceId }), "utf8");
    res.writeHead(status, {
      ...securityHeaders(route === "web" ? "edge" : route),
      "content-type": "application/json",
      "content-length": String(body.length),
      "x-trace-id": traceId,
      "cache-control": "no-store",
      ...extraHeaders,
    });
    res.end(body);
    return body.length;
  };

  const server = https.createServer(
    {
      SNICallback: (servername, callback) => {
        if (typeof servername === "string" && servername.toLowerCase() === host) {
          callback(null, context);
        } else {
          callback(new Error("unrecognized name"));
        }
      },
      cert,
      key,
      minVersion: "TLSv1.2",
      secureOptions: cryptoConstants.SSL_OP_NO_TICKET,
    },
    (req, res) => {
      void handleRequest(req, res);
    },
  );
  server.headersTimeout = headerTimeoutMs;
  server.requestTimeout = headerTimeoutMs;
  server.on("secureConnection", (socket) => {
    if (!socket.servername || socket.servername.toLowerCase() !== host) {
      // SNI 없는 연결도 기본 서버처럼 거절한다(ssl_reject_handshake)
      socket.destroy();
    }
  });
  server.on("connection", (socket) => {
    sockets.add(socket);
    socket.on("close", () => sockets.delete(socket));
  });
  server.on("tlsClientError", () => {});
  server.on("clientError", (_error, socket) => socket.destroy());

  async function handleRequest(req, res) {
    const started = Date.now();
    const traceId = newTraceId();
    const method = req.method ?? "GET";
    if (hostnameOf(req.headers.host) !== host) {
      req.socket.destroy();
      return;
    }
    const target = normalizeRequestTarget(req.url ?? "");
    if (!target.ok) {
      const bytes = sendEnvelope(res, 400, "VALIDATION_FAILED", traceId, "edge", {
        connection: "close",
      });
      access({
        method,
        path: "(rejected)",
        status: 400,
        bytes,
        ms: Date.now() - started,
        traceId,
        route: "edge",
      });
      req.resume();
      return;
    }
    const route = routeFor(target.path);
    const finishLog = (status, bytes) =>
      access({
        method,
        path: target.path,
        status,
        bytes,
        ms: Date.now() - started,
        traceId,
        route,
      });

    if (route === "forbidden") {
      res.writeHead(403, {
        ...securityHeaders("edge"),
        "content-length": "0",
        "x-trace-id": traceId,
      });
      res.end();
      finishLog(403, 0);
      req.resume();
      return;
    }
    if (route === "bootstrap" && !bucket.take(clientAddress(req.socket))) {
      finishLog(429, sendEnvelope(res, 429, "RATE_LIMITED", traceId, "core"));
      req.resume();
      return;
    }
    const declared = Number(req.headers["content-length"] ?? "0");
    if (Number.isFinite(declared) && declared > bodyLimitBytes) {
      finishLog(
        400,
        sendEnvelope(res, 400, "VALIDATION_FAILED", traceId, "edge", { connection: "close" }),
      );
      req.resume();
      return;
    }
    let body;
    try {
      body = await readBody(req, bodyLimitBytes);
    } catch {
      if (!res.headersSent) {
        finishLog(
          400,
          sendEnvelope(res, 400, "VALIDATION_FAILED", traceId, "edge", { connection: "close" }),
        );
      }
      return;
    }
    if (route === "media") {
      await serveMedia(req, res, target.path, traceId, finishLog);
      return;
    }
    const upstreamName = route === "bootstrap" ? "core" : route;
    proxyHttp({ req, res, body, target, traceId, upstreamName, route, finishLog });
  }

  function proxyHttp({ req, res, body, target, traceId, upstreamName, route, finishLog }) {
    const upstream = upstreams[upstreamName];
    const address = clientAddress(req.socket);
    const headers = forwardHeaders(req.rawHeaders, {
      "X-Forwarded-For": address,
      "X-Real-IP": address,
      "X-Forwarded-Proto": "https",
      "X-Trace-Id": traceId,
    }).filter(([name]) => name.toLowerCase() !== "content-length");
    if (body.length > 0 || !["GET", "HEAD", "OPTIONS"].includes(req.method)) {
      headers.push(["Content-Length", String(body.length)]);
    }
    let settled = false;
    const upstreamReq = http.request({
      hostname: upstream.hostname,
      port: upstream.port || 80,
      method: req.method,
      path: `${target.forwardPath}${target.query}`,
      headers: headers.flat(),
      agent: agents[upstreamName],
      setHost: false,
    });
    const timer = setTimeout(() => {
      if (!settled) {
        settled = true;
        upstreamReq.destroy();
        finishLog(504, sendEnvelope(res, 504, "UPSTREAM_UNAVAILABLE", traceId, route));
      }
    }, upstreamTimeoutMs);
    upstreamReq.on("response", (upstreamRes) => {
      if (settled) {
        upstreamRes.resume();
        return;
      }
      settled = true;
      clearTimeout(timer);
      const responseHeaders = {};
      for (let i = 0; i < upstreamRes.rawHeaders.length; i += 2) {
        const name = upstreamRes.rawHeaders[i].toLowerCase();
        if (HOP_BY_HOP.has(name)) {
          continue;
        }
        const value = upstreamRes.rawHeaders[i + 1];
        if (responseHeaders[name] === undefined) {
          responseHeaders[name] = value;
        } else {
          responseHeaders[name] = [].concat(responseHeaders[name], value);
        }
      }
      Object.assign(responseHeaders, securityHeaders(route === "bootstrap" ? "core" : route));
      res.writeHead(upstreamRes.statusCode ?? 502, responseHeaders);
      let bytes = 0;
      upstreamRes.on("data", (chunk) => {
        bytes += chunk.length;
      });
      upstreamRes.pipe(res);
      upstreamRes.on("end", () => finishLog(upstreamRes.statusCode ?? 502, bytes));
      upstreamRes.on("error", () => res.destroy());
    });
    upstreamReq.on("error", () => {
      if (settled) {
        res.destroy();
        return;
      }
      settled = true;
      clearTimeout(timer);
      finishLog(502, sendEnvelope(res, 502, "UPSTREAM_UNAVAILABLE", traceId, route));
    });
    upstreamReq.end(body);
  }

  async function serveMedia(req, res, normalizedPath, traceId, finishLog) {
    const name = normalizedPath.slice("/media/approved/".length);
    if (req.method !== "GET" && req.method !== "HEAD") {
      res.writeHead(405, {
        ...securityHeaders("media"),
        allow: "GET, HEAD",
        "content-length": "0",
        "x-trace-id": traceId,
      });
      res.end();
      finishLog(405, 0);
      return;
    }
    if (!MEDIA_FILE.test(name)) {
      res.writeHead(404, {
        ...securityHeaders("media"),
        "content-length": "0",
        "x-trace-id": traceId,
      });
      res.end();
      finishLog(404, 0);
      return;
    }
    const file = path.join(mediaDir, name);
    try {
      const info = await stat(file);
      if (!info.isFile()) {
        throw new Error("not a file");
      }
      const data = req.method === "HEAD" ? null : await readFile(file);
      res.writeHead(200, {
        ...securityHeaders("media"),
        "content-type": "audio/wav",
        "content-length": String(info.size),
        "x-trace-id": traceId,
      });
      res.end(data ?? undefined);
      finishLog(200, data ? data.length : 0);
    } catch {
      res.writeHead(404, {
        ...securityHeaders("media"),
        "content-length": "0",
        "x-trace-id": traceId,
      });
      res.end();
      finishLog(404, 0);
    }
  }

  server.on("upgrade", (req, socket, head) => {
    const started = Date.now();
    const traceId = newTraceId();
    socket.on("error", () => {});
    const reject = (status, reason, loggedPath) => {
      socket.end(
        `HTTP/1.1 ${status} ${reason}\r\nConnection: close\r\nContent-Length: 0\r\nX-Trace-Id: ${traceId}\r\n\r\n`,
      );
      access({
        method: req.method,
        path: loggedPath,
        status,
        bytes: 0,
        ms: Date.now() - started,
        traceId,
        route: "edge",
        upgrade: true,
      });
    };
    if (hostnameOf(req.headers.host) !== host) {
      socket.destroy();
      return;
    }
    const target = normalizeRequestTarget(req.url ?? "");
    if (!target.ok) {
      reject(400, "Bad Request", "(rejected)");
      return;
    }
    if (routeFor(target.path) !== "realtime") {
      reject(404, "Not Found", target.path);
      return;
    }
    const upstream = upstreams.realtime;
    const address = clientAddress(socket);
    const headers = forwardHeaders(req.rawHeaders, {
      Upgrade: req.headers.upgrade ?? "websocket",
      Connection: "upgrade",
      "X-Forwarded-For": address,
      "X-Real-IP": address,
      "X-Forwarded-Proto": "https",
      "X-Trace-Id": traceId,
    });
    const upstreamSocket = net.connect({
      host: upstream.hostname,
      port: Number(upstream.port || 80),
    });
    let status = null;
    let connected = false;
    const closeBoth = () => {
      socket.destroy();
      upstreamSocket.destroy();
    };
    upstreamSocket.setTimeout(upstreamTimeoutMs);
    upstreamSocket.on("timeout", () => {
      if (!connected || status === null) {
        if (!socket.destroyed) {
          writeUpgradeError(socket, 504, traceId);
        }
        upstreamSocket.destroy();
        return;
      }
      closeBoth();
    });
    upstreamSocket.on("connect", () => {
      connected = true;
      const lines = [
        `${req.method} ${target.forwardPath}${target.query} HTTP/1.1`,
        ...headers.map(([name, value]) => `${name}: ${value}`),
        "",
        "",
      ];
      upstreamSocket.write(lines.join("\r\n"));
      if (head && head.length > 0) {
        upstreamSocket.write(head);
      }
      upstreamSocket.once("data", (first) => {
        const match = /^HTTP\/1\.[01] (\d{3})/.exec(first.subarray(0, 32).toString("latin1"));
        status = match ? Number(match[1]) : 502;
        socket.write(first);
        upstreamSocket.pipe(socket);
        socket.pipe(upstreamSocket);
        if (status === 101) {
          upstreamSocket.setTimeout(wsIdleTimeoutMs);
          socket.setTimeout(wsIdleTimeoutMs);
          socket.on("timeout", closeBoth);
        }
      });
    });
    upstreamSocket.on("error", () => {
      if (status === null && !socket.destroyed) {
        writeUpgradeError(socket, 502, traceId);
        return;
      }
      closeBoth();
    });
    upstreamSocket.on("close", () => {
      socket.destroy();
      access({
        method: req.method,
        path: "/ws/device",
        status: status ?? 502,
        bytes: 0,
        ms: Date.now() - started,
        traceId,
        route: "realtime",
        upgrade: true,
      });
    });
    socket.on("close", () => upstreamSocket.destroy());
  });

  function writeUpgradeError(socket, status, traceId) {
    const body = JSON.stringify({
      code: "UPSTREAM_UNAVAILABLE",
      message: EDGE_ERRORS.UPSTREAM_UNAVAILABLE,
      traceId,
    });
    const reason = status === 504 ? "Gateway Timeout" : "Bad Gateway";
    socket.end(
      `HTTP/1.1 ${status} ${reason}\r\nContent-Type: application/json\r\nContent-Length: ${Buffer.byteLength(body)}\r\n` +
        `X-Trace-Id: ${traceId}\r\nContent-Security-Policy: ${API_CSP}\r\nConnection: close\r\n\r\n${body}`,
    );
  }

  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(Number(listenPortText), listenHost, () => {
      server.off("error", reject);
      resolve();
    });
  });
  const address = server.address();
  const port = typeof address === "object" && address ? address.port : Number(listenPortText);
  log({
    ts: new Date().toISOString(),
    event: "EDGE_READY",
    listen: `${listenHost}:${port}`,
    serviceHost: host,
  });

  async function close() {
    for (const socket of sockets) {
      socket.destroy();
    }
    for (const agent of Object.values(agents)) {
      agent.destroy();
    }
    await new Promise((resolve) => server.close(() => resolve()));
  }

  return { host: listenHost, port, close };
}

function readBody(req, limit) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on("data", (chunk) => {
      size += chunk.length;
      if (size > limit) {
        req.pause();
        reject(new RangeError("본문 한도 초과"));
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => resolve(Buffer.concat(chunks)));
    req.on("error", reject);
  });
}

function splitHostPort(text) {
  const at = text.lastIndexOf(":");
  if (at <= 0) {
    throw new RangeError(`--listen 형식이 틀렸다: ${text}`);
  }
  const portText = text.slice(at + 1);
  if (!/^[0-9]+$/.test(portText)) {
    throw new RangeError(`--listen 포트가 틀렸다: ${text}`);
  }
  return [text.slice(0, at).replace(/^\[|\]$/g, ""), portText];
}

/** 인자를 읽는다. 틀리면 RangeError. */
export function parseArgs(argv, env = process.env) {
  const options = { serviceHost: env.SERVICE_HOST || DEFAULTS.serviceHost };
  const names = {
    "--listen": "listen",
    "--cert": "certFile",
    "--key": "keyFile",
    "--service-host": "serviceHost",
    "--core": "core",
    "--realtime": "realtime",
    "--web": "web",
    "--media-dir": "mediaDir",
    "--hsts-max-age": "hstsMaxAge",
    "--upstream-timeout-ms": "upstreamTimeoutMs",
    "--ws-idle-timeout-ms": "wsIdleTimeoutMs",
  };
  const numeric = new Set(["hstsMaxAge", "upstreamTimeoutMs", "wsIdleTimeoutMs"]);
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--help" || arg === "-h") {
      options.help = true;
      continue;
    }
    const name = names[arg];
    if (!name) {
      throw new RangeError(`모르는 인자: ${arg}`);
    }
    const value = argv[i + 1];
    if (value === undefined || value.startsWith("--")) {
      throw new RangeError(`${arg}에 값이 없다`);
    }
    i += 1;
    if (numeric.has(name)) {
      if (!/^[0-9]+$/.test(value) || Number(value) <= 0) {
        throw new RangeError(`${arg}는 양의 정수여야 한다`);
      }
      options[name] = Number(value);
    } else {
      options[name] = value;
    }
  }
  if (!options.help) {
    for (const url of ["core", "realtime", "web"]) {
      if (options[url] !== undefined) {
        const parsed = new URL(options[url]);
        if (parsed.protocol !== "http:") {
          throw new RangeError(`--${url}는 http:// 주소여야 한다`);
        }
      }
    }
    if (!options.certFile || !options.keyFile) {
      throw new RangeError("--cert와 --key가 필요하다");
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
    io.stderr.write(`dev-edge: ${error.message}\n${USAGE}`);
    return { exitCode: 2 };
  }
  if (options.help) {
    io.stdout.write(USAGE);
    return { exitCode: 0 };
  }
  const { certFile, keyFile, help: _help, ...rest } = options;
  try {
    const edge = await startEdge({
      ...rest,
      cert: readFileSync(certFile),
      key: readFileSync(keyFile),
      log: (entry) => io.stdout.write(jsonLine(entry)),
    });
    return { exitCode: null, edge };
  } catch (error) {
    io.stderr.write(`dev-edge 기동 실패: ${error.code ?? error.message}\n`);
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
      await result.edge.close();
      process.exit(0);
    };
    process.on("SIGTERM", stop);
    process.on("SIGINT", stop);
  }
}
