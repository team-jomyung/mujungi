// host 모드 Nginx 대역 시험 (U1 3.5 Step 46 · J133 · SD-3 · OD-7 · BR8.9 · R4-17 · 편차 (r)(y) · SEC-12)
// 시험마다 openssl로 임시 CA · localhost 인증서를 만들고(J127, 저장소에 두지 않음), 무작위 포트의 업스트림 대역(core · realtime WS ·
// web)을 둔 채 대역을 띄워 /internal/ · /actuator/ 403, 위조 X-Trace-Id · X-Forwarded-For 덮어쓰기, WS 중계를 확인한다.
import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import tls from "node:tls";
import { fileURLToPath } from "node:url";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import WebSocket, { WebSocketServer } from "ws";
import { HttpRequestError, createHttpClient } from "../../lib/httpClient.mjs";
import {
  EDGE_ERRORS,
  LeakyBucket,
  main,
  newTraceId,
  normalizeRequestTarget,
  parseArgs,
  routeFor,
  startEdge,
} from "../../dev-edge/edge.mjs";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..", "..");
const FORGED_TRACE = "0123456789abcdef0123456789abcdef";
const FORGED_IP = "203.0.113.7";

/** 임시 CA와 localhost 서버 인증서(EC P-256, 하루짜리). */
function makeCertificates(dir) {
  const run = (args) =>
    execFileSync("openssl", args, { cwd: dir, stdio: ["ignore", "ignore", "pipe"] });
  const ec = ["-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes"];
  run([
    "req",
    "-x509",
    ...ec,
    "-keyout",
    "ca.key",
    "-out",
    "ca.crt",
    "-days",
    "1",
    "-subj",
    "/CN=mujoong-test-ca",
    "-addext",
    "basicConstraints=critical,CA:TRUE",
    "-addext",
    "keyUsage=critical,keyCertSign,cRLSign",
  ]);
  run(["req", ...ec, "-keyout", "server.key", "-out", "server.csr", "-subj", "/CN=localhost"]);
  writeFileSync(
    path.join(dir, "ext.cnf"),
    "subjectAltName=DNS:localhost\nbasicConstraints=CA:FALSE\nextendedKeyUsage=serverAuth\n",
  );
  run([
    "x509",
    "-req",
    "-in",
    "server.csr",
    "-CA",
    "ca.crt",
    "-CAkey",
    "ca.key",
    "-CAcreateserial",
    "-out",
    "server.crt",
    "-days",
    "1",
    "-extfile",
    "ext.cnf",
  ]);
  return {
    ca: readFileSync(path.join(dir, "ca.crt")),
    cert: readFileSync(path.join(dir, "server.crt")),
    key: readFileSync(path.join(dir, "server.key")),
  };
}

async function listen(server) {
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  return `http://127.0.0.1:${server.address().port}`;
}

let tmp;
let certs;
let core;
let realtime;
let web;
let edge;
let logs;
let client;
const coreSeen = [];
const realtimeSeen = [];

