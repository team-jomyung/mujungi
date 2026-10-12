"use client";

import { useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { CONSENT_TYPES, fetchMe, logout, saveConsents, usePolicyQuery, type ConsentType, type PolicyItem } from "../api/account";
import { ApiError } from "../api/apiClient";
import { useApiClient } from "../api/queries";
import { BottomSheet } from "../components/BottomSheet";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { useBackGuard } from "../hooks/useBackGuard";
import { PARENT_ROUTES, goToLogin, isUnauthenticated } from "../navigation";
import { ACCOUNT_COPY, ACCOUNT_PROPOSED_COPY, ACCOUNT_TEMP_COPY } from "@/shared/copy/account";
import { COPY, PROPOSED_COPY, TEMP_COPY } from "@/shared/copy/ko";
import { useToast } from "@/shared/toast/ToastProvider";

// 02 기록 · 동의 안내 + 02-1 약관 시트 + P5 동의 전 나가기 (U2 · US1.2 · AC1.2.1 ~ AC1.2.9 · mockups S-02 · S-02-1 · P-05 · R6 · R7)
// 네 항목을 모두 체크해야 「동의하고 계속」이 켜진다. 「자세히 보기 ›」는 그 항목의 약관(생성형 AI 안내 포함)을 바텀 시트로 보이고
// 「확인」 · 바깥 · Escape로 닫혀 보던 자리로 돌아온다. 「←」는 P5(기본 초점 「계속 동의하기」, 「로그아웃」 → 01).
// 저장하면 아이가 없으면 03(처음 등록), 있으면(재동의) 04로 간다. 동의를 바꾸거나 거두는 단추는 없다.

const ITEM_LABEL: Readonly<Record<ConsentType, string>> = {
  VOICE_AND_TEXT: ACCOUNT_COPY.consent.voiceAndText,
  GUARDIAN_VIEW: ACCOUNT_COPY.consent.guardianView,
  REPORT_SHARING: ACCOUNT_COPY.consent.reportSharing,
  RAW_AUDIO: ACCOUNT_COPY.consent.rawAudio,
};

const TEST_ID: Readonly<Record<ConsentType, string>> = {
  VOICE_AND_TEXT: "voice",
  GUARDIAN_VIEW: "view",
  REPORT_SHARING: "report",
  RAW_AUDIO: "audio",
};

export function Consent02() {
  const router = useRouter();
  const toast = useToast();
  const api = useApiClient();
  const queryClient = useQueryClient();
  const policy = usePolicyQuery();
  const [checked, setChecked] = useState<ReadonlySet<ConsentType>>(new Set());
  const [openItem, setOpenItem] = useState<PolicyItem | null>(null);
  const [leaving, setLeaving] = useState(false);
  const [saving, setSaving] = useState(false);
  const { error, refetch } = policy;

  // 기기 뒤로가기도 「←」와 같이 P5를 연다(AC1.2.5)
  useBackGuard(true, () => setLeaving(true));

  useEffect(() => {
    if (error === null) {
      return;
    }
    if (isUnauthenticated(error)) {
      goToLogin(router, toast);
      return;
    }
    toast.show({ message: TEMP_COPY.networkError, kind: "error", action: { label: PROPOSED_COPY.retry, onPress: () => void refetch() } });
  }, [error, refetch, router, toast]);

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
      router.replace(me !== null && me.childRegistered ? PARENT_ROUTES.home : PARENT_ROUTES.child);
    } catch (failure) {
      if (isUnauthenticated(failure)) {
        goToLogin(router, toast);
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
    router.replace(PARENT_ROUTES.login);
  };

  return (
    <div className="mj-screen" data-testid="consent-screen">
      <header className="mj-header mj-header--with-back">
        <button
          type="button"
          className="mj-header__back"
          aria-label={TEMP_COPY.back}
          data-testid="consent-back"
          onClick={() => setLeaving(true)}
        >
          {COPY.register.backArrow}
        </button>
        <h1 className="mj-header__title">{ACCOUNT_COPY.consent.title}</h1>
      </header>
      <main className="mj-screen__body">
        <ul className="mj-consent-list">
          {CONSENT_TYPES.map((type) => {
            const item = policy.data?.items.find((candidate) => candidate.type === type) ?? null;
            const inputId = `consent-${TEST_ID[type]}`;
            return (
              <li key={type} className="mj-consent-row">
                <input
                  type="checkbox"
                  id={inputId}
                  className="mj-consent-row__check"
                  data-testid={`consent-check-${TEST_ID[type]}`}
                  checked={checked.has(type)}
                  disabled={!loaded}
                  onChange={() => toggle(type)}
                />
                <label htmlFor={inputId} className="mj-consent-row__label">
                  {ITEM_LABEL[type]}
                  <span className="mj-badge">{ACCOUNT_COPY.consent.required}</span>
                </label>
                <button
                  type="button"
                  className="mj-link-button"
                  data-testid={`consent-detail-${TEST_ID[type]}`}
                  disabled={item === null}
                  onClick={() => setOpenItem(item)}
                >
                  {ACCOUNT_COPY.consent.detail}
                </button>
              </li>
            );
          })}
        </ul>
        <button
          type="button"
          className="mj-button mj-button--primary mj-button--block"
          data-testid="consent-agree"
          disabled={!allChecked || !loaded || saving}
          aria-busy={saving}
          onClick={() => void agree()}
        >
          {ACCOUNT_COPY.consent.agree}
        </button>
      </main>

      <BottomSheet open={openItem !== null} title={openItem?.title ?? ""} onClose={() => setOpenItem(null)} testIdPrefix="terms">
        {openItem !== null ? (
          <div className="mj-sheet__body" data-testid="terms-body">
            <p className="mj-badge">{ACCOUNT_COPY.consent.required}</p>
            <dl className="mj-terms">
              {openItem.sections.map((section) => (
                <div key={section.heading} className="mj-terms__section">
                  <dt className="mj-terms__heading">{section.heading}</dt>
                  <dd className="mj-terms__body">{section.body}</dd>
                </div>
              ))}
            </dl>
            <button
              type="button"
              className="mj-button mj-button--primary mj-button--block"
              data-testid="terms-confirm"
              onClick={() => setOpenItem(null)}
            >
              {ACCOUNT_COPY.consent.sheetConfirm}
            </button>
          </div>
        ) : null}
      </BottomSheet>

      <ConfirmDialog
        open={leaving}
        title={ACCOUNT_COPY.leaveConsent.title}
        body={ACCOUNT_COPY.leaveConsent.body}
        confirmLabel={ACCOUNT_COPY.leaveConsent.logout}
        cancelLabel={ACCOUNT_COPY.leaveConsent.keep}
        onConfirm={() => void leave()}
        onCancel={() => setLeaving(false)}
        testIdPrefix="p5"
      />
    </div>
  );
}
