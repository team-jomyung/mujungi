"use client";

import { useEffect, useId, useRef, type KeyboardEvent } from "react";

// 확인 팝업 (U1 3.5 Step 39 · interaction-spec 3.6 · 접근성 O4 · U4 · P1 끝내기)
// role=alertdialog + aria-modal, 열리면 안전한 쪽(「아니오」)에 초점, Tab은 팝업 안에서만 돈다, Escape로 닫힘(P1),
// 닫히면 열었던 단추로 초점을 돌려준다.

export interface ConfirmDialogProps {
  readonly open: boolean;
  readonly title: string;
  readonly body?: string;
  readonly confirmLabel: string;
  readonly cancelLabel: string;
  readonly onConfirm: () => void;
  readonly onCancel: () => void;
  /** 확인 요청 중이면 두 단추를 잠근다(두 번 누름 방지). */
  readonly busy?: boolean;
  /** Escape · 바깥 누름으로 닫을지(P1은 true). */
  readonly dismissible?: boolean;
  /** data-testid 접두(예: 'p1' → p1-dialog · p1-yes · p1-no). */
  readonly testIdPrefix: string;
  /** 파괴적 확인(P7 「삭제」)이면 위험 색 단추(AC1.6.9, U2). 기본은 주 단추. */
  readonly destructive?: boolean;
}

const FOCUSABLE = "button:not([disabled]), [href], input:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex='-1'])";

export function ConfirmDialog(props: ConfirmDialogProps) {
  const {
    open,
    title,
    body,
    confirmLabel,
    cancelLabel,
    onConfirm,
    onCancel,
    busy = false,
    dismissible = true,
    testIdPrefix,
    destructive = false,
  } = props;
  const titleId = useId();
  const bodyId = useId();
  const dialogRef = useRef<HTMLDivElement | null>(null);
  const cancelRef = useRef<HTMLButtonElement | null>(null);

  useEffect(() => {
    if (!open) {
      return undefined;
    }
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    cancelRef.current?.focus();
    return () => {
      opener?.focus();
    };
  }, [open]);

  if (!open) {
    return null;
  }

  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === "Escape" && dismissible && !busy) {
      event.preventDefault();
      onCancel();
      return;
    }
    if (event.key !== "Tab" || dialogRef.current === null) {
      return;
    }
    const focusable = Array.from(dialogRef.current.querySelectorAll<HTMLElement>(FOCUSABLE));
    if (focusable.length === 0) {
      event.preventDefault();
      return;
    }
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  };

  return (
    <div
      className="mj-dialog-backdrop"
      data-testid={`${testIdPrefix}-backdrop`}
      onClick={(event) => {
        if (event.target === event.currentTarget && dismissible && !busy) {
          onCancel();
        }
      }}
    >
      <div
        ref={dialogRef}
        className="mj-dialog"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={body === undefined ? undefined : bodyId}
        data-testid={`${testIdPrefix}-dialog`}
        onKeyDown={handleKeyDown}
      >
        <h2 className="mj-dialog__title" id={titleId}>
          {title}
        </h2>
        {body === undefined ? null : (
          <p className="mj-dialog__body" id={bodyId}>
            {body}
          </p>
        )}
        <div className="mj-dialog__actions">
          <button
            ref={cancelRef}
            type="button"
            className="mj-button mj-button--secondary"
            data-testid={`${testIdPrefix}-no`}
            onClick={onCancel}
            disabled={busy}
          >
            {cancelLabel}
          </button>
          <button
            type="button"
            className={`mj-button ${destructive ? "mj-button--danger" : "mj-button--primary"}`}
            data-testid={`${testIdPrefix}-yes`}
            onClick={onConfirm}
            disabled={busy}
          >
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
