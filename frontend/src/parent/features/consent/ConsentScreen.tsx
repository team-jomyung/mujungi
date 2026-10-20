"use client";

import { useState } from "react";
import { CONSENT_TYPES, useConsentScreen, type ConsentType, type PolicyItem } from "./useConsentScreen";
import { BottomSheet } from "../../common/components/BottomSheet";
import { ConfirmDialog } from "../../common/components/ConfirmDialog";
import { ACCOUNT_COPY } from "@/shared/copy/account";
import { COPY, TEMP_COPY } from "@/shared/copy/ko";

// 02 기록 · 동의 안내 + 02-1 약관 시트 + P5 동의 전 나가기 (U2 · US1.2 · AC1.2.1 ~ AC1.2.9 · mockups S-02 · S-02-1 · P-05 · R6 · R7)
// 네 항목을 모두 체크해야 「동의하고 계속」이 켜진다. 「자세히 보기 ›」는 그 항목의 약관(생성형 AI 안내 포함)을 바텀 시트로 보이고
// 「확인」 · 바깥 · Escape로 닫혀 보던 자리로 돌아온다. 「←」는 P5(기본 초점 「계속 동의하기」, 「로그아웃」 → 01).
// 저장하면 아이가 없으면 03(처음 등록), 있으면(재동의) 04로 간다. 동의를 바꾸거나 거두는 단추는 없다.
// 요청 · 오류 분기 · 이동은 useConsentScreen 훅이 맡고, 이 화면은 약관 시트 열림과 그리기를 맡는다.

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

export function ConsentScreen() {
  const { policy, checked, allChecked, loaded, saving, leaving, toggle, openLeaveDialog, closeLeaveDialog, agree, leave } = useConsentScreen();
  const [openItem, setOpenItem] = useState<PolicyItem | null>(null);

  return (
    <div className="mj-screen" data-testid="consent-screen">
      <header className="mj-header mj-header--with-back">
        <button
          type="button"
          className="mj-header__back"
          aria-label={TEMP_COPY.back}
          data-testid="consent-back"
          onClick={openLeaveDialog}
        >
          {COPY.register.backArrow}
        </button>
        <h1 className="mj-header__title">{ACCOUNT_COPY.consent.title}</h1>
      </header>
      <main className="mj-screen__body">
        <ul className="mj-consent-list">
          {CONSENT_TYPES.map((type) => {
            const item = policy?.items.find((candidate) => candidate.type === type) ?? null;
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
          onClick={agree}
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
        onConfirm={leave}
        onCancel={closeLeaveDialog}
        testIdPrefix="p5"
      />
    </div>
  );
}
