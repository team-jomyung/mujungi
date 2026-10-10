// 시험 도구용 HTTP(S) 클라이언트 (U1 3.5 Step 45 · J108 · J133 · SEC-05 · SEC-16 · F4)
//
// - 프록시 에이전트를 쓰지 않는다. node:https는 HTTPS_PROXY를 읽지 않으므로 루프백 · 8443 요청이 프록시로 가지 않는다.
// - 자체 서명 CA는 ca 옵션으로만 넘긴다. NODE_TLS_REJECT_UNAUTHORIZED · rejectUnauthorized: false를 쓰지 않는다(TLS 검증 유지).
// - Node fetch는 Origin을 보내지 않으므로 변경 요청(POST · PUT · PATCH · DELETE)에 허용 Origin을 직접 붙인다(F4 Origin 비교를 실제로 거침).
// - 모든 요청에 제한 시간이 있다(SEC-05). 넘으면 요청을 끊고 HttpTimeoutError를 던진다.
// - 쿠키 저장소(CookieJar)를 주면 Cookie를 붙이고 Set-Cookie를 받는다. 쿠키 · 본문 값은 오류 메시지에 쓰지 않는다.
import http from "node:http";
import https from "node:https";

const MUTATING = new Set(["POST", "PUT", "PATCH", "DELETE"]);

/** 요청이 제한 시간 안에 끝나지 않았다. */
export class HttpTimeoutError extends Error {
  constructor(method, path, timeoutMs) {
    super(`${method} ${path} 응답이 ${timeoutMs}ms 안에 오지 않았다`);
    this.name = "HttpTimeoutError";
    this.timeoutMs = timeoutMs;
  }
}

/** 연결 실패 등(상태 코드 없이 끝남). 원인 코드만 담는다. */
export class HttpRequestError extends Error {
  constructor(method, path, cause) {
    super(`${method} ${path} 요청 실패(${cause?.code ?? cause?.name ?? "UNKNOWN"})`);
    this.name = "HttpRequestError";
    this.code = cause?.code ?? null;
  }
}

/** 응답. body는 Buffer, json()은 파싱 실패면 null. */
export class HttpResponse {
  constructor({ method, url, status, headers, rawHeaders, body }) {
    this.method = method;
    this.url = url;
    this.status = status;
    this.headers = headers;
    this.rawHeaders = rawHeaders;
    this.body = body;
  }

  /** Set-Cookie 줄들(없으면 빈 배열). */
  get setCookies() {
    const value = this.headers["set-cookie"];
    if (value === undefined) {
      return [];
    }
    return Array.isArray(value) ? value : [value];
  }

  header(name) {
    const value = this.headers[name.toLowerCase()];
    return Array.isArray(value) ? value.join(", ") : (value ?? null);
  }

  /** 같은 이름 헤더의 개수(원시 헤더 기준). */
  headerCount(name) {
    const lower = name.toLowerCase();
    let count = 0;
    for (let i = 0; i < this.rawHeaders.length; i += 2) {
      if (this.rawHeaders[i].toLowerCase() === lower) {
        count += 1;
      }
    }
    return count;
  }

  get traceId() {
    return this.header("x-trace-id");
  }

  text() {
    return this.body.toString("utf8");
  }

  json() {
    if (this.body.length === 0) {
      return null;
    }
    try {
      return JSON.parse(this.text());
    } catch {
      return null;
    }
  }

  /** 오류 봉투의 code(없으면 null). */
  get errorCode() {
    const value = this.json();
    return value && typeof value === "object" && typeof value.code === "string" ? value.code : null;
  }

  toString() {
    // 본문 · 쿠키는 비밀일 수 있어 상태만 보인다
    return `HttpResponse[${this.method} ${new URL(this.url).pathname} → ${this.status}]`;
  }
}

/**
 * @param {object} options
 * @param {string} options.baseUrl 예: https://localhost:8443
 * @param {string|Buffer|null} [options.ca] 자체 서명 CA PEM(https일 때)
 * @param {string|null} [options.origin] 변경 요청에 붙일 허용 Origin(기본: baseUrl의 origin)
 * @param {import('./cookieJar.mjs').CookieJar|null} [options.jar]
 * @param {number} [options.timeoutMs] 요청 하나의 제한 시간
 * @param {string} [options.userAgent]
 */
