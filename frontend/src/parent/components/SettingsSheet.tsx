"use client";

import { useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { logout, requestAccountDeletion } from "../api/account";
import { useApiClient } from "../api/queries";
import { PARENT_ROUTES, isUnauthenticated } from "../navigation";
import { BottomSheet } from "./BottomSheet";
import { ConfirmDialog } from "./ConfirmDialog";
import { ACCOUNT_COPY, ACCOUNT_TEMP_COPY, deleteDialogBody } from "@/shared/copy/account";
import { useToast } from "@/shared/toast/ToastProvider";

// SET0 설정 시트 + P7 계정 삭제 확인 (U2 · US1.5 · US1.6 · AC1.5.1 ~ AC1.5.5 · AC1.6.1 · AC1.6.2 · AC1.6.9 · mockups S-SET0 · P-07)
// 시트: '← 설정' · 「로그아웃」 · 「계정 삭제」(위험 색). 「←」 · 바깥 · Escape → 04.
// 로그아웃은 지금 세션만 끝내고 01로 간다(진행 중 활동은 계속, B2). 「계정 삭제」 → P7(기본 초점 「아니오」 → SET0,
// 「삭제」는 위험 단추) → 접수(즉시 차단 · 로그아웃) → 01. 이미 세션이 끝났으면(401) 그대로 01이다.

export interface SettingsSheetProps {
  readonly open: boolean;
  readonly childName: string | null;
  readonly onClose: () => void;
}

export function SettingsSheet({ open, childName, onClose }: SettingsSheetProps) {
  const router = useRouter();
  const toast = useToast();
  const api = useApiClient();
  const queryClient = useQueryClient();
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const [busy, setBusy] = useState(false);

  const leaveTo = (href: string) => {
    queryClient.clear();
    router.replace(href);
  };

  const signOut = async () => {
    if (busy) {
      return;
    }
    setBusy(true);
    try {
      await logout(api);
      leaveTo(PARENT_ROUTES.login);
    } catch (failure) {
      if (isUnauthenticated(failure)) {
        leaveTo(PARENT_ROUTES.login);
        return;
      }
      toast.show({ message: ACCOUNT_TEMP_COPY.logoutFailed, kind: "error" });
    } finally {
      setBusy(false);
    }
  };

  const deleteAccount = async () => {
    if (busy) {
      return;
    }
    setBusy(true);
    try {
      await requestAccountDeletion(api);
      leaveTo(PARENT_ROUTES.login);
    } catch (failure) {
      if (isUnauthenticated(failure)) {
        leaveTo(PARENT_ROUTES.login);
        return;
      }
      toast.show({ message: ACCOUNT_TEMP_COPY.deleteFailed, kind: "error" });
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <BottomSheet open={open && !confirmingDelete} title={ACCOUNT_COPY.settings.title} onClose={onClose} withBack testIdPrefix="settings">
        <div className="mj-sheet__body mj-settings">
          <button
            type="button"
            className="mj-button mj-button--secondary mj-button--block"
            data-testid="settings-logout"
            disabled={busy}
            onClick={() => void signOut()}
          >
            {ACCOUNT_COPY.settings.logout}
          </button>
          <button
            type="button"
            className="mj-button mj-button--danger mj-button--block"
            data-testid="settings-delete"
            disabled={busy}
            onClick={() => setConfirmingDelete(true)}
          >
            {ACCOUNT_COPY.settings.deleteAccount}
          </button>
        </div>
      </BottomSheet>
      <ConfirmDialog
        open={open && confirmingDelete}
        title={ACCOUNT_COPY.deleteDialog.title}
        body={deleteDialogBody(childName)}
        confirmLabel={ACCOUNT_COPY.deleteDialog.yes}
        cancelLabel={ACCOUNT_COPY.deleteDialog.no}
        destructive
        busy={busy}
        onConfirm={() => void deleteAccount()}
        onCancel={() => setConfirmingDelete(false)}
        testIdPrefix="p7"
      />
    </>
  );
}
