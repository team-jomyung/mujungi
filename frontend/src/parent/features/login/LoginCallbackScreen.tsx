"use client";

import { useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useEffect, useRef } from "react";
import { loginWithGoogle, type Me } from "../../api/account";
import { useApiClient } from "../../api/queries";
import { PARENT_ROUTES } from "../../navigation";
import { ACCOUNT_COPY, ACCOUNT_TEMP_COPY } from "@/shared/copy/account";

// 01 구글 콜백 교환 (U2 · US1.1 · AC1.1.1 ~ AC1.1.4 · AC1.6.4 · R3 · U2-J4)
// 서버 컴포넌트(page.tsx)가 OAuth state 쿠키를 확인한 뒤에만 이 화면을 그린다. 화면은 인가 코드를 Core에 한 번 보내고
// (POST /api/auth/google, 브라우저는 토큰을 보지 않는다) 응답 Me로 고른다: 삭제 처리 중 → 01 안내, 동의 아님 → 02,
// 아이 없음 → 03, 둘 다 → 04. 실패면 01(?failed=1). 앞 사용자의 조회 결과는 버린다.

export interface LoginCallbackProps {
  readonly code: string;
  readonly redirectUri: string;
}

/** 로그인 응답 Me → 다음 화면(R3). */
export function routeAfterLogin(me: Me): string {
  if (me.deletionInProgress) {
    return PARENT_ROUTES.loginDeleting;
  }
  if (me.consentStatus !== "GIVEN") {
    return PARENT_ROUTES.consent;
  }
  return me.childRegistered ? PARENT_ROUTES.home : PARENT_ROUTES.child;
}

export function LoginCallbackScreen({ code, redirectUri }: LoginCallbackProps) {
  const router = useRouter();
  const api = useApiClient();
  const queryClient = useQueryClient();
  const sentRef = useRef(false);

  useEffect(() => {
    if (sentRef.current) {
      return;
    }
    sentRef.current = true;
    loginWithGoogle(api, code, redirectUri)
      .then((me) => {
        queryClient.clear();
        router.replace(routeAfterLogin(me));
      })
      .catch(() => {
        router.replace(PARENT_ROUTES.loginFailed);
      });
  }, [api, code, queryClient, redirectUri, router]);

  return (
    <div className="mj-screen mj-login" data-testid="login-callback-screen">
      <main className="mj-screen__body mj-login__body">
        <h1 className="mj-login__brand">{ACCOUNT_COPY.login.brand}</h1>
        <p className="mj-login__subtitle" role="status" aria-busy="true" data-testid="login-callback-status">
          {ACCOUNT_TEMP_COPY.authenticating}
        </p>
      </main>
    </div>
  );
}
