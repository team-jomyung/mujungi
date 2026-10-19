import { NextRequest } from "next/server";
import { afterEach, describe, expect, it, vi } from "vitest";
import { config, proxy } from "@/proxy";
import {
  ALLOWED_ORIGIN_ENV,
  CSP_HEADER,
  STRICT_DYNAMIC_EXCLUDED_PATH_PREFIXES,
  buildContentSecurityPolicy,
  createNonce,
  usesStrictDynamic,
  websocketOriginFromAllowedOrigin,
} from "@/shared/security/csp";

// 요청별 nonce · CSP 시험 (U1 3.5 Step 41 · SD-2 · J122 · J142 · NFR9.12)
// proxy 함수를 직접 불러 요청마다 다른 nonce, CSP 헤더 하나, 런타임 환경 변수 반영을 확인한다.

afterEach(() => {
  vi.unstubAllEnvs();
});

function directive(policy: string, name: string): string[] {
  const found = policy
    .split(";")
    .map((part) => part.trim())
    .find((part) => part.startsWith(`${name} `));
  return found === undefined ? [] : found.split(" ").slice(1);
}

function nonceOf(policy: string): string | null {
  const source = directive(policy, "script-src").find((value) => value.startsWith("'nonce-"));
  return source === undefined ? null : source.slice("'nonce-".length, -1);
}

function callProxy(pathname: string): Response {
  return proxy(new NextRequest(`https://localhost:8443${pathname}`));
}

describe("proxy — 요청별 nonce · CSP", () => {
  it("요청마다 새 nonce를 만들고 CSP 헤더 하나와 페이지 요청 헤더에 같은 값을 싣는다", () => {
    vi.stubEnv(ALLOWED_ORIGIN_ENV, "https://localhost:8443");
    const first = callProxy("/device");
    const second = callProxy("/device");
    const firstPolicy = first.headers.get(CSP_HEADER) ?? "";
    const secondPolicy = second.headers.get(CSP_HEADER) ?? "";
    const firstNonce = nonceOf(firstPolicy);
    expect(firstNonce).toMatch(/^[A-Za-z0-9+/]{22}==$/);
    expect(nonceOf(secondPolicy)).not.toBe(firstNonce);
    expect(first.headers.get("x-middleware-request-x-nonce")).toBe(firstNonce);
    expect(first.headers.get(`x-middleware-request-${CSP_HEADER.toLowerCase()}`)).toBe(firstPolicy);
    const cspHeaders = [...first.headers.entries()].filter(([name]) => name === CSP_HEADER.toLowerCase());
    expect(cspHeaders).toHaveLength(1);
  });

  it("허용 origin이 https://localhost:8443이면 connect-src에 wss://localhost:8443을 더한다(J142)", () => {
    vi.stubEnv(ALLOWED_ORIGIN_ENV, "https://localhost:8443");
    const policy = callProxy("/device").headers.get(CSP_HEADER) ?? "";
    expect(directive(policy, "connect-src")).toEqual(["'self'", "wss://localhost:8443"]);
  });

  it("환경 변수가 없으면 connect-src는 'self'뿐이고, 값을 바꾸면 다음 요청부터 반영된다(빌드 때 굽지 않음)", () => {
    vi.stubEnv(ALLOWED_ORIGIN_ENV, undefined);
    expect(directive(callProxy("/parent").headers.get(CSP_HEADER) ?? "", "connect-src")).toEqual(["'self'"]);
    vi.stubEnv(ALLOWED_ORIGIN_ENV, "https://mujoong.invalid");
    expect(directive(callProxy("/parent").headers.get(CSP_HEADER) ?? "", "connect-src")).toEqual(["'self'", "wss://mujoong.invalid"]);
  });

  it("운영 빌드에는 'unsafe-eval'이 없고 개발 서버에서만 더한다", () => {
    vi.stubEnv("NODE_ENV", "production");
    const production = callProxy("/device").headers.get(CSP_HEADER) ?? "";
    expect(directive(production, "script-src")).not.toContain("'unsafe-eval'");
    expect(directive(production, "script-src")).toContain("'strict-dynamic'");
    vi.stubEnv("NODE_ENV", "development");
    expect(directive(callProxy("/device").headers.get(CSP_HEADER) ?? "", "script-src")).toContain("'unsafe-eval'");
  });

  it("정적 파일 · 헬스 확인은 proxy 대상이 아니다", () => {
    const [matcher] = config.matcher;
    const pattern = new RegExp(`^${matcher.source}$`);
    expect(pattern.test("/device")).toBe(true);
    expect(pattern.test("/parent/free")).toBe(true);
    expect(pattern.test("/healthz")).toBe(false);
    expect(pattern.test("/_next/static/chunks/a.js")).toBe(false);
    expect(matcher.missing.map((item) => item.key)).toEqual(["next-router-prefetch", "purpose"]);
  });
});

