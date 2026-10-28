// Next.js HTML의 Content-Security-Policy (U1 3.5 Step 37 · SD-2 · security-design 2.2.1 · J122 · J142 · NFR9.12)
//
// 요청마다 새 nonce를 만들고 같은 값을 CSP의 script-src와 x-nonce 요청 헤더에 싣는다(Next.js가 프레임워크 스크립트에
// nonce를 붙인다). 값은 기동 환경 변수에서 요청마다 읽는다(NEXT_PUBLIC_* 금지, 같은 이미지 이동 J94).
// connect-src의 wss 출처는 SERVICE_HOST가 아니라 MUJOONG_SECURITY_ALLOWED_ORIGIN의 스킴만 https → wss로 바꾼 값(포트 유지)이다
// (J142, 검증 스택에서는 wss://localhost:8443). Nginx는 Next.js 경로에 CSP를 더하지 않는다(헤더가 둘이면 교집합이 된다).
// 이 파일은 순수 함수만 둔다(proxy.ts가 부르고 시험이 직접 부른다).

export const CSP_HEADER = "Content-Security-Policy";
export const NONCE_HEADER = "x-nonce";
export const ALLOWED_ORIGIN_ENV = "MUJOONG_SECURITY_ALLOWED_ORIGIN";

/**
 * script-src에서 'strict-dynamic'을 빼는 경로 접두. 비어 있다. 'strict-dynamic'이 /device의 AudioWorklet 모듈 로딩을
 * 막는 것이 3.6 실기에서 확인되면(X-6) 여기에 "/device"를 더해 그 경로만 'self' 'nonce-…'로 낮춘다(security-design 2.2.1).
 */
export const STRICT_DYNAMIC_EXCLUDED_PATH_PREFIXES: readonly string[] = [];

/**
 * script-src에 'wasm-unsafe-eval'을 더하는 경로 접두. /device의 3D 무중이(three.js Meshopt 디코더)가 WebAssembly를 컴파일한다
 * (docs/ui/device-subtitle.md). 'wasm-unsafe-eval'은 WebAssembly 컴파일만 허용하고 JS eval은 계속 막는다. 보호자 웹에는 더하지 않는다.
 */
export const WASM_ALLOWED_PATH_PREFIXES: readonly string[] = ["/device"];

const LOOPBACK_HOSTS: readonly string[] = ["localhost", "127.0.0.1", "[::1]"];
const NONCE_BYTES = 16;

export interface CspOptions {
  /** 이 요청의 nonce(base64). */
  readonly nonce: string;
  /** MUJOONG_SECURITY_ALLOWED_ORIGIN 값. 없거나 틀리면 wss 출처를 더하지 않는다(닫힌 쪽). */
  readonly allowedOrigin: string | undefined;
  /** 개발 서버(next dev)이면 'unsafe-eval'을 더한다(React 개발 도구). 운영 빌드에서는 false. */
  readonly isDevelopment: boolean;
  /** 'strict-dynamic'을 쓸지. 기본 true. */
  readonly strictDynamic?: boolean;
  /** 'wasm-unsafe-eval'을 더할지(WASM_ALLOWED_PATH_PREFIXES 경로). 기본 false. */
  readonly allowWasm?: boolean;
}

/**
 * 허용 origin → 기기 WSS 출처. https는 wss, http는 루프백일 때만 ws(개발 · 시험). 경로 · 질의가 붙은 값,
 * 다른 스킴, 형식 오류는 null.
 */
export function websocketOriginFromAllowedOrigin(allowedOrigin: string | undefined): string | null {
  if (allowedOrigin === undefined) {
    return null;
  }
  const trimmed = allowedOrigin.trim();
  if (trimmed.length === 0) {
    return null;
  }
  let url: URL;
  try {
    url = new URL(trimmed);
  } catch {
    return null;
  }
  if (url.origin !== trimmed.replace(/\/$/, "") || url.username !== "" || url.password !== "") {
    return null;
  }
  if (url.protocol === "https:") {
    return `wss://${url.host}`;
  }
  if (url.protocol === "http:" && LOOPBACK_HOSTS.includes(url.hostname)) {
    return `ws://${url.host}`;
  }
  return null;
}

/** 요청 경로에 'wasm-unsafe-eval'을 더할지. */
export function allowsWasm(pathname: string): boolean {
  return WASM_ALLOWED_PATH_PREFIXES.some((prefix) => pathname === prefix || pathname.startsWith(`${prefix}/`));
}

/** 요청 경로에 'strict-dynamic'을 쓸지. */
export function usesStrictDynamic(pathname: string): boolean {
  return !STRICT_DYNAMIC_EXCLUDED_PATH_PREFIXES.some(
    (prefix) => pathname === prefix || pathname.startsWith(`${prefix}/`),
  );
}

/** CSP 헤더 값 한 줄을 만든다(security-design 2.2.1 표). */
export function buildContentSecurityPolicy(options: CspOptions): string {
  const scriptSources = ["'self'", `'nonce-${options.nonce}'`];
  if (options.strictDynamic ?? true) {
    scriptSources.push("'strict-dynamic'");
  }
  if (options.allowWasm ?? false) {
    scriptSources.push("'wasm-unsafe-eval'");
  }
  if (options.isDevelopment) {
    scriptSources.push("'unsafe-eval'");
  }
  const connectSources = ["'self'"];
  const websocketOrigin = websocketOriginFromAllowedOrigin(options.allowedOrigin);
  if (websocketOrigin !== null) {
    connectSources.push(websocketOrigin);
  }
  const directives: ReadonlyArray<readonly [string, readonly string[]]> = [
    ["default-src", ["'self'"]],
    ["script-src", scriptSources],
    ["style-src", ["'self'", "'unsafe-inline'"]],
    ["connect-src", connectSources],
    ["img-src", ["'self'", "data:"]],
    ["font-src", ["'self'"]],
    ["media-src", ["'self'", "blob:"]],
    ["worker-src", ["'self'", "blob:"]],
    ["object-src", ["'none'"]],
    ["base-uri", ["'self'"]],
    ["form-action", ["'self'"]],
    ["frame-ancestors", ["'none'"]],
  ];
  return directives.map(([name, sources]) => `${name} ${sources.join(" ")}`).join("; ");
}

/** 요청마다 새 nonce(128비트 난수, base64). Web Crypto만 쓴다. */
export function createNonce(): string {
  const bytes = new Uint8Array(NONCE_BYTES);
  crypto.getRandomValues(bytes);
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary);
}
