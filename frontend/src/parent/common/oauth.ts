import { randomBytes, timingSafeEqual } from "node:crypto";

// 구글 OAuth 시작 · 콜백 도우미 — 서버 전용(라우트 처리기 · 서버 컴포넌트만 import) (U2)
// state는 HttpOnly 쿠키에만 두고(보호자 JS 저장소를 쓰지 않는다, SD-6) 콜백에서 같은지 본다(로그인 CSRF 방지).
// 구글 값은 기동 환경 변수 이름만 쓴다(값은 저장소에 없다). 비어 있으면 로그인은 01 실패 토스트로 끝난다.

/** state 쿠키 이름. */
export const OAUTH_STATE_COOKIE = "MUJOONG_OAUTH_STATE";
/** state 쿠키 경로(01 · 콜백만). */
export const OAUTH_STATE_PATH = "/parent/login";
/** state 쿠키 수명(초): 구글 화면에서 돌아올 시간. */
export const OAUTH_STATE_MAX_AGE_SECONDS = 600;
/** 구글 인가 엔드포인트(공개 주소). */
export const GOOGLE_AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth";
/** 환경 변수 이름(Core와 같은 이름 하나 — infra/bin/render-env가 core-api.env · web.env에 함께 쓴다, 리뷰 3). */
export const GOOGLE_CLIENT_ID_ENV = "GOOGLE_OAUTH_CLIENT_ID";
export const GOOGLE_REDIRECT_URI_ENV = "GOOGLE_OAUTH_REDIRECT_URI";

export interface GoogleOAuthConfig {
  readonly clientId: string;
  readonly redirectUri: string;
}

/** 환경 변수의 구글 설정(둘 다 있어야 한다). */
export function readGoogleConfig(env: Readonly<Record<string, string | undefined>>): GoogleOAuthConfig | null {
  const clientId = env[GOOGLE_CLIENT_ID_ENV]?.trim() ?? "";
  const redirectUri = env[GOOGLE_REDIRECT_URI_ENV]?.trim() ?? "";
  return clientId.length > 0 && redirectUri.length > 0 ? { clientId, redirectUri } : null;
}

/** 새 state(256비트, base64url). */
export function newState(): string {
  return randomBytes(32).toString("base64url");
}

/** 구글 인가 주소(scope openid만 — 식별은 sub, 이메일을 받지 않는다). */
export function authorizeUrl(config: GoogleOAuthConfig, state: string): string {
  const url = new URL(GOOGLE_AUTHORIZE_URL);
  url.searchParams.set("response_type", "code");
  url.searchParams.set("client_id", config.clientId);
  url.searchParams.set("redirect_uri", config.redirectUri);
  url.searchParams.set("scope", "openid");
  url.searchParams.set("state", state);
  url.searchParams.set("prompt", "select_account");
  return url.toString();
}

/** 콜백 state가 쿠키 state와 같은가(같은 길이에서 상수 시간 비교). */
export function stateMatches(received: string | undefined, expected: string | undefined): boolean {
  if (received === undefined || expected === undefined || received.length === 0) {
    return false;
  }
  const left = Buffer.from(received);
  const right = Buffer.from(expected);
  return left.length === right.length && timingSafeEqual(left, right);
}

/** 질의 값 하나(배열 · 없음은 null). */
export function singleParam(value: string | string[] | undefined): string | null {
  return typeof value === "string" && value.length > 0 ? value : null;
}
