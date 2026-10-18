import type { ReactNode } from "react";
import { TimingProvider } from "@/shared/timing/TimingProvider";
import type { Timing } from "@/shared/timing/timing";

// renderHook용 TimingProvider 감싸개 (U1 3.5 Step 41 · NFR6.2 줄인 시간 값 주입)
export function timingWrapper(timing: Timing) {
  return function TimingWrapper({ children }: { children: ReactNode }) {
    return <TimingProvider timing={timing}>{children}</TimingProvider>;
  };
}
