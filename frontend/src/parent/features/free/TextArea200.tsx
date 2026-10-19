"use client";

import { useId } from "react";
import { counterText } from "@/shared/copy/ko";
import { FREE_TEXT_MAX_LENGTH } from "../../common/api/types";

// 서술형 입력 (U1 3.5 Step 39 · interaction-spec 3.9 · 접근성 U2)
// 보이는 라벨(<label for>), 200자에서 입력이 막힘(maxLength), 글자 수 'n/200'은 aria-live polite로 알린다.
// 예시 글은 placeholder로만 쓰고 라벨을 대신하지 않는다.

export interface TextArea200Props {
  readonly label: string;
  readonly value: string;
  readonly onChange: (value: string) => void;
  readonly placeholder?: string;
  readonly disabled?: boolean;
  readonly maxLength?: number;
  readonly testId: string;
}

export function TextArea200({ label, value, onChange, placeholder, disabled = false, maxLength = FREE_TEXT_MAX_LENGTH, testId }: TextArea200Props) {
  const inputId = useId();
  const counterId = useId();
  const atLimit = value.length >= maxLength;
  return (
    <div className="mj-field">
      <label className="mj-field__label" htmlFor={inputId}>
        {label}
      </label>
      <textarea
        id={inputId}
        className="mj-textarea"
        value={value}
        maxLength={maxLength}
        placeholder={placeholder}
        disabled={disabled}
        rows={3}
        aria-describedby={counterId}
        data-testid={testId}
        onChange={(event) => onChange(event.target.value.slice(0, maxLength))}
      />
      <span
        id={counterId}
        className={`mj-field__counter${atLimit ? " mj-field__counter--limit" : ""}`}
        aria-live="polite"
        data-testid={`${testId}-counter`}
      >
        {counterText(value.length, maxLength)}
      </span>
    </div>
  );
}
