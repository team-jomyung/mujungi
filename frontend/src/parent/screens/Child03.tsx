"use client";

import { useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useEffect, useId, useState } from "react";
import { accountQueryKeys, registerChild, updateChild, useChildQuery, type Child } from "../api/account";
import { ApiError } from "../api/apiClient";
import { queryKeys, useApiClient, useHomeQuery } from "../api/queries";
import type { DeviceView } from "../api/types";
import { useBackGuard } from "../hooks/useBackGuard";
import { PARENT_ROUTES, goToLogin, isUnauthenticated } from "../navigation";
import {
  ACCOUNT_COPY,
  ACCOUNT_PROPOSED_COPY,
  ACCOUNT_TEMP_COPY,
  childAgeInterestsLine,
  childDeviceLine,
} from "@/shared/copy/account";
import { COPY, PROPOSED_COPY, TEMP_COPY } from "@/shared/copy/ko";
import { useToast } from "@/shared/toast/ToastProvider";

// 03 아이 정보 — 처음 등록 · 보기 · 수정 한 화면 (U2 · US1.3 · AC1.3.1 ~ AC1.3.10 · mockups S-03 · 9-2 · 9-3 · R8 · U2-J14)
// 아이가 없으면(404 CHILD_NOT_REGISTERED) 처음 등록(뒤로 없음, 기기 뒤로가기도 아무 일 없음) → 「확인」이면 04.
// 있으면 보기(이름 · 'n세 · 관심사' · 기기 상태 줄 · 「정보 수정」) → 수정 중(카드 안 입력 + 「취소」 · 「확인」) → 저장되면 보기.
// 이름이 비면 「확인」 꺼짐, 나이가 6 ~ 12 밖이면 칸 아래 글(색만이 아님), 관심사는 쉼표로 5개 · 하나에 10자를 넘는 입력을 막는다.
// 저장 실패는 토스트이고 입력은 그대로다. 아이 선택 · 추가 · 삭제 단추는 없다.

export const NAME_MAX_LENGTH = 10;
export const AGE_MIN = 6;
export const AGE_MAX = 12;
export const INTERESTS_MAX = 5;
export const INTEREST_MAX_LENGTH = 10;

interface FormValues {
  readonly name: string;
  readonly age: string;
  readonly interests: string;
}

/** 쉼표로 나눈 관심사(앞뒤 공백 · 빈 칸 제거, 중복 제거). */
export function parseInterests(text: string): string[] {
  const items = text
    .split(",")
    .map((item) => item.trim())
    .filter((item) => item.length > 0);
  return [...new Set(items)];
}

/** 관심사 입력이 허용 범위 안인가(초과 입력은 받아들이지 않는다, AC1.3.8). */
export function interestsWithinLimit(text: string): boolean {
  const items = text.split(",").map((item) => item.trim());
  const filled = items.filter((item) => item.length > 0);
  return filled.length <= INTERESTS_MAX && items.every((item) => [...item].length <= INTEREST_MAX_LENGTH);
}

/** 나이 글 → 정수(6 ~ 12가 아니면 null). */
export function parseAge(text: string): number | null {
  if (!/^\d{1,2}$/.test(text.trim())) {
    return null;
  }
  const age = Number(text.trim());
  return age >= AGE_MIN && age <= AGE_MAX ? age : null;
}

function deviceStateName(device: DeviceView): string {
  if (device.registration.status === "NONE" || device.network.state === "NONE") {
    return ACCOUNT_PROPOSED_COPY.deviceLine.NONE;
  }
  switch (device.network.state) {
    case "STABLE":
    case "UNSTABLE":
    case "DISCONNECTED":
    case "UNRESPONSIVE":
      return ACCOUNT_PROPOSED_COPY.deviceLine[device.network.state];
    default:
      return PROPOSED_COPY.home.unknown;
  }
}

function valuesOf(child: Child | null): FormValues {
  return child === null
    ? { name: "", age: "", interests: "" }
    : { name: child.displayName, age: String(child.age), interests: child.interests.join(", ") };
}

