"use client";

import { useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { accountQueryKeys, saveStopExpressions, useStopExpressionsQuery } from "../../common/api/account";
import { ApiError } from "../../common/api/apiClient";
import { useApiClient, useHomeQuery } from "../../common/api/queries";
import { PARENT_ROUTES, goToLogin, isUnauthenticated } from "../../common/navigation";
import { ACCOUNT_PROPOSED_COPY } from "@/shared/copy/account";
import { PROPOSED_COPY, TEMP_COPY } from "@/shared/copy/ko";
import { useToast } from "@/shared/toast/ToastProvider";

// SET-01 종료 표현 설정 화면의 요청(조회 · 통째 저장) · 오류 분기 · 화면 이동을 맡는 흐름 훅(BR2.1).
// 편집 목록 · 입력 칸 · 검증은 화면에 두고, 훅은 저장할 목록을 받아 저장한다. 동작 규칙은 StopExpressionsScreen에 있던 그대로다(BR1.1).

export function useStopExpressionsScreen() {
  const router = useRouter();
  const toast = useToast();
  const queryClient = useQueryClient();
  const api = useApiClient();
  const stops = useStopExpressionsQuery();
  const home = useHomeQuery();
  const [saving, setSaving] = useState(false);
  const { error, refetch } = stops;

  useEffect(() => {
    if (error === null) {
      return;
    }
    if (isUnauthenticated(error)) {
      goToLogin(router, toast);
      return;
    }
    if (error instanceof ApiError && error.code === "CHILD_NOT_REGISTERED") {
      router.replace(PARENT_ROUTES.child);
      return;
    }
    toast.show({ message: TEMP_COPY.networkError, kind: "error", action: { label: PROPOSED_COPY.retry, onPress: () => void refetch() } });
  }, [error, refetch, router, toast]);

  const leave = () => router.push(PARENT_ROUTES.device);

  const persist = async (list: string[]) => {
    setSaving(true);
    try {
      await saveStopExpressions(api, list);
      await queryClient.invalidateQueries({ queryKey: accountQueryKeys.stopExpressions });
      leave();
    } catch (failure) {
      if (isUnauthenticated(failure)) {
        goToLogin(router, toast);
        return;
      }
      toast.show({ message: ACCOUNT_PROPOSED_COPY.stopSaveFailed, kind: "error" });
    } finally {
      setSaving(false);
    }
  };

  return {
    loaded: stops.data,
    childName: home.data?.child?.displayName ?? null,
    saving,
    leave,
    save: (list: string[]) => void persist(list),
  };
}