beforeAll(async () => {
  tmp = mkdtempSync(path.join(os.tmpdir(), "mujoong-edge-"));
  certs = makeCertificates(tmp);

  core = http.createServer((req, res) => {
    const chunks = [];
    req.on("data", (chunk) => chunks.push(chunk));
    req.on("end", () => {
      const pathname = req.url.split("?")[0];
      coreSeen.push({
        method: req.method,
        url: req.url,
        headers: req.headers,
        bodyBytes: Buffer.concat(chunks).length,
      });
      if (pathname === "/api/app503") {
        res.writeHead(503, { "content-type": "application/json", "retry-after": "5" });
        res.end(
          '{"code":"SERVICE_NOT_READY","message":"잠시 뒤 다시 시도해 주세요.","traceId":"x"}',
        );
        return;
      }
      if (pathname === "/api/slow") {
        return; // 응답하지 않는다(504)
      }
      res.writeHead(200, {
        "content-type": "application/json",
        "x-trace-id": req.headers["x-trace-id"] ?? "none",
      });
      res.end(JSON.stringify({ path: req.url, bodyBytes: Buffer.concat(chunks).length }));
    });
  });
  realtime = http.createServer((_req, res) => {
    res.writeHead(404);
    res.end();
  });
  const wss = new WebSocketServer({ noServer: true });
  realtime.on("upgrade", (req, socket, head) => {
    realtimeSeen.push({ url: req.url, headers: req.headers });
    if (req.headers.origin !== "https://localhost:8443") {
      socket.end("HTTP/1.1 403 Forbidden\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => {
      ws.on("message", (data, isBinary) => ws.send(data, { binary: isBinary }));
    });
  });
  realtime.wss = wss;
  web = http.createServer((req, res) => {
    res.writeHead(200, { "content-type": "text/html" });
    res.end(`<html>${req.url}</html>`);
  });
  const coreUrl = await listen(core);
  const realtimeUrl = await listen(realtime);
  await listen(web);
  logs = [];
  edge = await startEdge({
    listen: "127.0.0.1:0",
    cert: certs.cert,
    key: certs.key,
    serviceHost: "localhost",
    core: coreUrl,
    realtime: realtimeUrl,
    web: `http://127.0.0.1:${web.address().port}`,
    upstreamTimeoutMs: 400,
    bootstrapRatePerMinute: 1,
    bootstrapBurst: 3,
    log: (entry) => logs.push(entry),
  });
  client = createHttpClient({
    baseUrl: `https://localhost:${edge.port}`,
    ca: certs.ca,
    origin: "https://localhost:8443",
    timeoutMs: 5000,
  });
});

afterAll(async () => {
  await edge?.close();
  for (const ws of realtime?.wss?.clients ?? []) ws.terminate();
  for (const server of [core, realtime, web]) {
    server?.closeAllConnections();
    await new Promise((resolve) => server?.close(resolve));
  }
  rmSync(tmp, { recursive: true, force: true });
});

beforeEach(() => {
  coreSeen.length = 0;
  realtimeSeen.length = 0;
  logs.length = 0;
});

describe("막는 경로(SD-3 · R3-21 B1 필수)", () => {
  it("/internal/ · /actuator/는 업스트림에 닿지 않고 403이다(점 구간 정규화 뒤에도)", async () => {
    for (const target of [
      "/internal/x",
      "/actuator/health",
      "/api/../internal/events",
      "//actuator/prometheus",
    ]) {
      const res = await client.get(target);
      expect(res.status, target).toBe(403);
      expect(res.header("content-security-policy")).toBe(
        "default-src 'none'; frame-ancestors 'none'",
      );
    }
    expect(coreSeen).toHaveLength(0);
  });

  it("원시 URI에 ; · \\ · %2e · %2f · %5c가 있으면 400 VALIDATION_FAILED 봉투다(우회 방지)", async () => {
    for (const target of [
      "/api/..;/internal/x",
      "/api/%2e%2e/internal/x",
      "/api/a%2Fb",
      "/api/a%5cb",
      "/api/a\\b",
    ]) {
      const res = await client.get(target);
      expect(res.status, target).toBe(400);
      expect(res.json()).toMatchObject({
        code: "VALIDATION_FAILED",
        message: EDGE_ERRORS.VALIDATION_FAILED,
      });
      expect(res.json().traceId).toBe(res.traceId);
    }
    expect(coreSeen).toHaveLength(0);
  });

  it("본문 64KB(65,536바이트)까지는 넘기고 1바이트라도 넘으면 400 봉투다(편차 (r))", async () => {
    const atLimit = await client.post("/api/device/register", undefined, {
      body: Buffer.alloc(65_536, 0x61),
    });
    expect(atLimit.status).toBe(200);
    expect(coreSeen.at(-1).bodyBytes).toBe(65_536);
    const over = await client.post("/api/device/register", undefined, {
      body: Buffer.alloc(65_537, 0x61),
    });
    expect(over.status).toBe(400);
    expect(over.errorCode).toBe("VALIDATION_FAILED");
    expect(coreSeen).toHaveLength(1);
  });
});

