"use client";

import { useCallback, useEffect, useMemo, useRef } from "react";

// 기기 뒤로가기 가로채기 (U2 · AC1.2.5 · AC1.3.2 · interaction-spec 1절 '뒤로가기': 머리 「←」와 기기 뒤로가기는 같은 동작 · 리뷰 5)
// 켜져 있는 동안 같은 주소의 기록 한 칸(가드 표시가 있는 칸)을 더 쌓아 두고, 뒤로가기(popstate)가 오면 그 칸을 다시 쌓은 뒤 onBack을 부른다.
// 맨 위가 이미 가드 칸이면 다시 쌓지 않는다(StrictMode의 effect 두 번 실행에도 한 칸). 화면을 마치고 떠날 때는 leave(navigate)로
// 쌓은 칸을 먼저 되돌린 뒤 이동한다 — 이동이 replace라 마친 화면(02 · 03)이 뒤로가기 기록에 남지 않는다.
// 02는 P5를 열고, 03 처음 등록은 아무 일도 하지 않는다(등록을 마쳐야 넘어감).
// 가드 칸 표시는 '이 문서'의 id다(2차 리뷰 1). 새로고침 · 다시 불러오기 뒤 맨 위 칸에 남의 표시(이전 문서가 쌓은 가드 칸)가 있으면
// 그 칸을 이 문서의 가드 칸으로 넘겨받는다(표시만 이 문서 id로 바꿔 쓰고 새 칸을 쌓지 않는다, 3차 리뷰 5). pushState로 쌓인 칸들은
// 새로고침 뒤에도 같은 문서 칸이라(브라우저에서 확인 — back()은 popstate만 내고 다시 불러오지 않는다) 그 아래 기본 칸으로 back() 할 수
// 있고, 그래야 leave()의 replace 이동이 기본 칸을 덮어 마친 화면이 기록에 남지 않는다. 새 칸을 쌓으면(2차 방침) 남의 칸 아래 기본 칸이
// 기록에 남아 03에서 뒤로가기가 02로 돌아갔다.

const GUARD_KEY = "__mujoongBackGuard";
/** 이 문서(모듈 적재 한 번)의 표시. 새로고침하면 바뀐다. */
const DOCUMENT_MARK = typeof crypto !== "undefined" && typeof crypto.randomUUID === "function"
  ? crypto.randomUUID()
  : `${Math.random().toString(36).slice(2)}${Math.random().toString(36).slice(2)}`;

function guardMark(state: unknown): unknown {
  return typeof state === "object" && state !== null ? (state as Record<string, unknown>)[GUARD_KEY] : undefined;
}

/** 맨 위 칸이 이 문서가 쌓은(또는 넘겨받은) 가드 칸인가. */
function isOwnGuardEntry(state: unknown): boolean {
  return guardMark(state) === DOCUMENT_MARK;
}

function markedState(): Record<string, unknown> {
  const state: unknown = window.history.state;
  const base = typeof state === "object" && state !== null ? state : {};
  return { ...base, [GUARD_KEY]: DOCUMENT_MARK };
}

function pushGuardEntry(): void {
  window.history.pushState(markedState(), "", window.location.href);
}

/** 맨 위 칸을 이 문서의 가드 칸으로 만든다: 이미 이 문서 칸이면 그대로, 남의 가드 칸이면 넘겨받고, 가드 칸이 아니면 새로 쌓는다. */
function ensureGuardEntry(): void {
  const mark = guardMark(window.history.state);
  if (mark === DOCUMENT_MARK) {
    return;
  }
  if (mark !== undefined) {
    window.history.replaceState(markedState(), "", window.location.href);
    return;
  }
  pushGuardEntry();
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
    ensureGuardEntry();
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
