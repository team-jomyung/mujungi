"use client";

import { useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useId, useRef, useState } from "react";
import { ApiError } from "../api/apiClient";
import { queryKeys, registerDevice, useApiClient } from "../api/queries";
import { CodeInput, emptyCodeCells, isCodeComplete } from "../components/CodeInput";
import { PARENT_ROUTES } from "../navigation";
import { COPY, PROPOSED_COPY, TEMP_COPY } from "@/shared/copy/ko";
import { messageForErrorCode } from "@/shared/errors/codeToCopy";
import { useToast } from "@/shared/toast/ToastProvider";

// 05a 기기 등록 (U1 3.5 Step 40 · BR2.7 · BR2.8 · BR2.9 · AC2.2.1 ~ AC2.2.4 · AC2.2.8 · mockups 9-9 · 9-10 · J118)
// 8칸이 다 차야 「등록」이 켜지고 전송 중에는 꺼지며 '등록하고 있어요'로 바뀐다. 성공하면 05로 간다(U1은 05 스텁 → 04, J57).
// 없는 코드(404) · 이미 등록된 코드(409) · 연결된 기존 기기(409) · 한도(429)는 빨간 카드이고 입력을 지우지 않는다.
// 네트워크 · 5xx는 토스트 + 「다시 시도」. 「취소」 · 「←」는 05로 간다. 기기 검색 · 페어링 · 해제 · 재등록 단추는 없다.
// 「다시 시도」는 실패한 렌더의 클로저가 아니라 지금 화면의 폼을 다시 제출해 보이는 코드로 보내고, 요청 중이면 막힌다(J362).

function errorCardFor(error: ApiError): string | null {
  switch (error.code) {
    case "CODE_NOT_FOUND":
    case "VALIDATION_FAILED":
      return COPY.register.codeNotFound;
    case "CODE_ALREADY_REGISTERED":
      return PROPOSED_COPY.register.codeAlreadyRegistered;
    case "DEVICE_ALREADY_ACTIVE":
      return TEMP_COPY.registerDeviceAlreadyActive;
    case "TOO_MANY_ATTEMPTS":
      return TEMP_COPY.registerTooManyAttempts;
    default:
      return null;
  }
}

export function Register05a() {
  const router = useRouter();
  const toast = useToast();
  const api = useApiClient();
  const queryClient = useQueryClient();
  const guideId = useId();
  const [cells, setCells] = useState<string[]>(emptyCodeCells);
  const [submitting, setSubmitting] = useState(false);
  const [errorCard, setErrorCard] = useState<string | null>(null);
  const formRef = useRef<HTMLFormElement>(null);
  // 요청 중 표시. submitting 상태는 다시 그린 뒤에야 바뀌므로 두 번째 요청은 이 값으로 막는다(BR2.8 · J362).
  const inFlightRef = useRef(false);

  const complete = isCodeComplete(cells);

  /** 토스트 「다시 시도」: 지금 화면의 입력으로 폼을 다시 제출한다(화면을 떠났으면 아무것도 하지 않는다, J362). */
  const resubmit = () => {
    formRef.current?.requestSubmit();
  };

  const submit = async () => {
    if (!isCodeComplete(cells) || inFlightRef.current) {
      return;
    }
    inFlightRef.current = true;
    setSubmitting(true);
    setErrorCard(null);
    try {
      await registerDevice(api, cells.join(""));
      await queryClient.invalidateQueries({ queryKey: queryKeys.home });
      router.push(PARENT_ROUTES.device);
    } catch (error) {
      if (!(error instanceof ApiError)) {
        toast.show({ message: TEMP_COPY.networkError, kind: "error" });
        return;
      }
      if (error.isUnauthenticated) {
        router.replace(PARENT_ROUTES.devLogin);
        return;
      }
      const card = error.kind === "http" ? errorCardFor(error) : null;
      if (card !== null) {
        setErrorCard(card);
        return;
      }
      toast.show({
        message: error.kind === "http" ? messageForErrorCode(error.code) : TEMP_COPY.networkError,
        kind: "error",
        action: { label: PROPOSED_COPY.retry, onPress: resubmit },
      });
    } finally {
      inFlightRef.current = false;
      setSubmitting(false);
    }
  };

  const handleCellsChange = (next: string[]) => {
    setCells(next);
    setErrorCard(null);
  };

  return (
    <div className="mj-screen" data-testid="register-screen">
      <header className="mj-header mj-header--with-back">
        <button
          type="button"
          className="mj-header__back"
          aria-label={TEMP_COPY.back}
          data-testid="register-back"
          onClick={() => router.push(PARENT_ROUTES.device)}
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
            void submit();
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
              onClick={() => router.push(PARENT_ROUTES.device)}
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
