"use client";

import { useRef, type ChangeEvent, type ClipboardEvent, type KeyboardEvent } from "react";
import { COPY, TEMP_COPY, codeCellLabel } from "@/shared/copy/ko";
import { REGISTRATION_CODE_LENGTH } from "../api/types";

// 05a 코드 입력 8칸 (U1 3.5 Step 39 · BR2.8 · BR1.1 · AC2.2.1 · interaction-spec 3.7 · 접근성 O2 · U3)
// OTP식: 자동 대문자, 넣으면 다음 칸으로, 빈 칸에서 지우면 앞 칸으로, 8자 붙여넣기. 공백 · 하이픈 · 가운뎃점은 지운다(BR1.1 정규화).
// 영숫자만 받고 발급 알파벳 밖 글자(0 · 1 · I · L · O)도 그대로 받는다(서버가 404 CODE_NOT_FOUND, 편차 (d)).
// 칸마다 'n번째 글자' 이름, 묶음은 role=group + 안내 문장 연결, 칸 44px 이상, 4 · 4로 묶어 보인다.

/** 붙여넣기 · 입력 글을 코드 글자들로 바꾼다(공백 · 하이픈 · 가운뎃점 제거, 대문자, 영숫자만). */
export function normalizeCodeText(text: string): string {
  return text
    .replace(/[\s\-‐‑‒–—―·•∙・]/g, "")
    .toUpperCase()
    .replace(/[^A-Z0-9]/g, "");
}

/** 빈 칸 8개. */
export function emptyCodeCells(): string[] {
  return Array.from({ length: REGISTRATION_CODE_LENGTH }, () => "");
}

/** 칸이 모두 찼는지(「등록」 켜짐 조건, AC2.2.1). */
export function isCodeComplete(cells: readonly string[]): boolean {
  return cells.length === REGISTRATION_CODE_LENGTH && cells.every((cell) => cell.length === 1);
}

export interface CodeInputProps {
  readonly cells: readonly string[];
  readonly onChange: (cells: string[]) => void;
  readonly disabled?: boolean;
  readonly invalid?: boolean;
  /** 안내 문장 요소 id(aria-describedby). */
  readonly describedById?: string;
}

export function CodeInput({ cells, onChange, disabled = false, invalid = false, describedById }: CodeInputProps) {
  const inputsRef = useRef<Array<HTMLInputElement | null>>([]);

  const focusCell = (index: number) => {
    const target = inputsRef.current[Math.max(0, Math.min(REGISTRATION_CODE_LENGTH - 1, index))];
    target?.focus();
    target?.select();
  };

  /** start 칸부터 글자들을 채운다. 마지막으로 채운 다음 칸에 초점. */
  const fillFrom = (start: number, chars: string) => {
    const next = [...cells];
    let index = start;
    for (const char of chars) {
      if (index >= REGISTRATION_CODE_LENGTH) {
        break;
      }
      next[index] = char;
      index += 1;
    }
    onChange(next);
    focusCell(index);
  };

  const handleChange = (index: number) => (event: ChangeEvent<HTMLInputElement>) => {
    const raw = event.target.value;
    let chars = normalizeCodeText(raw);
    const previous = cells[index] ?? "";
    if (chars.length > 1 && previous.length === 1 && chars.startsWith(previous)) {
      // 이미 글자가 있는 칸에 이어 친 경우 새 글자만 쓴다.
      chars = chars.slice(previous.length);
    }
    if (chars.length === 0) {
      const next = [...cells];
      next[index] = "";
      onChange(next);
      return;
    }
    fillFrom(chars.length >= REGISTRATION_CODE_LENGTH ? 0 : index, chars);
  };

  const handleKeyDown = (index: number) => (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "Backspace" && (cells[index] ?? "") === "" && index > 0) {
      event.preventDefault();
      const next = [...cells];
      next[index - 1] = "";
      onChange(next);
      focusCell(index - 1);
    } else if (event.key === "ArrowLeft" && index > 0) {
      event.preventDefault();
      focusCell(index - 1);
    } else if (event.key === "ArrowRight" && index < REGISTRATION_CODE_LENGTH - 1) {
      event.preventDefault();
      focusCell(index + 1);
    }
  };

  const handlePaste = (index: number) => (event: ClipboardEvent<HTMLInputElement>) => {
    const chars = normalizeCodeText(event.clipboardData.getData("text"));
    event.preventDefault();
    if (chars.length === 0) {
      return;
    }
    fillFrom(chars.length >= REGISTRATION_CODE_LENGTH ? 0 : index, chars);
  };

  const renderCell = (index: number) => (
    <input
      key={index}
      ref={(element) => {
        inputsRef.current[index] = element;
      }}
      className={`mj-code-cell${invalid ? " mj-code-cell--invalid" : ""}`}
      type="text"
      inputMode="text"
      autoCapitalize="characters"
      autoComplete="off"
      autoCorrect="off"
      spellCheck={false}
      value={cells[index] ?? ""}
      aria-label={codeCellLabel(index + 1)}
      aria-invalid={invalid ? true : undefined}
      disabled={disabled}
      data-testid={`code-cell-${index}`}
      onChange={handleChange(index)}
      onKeyDown={handleKeyDown(index)}
      onPaste={handlePaste(index)}
      onFocus={(event) => event.currentTarget.select()}
    />
  );

  const half = REGISTRATION_CODE_LENGTH / 2;
  return (
    <div className="mj-code-input" role="group" aria-label={TEMP_COPY.codeGroupLabel} aria-describedby={describedById} data-testid="code-input">
      <div className="mj-code-input__half">{Array.from({ length: half }, (_, offset) => renderCell(offset))}</div>
      <span className="mj-code-input__separator" aria-hidden="true">
        {COPY.device.d0CodeSeparator.trim()}
      </span>
      <div className="mj-code-input__half">{Array.from({ length: half }, (_, offset) => renderCell(half + offset))}</div>
    </div>
  );
}
