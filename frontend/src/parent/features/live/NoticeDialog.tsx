"use client";

import { useEffect, useId, useRef, type KeyboardEvent } from "react";

// 알림 팝업 — 단추 「확인」 하나 (U4a · P2 예상치 못한 종료 AC3.4.1 · P3 아이 무응답 AC3.5.1 · interaction-spec 3.6 · 접근성 O4)
// role=alertdialog + aria-modal, 열리면 「확인」에 초점, Tab은 팝업 안에 머문다. 바깥 누름 · Escape로 닫히지 않는다 — 다음 화면(17 · 04)으로
// 가는 길이 「확인」 하나다.

export interface NoticeDialogProps {
  readonly open: boolean;
  readonly title: string;
  readonly confirmLabel: string;
  readonly onConfirm: () => void;
  /** data-testid 접두(예: 'p2' → p2-dialog · p2-confirm). */
  readonly testIdPrefix: string;
}

export function NoticeDialog({ open, title, confirmLabel, onConfirm, testIdPrefix }: NoticeDialogProps) {
  const titleId = useId();
  const confirmRef = useRef<HTMLButtonElement | null>(null);

  useEffect(() => {
    if (open) {
      confirmRef.current?.focus();
    }
  }, [open]);

  if (!open) {
    return null;
  }

  const keepFocusInside = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === "Tab") {
      event.preventDefault();
      confirmRef.current?.focus();
    }
  };

  return (
    <div className="mj-dialog-backdrop" data-testid={`${testIdPrefix}-backdrop`}>
      <div
        className="mj-dialog"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby={titleId}
        data-testid={`${testIdPrefix}-dialog`}
        onKeyDown={keepFocusInside}
      >
        <h2 className="mj-dialog__title" id={titleId} style={{ overflowWrap: "anywhere" }}>
          {title}
        </h2>
        <div className="mj-dialog__actions">
          <button
            ref={confirmRef}
            type="button"
            className="mj-button mj-button--primary"
            data-testid={`${testIdPrefix}-confirm`}
            onClick={onConfirm}
          >
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