export function createHttpClient({
  baseUrl,
  ca = null,
  origin = undefined,
  jar = null,
  timeoutMs = 10_000,
  userAgent = "mujoong-tools/1",
}) {
  const base = new URL(baseUrl);
  const defaultOrigin = origin === undefined ? base.origin : origin;
  const isHttps = base.protocol === "https:";
  if (!isHttps && base.protocol !== "http:") {
    throw new TypeError(`지원하지 않는 스킴이다: ${base.protocol}`);
  }

  /**
   * @param {object} req
   * @param {string} req.method
   * @param {string} req.path 경로(+ 쿼리). 원시 그대로 보낸다(URL 정규화를 하지 않음)
   * @param {object} [req.headers]
   * @param {any} [req.json] JSON 본문
   * @param {Buffer|string} [req.body] 원시 본문
   * @param {string|boolean|null} [req.origin] true · undefined = 변경 요청이면 기본 Origin, 문자열 = 그 값, false · null = 보내지 않음
   * @param {boolean} [req.cookies] false면 Cookie를 붙이지도 받지도 않는다
   * @param {number} [req.timeoutMs]
   * @returns {Promise<HttpResponse>}
   */
  function request({ method = "GET", path, headers = {}, json, body, origin: originOption, cookies = true, timeoutMs: perRequest }) {
    const upper = method.toUpperCase();
    const limit = perRequest ?? timeoutMs;
    const url = new URL(base.origin);
    const finalHeaders = { "user-agent": userAgent, accept: "application/json", ...lowerKeys(headers) };
    let payload = null;
    if (json !== undefined) {
      payload = Buffer.from(JSON.stringify(json), "utf8");
      finalHeaders["content-type"] ??= "application/json";
    } else if (body !== undefined) {
      payload = Buffer.isBuffer(body) ? body : Buffer.from(String(body), "utf8");
    }
    if (payload !== null) {
      finalHeaders["content-length"] = String(payload.length);
    } else if (MUTATING.has(upper)) {
      finalHeaders["content-length"] = "0";
    }
    const originValue =
      originOption === undefined || originOption === true ? (MUTATING.has(upper) ? defaultOrigin : null) : originOption || null;
    if (originValue) {
      finalHeaders.origin = originValue;
    }
    const target = `${base.origin}${path}`;
    if (cookies && jar) {
      const cookieHeader = jar.cookieHeader(target);
      if (cookieHeader && finalHeaders.cookie === undefined) {
        finalHeaders.cookie = cookieHeader;
      }
    }
    const transport = isHttps ? https : http;
    const options = {
      protocol: base.protocol,
      hostname: base.hostname,
      port: base.port || (isHttps ? 443 : 80),
      method: upper,
      path,
      headers: finalHeaders,
      agent: false,
    };
    if (isHttps) {
      options.ca = ca ?? undefined;
      options.servername = isIpLiteral(base.hostname) ? undefined : base.hostname;
    }
    return new Promise((resolve, reject) => {
      let settled = false;
      const finish = (fn, value) => {
        if (!settled) {
          settled = true;
          clearTimeout(timer);
          fn(value);
        }
      };
      const req = transport.request(options, (res) => {
        const chunks = [];
        res.on("data", (chunk) => chunks.push(chunk));
        res.on("error", (error) => finish(reject, new HttpRequestError(upper, pathOnly(path), error)));
        res.on("end", () => {
          const response = new HttpResponse({
            method: upper,
            url: target,
            status: res.statusCode,
            headers: res.headers,
            rawHeaders: res.rawHeaders,
            body: Buffer.concat(chunks),
          });
          if (cookies && jar) {
            jar.storeFromResponse(target, response.setCookies);
          }
          finish(resolve, response);
        });
      });
      const timer = setTimeout(() => {
        req.destroy();
        finish(reject, new HttpTimeoutError(upper, pathOnly(path), limit));
      }, limit);
      req.on("error", (error) => finish(reject, new HttpRequestError(upper, pathOnly(path), error)));
      if (payload !== null) {
        req.end(payload);
      } else {
        req.end();
      }
    });
  }

  return {
    baseUrl: base.origin,
    origin: defaultOrigin,
    jar,
    ca,
    request,
    get: (path, extra = {}) => request({ ...extra, method: "GET", path }),
    post: (path, json, extra = {}) => request({ ...extra, method: "POST", path, json }),
  };
}

function lowerKeys(headers) {
  const out = {};
  for (const [key, value] of Object.entries(headers)) {
    if (value !== undefined && value !== null) {
      out[key.toLowerCase()] = value;
    }
  }
  return out;
}

function pathOnly(path) {
  const at = path.indexOf("?");
  return at < 0 ? path : path.slice(0, at);
}

function isIpLiteral(hostname) {
  return /^[0-9.]+$/.test(hostname) || hostname.includes(":");
}
