"use client";

import { useId } from "react";
import { CodeInput } from "./CodeInput";
import { useDeviceRegisterScreen } from "./useDeviceRegisterScreen";
import { COPY, PROPOSED_COPY, TEMP_COPY } from "@/shared/copy/ko";

// 05a 기기 등록 (U1 3.5 Step 40 · mockups 9-9 · 9-10)
// 8칸이 다 차야 「등록」이 켜지고 전송 중에는 꺼지며 '등록하고 있어요'로 바뀐다. 성공하면 05로 간다(U2가 05 화면을 만들었다).
// 없는 코드(404) · 이미 등록된 코드(409) · 연결된 기존 기기(409) · 한도(429)는 빨간 카드이고 입력을 지우지 않는다.
// 네트워크 · 5xx는 토스트 + 「다시 시도」. 「취소」 · 「←」는 05로 간다. 기기 검색 · 페어링 · 해제 · 재등록 단추는 없다.
// 「다시 시도」는 실패한 렌더의 클로저가 아니라 지금 화면의 폼을 다시 제출해 보이는 코드로 보내고, 요청 중이면 막힌다.

export function DeviceRegisterScreen() {
  const guideId = useId();
  const { cells, submitting, errorCard, complete, formRef, handleCellsChange, submit, goToDevice } = useDeviceRegisterScreen();

  return (
    <div className="mj-screen" data-testid="register-screen">
      <header className="mj-header mj-header--with-back">
        <button
          type="button"
          className="mj-header__back"
          aria-label={TEMP_COPY.back}
          data-testid="register-back"
          onClick={goToDevice}
        >
          {COPY.register.backArrow}
        </button>
        <h1 className="mj-header__title">{COPY.register.title}</h1>
      </header>
      <main className="mj-screen__body">
        <form
          ref={formRef}
          className="mj-register"
          noValidate
          data-testid="register-form"
          onSubmit={(event) => {
            event.preventDefault();
            submit();
          }}
        >
          <p className="mj-register__guide" id={guideId}>
            {COPY.register.guide}
          </p>
          <CodeInput cells={cells} onChange={handleCellsChange} disabled={submitting} invalid={errorCard !== null} describedById={guideId} />
          <div aria-live="assertive" className="mj-register__error-slot">
            {errorCard !== null ? (
              <p className="mj-error-card" role="alert" data-testid="register-error">
                {errorCard}
              </p>
            ) : null}
          </div>
          <div className="mj-actions">
            <button
              type="button"
              className="mj-button mj-button--secondary"
              data-testid="register-cancel"
              onClick={goToDevice}
            >
              {COPY.register.cancel}
            </button>
            <button type="submit" className="mj-button mj-button--primary" data-testid="register-submit" disabled={!complete || submitting}>
              {submitting ? PROPOSED_COPY.register.submitting : COPY.register.submit}
            </button>
          </div>
        </form>
      </main>
    </div>
  );
}
