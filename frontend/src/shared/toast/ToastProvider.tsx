"use client";

import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from "react";
import { useTiming } from "../timing/TimingProvider";
import { ToastViewport, type ActiveToast, type ToastAction, type ToastKind } from "./Toast";

// 토스트 문맥 (U1 3.5 Step 37 · interaction-spec 1절 '토스트' · 3.4)
// 한 번에 하나이고 새 토스트는 이전 것을 대체한다. 레이아웃 수준에 두어 화면 이동 뒤에도 이어 보인다
// (예: 잠긴 탭 이유 토스트 → 05 스텁 → 04, BR4.5).

export interface ShowToastOptions {
  readonly message: string;
  readonly kind?: ToastKind;
  readonly action?: ToastAction;
}

export interface ToastApi {
  readonly show: (options: ShowToastOptions) => void;
  readonly dismiss: () => void;
}

const ToastContext = createContext<ToastApi | null>(null);

export function ToastProvider({ children }: Readonly<{ children: ReactNode }>) {
  const timing = useTiming();
  const [toast, setToast] = useState<ActiveToast | null>(null);
  const nextIdRef = useRef(1);

  const show = useCallback((options: ShowToastOptions) => {
    const id = nextIdRef.current;
    nextIdRef.current += 1;
    setToast({ id, message: options.message, kind: options.kind ?? "info", action: options.action ?? null });
  }, []);

  const dismiss = useCallback(() => setToast(null), []);

  const dismissById = useCallback((id: number) => {
    setToast((current) => (current !== null && current.id === id ? null : current));
  }, []);

  const api = useMemo<ToastApi>(() => ({ show, dismiss }), [show, dismiss]);

  return (
    <ToastContext.Provider value={api}>
      {children}
      <ToastViewport
        toast={toast}
        durationMs={timing.toastDurationMs}
        actionMinMs={timing.toastActionMinMs}
        onDismiss={dismissById}
      />
    </ToastContext.Provider>
  );
}

/** 토스트 API. Provider 밖에서 부르면 멈춘다. */
export function useToast(): ToastApi {
  const api = useContext(ToastContext);
  if (api === null) {
    throw new Error("ToastProvider가 없다: 보호자 레이아웃이 토스트 문맥을 두지 않았다.");
  }
  return api;
}
