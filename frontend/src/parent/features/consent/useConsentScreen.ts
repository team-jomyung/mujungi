"use client";

import { useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { CONSENT_TYPES, fetchMe, logout, saveConsents, usePolicyQuery, type ConsentType, type PolicyItem } from "../../common/api/account";
import { ApiError } from "../../common/api/apiClient";
import { useApiClient } from "../../common/api/queries";
import { useBackGuard } from "../../common/hooks/useBackGuard";
import { PARENT_ROUTES, goToLogin, isUnauthenticated } from "../../common/navigation";
import { ACCOUNT_PROPOSED_COPY, ACCOUNT_TEMP_COPY } from "@/shared/copy/account";
import { PROPOSED_COPY, TEMP_COPY } from "@/shared/copy/ko";
import { useToast } from "@/shared/toast/ToastProvider";

// 02 기록 · 동의 안내 화면의 요청(약관 조회 · 동의 저장 · 로그아웃) · 오류 분기 · 화면 이동을 맡는 흐름 훅(BR2.1).
// 동작 규칙은 ConsentScreen에 있던 그대로다(BR1.1).

export { CONSENT_TYPES };
export type { ConsentType, PolicyItem };

export function useConsentScreen() {
  const router = useRouter();
  const toast = useToast();
  const api = useApiClient();
  const queryClient = useQueryClient();
  const policy = usePolicyQuery();
  const [checked, setChecked] = useState<ReadonlySet<ConsentType>>(new Set());
  const [leaving, setLeaving] = useState(false);
  const [saving, setSaving] = useState(false);
  const { error, refetch } = policy;

  // 기기 뒤로가기도 「←」와 같이 P5를 연다(AC1.2.5). 02를 떠날 때는 가드 칸을 되돌린 뒤 이동한다(리뷰 5)
  const guard = useBackGuard(true, () => setLeaving(true));

  useEffect(() => {
    if (error === null) {
      return;
    }
    if (isUnauthenticated(error)) {
      guard.leave(() => goToLogin(router, toast));
      return;
    }
    toast.show({ message: TEMP_COPY.networkError, kind: "error", action: { label: PROPOSED_COPY.retry, onPress: () => void refetch() } });
  }, [error, guard, refetch, router, toast]);

  const allChecked = CONSENT_TYPES.every((type) => checked.has(type));
  const loaded = policy.data !== undefined;

  const toggle = (type: ConsentType) => {
    setChecked((previous) => {
      const next = new Set(previous);
      if (next.has(type)) {
        next.delete(type);
      } else {
        next.add(type);
      }
      return next;
    });
  };

  const agree = async () => {
    if (!allChecked || policy.data === undefined || saving) {
      return;
    }
    setSaving(true);
    try {
      await saveConsents(api, policy.data.policyVersion);
      const me = await fetchMe(api).catch(() => null);
      queryClient.clear();
      guard.leave(() => router.replace(me !== null && me.childRegistered ? PARENT_ROUTES.home : PARENT_ROUTES.child));
    } catch (failure) {
      if (isUnauthenticated(failure)) {
        guard.leave(() => goToLogin(router, toast));
        return;
      }
      if (failure instanceof ApiError && failure.status === 400) {
        // 그 사이 약관 버전이 바뀌었다(편차 (U2-f)): 새 약관을 다시 읽고 다시 동의받는다
        setChecked(new Set());
        void refetch();
      }
      toast.show({ message: ACCOUNT_PROPOSED_COPY.consentSaveFailed, kind: "error" });
    } finally {
      setSaving(false);
    }
  };

  const leave = async () => {
    try {
      await logout(api);
    } catch (failure) {
      if (!isUnauthenticated(failure)) {
        toast.show({ message: ACCOUNT_TEMP_COPY.logoutFailed, kind: "error" });
        return;
      }
    }
    queryClient.clear();
    guard.leave(() => router.replace(PARENT_ROUTES.login));
  };

  return {
    policy: policy.data,
    checked,
    allChecked,
    loaded,
    saving,
    leaving,
    toggle,
    openLeaveDialog: () => setLeaving(true),
    closeLeaveDialog: () => setLeaving(false),
    agree: () => void agree(),
    leave: () => void leave(),
  };
}
