// 시험 도구 쿠키 저장소 · HTTP 클라이언트 시험 (U1 3.5 Step 46 · RFC 6265 · SD-5 · SEC-16 · F4 · SEC-05)
// C안이 기기 · 보호자 쿠키 속성을 단언하고 다음 요청에 Cookie를 붙이는 기준이라 브라우저 규칙(Secure · Path · Max-Age=0)을 확인한다.
// HTTP 클라이언트는 루프백 http 대역 서버(무작위 포트)로 Origin · Cookie · 제한 시간을 확인한다.
import http from "node:http";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { CookieJar, defaultPath, parseSetCookie, pathMatches } from "../../lib/cookieJar.mjs";
import { HttpRequestError, HttpTimeoutError, createHttpClient } from "../../lib/httpClient.mjs";

describe("Set-Cookie 읽기", () => {
  it("속성 이름은 대소문자와 무관하고 HttpOnly · Secure · SameSite · Path · Max-Age를 읽는다", () => {
    const cookie = parseSetCookie(
      "MUJOONG_DEVICE=abc.def; Path=/; max-age=34560000; secure; HTTPONLY; SameSite=Strict",
    );
    expect(cookie.name).toBe("MUJOONG_DEVICE");
    expect(cookie.value).toBe("abc.def");
    expect(cookie.attributes).toMatchObject({
      path: "/",
      maxAge: 34_560_000,
      secure: true,
      httpOnly: true,
      sameSite: "Strict",
      domain: null,
    });
    expect(cookie.attributeNames).toEqual(["path", "max-age", "secure", "httponly", "samesite"]);
  });

  it("Domain은 앞 점을 떼고 소문자로, 따옴표 값은 벗기고, 형식이 틀리면 null이다", () => {
    expect(parseSetCookie("a=1; Domain=.Example.INVALID").attributes.domain).toBe(
      "example.invalid",
    );
    expect(parseSetCookie('q="quoted"').value).toBe("quoted");
    expect(parseSetCookie("=novalue")).toBeNull();
    expect(parseSetCookie("noequals")).toBeNull();
    expect(parseSetCookie("")).toBeNull();
    expect(parseSetCookie(null)).toBeNull();
    expect(parseSetCookie("a=1; Max-Age=abc").attributes.maxAge).toBeNull();
  });

  it("기본 경로와 path-match는 RFC 6265 5.1.4를 따른다", () => {
    expect(defaultPath("/api/device/bootstrap")).toBe("/api/device");
    expect(defaultPath("/api")).toBe("/");
    expect(defaultPath("")).toBe("/");
    expect(pathMatches("/api/device/me", "/api/device")).toBe(true);
    expect(pathMatches("/api/device", "/api/device")).toBe(true);
    expect(pathMatches("/api/devices", "/api/device")).toBe(false);
    expect(pathMatches("/api/device/x", "/api/device/")).toBe(true);
    expect(pathMatches("/parent", "/api/device")).toBe(false);
  });
});

