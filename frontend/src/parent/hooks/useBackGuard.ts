"use client";

import { useEffect, useRef } from "react";

// 기기 뒤로가기 가로채기 (U2 · AC1.2.5 · AC1.3.2 · interaction-spec 1절 '뒤로가기': 머리 「←」와 기기 뒤로가기는 같은 동작)
// 켜져 있는 동안 같은 주소의 기록 한 칸을 더 쌓아 두고, 뒤로가기(popstate)가 오면 그 칸을 다시 쌓은 뒤 onBack을 부른다.
// 02는 P5를 열고, 03 처음 등록은 아무 일도 하지 않는다(등록을 마쳐야 넘어감).

export function useBackGuard(enabled: boolean, onBack: () => void): void {
  const onBackRef = useRef(onBack);

  useEffect(() => {
    onBackRef.current = onBack;
  }, [onBack]);

  useEffect(() => {
    if (!enabled) {
      return undefined;
    }
    window.history.pushState(window.history.state, "", window.location.href);
    const handlePopState = () => {
      window.history.pushState(window.history.state, "", window.location.href);
      onBackRef.current();
    };
    window.addEventListener("popstate", handlePopState);
    return () => window.removeEventListener("popstate", handlePopState);
  }, [enabled]);
}
