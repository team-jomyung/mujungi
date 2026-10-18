"use client";

import { useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { ApiError } from "../../common/api/apiClient";
import { devLogin, useApiClient } from "../../common/api/queries";
import { PARENT_ROUTES } from "../../common/navigation";
import { TEMP_COPY } from "@/shared/copy/ko";
import { messageForErrorCode } from "@/shared/errors/codeToCopy";

// 시험 로그인 (U1 3.5 Step 40 · BR8.4 · J119 · SD-14 · AC1.1.7 · AC1.1.9)
// dev 프로필 + MUJOONG_DEVLOGIN_ENABLED=true일 때만 라우트가 열린다(page.tsx가 그 밖에는 notFound). 시드 보호자의
// 세션 쿠키를 열 뿐이고 아이디 · 비밀번호 입력란이 없다. 성공하면 CSRF 토큰을 다시 받고 04로 간다. 서버가 404면(꺼짐) 안내만 한다.
// U1에서 401을 받은 화면이 여기로 온다(01 구글 로그인은 U2).

export function DevLoginScreen() {
  const router = useRouter();
  const api = useApiClient();
  const queryClient = useQueryClient();
  const [submitting, setSubmitting] = useState(false);
  const [errorText, setErrorText] = useState<string | null>(null);

  const submit = async () => {
    if (submitting) {
      return;
    }
    setSubmitting(true);
    setErrorText(null);
    try {
      await devLogin(api);
      // 세션이 바뀌었으므로 앞 사용자의 조회 결과를 버린다.
      queryClient.clear();
      router.replace(PARENT_ROUTES.home);
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) {
        setErrorText(TEMP_COPY.devLogin.unavailable);
      } else if (error instanceof ApiError && error.kind === "http") {
        setErrorText(messageForErrorCode(error.code));
      } else {
        setErrorText(TEMP_COPY.networkError);
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="mj-screen" data-testid="dev-login-screen">
      <header className="mj-header">
        <h1 className="mj-header__title">{TEMP_COPY.devLogin.title}</h1>
      </header>
      <main className="mj-screen__body">
        <p className="mj-dev-login__body">{TEMP_COPY.devLogin.body}</p>
        <div aria-live="assertive">
          {errorText !== null ? (
            <p className="mj-error-card" role="alert" data-testid="dev-login-error">
              {errorText}
            </p>
          ) : null}
        </div>
        <button
          type="button"
          className="mj-button mj-button--primary mj-button--block"
          data-testid="dev-login-submit"
          disabled={submitting}
          onClick={() => void submit()}
        >
          {TEMP_COPY.devLogin.submit}
        </button>
      </main>
    </div>
  );
}
