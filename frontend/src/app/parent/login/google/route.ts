import { NextResponse, type NextRequest } from "next/server";
import {
  OAUTH_STATE_COOKIE,
  OAUTH_STATE_MAX_AGE_SECONDS,
  OAUTH_STATE_PATH,
  authorizeUrl,
  newState,
  readGoogleConfig,
} from "@/parent/common/oauth";

// GET /parent/login/google — 01 「구글로 시작」 (U2 · US1.1 · J21 · U2-J4)
// 새 state를 HttpOnly 쿠키(Secure · SameSite=Lax · Path=/parent/login · 10분)에 두고 구글 인가 화면으로 보낸다.
// 구글 설정 환경 변수가 없으면 01로 돌려보내 실패 토스트를 보인다. 요청마다 기동 환경 변수를 읽는다(빌드 때 굽지 않음).

export const dynamic = "force-dynamic";

export function GET(request: NextRequest): NextResponse {
  const config = readGoogleConfig(process.env);
  if (config === null) {
    const failed = request.nextUrl.clone();
    failed.pathname = OAUTH_STATE_PATH;
    failed.search = "?failed=1";
    return NextResponse.redirect(failed, 303);
  }
  const state = newState();
  const response = NextResponse.redirect(authorizeUrl(config, state), 302);
  response.headers.set("Cache-Control", "no-store");
  response.cookies.set(OAUTH_STATE_COOKIE, state, {
    httpOnly: true,
    secure: true,
    sameSite: "lax",
    path: OAUTH_STATE_PATH,
    maxAge: OAUTH_STATE_MAX_AGE_SECONDS,
  });
  return response;
}
