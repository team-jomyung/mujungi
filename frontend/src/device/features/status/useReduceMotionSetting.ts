"use client";

import { useCallback, useState } from "react";

// 기기 화면 「동작 줄이기」 스위치(docs/ui/device-subtitle.md). 기본은 꺼짐이고 켜면 3D 무중이가 중립 자세로 멈춘다.
// 기기 영역은 브라우저 저장소를 쓰지 않으므로(BR9.1 · BR1.5) 값은 이번 화면에서만 기억하고, 다시 열면 꺼짐으로 시작한다.

export function useReduceMotionSetting(): { readonly reduceMotion: boolean; readonly toggle: () => void } {
  const [reduceMotion, setReduceMotion] = useState(false);
  const toggle = useCallback(() => setReduceMotion((current) => !current), []);
  return { reduceMotion, toggle };
}
