"use client";

import { useCallback, useEffect, useMemo, useRef } from "react";

// 기기 뒤로가기 가로채기 (U2 · AC1.2.5 · AC1.3.2 · interaction-spec 1절 '뒤로가기': 머리 「←」와 기기 뒤로가기는 같은 동작 · 리뷰 5)
// 켜져 있는 동안 같은 주소의 기록 한 칸(가드 표시가 있는 칸)을 더 쌓아 두고, 뒤로가기(popstate)가 오면 그 칸을 다시 쌓은 뒤 onBack을 부른다.
// 맨 위가 이미 가드 칸이면 다시 쌓지 않는다(StrictMode의 effect 두 번 실행에도 한 칸). 화면을 마치고 떠날 때는 leave(navigate)로
// 쌓은 칸을 먼저 되돌린 뒤 이동한다 — 이동이 replace라 마친 화면(02 · 03)이 뒤로가기 기록에 남지 않는다.
// 02는 P5를 열고, 03 처음 등록은 아무 일도 하지 않는다(등록을 마쳐야 넘어감).
// 가드 칸 표시는 '이 문서'의 id다(2차 리뷰 1): history.state는 새로고침 뒤에도 남지만 그 앞 칸은 이전 문서라, 다른 문서가 남긴 표시를
// 믿고 back()을 부르면 문서 전체를 다시 불러온다. 이 문서가 쌓은 칸만 가드로 보고, 남의 표시 위에서는 새 칸을 쌓는다.

const GUARD_KEY = "__mujoongBackGuard";
/** 이 문서(모듈 적재 한 번)의 표시. 새로고침하면 바뀐다. */
const DOCUMENT_MARK = typeof crypto !== "undefined" && typeof crypto.randomUUID === "function"
  ? crypto.randomUUID()
  : `${Math.random().toString(36).slice(2)}${Math.random().toString(36).slice(2)}`;

/** 맨 위 칸이 이 문서가 쌓은 가드 칸인가. */
function isOwnGuardEntry(state: unknown): boolean {
  return typeof state === "object" && state !== null && (state as Record<string, unknown>)[GUARD_KEY] === DOCUMENT_MARK;
}

function pushGuardEntry(): void {
  const state: unknown = window.history.state;
  const base = typeof state === "object" && state !== null ? state : {};
  window.history.pushState({ ...base, [GUARD_KEY]: DOCUMENT_MARK }, "", window.location.href);
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
    if (!isOwnGuardEntry(window.history.state)) {
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
    if (!isOwnGuardEntry(window.history.state)) {
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