describe("쿠키 저장소", () => {
  const base = "https://localhost:8443";

  it("Secure 쿠키는 https · wss 요청에만 보낸다", () => {
    const jar = new CookieJar();
    jar.storeFromResponse(`${base}/api/device/bootstrap/claim`, [
      "MUJOONG_DEVICE=tok; Path=/; Secure; HttpOnly; SameSite=Strict",
    ]);
    expect(jar.cookieHeader(`${base}/api/device/me`)).toBe("MUJOONG_DEVICE=tok");
    expect(jar.cookieHeader("wss://localhost:8443/ws/device")).toBe("MUJOONG_DEVICE=tok");
    expect(jar.cookieHeader("http://localhost:8443/api/device/me")).toBeNull();
    expect(jar.cookieHeader("ws://localhost:8443/ws/device")).toBeNull();
  });

  it("Path 밖 요청에는 보내지 않고, Path가 없으면 요청의 기본 경로를 쓴다", () => {
    const jar = new CookieJar();
    jar.storeFromResponse(`${base}/api/device/bootstrap`, [
      "MUJOONG_DEVICE_BOOTSTRAP=boot; Path=/api/device; Secure; HttpOnly",
      "NOPATH=v; Secure",
    ]);
    expect(jar.cookieHeader(`${base}/api/device/bootstrap/status`)).toBe(
      "MUJOONG_DEVICE_BOOTSTRAP=boot; NOPATH=v",
    );
    expect(jar.cookieHeader(`${base}/ws/device`)).toBeNull();
    expect(jar.cookieHeader(`${base}/api/home`)).toBeNull();
    expect(jar.get("NOPATH").path).toBe("/api/device");
  });

  it("Max-Age=0 · 지난 Expires는 같은 (이름, 도메인, 경로) 쿠키를 지운다", () => {
    const jar = new CookieJar();
    jar.storeFromResponse(`${base}/api/device/bootstrap`, [
      "MUJOONG_DEVICE_BOOTSTRAP=boot; Path=/api/device; Secure",
    ]);
    // 다른 경로의 삭제는 영향이 없다
    jar.storeFromResponse(`${base}/x`, ["MUJOONG_DEVICE_BOOTSTRAP=; Path=/; Max-Age=0; Secure"]);
    expect(jar.get("MUJOONG_DEVICE_BOOTSTRAP")).not.toBeNull();
    const results = jar.storeFromResponse(`${base}/api/device/bootstrap/claim`, [
      "MUJOONG_DEVICE_BOOTSTRAP=; Path=/api/device; Max-Age=0; Secure; HttpOnly; SameSite=Strict",
    ]);
    expect(results[0].deleted).toBe(true);
    expect(jar.get("MUJOONG_DEVICE_BOOTSTRAP")).toBeNull();
    expect(jar.size).toBe(0);

    jar.storeFromResponse(`${base}/`, ["OLD=v; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT"]);
    expect(jar.get("OLD")).toBeNull();
  });

  it("Max-Age가 지나면 보내지 않는다(주입한 시계)", () => {
    let now = 1_000_000;
    const jar = new CookieJar({ now: () => now });
    jar.storeFromResponse(`${base}/`, [
      "S=v; Path=/; Max-Age=10; Expires=Fri, 01 Jan 2100 00:00:00 GMT",
    ]);
    expect(jar.cookieHeader(`${base}/`)).toBe("S=v");
    now += 10_001;
    expect(jar.cookieHeader(`${base}/`)).toBeNull();
    expect(jar.size).toBe(0);
  });

  it("다른 도메인 쿠키는 받지 않고, 같은 이름은 긴 경로부터 보낸다", () => {
    const jar = new CookieJar();
    jar.storeFromResponse(`${base}/`, ["EVIL=v; Domain=evil.invalid; Path=/"]);
    expect(jar.size).toBe(0);
    jar.storeFromResponse(`${base}/`, ["A=root; Path=/", "A=deep; Path=/api/device"]);
    expect(jar.cookieHeader(`${base}/api/device/me`)).toBe("A=deep; A=root");
    expect(jar.get("A").value).toBe("deep");
    // host-only 쿠키는 하위 도메인에 보내지 않는다
    expect(jar.cookieHeader("https://sub.localhost:8443/")).toBeNull();
  });

  it("toString은 쿠키 값을 드러내지 않는다", () => {
    const jar = new CookieJar();
    jar.storeFromResponse(`${base}/`, ["MUJOONG_SESSION=secret-session-value; Path=/; Secure"]);
    expect(String(jar)).toContain("MUJOONG_SESSION");
    expect(String(jar)).not.toContain("secret-session-value");
    jar.clear();
    expect(jar.size).toBe(0);
  });
});

