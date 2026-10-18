"use client";

import { useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useEffect, useId, useRef, useState } from "react";
import { accountQueryKeys, saveStopExpressions, useStopExpressionsQuery } from "../../api/account";
import { ApiError } from "../../api/apiClient";
import { useApiClient, useHomeQuery } from "../../api/queries";
import { PARENT_ROUTES, goToLogin, isUnauthenticated } from "../../navigation";
import { ACCOUNT_COPY, ACCOUNT_PROPOSED_COPY, ACCOUNT_TEMP_COPY, quoted, stopSectionTitle } from "@/shared/copy/account";
import { COPY, PROPOSED_COPY, TEMP_COPY } from "@/shared/copy/ko";
import { useToast } from "@/shared/toast/ToastProvider";

// SET-01 종료 표현 설정 (U2 · US2.3 · AC2.3.1 ~ AC2.3.6 · AC2.3.8 · mockups S-SET1 · 9-11 · R9 · U2-J11)
// 기본 표현 '무중아 그만할래'는 파란 고정 카드이고 삭제 · 수정 단추가 없다(색만이 아니라 구조로도 구분). 추가 표현 카드를 누르면 같은 화면
// 입력 칸이 수정 상태가 되고, 「+ 표현 추가」는 빈 입력 칸을 연다(5개면 꺼짐). 칸의 「확인」은 목록에만 반영하고(저장 전),
// 「삭제」는 목록에서 뺀다. 「저장」이면 통째 저장 후 05, 화면 「취소」 · 「←」는 변경을 버리고 05.
// 2 ~ 20자 · 중복(공백 · 대소문자 무시, 기본 표현 포함) · 5개를 어기면 칸 아래 오류 글과 「저장」 꺼짐. 실패는 실패로 알린다.
// 규칙은 서버 StopExpressionRules와 글자 하나까지 같다(리뷰 8): 공백 = 유니코드 White_Space 속성, 글자 수 = NFC 뒤 코드 포인트.
// 입력 칸에 maxLength를 두지 않는다(UTF-16 단위라 이모지 20개를 못 친다) — 넘으면 오류 글로 알린다.

export const STOP_MIN_LENGTH = 2;
export const STOP_MAX_LENGTH = 20;
export const STOP_MAX_ITEMS = 5;

const EDGE_SPACE = /^\p{White_Space}+|\p{White_Space}+$/gu;
const ANY_SPACE = /\p{White_Space}+/gu;

/** 저장할 글: NFC 뒤 앞뒤 공백(White_Space)을 뺀다. */
export function cleanExpression(text: string): string {
  return text.normalize("NFC").replace(EDGE_SPACE, "");
}

/** 정규화(NFC → 공백 전부 제거 → 소문자, 서버 R9와 같은 기준). */
export function normalizeExpression(text: string): string {
  return text.normalize("NFC").replace(ANY_SPACE, "").toLowerCase();
}

/** 칸 입력 검증: 오류 문구 또는 null(통과). {@code editingIndex}의 자기 자신과는 겹침으로 보지 않는다. */
export function validateExpression(text: string, others: readonly string[], editingIndex: number | null): string | null {
  const trimmed = cleanExpression(text);
  const length = [...trimmed].length;
  if (length < STOP_MIN_LENGTH || length > STOP_MAX_LENGTH) {
    return ACCOUNT_PROPOSED_COPY.stopLengthError;
  }
  const normalized = normalizeExpression(trimmed);
  if (normalized === normalizeExpression(ACCOUNT_COPY.stopExpressions.defaultText)) {
    return ACCOUNT_PROPOSED_COPY.stopDuplicateError;
  }
  const duplicate = others.some((other, index) => index !== editingIndex && normalizeExpression(other) === normalized);
  if (duplicate) {
    return ACCOUNT_PROPOSED_COPY.stopDuplicateError;
  }
  if (editingIndex === null && others.length >= STOP_MAX_ITEMS) {
    return ACCOUNT_PROPOSED_COPY.stopMaxError;
  }
  return null;
}

interface Draft {
  /** 고치는 칸(null이면 새 표현). */
  readonly index: number | null;
  readonly text: string;
  readonly error: string | null;
}