describe("헤더 덮어쓰기(OD-7 · BR8.9)", () => {
  it("위조 X-Trace-Id · X-Forwarded-For · X-Real-IP · Forwarded를 버리고 접속 주소와 새 traceId를 넘긴다", async () => {
    const res = await client.get("/api/home", {
      headers: {
        "X-Trace-Id": FORGED_TRACE,
        "X-Forwarded-For": FORGED_IP,
        "X-Real-IP": FORGED_IP,
        Forwarded: `for=${FORGED_IP}`,
      },
    });
    expect(res.status).toBe(200);
    const seen = coreSeen[0].headers;
    expect(seen["x-forwarded-for"]).toBe("127.0.0.1");
    expect(seen["x-real-ip"]).toBe("127.0.0.1");
    expect(seen["x-forwarded-proto"]).toBe("https");
    expect(seen.forwarded).toBeUndefined();
    expect(seen["x-trace-id"]).toMatch(/^[0-9a-f]{32}$/);
    expect(seen["x-trace-id"]).not.toBe(FORGED_TRACE);
    expect(res.traceId).toBe(seen["x-trace-id"]);
    expect(seen.host).toBe(`localhost:${edge.port}`);
  });

  it("보안 헤더: 모든 응답에 HSTS · nosniff · Permissions-Policy, CSP는 /api/에만(Next.js 경로 제외)", async () => {
    const api = await client.get("/api/home");
    const page = await client.get("/device");
    for (const res of [api, page]) {
      expect(res.header("strict-transport-security")).toMatch(/^max-age=\d+/);
      expect(res.header("x-content-type-options")).toBe("nosniff");
      expect(res.header("permissions-policy")).toBe("microphone=(self), camera=(), geolocation=()");
    }
    expect(api.header("content-security-policy")).toBe(
      "default-src 'none'; frame-ancestors 'none'",
    );
    expect(page.header("content-security-policy")).toBeNull();
    expect(page.text()).toBe("<html>/device</html>");
  });

  it("접근 로그에는 쿼리 · 쿠키가 없고 폴링 2xx는 남기지 않는다", async () => {
    await client.get("/api/home?token=query-secret-value", {
      headers: { cookie: "MUJOONG_SESSION=cookie-secret-value" },
    });
    await client.get("/api/device/bootstrap/status");
    const text = JSON.stringify(logs);
    expect(text).not.toContain("query-secret-value");
    expect(text).not.toContain("cookie-secret-value");
    expect(logs.map((entry) => entry.path)).toEqual(["/api/home"]);
    expect(logs[0]).toMatchObject({
      event: "EDGE_ACCESS",
      method: "GET",
      status: 200,
      route: "core",
    });
  });
});

