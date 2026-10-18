"use client";

import { useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useRef, useState } from "react";
import { ApiError } from "../../common/api/apiClient";
import { queryKeys, registerDevice, useApiClient } from "../../common/api/queries";
import { emptyCodeCells, isCodeComplete } from "./CodeInput";
import { PARENT_ROUTES, goToLogin } from "../../common/navigation";
import { COPY, PROPOSED_COPY, TEMP_COPY } from "@/shared/copy/ko";
import { messageForErrorCode } from "@/shared/errors/codeToCopy";
import { useToast } from "@/shared/toast/ToastProvider";

// 05a 기기 등록 화면의 요청 · 오류 분기 · 화면 이동을 맡는 흐름 훅(BR2.1).
// 동작 규칙(요청 중 막기 J362 · 오류 카드 · 토스트 「다시 시도」)은 DeviceRegisterScreen에 있던 그대로다(BR1.1).

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

export function useDeviceRegisterScreen() {
  const router = useRouter();
  const toast = useToast();
  const api = useApiClient();
  const queryClient = useQueryClient();
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
        goToLogin(router, toast);
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

  return {
    cells,
    submitting,
    errorCard,
    complete,
    formRef,
    handleCellsChange,
    submit: () => void submit(),
    goToDevice: () => router.push(PARENT_ROUTES.device),
  };
}