export function StopExpressionsScreen() {
  const router = useRouter();
  const toast = useToast();
  const queryClient = useQueryClient();
  const api = useApiClient();
  const stops = useStopExpressionsQuery();
  const home = useHomeQuery();
  const [items, setItems] = useState<string[] | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [saving, setSaving] = useState(false);
  const inputId = useId();
  const errorId = useId();
  const inputRef = useRef<HTMLInputElement | null>(null);
  const { error, refetch } = stops;

  // 화면 목록 = 손대기 전에는 서버 목록, 손댄 뒤에는 저장 전 편집 목록
  const loaded = stops.data;

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

  // 입력 칸이 열리거나 다른 표현으로 바뀌면 그 칸에 초점(입력 중에는 다시 옮기지 않는다)
  const draftKey = draft === null ? null : (draft.index ?? -1);
  useEffect(() => {
    if (draftKey !== null) {
      inputRef.current?.focus();
    }
  }, [draftKey]);

  const list = items ?? loaded?.items.map((item) => item.text) ?? [];
  const ready = loaded !== undefined;
  const childName = home.data?.child?.displayName ?? null;
  const title = childName === null ? ACCOUNT_COPY.device.stopSettings : stopSectionTitle(childName);
  const canSave = ready && draft === null && !saving;

  const openNew = () => setDraft({ index: null, text: "", error: null });
  const openEdit = (index: number) => setDraft({ index, text: list[index], error: null });

  const confirmDraft = () => {
    if (draft === null) {
      return;
    }
    const problem = validateExpression(draft.text, list, draft.index);
    if (problem !== null) {
      setDraft({ ...draft, error: problem });
      return;
    }
    const text = cleanExpression(draft.text);
    setItems(draft.index === null ? [...list, text] : list.map((item, index) => (index === draft.index ? text : item)));
    setDraft(null);
  };

  const remove = (index: number) => {
    setItems(list.filter((_, position) => position !== index));
    if (draft !== null && draft.index === index) {
      setDraft(null);
    }
  };

  const leave = () => router.push(PARENT_ROUTES.device);

  const save = async () => {
    if (!canSave) {
      return;
    }
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

  return (
    <div className="mj-screen" data-testid="stop-screen">
      <header className="mj-header mj-header--with-back">
        <button type="button" className="mj-header__back" aria-label={TEMP_COPY.back} data-testid="stop-back" onClick={leave}>
          {COPY.register.backArrow}
        </button>
        <h1 className="mj-header__title">{title}</h1>
      </header>
      <main className="mj-screen__body">
        <ul className="mj-stop-list" data-testid="stop-list" aria-busy={!ready}>
          <li className="mj-stop-card mj-stop-card--fixed" data-testid="stop-default">
            {quoted(ACCOUNT_COPY.stopExpressions.defaultText)}
          </li>
          {list.map((text, index) => (
            <li key={`${index}-${text}`} className="mj-stop-card" data-testid="stop-item">
              <button
                type="button"
                className="mj-stop-card__text"
                aria-label={`${ACCOUNT_TEMP_COPY.editExpression}${ACCOUNT_TEMP_COPY.separator}${text}`}
                data-testid={`stop-item-edit-${index + 1}`}
                onClick={() => openEdit(index)}
              >
                {quoted(text)}
              </button>
              <button
                type="button"
                className="mj-button mj-button--secondary mj-stop-card__remove"
                data-testid={`stop-item-remove-${index + 1}`}
                onClick={() => remove(index)}
              >
                {ACCOUNT_COPY.stopExpressions.remove}
              </button>
            </li>
          ))}
        </ul>
        {draft !== null ? (
          <div className="mj-field mj-stop-input" data-testid="stop-input-area">
            <label className="mj-field__label" htmlFor={inputId}>
              {ACCOUNT_COPY.stopExpressions.newLabel}
            </label>
            <div className="mj-stop-input__row">
              <input
                ref={inputRef}
                id={inputId}
                className={`mj-input${draft.error !== null ? " mj-input--invalid" : ""}`}
                data-testid="stop-input"
                value={draft.text}
                aria-invalid={draft.error !== null}
                aria-describedby={draft.error !== null ? errorId : undefined}
                autoComplete="off"
                onChange={(event) => setDraft({ ...draft, text: event.target.value, error: null })}
                onKeyDown={(event) => {
                  if (event.key === "Enter") {
                    event.preventDefault();
                    confirmDraft();
                  }
                }}
              />
              <button type="button" className="mj-button mj-button--primary" data-testid="stop-input-confirm" onClick={confirmDraft}>
                {ACCOUNT_COPY.stopExpressions.inputConfirm}
              </button>
            </div>
            {draft.error !== null ? (
              <p className="mj-field__error" id={errorId} role="alert" data-testid="stop-input-error">
                {draft.error}
              </p>
            ) : null}
          </div>
        ) : null}
        <button
          type="button"
          className="mj-button mj-button--secondary mj-button--block"
          data-testid="stop-add"
          disabled={!ready || list.length >= STOP_MAX_ITEMS || draft !== null}
          onClick={openNew}
        >
          {ACCOUNT_COPY.stopExpressions.add}
        </button>
        <div className="mj-actions">
          <button type="button" className="mj-button mj-button--secondary" data-testid="stop-cancel" onClick={leave}>
            {ACCOUNT_COPY.stopExpressions.cancel}
          </button>
          <button
            type="button"
            className="mj-button mj-button--primary"
            data-testid="stop-save"
            disabled={!canSave}
            aria-busy={saving}
            onClick={() => void save()}
          >
            {ACCOUNT_COPY.stopExpressions.save}
          </button>
        </div>
      </main>
    </div>
  );
}
