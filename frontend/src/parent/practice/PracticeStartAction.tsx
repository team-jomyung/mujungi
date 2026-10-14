"use client";

import { useRef, useState } from "react";
import { PRACTICE_COPY } from "./copy";
import { PracticeStartCoordinator, ownedSelection, type StartOutcome, type StartPort } from "./practiceStart";
import { U5B_COPY } from "./u5bCopy";

/** 계약 대역으로 사용하는 독립 시작 동작. Practice18에는 연결하지 않는다. */
export function PracticeStartAction({ selected, owned, port, newKey, onAccepted }: {
  selected: readonly string[]; owned: readonly string[]; port: StartPort; newKey: () => string;
  onAccepted: (sessionId: string) => void;
}) {
  const [coordinator] = useState(() => new PracticeStartCoordinator(port, newKey));
  const [sending, setSending] = useState(false);
  const [outcome, setOutcome] = useState<StartOutcome | null>(null);
  const navigated = useRef(false);
  const count = ownedSelection(selected, owned).length;
  async function submit() {
    setSending(true);
    try {
      const result = await coordinator.submit(selected, owned);
      setOutcome(result);
      if (result.kind === "accepted" && !navigated.current) { navigated.current = true; onAccepted(result.sessionId); }
    } finally { setSending(false); }
  }
  let message = "";
  if (sending) message = U5B_COPY.sending;
  else if (outcome?.kind === "unknown") message = U5B_COPY.unknown;
  else if (outcome?.kind === "rejected") message = U5B_COPY.rejected[outcome.code as keyof typeof U5B_COPY.rejected] ?? U5B_COPY.otherRejected;
  return <div>
    <button type="button" style={{ minHeight: 44 }} disabled={sending || count === 0 || outcome?.kind === "accepted"}
      onClick={() => { void submit(); }}>{count ? PRACTICE_COPY.start(count) : PRACTICE_COPY.choose}</button>
    <p role="status" aria-live="polite">{message}</p>
  </div>;
}
