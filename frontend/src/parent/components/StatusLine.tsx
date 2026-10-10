import type { ReactNode } from "react";

// 상태 한 줄 (U1 3.5 Step 39 · mockups 9-16 · interaction-spec 3.10 · 접근성 P1)
// 12의 '기기 상태를 확인할 수 없어요 · 마지막 확인 hh:mm' 같은 줄. role=status라 보조기기가 조용히 읽는다.
// 아이콘 + 글로 보이고 색만으로 뜻을 전하지 않는다.

export type StatusTone = "info" | "warning" | "error";

const TONE_ICON: Readonly<Record<StatusTone, string>> = {
  info: "ℹ",
  warning: "!",
  error: "✕",
};

export interface StatusLineProps {
  readonly tone: StatusTone;
  readonly testId: string;
  readonly children: ReactNode;
}

export function StatusLine({ tone, testId, children }: StatusLineProps) {
  return (
    <p className={`mj-status-line mj-status-line--${tone}`} role="status" data-testid={testId}>
      <span className="mj-status-line__icon" aria-hidden="true">
        {TONE_ICON[tone]}
      </span>
      <span>{children}</span>
    </p>
  );
}
