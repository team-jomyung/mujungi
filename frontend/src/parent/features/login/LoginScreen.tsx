"use client";

import { useEffect, useRef, useState } from "react";
import { PARENT_ROUTES } from "../../common/navigation";
import { ACCOUNT_COPY, ACCOUNT_PROPOSED_COPY, ACCOUNT_TEMP_COPY } from "@/shared/copy/account";
import { useToast } from "@/shared/toast/ToastProvider";

// 01 보호자 로그인 (U2 · US1.1 · AC1.1.4 · AC1.1.7 · AC1.1.10 · AC1.6.4 · mockups S-01 · 9-1 · 9-7 · U2-J4)
// 로고 · 부제 · 「구글로 시작」 하나뿐이다(구글 외 로그인 · 회원가입 · 비밀번호 칸 없음). 누르면 인증 중(단추 잠김)이 되고
// 같은 origin의 /parent/login/google(서버가 OAuth state 쿠키를 두고 구글로 보냄)로 이동한다. 돌아와 실패했으면 ?failed=1로 토스트,
// 계정 삭제 처리 중이면 ?deleting=1로 안내 카드만 보인다.

export interface LoginScreenProps {
  readonly failed?: boolean;
  readonly deleting?: boolean;
  /** 시험 대역용(기본 window.location.assign). */
  readonly navigate?: (href: string) => void;
}

function assign(href: string): void {
  window.location.assign(href);
}

export function LoginScreen({ failed = false, deleting = false, navigate = assign }: LoginScreenProps) {
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const shownRef = useRef(false);

  useEffect(() => {
    if (failed && !shownRef.current) {
      shownRef.current = true;
      toast.show({ message: ACCOUNT_PROPOSED_COPY.loginFailed, kind: "error" });
    }
  }, [failed, toast]);

  const start = () => {
    if (busy) {
      return;
    }
    setBusy(true);
    navigate(PARENT_ROUTES.googleStart);
  };

  return (
    <div className="mj-screen mj-login" data-testid="login-screen">
      <main className="mj-screen__body mj-login__body">
        <h1 className="mj-login__brand">{ACCOUNT_COPY.login.brand}</h1>
        <p className="mj-login__subtitle">{ACCOUNT_COPY.login.subtitle}</p>
        {deleting ? (
          <p className="mj-reason-card" role="status" data-testid="login-deleting">
            {ACCOUNT_PROPOSED_COPY.deletionInProgress}
          </p>
        ) : null}
        <button
          type="button"
          className="mj-button mj-button--primary mj-button--block"
          data-testid="login-google"
          disabled={busy}
          aria-busy={busy}
          onClick={start}
        >
          {busy ? ACCOUNT_TEMP_COPY.authenticating : ACCOUNT_COPY.login.google}
        </button>
      </main>
    </div>
  );
}
