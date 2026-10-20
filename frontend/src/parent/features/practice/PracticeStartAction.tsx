"use client";

import { useEffect, useRef, useState } from "react";
import { U5B_COPY } from "../../common/copy/practice";
import { PRACTICE_COPY } from "./copy";
import { PracticeStartCoordinator, ownedSelection, type StartOutcome, type StartPort } from "./practiceStart";

/** 18 시작 동작. 미확인 결과의 재시도는 화면 목록이 바뀌어도 처음 요청을 그대로 사용한다. */
export function PracticeStartAction({ selected, owned, port, newKey, onAccepted, onLockedChange, onRejected, blocked = false, onSubmitting }: {
  selected: readonly string[]; owned: readonly string[]; port: StartPort; newKey: () => string;
  onAccepted: (sessionId: string) => void;
  onLockedChange?: (locked: boolean) => void;
  onRejected?: (code: string) => void;
  onSubmitting?: () => void;
  blocked?: boolean;
}) {
  const [coordinator] = useState(() => new PracticeStartCoordinator(port, newKey));
  const [sending, setSending] = useState(false);
  const [outcome, setOutcome] = useState<StartOutcome | null>(null);
  const navigated = useRef(false);
  const mounted = useRef(true);
  const running = useRef(false);
  const [requestedCount, setRequestedCount] = useState(0);
  const unresolved = outcome?.kind === "unknown";
  const count = sending || unresolved ? requestedCount : ownedSelection(selected, owned).length;
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  async function submit() {
    if (running.current || outcome?.kind === "accepted" || (count === 0 && !unresolved)) return;
    running.current = true;
    if (!unresolved) setRequestedCount(count);
    onSubmitting?.();
    onLockedChange?.(true);
    setSending(true);
    try {
      const result = await coordinator.submit(selected, owned);
      if (!mounted.current) return;
      setOutcome(result);
      onLockedChange?.(result.kind !== "rejected");
      if (result.kind === "rejected") onRejected?.(result.code);
      if (result.kind === "accepted" && !navigated.current) { navigated.current = true; onAccepted(result.sessionId); }
    } finally { running.current = false; if (mounted.current) setSending(false); }
  }
  let message = "";
  if (sending) message = U5B_COPY.sending;
  else if (outcome?.kind === "unknown") message = U5B_COPY.unknown;
  else if (outcome?.kind === "rejected") message = U5B_COPY.rejected[outcome.code as keyof typeof U5B_COPY.rejected] ?? U5B_COPY.otherRejected;
  return <div className="mj-practice__start">
    <button type="button" className="mj-button mj-button--primary mj-button--block" data-testid="practice-start"
      disabled={sending || (!unresolved && (count === 0 || blocked)) || outcome?.kind === "accepted"}
      onClick={() => { void submit(); }}>{count ? PRACTICE_COPY.start(count) : PRACTICE_COPY.choose}</button>
    <p className="mj-practice__notice" role="status" aria-live="polite">{message}</p>
  </div>;
}
