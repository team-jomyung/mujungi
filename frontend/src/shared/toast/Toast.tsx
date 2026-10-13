"use client";

import { useEffect, useRef, useState } from "react";

// 토스트 한 장 (U1 3.5 Step 37 · interaction-spec 3.4 · 접근성 O5 · P1)
// - 아래 중앙, 글 + 아이콘(색만으로 구분하지 않음). 조작 없음은 toast-duration 뒤 사라지고, 조작이 달린 토스트는
//   toast-action-min 이상 떠 있으며 초점이 안에 있는 동안은 사라지지 않는다(B25).
// - 오류는 assertive, 그 밖은 polite 영역에 놓는다(영역은 늘 렌더해 둬 보조기기가 바뀐 글을 읽게 한다).

export type ToastKind = "info" | "error";
/** Optional lifecycle observers use expiration, rather than a second timer (U5a-J3). */
export type ToastCloseReason = "expired" | "action" | "dismissed" | "replaced";

export interface ToastAction {
  readonly label: string;
  readonly onPress: () => void;
}

export interface ActiveToast {
  readonly id: number;
  readonly message: string;
  readonly kind: ToastKind;
  readonly action: ToastAction | null;
}

const KIND_ICON: Readonly<Record<ToastKind, string>> = {
  info: "ℹ",
  error: "!",
};

interface ToastCardProps {
  readonly toast: ActiveToast;
  readonly durationMs: number;
  readonly actionMinMs: number;
  readonly onDismiss: (id: number, reason: ToastCloseReason) => void;
}

function ToastCard({ toast, durationMs, actionMinMs, onDismiss }: ToastCardProps) {
  const focusedRef = useRef(false);
  const [expired, setExpired] = useState(false);
  const visibleMs = toast.action === null ? durationMs : Math.max(durationMs, actionMinMs);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      if (focusedRef.current) {
        setExpired(true);
        return;
      }
      onDismiss(toast.id, "expired");
    }, visibleMs);
    return () => window.clearTimeout(timer);
  }, [toast.id, visibleMs, onDismiss]);

  const handleBlur = () => {
    focusedRef.current = false;
    if (expired) {
      onDismiss(toast.id, "expired");
    }
  };

  const handleAction = () => {
    toast.action?.onPress();
    onDismiss(toast.id, "action");
  };

  return (
    <div
      className={`mj-toast mj-toast--${toast.kind}`}
      data-testid="toast"
      data-kind={toast.kind}
      onFocus={() => {
        focusedRef.current = true;
      }}
      onBlur={handleBlur}
    >
      <span className="mj-toast__icon" aria-hidden="true">
        {KIND_ICON[toast.kind]}
      </span>
      <span className="mj-toast__message">{toast.message}</span>
      {toast.action !== null ? (
        <button type="button" className="mj-toast__action" data-testid="toast-action" onClick={handleAction}>
          {toast.action.label}
        </button>
      ) : null}
    </div>
  );
}

interface ToastViewportProps {
  readonly toast: ActiveToast | null;
  readonly durationMs: number;
  readonly actionMinMs: number;
  readonly onDismiss: (id: number, reason: ToastCloseReason) => void;
}

/** polite · assertive 두 영역을 늘 두고 토스트는 종류에 맞는 영역에 그린다. */
export function ToastViewport({ toast, durationMs, actionMinMs, onDismiss }: ToastViewportProps) {
  const card = (kind: ToastKind) =>
    toast !== null && toast.kind === kind ? (
      <ToastCard key={toast.id} toast={toast} durationMs={durationMs} actionMinMs={actionMinMs} onDismiss={onDismiss} />
    ) : null;
  return (
    <div className="mj-toast-viewport">
      <div role="status" aria-live="polite" aria-atomic="true" data-testid="toast-region-polite">
        {card("info")}
      </div>
      <div role="alert" aria-live="assertive" aria-atomic="true" data-testid="toast-region-assertive">
        {card("error")}
      </div>
    </div>
  );
}
