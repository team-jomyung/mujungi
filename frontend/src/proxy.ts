import { NextResponse, type NextRequest } from "next/server";
import {
  ALLOWED_ORIGIN_ENV,
  CSP_HEADER,
  NONCE_HEADER,
  allowsWasm,
  buildContentSecurityPolicy,
  createNonce,
  usesStrictDynamic,
} from "@/shared/security/csp";

// 요청별 nonce · CSP (U1 3.5 Step 37 · SD-2 · J122 · J142). Next.js 16은 middleware 대신 proxy 파일 규약을 쓰고
// Node.js 런타임에서 돌므로 기동 환경 변수를 요청마다 읽을 수 있다. 헤더 값은 shared/security/csp.ts가 만든다.
// 페이지는 루트 레이아웃이 connection()으로 동적 렌더링되어 Next.js가 nonce를 스크립트에 붙인다.

export function proxy(request: NextRequest): NextResponse {
  const nonce = createNonce();
  const contentSecurityPolicy = buildContentSecurityPolicy({
    nonce,
    allowedOrigin: process.env[ALLOWED_ORIGIN_ENV],
    isDevelopment: process.env.NODE_ENV === "development",
    strictDynamic: usesStrictDynamic(request.nextUrl.pathname),
    allowWasm: allowsWasm(request.nextUrl.pathname),
  });

  const requestHeaders = new Headers(request.headers);
  requestHeaders.set(NONCE_HEADER, nonce);
  requestHeaders.set(CSP_HEADER, contentSecurityPolicy);

  const response = NextResponse.next({ request: { headers: requestHeaders } });
  response.headers.set(CSP_HEADER, contentSecurityPolicy);
  return response;
}

export const config = {
  matcher: [
    {
      // 정적 파일 · 이미지 최적화 · 헬스 확인은 CSP 대상이 아니다. 링크 미리 받기 요청도 건너뛴다.
      source: "/((?!_next/static|_next/image|favicon.ico|healthz).*)",
      missing: [
        { type: "header", key: "next-router-prefetch" },
        { type: "header", key: "purpose", value: "prefetch" },
      ],
    },
  ],
};
