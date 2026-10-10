"use client";

import { createContext, useContext, type ReactNode } from "react";
import type { Timing } from "./timing";

// 시간 설정 문맥 (U1 3.5 Step 37 · NFR6.1 · PD-14 · interaction-spec 1절 '시간값'):
// 서버 컴포넌트가 읽은 Timing을 클라이언트 트리에 건넨다. 시험은 같은 Provider에 줄인 값을 주입한다(NFR6.2).

const TimingContext = createContext<Timing | null>(null);

export function TimingProvider({ timing, children }: Readonly<{ timing: Timing; children: ReactNode }>) {
  return <TimingContext.Provider value={timing}>{children}</TimingContext.Provider>;
}

/** 현재 Timing. Provider가 없으면 숫자를 지어내지 않고 멈춘다(fail fast). */
export function useTiming(): Timing {
  const timing = useContext(TimingContext);
  if (timing === null) {
    throw new Error("TimingProvider가 없다: 루트 레이아웃이 시간 설정을 싣지 않았다(NFR6.1).");
  }
  return timing;
}