describe("HTTP 클라이언트(루프백 대역 서버)", () => {
  let server;
  let baseUrl;
  let seen;

  beforeEach(async () => {
    seen = [];
    server = http.createServer((req, res) => {
      const chunks = [];
      req.on("data", (chunk) => chunks.push(chunk));
      req.on("end", () => {
        seen.push({
          method: req.method,
          url: req.url,
          headers: req.headers,
          body: Buffer.concat(chunks).toString("utf8"),
        });
        const pathname = req.url.split("?")[0];
        if (pathname === "/slow") {
          return; // 응답하지 않는다(제한 시간 확인)
        }
        if (pathname === "/login") {
          res.writeHead(200, {
            "set-cookie": ["MUJOONG_SESSION=sid; Path=/; HttpOnly; SameSite=Lax"],
            "content-type": "application/json",
          });
          res.end('{"guardianId":"g"}');
          return;
        }
        if (pathname === "/error") {
          res.writeHead(403, {
            "content-type": "application/json",
            "x-trace-id": "0123456789abcdef0123456789abcdef",
          });
          res.end(
            '{"code":"CSRF_TOKEN_INVALID","message":"m","traceId":"0123456789abcdef0123456789abcdef"}',
          );
          return;
        }
        res.writeHead(200, { "content-type": "text/plain" });
        res.end("ok");
      });
    });
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    baseUrl = `http://127.0.0.1:${server.address().port}`;
  });

  afterEach(async () => {
    server.closeAllConnections();
    await new Promise((resolve) => server.close(resolve));
  });

  it("변경 요청에만 허용 Origin을 붙이고(F4), 요청별로 바꾸거나 뺄 수 있다", async () => {
    const client = createHttpClient({ baseUrl, origin: "https://localhost:8443", timeoutMs: 2000 });
    await client.get("/a");
    await client.post("/b", { code: "X" });
    await client.post("/c", undefined, { origin: "https://evil.invalid" });
    await client.post("/d", undefined, { origin: false });

    expect(seen[0].headers.origin).toBeUndefined();
    expect(seen[1].headers.origin).toBe("https://localhost:8443");
    expect(seen[1].headers["content-type"]).toBe("application/json");
    expect(JSON.parse(seen[1].body)).toEqual({ code: "X" });
    expect(seen[2].headers.origin).toBe("https://evil.invalid");
    expect(seen[3].headers.origin).toBeUndefined();
    expect(seen[3].headers["content-length"]).toBe("0");
  });

  it("쿠키 저장소를 주면 Set-Cookie를 받고 다음 요청에 붙인다(Secure 아닌 쿠키, http 대역)", async () => {
    const jar = new CookieJar();
    const client = createHttpClient({ baseUrl, jar, timeoutMs: 2000 });
    const login = await client.post("/login");
    expect(login.status).toBe(200);
    expect(login.setCookies).toHaveLength(1);
    expect(login.json()).toEqual({ guardianId: "g" });
    await client.get("/home");
    await client.get("/home", { cookies: false });
    expect(seen[1].headers.cookie).toBe("MUJOONG_SESSION=sid");
    expect(seen[2].headers.cookie).toBeUndefined();
  });

  it("오류 봉투 code · X-Trace-Id를 읽고, toString에 본문을 쓰지 않는다", async () => {
    const client = createHttpClient({ baseUrl, timeoutMs: 2000 });
    const res = await client.get("/error?q=1");
    expect(res.status).toBe(403);
    expect(res.errorCode).toBe("CSRF_TOKEN_INVALID");
    expect(res.traceId).toBe("0123456789abcdef0123456789abcdef");
    expect(res.headerCount("x-trace-id")).toBe(1);
    expect(String(res)).toBe("HttpResponse[GET /error → 403]");
    const text = await client.get("/plain");
    expect(text.json()).toBeNull();
    expect(text.errorCode).toBeNull();
  });

  it("제한 시간을 넘으면 요청을 끊고 HttpTimeoutError(SEC-05)", async () => {
    const client = createHttpClient({ baseUrl, timeoutMs: 5000 });
    const started = Date.now();
    await expect(client.get("/slow", { timeoutMs: 150 })).rejects.toBeInstanceOf(HttpTimeoutError);
    expect(Date.now() - started).toBeLessThan(2000);
  });

  it("연결 실패는 원인 코드만 담은 HttpRequestError, 지원하지 않는 스킴은 거부한다", async () => {
    const closed = http.createServer();
    await new Promise((resolve) => closed.listen(0, "127.0.0.1", resolve));
    const port = closed.address().port;
    await new Promise((resolve) => closed.close(resolve));
    const client = createHttpClient({ baseUrl: `http://127.0.0.1:${port}`, timeoutMs: 2000 });
    const error = await client.get("/x?token=secret").catch((e) => e);
    expect(error).toBeInstanceOf(HttpRequestError);
    expect(error.code).toBe("ECONNREFUSED");
    expect(error.message).not.toContain("secret");
    expect(() => createHttpClient({ baseUrl: "ftp://127.0.0.1" })).toThrow(TypeError);
  });
});