export function Child03() {
  const router = useRouter();
  const toast = useToast();
  const api = useApiClient();
  const queryClient = useQueryClient();
  const childQuery = useChildQuery();
  const home = useHomeQuery();
  const [editing, setEditing] = useState(false);
  const [values, setValues] = useState<FormValues>(valuesOf(null));
  const [saving, setSaving] = useState(false);
  const nameId = useId();
  const ageId = useId();
  const ageErrorId = useId();
  const interestsId = useId();
  const interestsHintId = useId();
  const { error, refetch } = childQuery;

  const notRegistered = error instanceof ApiError && error.code === "CHILD_NOT_REGISTERED";
  const child = childQuery.data ?? null;
  const mode: "loading" | "first" | "view" | "edit" = notRegistered ? "first" : child === null ? "loading" : editing ? "edit" : "view";

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

  const age = parseAge(values.age);
  const ageInvalid = values.age.trim().length > 0 && age === null;
  const nameValid = values.name.trim().length > 0 && [...values.name.trim()].length <= NAME_MAX_LENGTH;
  const canConfirm = nameValid && age !== null && !saving;

  const startEdit = () => {
    setValues(valuesOf(child));
    setEditing(true);
  };

  const confirm = async () => {
    if (!canConfirm || age === null) {
      return;
    }
    setSaving(true);
    const input = { displayName: values.name.trim(), age, interests: parseInterests(values.interests) };
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

  const form = (
    <form
      className="mj-form mj-child-form"
      noValidate
      data-testid="child-form"
      onSubmit={(event) => {
        event.preventDefault();
        void confirm();
      }}
    >
      <div className="mj-field">
        <label className="mj-field__label" htmlFor={nameId}>
          {ACCOUNT_COPY.child.nameLabel}
        </label>
        <input
          id={nameId}
          className="mj-input"
          data-testid="child-name"
          value={values.name}
          maxLength={NAME_MAX_LENGTH}
          autoComplete="off"
          onChange={(event) => setValues({ ...values, name: event.target.value })}
        />
      </div>
      <div className="mj-field">
        <label className="mj-field__label" htmlFor={ageId}>
          {ACCOUNT_COPY.child.ageLabel}
        </label>
        <input
          id={ageId}
          className={`mj-input${ageInvalid ? " mj-input--invalid" : ""}`}
          data-testid="child-age"
          inputMode="numeric"
          maxLength={2}
          value={values.age}
          aria-invalid={ageInvalid}
          aria-describedby={ageInvalid ? ageErrorId : undefined}
          onChange={(event) => setValues({ ...values, age: event.target.value.replace(/\D/g, "") })}
        />
        {ageInvalid ? (
          <p className="mj-field__error" id={ageErrorId} role="alert" data-testid="child-age-error">
            {ACCOUNT_PROPOSED_COPY.ageError}
          </p>
        ) : null}
      </div>
      <div className="mj-field">
        <label className="mj-field__label" htmlFor={interestsId}>
          {ACCOUNT_COPY.child.interestsLabel}
        </label>
        <input
          id={interestsId}
          className="mj-input"
          data-testid="child-interests"
          value={values.interests}
          aria-describedby={interestsHintId}
          autoComplete="off"
          onChange={(event) => {
            if (interestsWithinLimit(event.target.value)) {
              setValues({ ...values, interests: event.target.value });
            }
          }}
        />
        <p className="mj-field__hint" id={interestsHintId}>
          {ACCOUNT_TEMP_COPY.interestsHint}
        </p>
      </div>
      <div className="mj-actions">
        {mode === "edit" ? (
          <button
            type="button"
            className="mj-button mj-button--secondary"
            data-testid="child-cancel"
            disabled={saving}
            onClick={() => setEditing(false)}
          >
            {ACCOUNT_COPY.child.cancel}
          </button>
        ) : null}
        <button type="submit" className="mj-button mj-button--primary" data-testid="child-confirm" disabled={!canConfirm}>
          {ACCOUNT_COPY.child.confirm}
        </button>
      </div>
    </form>
  );

  if (mode === "first") {
    return (
      <div className="mj-screen" data-testid="child-screen" data-mode="first">
        <header className="mj-header">
          <h1 className="mj-header__title">{ACCOUNT_COPY.child.firstTitle}</h1>
        </header>
        <main className="mj-screen__body">{form}</main>
      </div>
    );
  }

  return (
    <div className="mj-screen" data-testid="child-screen" data-mode={mode}>
      <header className="mj-header mj-header--with-back">
        <button
          type="button"
          className="mj-header__back"
          aria-label={TEMP_COPY.back}
          data-testid="child-back"
          onClick={() => router.push(PARENT_ROUTES.home)}
        >
          {COPY.register.backArrow}
        </button>
        <h1 className="mj-header__title">{ACCOUNT_COPY.child.viewTitle}</h1>
      </header>
      <main className="mj-screen__body">
        {mode === "loading" ? <section className="mj-card mj-card--placeholder" aria-busy="true" data-testid="child-loading" /> : null}
        {mode === "view" && child !== null ? (
          <section className="mj-card mj-child-card" data-testid="child-card">
            <h2 className="mj-child-card__name" data-testid="child-card-name">
              {child.displayName}
            </h2>
            <p className="mj-child-card__line">{childAgeInterestsLine(child.age, child.interests)}</p>
            {home.data !== undefined ? (
              <p className="mj-child-card__device" data-testid="child-card-device">
                {childDeviceLine(deviceStateName(home.data.device))}
              </p>
            ) : null}
            <button type="button" className="mj-button mj-button--secondary" data-testid="child-edit" onClick={startEdit}>
              {ACCOUNT_COPY.child.edit}
            </button>
          </section>
        ) : null}
        {mode === "edit" ? <section className="mj-card">{form}</section> : null}
      </main>
    </div>
  );
}