describe("csp — 정책 문자열 · 허용 origin 변환", () => {
  it("보안 지시어를 모두 담고 script-src에 'unsafe-inline'이 없다(SD-2 2.2.1)", () => {
    const policy = buildContentSecurityPolicy({ nonce: "abc", allowedOrigin: undefined, isDevelopment: false });
    expect(directive(policy, "default-src")).toEqual(["'self'"]);
    expect(directive(policy, "script-src")).toEqual(["'self'", "'nonce-abc'", "'strict-dynamic'"]);
    expect(directive(policy, "object-src")).toEqual(["'none'"]);
    expect(directive(policy, "frame-ancestors")).toEqual(["'none'"]);
    expect(directive(policy, "base-uri")).toEqual(["'self'"]);
    expect(directive(policy, "form-action")).toEqual(["'self'"]);
    expect(directive(policy, "media-src")).toEqual(["'self'", "blob:"]);
    expect(buildContentSecurityPolicy({ nonce: "n", allowedOrigin: undefined, isDevelopment: false, strictDynamic: false })).not.toContain(
      "strict-dynamic",
    );
  });

  it.each([
    ["https://localhost:8443", "wss://localhost:8443"],
    ["https://localhost:8443/", "wss://localhost:8443"],
    ["https://mujoong.invalid", "wss://mujoong.invalid"],
    ["http://localhost:3000", "ws://localhost:3000"],
    ["http://127.0.0.1:8080", "ws://127.0.0.1:8080"],
  ])("허용 origin %s → %s(스킴만 바꾸고 포트 유지)", (origin, expected) => {
    expect(websocketOriginFromAllowedOrigin(origin)).toBe(expected);
  });

  it.each([
    undefined,
    "",
    "   ",
    "not a url",
    "http://example.invalid",
    "https://localhost:8443/app",
    "https://user:pw@localhost:8443", // gitleaks:allow — 자격 포함 origin 거부를 보는 가짜 값(비밀 아님)
    "https://localhost:8443?x=1",
    "ftp://localhost",
  ])("형식이 틀리거나 위험한 허용 origin %j은 wss 출처를 만들지 않는다(닫힌 쪽)", (origin) => {
    expect(websocketOriginFromAllowedOrigin(origin)).toBeNull();
  });

  it("nonce는 128비트 난수 base64이고 겹치지 않는다", () => {
    const nonces = new Set(Array.from({ length: 200 }, () => createNonce()));
    expect(nonces.size).toBe(200);
    for (const nonce of nonces) {
      expect(atob(nonce)).toHaveLength(16);
    }
  });

  it("'strict-dynamic' 제외 경로는 지금 비어 있어 모든 경로가 'strict-dynamic'을 쓴다(X-6 되돌림 장치)", () => {
    expect(STRICT_DYNAMIC_EXCLUDED_PATH_PREFIXES).toEqual([]);
    expect(usesStrictDynamic("/device")).toBe(true);
    expect(usesStrictDynamic("/parent/free")).toBe(true);
  });

  it("3.6에서 /device를 제외 목록에 더하면 그 경로만 'strict-dynamic'이 빠진다", () => {
    const prefixes = STRICT_DYNAMIC_EXCLUDED_PATH_PREFIXES as string[];
    prefixes.push("/device");
    try {
      expect(usesStrictDynamic("/device")).toBe(false);
      expect(usesStrictDynamic("/device/pcm-capture-worklet")).toBe(false);
      expect(usesStrictDynamic("/devices")).toBe(true);
      expect(callProxy("/device").headers.get(CSP_HEADER)).not.toContain("'strict-dynamic'");
      expect(callProxy("/parent").headers.get(CSP_HEADER)).toContain("'strict-dynamic'");
    } finally {
      prefixes.pop();
    }
  });
});
