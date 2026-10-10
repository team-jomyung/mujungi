// 시험 도구용 쿠키 저장소 (U1 3.5 Step 45 · RFC 6265 4.1 · 5.1.4 · 5.3 · SD-5 · BR1.5 · BR3.3)
//
// 기기 시뮬레이터와 C안 시나리오가 브라우저처럼 Set-Cookie를 받아 다음 요청에 Cookie를 붙이는 데 쓴다. 쿠키 값(bootstrap 비밀 ·
// 기기 토큰 · 세션 ID)은 비밀이라 toString · 오류 메시지에 쓰지 않는다. 시나리오는 parseSetCookie가 돌려준 속성으로 HttpOnly ·
// Secure · SameSite · Path · Domain을 단언한다(SD-5 · SEC-16).
//
// 규칙(RFC 6265 중 도구에 필요한 만큼):
//   - Domain이 없으면 그 호스트 전용(host-only). 있으면 그 도메인과 하위 도메인.
//   - Path가 없거나 '/'로 시작하지 않으면 요청 경로의 기본 경로(마지막 '/' 앞).
//   - Max-Age가 Expires보다 우선. Max-Age ≤ 0 또는 지난 Expires면 같은 (이름, 도메인, 경로) 쿠키를 지운다.
//   - Secure 쿠키는 https 요청에만 보낸다. 경로는 path-match(5.1.4)로 고르고 긴 경로부터 보낸다.

/** Set-Cookie 한 줄을 읽는다. 속성 이름은 소문자로 둔다. 형식이 틀리면 null. */
export function parseSetCookie(header) {
  if (typeof header !== "string" || header.length === 0) {
    return null;
  }
  const parts = header.split(";");
  const pair = parts.shift();
  const eq = pair.indexOf("=");
  if (eq <= 0) {
    return null;
  }
  const name = pair.slice(0, eq).trim();
  let value = pair.slice(eq + 1).trim();
  if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
    value = value.slice(1, -1);
  }
  if (name.length === 0) {
    return null;
  }
  const attributes = {
    path: null,
    domain: null,
    maxAge: null,
    expires: null,
    secure: false,
    httpOnly: false,
    sameSite: null,
  };
  const names = [];
  for (const raw of parts) {
    const attr = raw.trim();
    if (attr.length === 0) {
      continue;
    }
    const at = attr.indexOf("=");
    const key = (at < 0 ? attr : attr.slice(0, at)).trim().toLowerCase();
    const val = at < 0 ? "" : attr.slice(at + 1).trim();
    names.push(key);
    switch (key) {
      case "path":
        attributes.path = val;
        break;
      case "domain":
        attributes.domain = val.replace(/^\./, "").toLowerCase();
        break;
      case "max-age": {
        if (/^-?[0-9]+$/.test(val)) {
          attributes.maxAge = Number.parseInt(val, 10);
        }
        break;
      }
      case "expires": {
        const ms = Date.parse(val);
        if (!Number.isNaN(ms)) {
          attributes.expires = ms;
        }
        break;
      }
      case "secure":
        attributes.secure = true;
        break;
      case "httponly":
        attributes.httpOnly = true;
        break;
      case "samesite":
        attributes.sameSite = val;
        break;
      default:
        break;
    }
  }
  return { name, value, attributes, attributeNames: names };
}

/** 요청 경로의 기본 경로(RFC 6265 5.1.4). */
export function defaultPath(pathname) {
  if (!pathname || !pathname.startsWith("/")) {
    return "/";
  }
  const last = pathname.lastIndexOf("/");
  return last <= 0 ? "/" : pathname.slice(0, last);
}

/** path-match(RFC 6265 5.1.4). */
export function pathMatches(requestPath, cookiePath) {
  if (requestPath === cookiePath) {
    return true;
  }
  if (!requestPath.startsWith(cookiePath)) {
    return false;
  }
  return cookiePath.endsWith("/") || requestPath.charAt(cookiePath.length) === "/";
}

function domainMatches(host, cookie) {
  if (cookie.hostOnly) {
    return host === cookie.domain;
  }
  return host === cookie.domain || host.endsWith(`.${cookie.domain}`);
}

/** 쿠키 저장소. 같은 (이름, 도메인, 경로)는 덮어쓴다. */
export class CookieJar {
  #cookies = new Map();
  #now;

