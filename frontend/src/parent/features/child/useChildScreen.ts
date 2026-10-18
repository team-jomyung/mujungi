"use client";

import { useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { accountQueryKeys } from "../../common/api/account";
import { registerChild, updateChild, useChildQuery, type Child, type ChildInput } from "../../common/api/child";
import { ApiError } from "../../common/api/apiClient";
import { queryKeys, useApiClient } from "../../common/api/queries";
import { useHomeQuery } from "../../common/api/home";
import { useBackGuard } from "../../common/hooks/useBackGuard";
import { PARENT_ROUTES, goToLogin, isUnauthenticated } from "../../common/navigation";
import { ACCOUNT_PROPOSED_COPY } from "@/shared/copy/account";
import { PROPOSED_COPY, TEMP_COPY } from "@/shared/copy/ko";
import { useToast } from "@/shared/toast/ToastProvider";

// 03 아이 정보 화면의 요청(조회 · 등록 · 수정) · 오류 분기 · 화면 이동을 맡는 흐름 훅(BR2.1).
// 입력 칸 값과 검증은 화면에 두고, 훅은 검증을 마친 입력을 받아 저장한다. 동작 규칙은 ChildScreen에 있던 그대로다(BR1.1).

export type { Child, ChildInput };

export type ChildMode = "loading" | "first" | "view" | "edit";

export function useChildScreen() {
  const router = useRouter();
  const toast = useToast();
  const api = useApiClient();
  const queryClient = useQueryClient();
  const childQuery = useChildQuery();
  const home = useHomeQuery();
  const [editing, setEditing] = useState(false);
  const [saving, setSaving] = useState(false);
  const { error, refetch } = childQuery;

  const notRegistered = error instanceof ApiError && error.code === "CHILD_NOT_REGISTERED";
  const child = childQuery.data ?? null;
  const mode: ChildMode = notRegistered ? "first" : child === null ? "loading" : editing ? "edit" : "view";

  // 처음 등록 중에는 기기 뒤로가기가 아무 일도 하지 않는다(AC1.3.2). 마치고 떠날 때는 가드 칸을 되돌린 뒤 이동한다(리뷰 5)
  const guard = useBackGuard(mode === "first", () => undefined);

  useEffect(() => {
    if (error === null || notRegistered) {
      return;
    }
    if (isUnauthenticated(error)) {
      guard.leave(() => goToLogin(router, toast));
      return;
    }
    toast.show({ message: TEMP_COPY.networkError, kind: "error", action: { label: PROPOSED_COPY.retry, onPress: () => void refetch() } });
  }, [error, guard, notRegistered, refetch, router, toast]);

  /** 검증을 마친 입력을 저장한다(처음이면 등록, 아니면 수정). */
  const confirm = async (input: ChildInput) => {
    setSaving(true);
    try {
      if (mode === "first") {
        await registerChild(api, input);
        await queryClient.invalidateQueries({ queryKey: queryKeys.home });
        queryClient.removeQueries({ queryKey: accountQueryKeys.me });
        guard.leave(() => router.replace(PARENT_ROUTES.home));
        return;
      }
      const saved = await updateChild(api, input);
      queryClient.setQueryData(accountQueryKeys.child, saved);
      await queryClient.invalidateQueries({ queryKey: queryKeys.home });
      setEditing(false);
    } catch (failure) {
      if (isUnauthenticated(failure)) {
        guard.leave(() => goToLogin(router, toast));
        return;
      }
      toast.show({ message: ACCOUNT_PROPOSED_COPY.childSaveFailed, kind: "error" });
    } finally {
      setSaving(false);
    }
  };

  return {
    child,
    mode,
    saving,
    homeDevice: home.data?.device,
    startEdit: () => setEditing(true),
    cancelEdit: () => setEditing(false),
    confirm: (input: ChildInput) => void confirm(input),
    goToHome: () => router.push(PARENT_ROUTES.home),
  };
}
