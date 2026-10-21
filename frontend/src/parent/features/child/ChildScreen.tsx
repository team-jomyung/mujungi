"use client";

import { useId, useState } from "react";
import { ageOnDate, birthDateOf, birthYears, daysInMonth } from "../../common/birthDate";
import { seoulDate } from "../../common/components/MonthCalendar";
import { BIRTHDATE_COPY as B, currentAgeLine, birthDateLine } from "@/shared/copy/account";
import type { DeviceView } from "../../common/api/types";
import { useChildScreen, type Child } from "./useChildScreen";
import {
  ACCOUNT_COPY,
  ACCOUNT_PROPOSED_COPY,
  ACCOUNT_TEMP_COPY,
  childAgeInterestsLine,
  childDeviceLine,
} from "@/shared/copy/account";
import { COPY, PROPOSED_COPY, TEMP_COPY } from "@/shared/copy/ko";

// 03 아이 정보 — 처음 등록 · 보기 · 수정 한 화면 (U2 · US1.3 · AC1.3.1 ~ AC1.3.10 · mockups S-03 · 9-2 · 9-3)
// 아이가 없으면(404 CHILD_NOT_REGISTERED) 처음 등록(뒤로 없음, 기기 뒤로가기도 아무 일 없음) → 「확인」이면 04.
// 있으면 보기(이름 · 'n세 · 관심사' · 기기 상태 줄 · 「정보 수정」) → 수정 중(카드 안 입력 + 「취소」 · 「확인」) → 저장되면 보기.
// 이름이 비면 「확인」 꺼짐, 나이가 6 ~ 12 밖이면 칸 아래 글(색만이 아님), 관심사는 쉼표로 5개 · 하나에 10자를 넘는 입력을 막는다.
// 저장 실패는 토스트이고 입력은 그대로다. 아이 선택 · 추가 · 삭제 단추는 없다.
// 요청 · 오류 분기 · 이동은 useChildScreen 훅이 맡고, 이 화면은 입력 칸 값 · 검증 · 그리기를 맡는다.

export const NAME_MAX_LENGTH = 10;
export const AGE_MIN = 6;
export const AGE_MAX = 12;
export const INTERESTS_MAX = 5;
export const INTEREST_MAX_LENGTH = 10;

interface FormValues {
  readonly name: string;
  readonly year: string;
  readonly month: string;
  readonly day: string;
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
    ? { name: "", year: "", month: "", day: "", interests: "" }
    : { name: child.displayName, year: child.birthDate?.slice(0, 4) ?? "", month: child.birthDate ? String(Number(child.birthDate.slice(5, 7))) : "", day: child.birthDate ? String(Number(child.birthDate.slice(8, 10))) : "", interests: child.interests.join(", ") };
}

export function ChildScreen() {
  const { child, mode, saving, homeDevice, startEdit: beginEdit, cancelEdit, confirm: save, goToHome } = useChildScreen();
  const [values, setValues] = useState<FormValues>(valuesOf(null));
  const nameId = useId();
  const ageId = useId();
  const ageErrorId = useId();
  const interestsId = useId();
  const interestsHintId = useId();

  const today = seoulDate();
  const referenceDate = mode === "edit" && child ? child.registeredOn : today;
  const birthDate = birthDateOf(values.year, values.month, values.day);
  const age = birthDate ? ageOnDate(birthDate, referenceDate) : null;
  const ageInvalid = age !== null && (age < 6 || age >= 13);
  const currentAge = birthDate ? ageOnDate(birthDate, today) : null;
  const changeDatePart = (key: "year" | "month" | "day", value: string) => {
    const next = { ...values, [key]: value };
    if (key !== "day" && next.day && Number(next.day) > daysInMonth(Number(next.year), Number(next.month))) next.day = "";
    setValues(next);
  };
  const nameValid = values.name.trim().length > 0 && [...values.name.trim()].length <= NAME_MAX_LENGTH;
  const canConfirm = nameValid && birthDate !== null && !ageInvalid && !saving;

  const startEdit = () => {
    setValues(valuesOf(child));
    beginEdit();
  };

  const confirm = () => {
    if (!canConfirm || birthDate === null) {
      return;
    }
    save({ displayName: values.name.trim(), birthDate, interests: parseInterests(values.interests) });
  };

  const form = (
    <form
      className="mj-form mj-child-form"
      noValidate
      data-testid="child-form"
      onSubmit={(event) => {
        event.preventDefault();
        confirm();
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
      <fieldset className="mj-field mj-birth-field" aria-describedby={ageInvalid ? ageErrorId : `${ageId}-hint`}>
        <legend className="mj-field__label">{B.label}</legend>
        <div className="mj-birth-selects">
          <label><span className="mj-field__label">{B.year}</span><select className="mj-input" aria-label={B.year} data-testid="child-birth-year" value={values.year} aria-invalid={ageInvalid} onChange={event => changeDatePart("year", event.target.value)}>
            <option value="">{B.year}</option>{birthYears(referenceDate).map(year => <option key={year} value={year}>{year}</option>)}
          </select></label>
          <label><span className="mj-field__label">{B.month}</span><select className="mj-input" aria-label={B.month} data-testid="child-birth-month" value={values.month} aria-invalid={ageInvalid} onChange={event => changeDatePart("month", event.target.value)}>
            <option value="">{B.month}</option>{Array.from({ length: 12 }, (_, i) => i + 1).map(month => <option key={month} value={month}>{month}</option>)}
          </select></label>
          <label><span className="mj-field__label">{B.day}</span><select className="mj-input" aria-label={B.day} data-testid="child-birth-day" value={values.day} disabled={!values.year || !values.month} aria-invalid={ageInvalid} onChange={event => changeDatePart("day", event.target.value)}>
            <option value="">{B.day}</option>{Array.from({ length: values.year && values.month ? daysInMonth(Number(values.year), Number(values.month)) : 0 }, (_, i) => i + 1).map(day => <option key={day} value={day}>{day}</option>)}
          </select></label>
        </div>
        <p className="mj-field__hint" id={`${ageId}-hint`}>{mode === "edit" ? B.editHint : B.hint}</p>
        {currentAge !== null ? <p data-testid="child-current-age">{currentAgeLine(currentAge)}</p> : null}
        {ageInvalid ? <p className="mj-field__error" id={ageErrorId} role="alert" data-testid="child-birth-error">{B.invalid}</p> : null}
      </fieldset>
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
            onClick={cancelEdit}
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
          onClick={goToHome}
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
            {child.birthDate ? <p>{birthDateLine(child.birthDate)}</p> : null}
            <p className="mj-child-card__line">{childAgeInterestsLine(child.age, child.interests)}</p>
            {homeDevice !== undefined ? (
              <p className="mj-child-card__device" data-testid="child-card-device">
                {childDeviceLine(deviceStateName(homeDevice))}
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