  /** @param {{now?: () => number}} [options] 시험에서 시계를 바꿀 수 있다 */
  constructor({ now = () => Date.now() } = {}) {
    this.#now = now;
  }

  /**
   * 응답의 Set-Cookie들을 받는다.
   * @param {string|URL} requestUrl 요청 주소(도메인 · 기본 경로 계산)
   * @param {string[]} setCookieHeaders
   * @returns {Array<object>} 받아들인(또는 지운) 쿠키 기록
   */
  storeFromResponse(requestUrl, setCookieHeaders) {
    const url = new URL(String(requestUrl));
    const host = url.hostname.toLowerCase();
    const results = [];
    for (const header of setCookieHeaders ?? []) {
      const parsed = parseSetCookie(header);
      if (parsed === null) {
        continue;
      }
      const { attributes } = parsed;
      if (attributes.domain && !(host === attributes.domain || host.endsWith(`.${attributes.domain}`))) {
        // 다른 도메인 쿠키는 받지 않는다(RFC 6265 5.3 6단계)
        continue;
      }
      const path = attributes.path && attributes.path.startsWith("/") ? attributes.path : defaultPath(url.pathname);
      const domain = attributes.domain ?? host;
      const key = `${parsed.name}\u0000${domain}\u0000${path}`;
      const now = this.#now();
      let expiresAt = null;
      if (attributes.maxAge !== null) {
        expiresAt = attributes.maxAge <= 0 ? 0 : now + attributes.maxAge * 1000;
      } else if (attributes.expires !== null) {
        expiresAt = attributes.expires;
      }
      const record = {
        name: parsed.name,
        value: parsed.value,
        domain,
        hostOnly: attributes.domain === null,
        path,
        secure: attributes.secure,
        httpOnly: attributes.httpOnly,
        sameSite: attributes.sameSite,
        expiresAt,
        setCookie: parsed,
      };
      if (expiresAt !== null && expiresAt <= now) {
        this.#cookies.delete(key);
        results.push({ ...record, deleted: true });
        continue;
      }
      this.#cookies.set(key, record);
      results.push({ ...record, deleted: false });
    }
    return results;
  }

  /** 그 주소로 보낼 Cookie 헤더 값(없으면 null). */
  cookieHeader(requestUrl) {
    const list = this.matching(requestUrl);
    if (list.length === 0) {
      return null;
    }
    return list.map((cookie) => `${cookie.name}=${cookie.value}`).join("; ");
  }

  /** 그 주소로 보낼 쿠키들(긴 경로부터). */
  matching(requestUrl) {
    const url = new URL(String(requestUrl));
    const host = url.hostname.toLowerCase();
    const secureChannel = url.protocol === "https:" || url.protocol === "wss:";
    const now = this.#now();
    const out = [];
    for (const [key, cookie] of this.#cookies) {
      if (cookie.expiresAt !== null && cookie.expiresAt <= now) {
        this.#cookies.delete(key);
        continue;
      }
      if (!domainMatches(host, cookie) || !pathMatches(url.pathname || "/", cookie.path)) {
        continue;
      }
      if (cookie.secure && !secureChannel) {
        continue;
      }
      out.push(cookie);
    }
    out.sort((a, b) => b.path.length - a.path.length);
    return out;
  }

  /** 이름으로 찾은 저장된 쿠키(여럿이면 긴 경로 것). 없으면 null. */
  get(name) {
    let found = null;
    const now = this.#now();
    for (const cookie of this.#cookies.values()) {
      if (cookie.name !== name || (cookie.expiresAt !== null && cookie.expiresAt <= now)) {
        continue;
      }
      if (found === null || cookie.path.length > found.path.length) {
        found = cookie;
      }
    }
    return found;
  }

  /** 저장된 쿠키 수(만료 제외). */
  get size() {
    const now = this.#now();
    let count = 0;
    for (const cookie of this.#cookies.values()) {
      if (cookie.expiresAt === null || cookie.expiresAt > now) {
        count += 1;
      }
    }
    return count;
  }

  clear() {
    this.#cookies.clear();
  }

  toString() {
    // 값은 비밀이라 이름만 보인다
    return `CookieJar[${[...this.#cookies.values()].map((cookie) => cookie.name).join(", ")}]`;
  }
}