describe("업스트림 오류 · 한도(편차 (y) · BR8.9)", () => {
  it("업스트림이 낸 503은 그대로 통과하고, 응답이 없으면 504 · 닿지 않으면 502 UPSTREAM_UNAVAILABLE이다", async () => {
    const passed = await client.get("/api/app503");
    expect(passed.status).toBe(503);
    expect(passed.errorCode).toBe("SERVICE_NOT_READY");
    expect(passed.header("retry-after")).toBe("5");

    const slow = await client.get("/api/slow");
    expect(slow.status).toBe(504);
    expect(slow.json()).toMatchObject({
      code: "UPSTREAM_UNAVAILABLE",
      message: EDGE_ERRORS.UPSTREAM_UNAVAILABLE,
    });

    const closed = http.createServer();
    const url = await listen(closed);
    await new Promise((resolve) => closed.close(resolve));
    const lonely = await startEdge({
      listen: "127.0.0.1:0",
      cert: certs.cert,
      key: certs.key,
      web: url,
      log: () => {},
    });
    try {
      const lonelyClient = createHttpClient({
        baseUrl: `https://localhost:${lonely.port}`,
        ca: certs.ca,
        timeoutMs: 5000,
      });
      const down = await lonelyClient.get("/");
      expect(down.status).toBe(502);
      expect(down.errorCode).toBe("UPSTREAM_UNAVAILABLE");
    } finally {
      await lonely.close();
    }
  });

  it("= /api/device/bootstrap은 IP당 rate · burst nodelay를 넘으면 429 RATE_LIMITED이고 다른 경로는 영향이 없다", async () => {
    const statuses = [];
    for (let i = 0; i < 5; i += 1) {
      statuses.push((await client.post("/api/device/bootstrap")).status);
    }
    expect(statuses).toEqual([200, 200, 200, 200, 429]);
    expect((await client.post("/api/device/register", { code: "ABCD2345" })).status).toBe(200);
    const limited = await client.post("/api/device/bootstrap");
    expect(limited.json()).toMatchObject({
      code: "RATE_LIMITED",
      message: EDGE_ERRORS.RATE_LIMITED,
    });

    let now = 0;
    const bucket = new LeakyBucket({ ratePerMinute: 60, burst: 0, now: () => now });
    expect(bucket.take("a")).toBe(true);
    expect(bucket.take("a")).toBe(false);
    expect(bucket.take("b")).toBe(true);
    now += 1000;
    expect(bucket.take("a")).toBe(true);
  });

  it("승인 음성은 .wav GET · HEAD만, 그 밖은 404 · 405다", async () => {
    const wav = await client.get("/media/approved/closing-v1.wav");
    expect(wav.status).toBe(200);
    expect(wav.header("content-type")).toBe("audio/wav");
    expect(
      wav.body.equals(readFileSync(path.join(repoRoot, "infra", "seed-audio", "closing-v1.wav"))),
    ).toBe(true);
    const head = await client.request({ method: "HEAD", path: "/media/approved/closing-v1.wav" });
    expect(head.status).toBe(200);
    expect(head.body.length).toBe(0);
    expect((await client.get("/media/approved/notes.txt")).status).toBe(404);
    expect((await client.get("/media/approved/missing.wav")).status).toBe(404);
    expect((await client.post("/media/approved/closing-v1.wav")).status).toBe(405);
  });
});

describe("TLS · Host(SEC-12)", () => {
  it("SNI가 서비스 호스트가 아니면 핸드셰이크를 거절하고, Host가 다르면 응답 없이 닫는다", async () => {
    const outcome = await new Promise((resolve) => {
      const socket = tls.connect({
        host: "127.0.0.1",
        port: edge.port,
        servername: "other.invalid",
        ca: certs.ca,
        rejectUnauthorized: true,
      });
      socket.once("secureConnect", () => {
        socket.destroy();
        resolve("connected");
      });
      socket.once("error", (error) => resolve(error.code ?? "error"));
    });
    expect(outcome).not.toBe("connected");

    const error = await client
      .get("/api/home", { headers: { host: "other.invalid" } })
      .catch((e) => e);
    expect(error).toBeInstanceOf(HttpRequestError);
    expect(coreSeen).toHaveLength(0);
  });
});

