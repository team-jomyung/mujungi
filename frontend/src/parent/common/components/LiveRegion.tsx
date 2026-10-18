import type { ReactNode } from "react";
/** BR11.11: 상태 변화는 색과 함께 글자로 알린다. */
export function LiveRegion({ children, testId }: { children: ReactNode; testId?: string }) { return <div role="status" aria-live="polite" aria-atomic="true" data-testid={testId}>{children}</div>; }
