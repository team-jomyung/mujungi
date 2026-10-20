"use client";

import { useId } from "react";

// 단일 선택 칩 (U1 3.5 Step 39 · interaction-spec 3.8 · 가정 C3)
// 08 '관련 상황' · '정보 출처'. 네이티브 라디오(role=radiogroup)라 화살표 키로 옮겨 가고, 고른 칩을 다시 누르면 선택을 지운다
// (네 칸 모두 선택 사항). 선택은 색 + 체크 표시로 보인다.

export interface ChipOption<V extends string> {
  readonly value: V;
  readonly label: string;
}

export interface ChipGroupProps<V extends string> {
  readonly name: string;
  readonly legend: string;
  readonly options: readonly ChipOption<V>[];
  readonly value: V | null;
  readonly onChange: (value: V | null) => void;
  readonly disabled?: boolean;
  /** data-testid 접두(예: 'free-situation' → free-situation-friend). */
  readonly testIdPrefix: string;
}

const CHECK_MARK = "✓";

function testIdSuffix(value: string): string {
  return value.toLowerCase().replace(/_/g, "-");
}

export function ChipGroup<V extends string>(props: ChipGroupProps<V>) {
  const { name, legend, options, value, onChange, disabled = false, testIdPrefix } = props;
  const legendId = useId();
  return (
    <fieldset className="mj-chip-group" role="radiogroup" aria-labelledby={legendId} data-testid={testIdPrefix} disabled={disabled}>
      <legend className="mj-field__label" id={legendId}>
        {legend}
      </legend>
      <div className="mj-chip-group__options">
        {options.map((option) => {
          const checked = option.value === value;
          return (
            <label key={option.value} className={`mj-chip${checked ? " mj-chip--checked" : ""}`}>
              <input
                className="mj-chip__input"
                type="radio"
                name={name}
                value={option.value}
                checked={checked}
                data-testid={`${testIdPrefix}-${testIdSuffix(option.value)}`}
                onChange={() => onChange(option.value)}
                onClick={() => {
                  if (checked) {
                    onChange(null);
                  }
                }}
              />
              {checked ? (
                <span className="mj-chip__check" aria-hidden="true">
                  {CHECK_MARK}
                </span>
              ) : null}
              <span>{option.label}</span>
            </label>
          );
        })}
      </div>
    </fieldset>
  );
}