describe("WSS 중계(/ws/device)", () => {
  function open(origin, headers = {}) {
    const ws = new WebSocket(`wss://localhost:${edge.port}/ws/device`, {
      ca: certs.ca,
      origin,
      headers,
      handshakeTimeout: 3000,
    });
    ws.on("error", () => {});
    return new Promise((resolve, reject) => {
      ws.once("open", () => resolve(ws));
      ws.once("unexpected-response", (req, res) => {
        res.resume();
        req.destroy();
        reject(Object.assign(new Error("refused"), { status: res.statusCode }));
      });
      ws.once("error", reject);
    });
  }

  it("업그레이드를 realtime으로 넘기며 위조 헤더를 덮어쓰고, 텍스트 · 바이너리를 그대로 나른다", async () => {
    const ws = await open("https://localhost:8443", {
      "x-trace-id": FORGED_TRACE,
      "x-forwarded-for": FORGED_IP,
      cookie: "MUJOONG_DEVICE=tok",
    });
    try {
      const seen = realtimeSeen[0].headers;
      expect(realtimeSeen[0].url).toBe("/ws/device");
      expect(seen["x-forwarded-for"]).toBe("127.0.0.1");
      expect(seen["x-trace-id"]).toMatch(/^[0-9a-f]{32}$/);
      expect(seen["x-trace-id"]).not.toBe(FORGED_TRACE);
      expect(seen.cookie).toBe("MUJOONG_DEVICE=tok");
      expect(seen.origin).toBe("https://localhost:8443");

      const echoed = [];
      ws.on("message", (data, isBinary) => echoed.push({ data: Buffer.from(data), isBinary }));
      const frame = Buffer.concat([Buffer.from([0x01, 0, 0, 0, 1]), Buffer.alloc(960, 7)]);
      ws.send('{"type":"HELLO"}');
      ws.send(frame, { binary: true });
      const deadline = Date.now() + 3000;
      while (echoed.length < 2 && Date.now() < deadline) {
        await new Promise((resolve) => setTimeout(resolve, 10));
      }
      expect(echoed[0]).toMatchObject({ isBinary: false });
      expect(echoed[0].data.toString()).toBe('{"type":"HELLO"}');
      expect(echoed[1].isBinary).toBe(true);
      expect(echoed[1].data.equals(frame)).toBe(true);
    } finally {
      ws.terminate();
    }
  });

  it("realtime이 거절한 업그레이드(Origin 403)는 그대로 전하고, 다른 경로 업그레이드는 404다", async () => {
    await expect(open("https://evil.invalid")).rejects.toMatchObject({ status: 403 });
    const other = new WebSocket(`wss://localhost:${edge.port}/api/home`, {
      ca: certs.ca,
      handshakeTimeout: 3000,
    });
    other.on("error", () => {});
    const status = await new Promise((resolve) => {
      other.once("unexpected-response", (req, res) => {
        res.resume();
        req.destroy();
        resolve(res.statusCode);
      });
      other.once("open", () => resolve(101));
    });
    expect(status).toBe(404);
  });
});

describe("길 고르기 · 인자 · 문구", () => {
  it("Nginx location 순서와 같은 결과로 길을 고른다", () => {
    expect(routeFor("/internal/events")).toBe("forbidden");
    expect(routeFor("/actuator/health")).toBe("forbidden");
    expect(routeFor("/ws/device")).toBe("realtime");
    expect(routeFor("/api/device/bootstrap")).toBe("bootstrap");
    expect(routeFor("/api/device/bootstrap/status")).toBe("core");
    expect(routeFor("/media/approved/closing-v1.wav")).toBe("media");
    expect(routeFor("/_next/static/x.js")).toBe("web");
    expect(normalizeRequestTarget("/api//home/./x?y=1")).toEqual({
      ok: true,
      path: "/api/home/x",
      forwardPath: "/api/home/x",
      query: "?y=1",
    });
    expect(normalizeRequestTarget("relative")).toEqual({ ok: false });
    expect(normalizeRequestTarget("/a%00b")).toEqual({ ok: false });
    expect(newTraceId()).toMatch(/^[0-9a-f]{32}$/);
  });

  it("대역이 만드는 오류 문장은 contracts/errors.md 표와 같다", () => {
    const table = readFileSync(path.join(repoRoot, "contracts", "errors.md"), "utf8");
    for (const [code, message] of Object.entries(EDGE_ERRORS)) {
      const row = table.split("\n").find((line) => line.startsWith(`| \`${code}\` |`));
      expect(row, code).toBeDefined();
      expect(row.split("|")[3].trim(), code).toBe(message);
    }
  });

  it("인자 오류는 종료 코드 2, --help는 0이다", async () => {
    const out = [];
    const io = {
      stdout: { write: (text) => out.push(text) },
      stderr: { write: (text) => out.push(text) },
    };
    expect((await main([], io)).exitCode).toBe(2);
    expect((await main(["--cert", "a", "--key", "b", "--core", "https://x"], io)).exitCode).toBe(2);
    expect((await main(["--cert", "a", "--key", "b", "--hsts-max-age", "0"], io)).exitCode).toBe(2);
    expect((await main(["--bogus"], io)).exitCode).toBe(2);
    expect((await main(["--help"], io)).exitCode).toBe(0);
    expect(
      (await main(["--cert", "/nonexistent.pem", "--key", "/nonexistent.key"], io)).exitCode,
    ).toBe(1);
    expect(
      parseArgs(["--cert", "c", "--key", "k"], { SERVICE_HOST: "svc.invalid" }).serviceHost,
    ).toBe("svc.invalid");
  });
});
