"use client";

import { useRouter } from "next/navigation";
import { useRef, useState } from "react";
import { startFailureReason } from "../../common/activity/failure";
import { ApiError } from "../../common/api/apiClient";
import { useApiClient } from "../../common/api/queries";
import { startFreeTalk } from "./api";
import type { FreeTalkContext } from "../../common/api/types";
import type { TabKey } from "../../common/components/BottomTabBar";
import { PARENT_ROUTES, goToLogin, routeForTab } from "../../common/navigation";
import { PROPOSED_COPY, TEMP_COPY } from "@/shared/copy/ko";
import { messageForErrorCode } from "@/shared/errors/codeToCopy";
import { useToast } from "@/shared/toast/ToastProvider";

// 08 자유대화 준비 화면의 시작 요청 · 거절 사유 · 오류 분기 · 화면 이동을 맡는 흐름 훅.
// 입력 칸 값과 본문(context) 조립은 화면에 두고, 훅은 만든 본문을 받아 보낸다. 동작 규칙은 FreeTalkScreen에 있던 그대로다.

/** 거절 code → 사유 문구(mockups 9-12). 화면 사유가 없는 code는 null(토스트로 알린다). */
function rejectReasonFor(code: string): string | null {
  switch (code) {
    case "SESSION_IN_PROGRESS":
      return PROPOSED_COPY.free.rejectSessionInProgress;
    case "DEVICE_NOT_STABLE":
      return PROPOSED_COPY.free.rejectDeviceNotStable;
    case "CONSENT_MISSING":
      return PROPOSED_COPY.free.rejectConsentMissing;
    case "REALTIME_UNAVAILABLE":
      return TEMP_COPY.freeRealtimeUnavailable;
    default:
      return null;
  }
}

interface PendingStart {
  readonly key: string;
  readonly bodyJson: string;
}

export function useFreeTalkScreen(returnedFailed: boolean, failureCode: string | null) {
  const router = useRouter();
  const toast = useToast();
  const api = useApiClient();
  const [submitting, setSubmitting] = useState(false);
  const [reason, setReason] = useState<string | null>(returnedFailed ? startFailureReason(failureCode) : null);
  const pendingRef = useRef<PendingStart | null>(null);
  const formRef = useRef<HTMLFormElement>(null);
  // 요청 중 표시. submitting 상태는 다시 그린 뒤에야 바뀌므로 두 번째 요청은 이 값으로 막는다.
  const inFlightRef = useRef(false);

  /** 토스트 「다시 시도」: 지금 화면의 입력으로 폼을 다시 제출한다(화면을 떠났으면 아무것도 하지 않는다). */
  const resubmit = () => {
    formRef.current?.requestSubmit();
  };

  const submit = async (context: FreeTalkContext | undefined) => {
    if (inFlightRef.current) {
      return;
    }
    inFlightRef.current = true;
    const bodyJson = JSON.stringify(context ?? null);
    const pending = pendingRef.current;
    const key = pending !== null && pending.bodyJson === bodyJson ? pending.key : crypto.randomUUID();
    pendingRef.current = { key, bodyJson };
    setSubmitting(true);
    setReason(null);
    try {
      const result = await startFreeTalk(api, context === undefined ? { idempotencyKey: key } : { idempotencyKey: key, context });
      pendingRef.current = null;
      router.push(PARENT_ROUTES.session(result.sessionId));
    } catch (error) {
      if (!(error instanceof ApiError)) {
        pendingRef.current = null;
        toast.show({ message: TEMP_COPY.networkError, kind: "error" });
        return;
      }
      if (error.isUnauthenticated) {
        pendingRef.current = null;
        goToLogin(router, toast);
        return;
      }
      const sameKeyRetry = error.isDeliveryUnknown || (error.status === 403 && error.code === "CSRF_TOKEN_INVALID");
      if (!sameKeyRetry) {
        // 행이 남지 않은 거절 · 로컬 FAILED 재생은 새 시도로 본다(새 키).
        pendingRef.current = null;
      }
      const rejectReason = error.kind === "http" ? rejectReasonFor(error.code) : null;
      if (rejectReason !== null) {
        setReason(rejectReason);
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

  const handleNavigate = (tab: TabKey) => {
    if (tab !== "free") {
      router.push(routeForTab(tab));
    }
  };

  return {
    submitting,
    reason,
    formRef,
    submit: (context: FreeTalkContext | undefined) => void submit(context),
    handleNavigate,
    onLockedPress: (message: string) => toast.show({ message }),
  };
}
