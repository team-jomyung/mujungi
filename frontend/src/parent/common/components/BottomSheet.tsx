"use client";

import { useEffect, useId, useRef, type KeyboardEvent, type ReactNode } from "react";
import { COPY } from "@/shared/copy/ko";
import { ACCOUNT_TEMP_COPY } from "@/shared/copy/account";

// 바텀 시트 (U2 · interaction-spec 3.5 · SET0 · 02-1)
// role=dialog + aria-modal, 열리면 제목에 초점, Tab은 시트 안에서만 돈다, Escape · 바깥 누름 · 머리 「←」로 닫히고
// 닫히면 열었던 단추로 초점을 돌려준다. 배경 스크롤은 잠근다.

export interface BottomSheetProps {
  readonly open: boolean;
  readonly title: string;
  readonly onClose: () => void;
  /** 머리에 「←」를 둘지(SET0은 '← 설정'). */
  readonly withBack?: boolean;
  /** data-testid 접두(예: 'settings' → settings-sheet · settings-back · settings-backdrop). */
  readonly testIdPrefix: string;
  readonly children: ReactNode;
}

const FOCUSABLE = "button:not([disabled]), [href], input:not([disabled]), [tabindex]:not([tabindex='-1'])";

export function BottomSheet({ open, title, onClose, withBack = false, testIdPrefix, children }: BottomSheetProps) {
  const titleId = useId();
  const sheetRef = useRef<HTMLDivElement | null>(null);
  const titleRef = useRef<HTMLHeadingElement | null>(null);

  useEffect(() => {
    if (!open) {
      return undefined;
    }
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    titleRef.current?.focus();
    return () => {
      document.body.style.overflow = previousOverflow;
      opener?.focus();
    };
  }, [open]);

  if (!open) {
    return null;
  }

  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === "Escape") {
      event.preventDefault();
      onClose();
      return;
    }
    if (event.key !== "Tab" || sheetRef.current === null) {
      return;
    }
    const focusable = Array.from(sheetRef.current.querySelectorAll<HTMLElement>(FOCUSABLE));
    if (focusable.length === 0) {
      event.preventDefault();
      return;
    }
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (event.shiftKey && (document.activeElement === first || document.activeElement === titleRef.current)) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  };

  return (
    <div
      className="mj-sheet-backdrop"
      data-testid={`${testIdPrefix}-backdrop`}
      onClick={(event) => {
        if (event.target === event.currentTarget) {
          onClose();
        }
      }}
    >
      <div
        ref={sheetRef}
        className="mj-sheet"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        data-testid={`${testIdPrefix}-sheet`}
        onKeyDown={handleKeyDown}
      >
        <div className="mj-sheet__header">
          {withBack ? (
            <button
              type="button"
              className="mj-header__back"
              aria-label={ACCOUNT_TEMP_COPY.closeSheet}
              data-testid={`${testIdPrefix}-back`}
              onClick={onClose}
            >
              {COPY.register.backArrow}
            </button>
          ) : null}
          <h2 className="mj-sheet__title" id={titleId} ref={titleRef} tabIndex={-1}>
            {title}
          </h2>
        </div>
        {children}
      </div>
    </div>
  );
}
