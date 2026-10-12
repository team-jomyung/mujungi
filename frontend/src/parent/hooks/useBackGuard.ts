"use client";

import { useCallback, useEffect, useMemo, useRef } from "react";

// 기기 뒤로가기 가로채기 (U2 · AC1.2.5 · AC1.3.2 · interaction-spec 1절 '뒤로가기': 머리 「←」와 기기 뒤로가기는 같은 동작 · 리뷰 5)
// 켜져 있는 동안 같은 주소의 기록 한 칸(가드 표시가 있는 칸)을 더 쌓아 두고, 뒤로가기(popstate)가 오면 그 칸을 다시 쌓은 뒤 onBack을 부른다.
// 맨 위가 이미 가드 칸이면 다시 쌓지 않는다(StrictMode의 effect 두 번 실행에도 한 칸). 화면을 마치고 떠날 때는 leave(navigate)로
// 쌓은 칸을 먼저 되돌린 뒤 이동한다 — 이동이 replace라 마친 화면(02 · 03)이 뒤로가기 기록에 남지 않는다.
// 02는 P5를 열고, 03 처음 등록은 아무 일도 하지 않는다(등록을 마쳐야 넘어감).

const GUARD_KEY = "__mujoongBackGuard";

function isGuardEntry(state: unknown): boolean {
  return typeof state === "object" && state !== null && GUARD_KEY in state;
}

function pushGuardEntry(): void {
  const state: unknown = window.history.state;
  const base = typeof state === "object" && state !== null ? state : {};
  window.history.pushState({ ...base, [GUARD_KEY]: true }, "", window.location.href);
}

export interface BackGuard {
  /** 쌓은 가드 칸을 되돌린 뒤 navigate를 부른다(가드 칸이 맨 위가 아니면 곧바로). */
  readonly leave: (navigate: () => void) => void;
}

export function useBackGuard(enabled: boolean, onBack: () => void): BackGuard {
  const onBackRef = useRef(onBack);
  const leavingRef = useRef(false);

  useEffect(() => {
    onBackRef.current = onBack;
  }, [onBack]);

  useEffect(() => {
    if (!enabled) {
      return undefined;
    }
    leavingRef.current = false;
    if (!isGuardEntry(window.history.state)) {
      pushGuardEntry();
    }
    const handlePopState = () => {
      if (leavingRef.current) {
        return;
      }
      pushGuardEntry();
      onBackRef.current();
    };
    window.addEventListener("popstate", handlePopState);
    return () => window.removeEventListener("popstate", handlePopState);
  }, [enabled]);

  const leave = useCallback((navigate: () => void) => {
    leavingRef.current = true;
    if (!isGuardEntry(window.history.state)) {
      navigate();
      return;
    }
    const afterBack = () => {
      window.removeEventListener("popstate", afterBack);
      navigate();
    };
    window.addEventListener("popstate", afterBack);
    window.history.back();
  }, []);

  return useMemo(() => ({ leave }), [leave]);
}
