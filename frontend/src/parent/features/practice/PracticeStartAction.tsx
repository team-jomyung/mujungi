"use client";

import { useEffect, useRef, useState } from "react";
import { U5B_COPY } from "../../common/copy/practice";
import { PRACTICE_COPY } from "./copy";
import { MAX_PRACTICE_GOALS, ownedSelection, practiceStartStore, type PracticeStartStore, type StartOutcome, type StartPort } from "./practiceStart";

/**
 * 18 시작 동작. 미확인 결과의 재시도는 화면 목록이 바뀌어도 처음 요청을 그대로 사용한다. 시작 요청은 화면 밖 저장소에 두어, 준비 중에 다른
 * 탭으로 갔다 돌아와도 같은 요청의 준비 중 상태를 이어 보이고 그 결과를 받는다(새 목표 선택 · 시작을 막는다).
 */
export function PracticeStartAction({ selected, owned, port, newKey, onAccepted, onLockedChange, onRejected, blocked = false, onSubmitting, store = practiceStartStore }: {
  selected: readonly string[]; owned: readonly string[]; port: StartPort; newKey: () => string;
  onAccepted: (sessionId: string) => void;
  onLockedChange?: (locked: boolean) => void;
  onRejected?: (code: string) => void;
  onSubmitting?: () => void;
  blocked?: boolean;
  store?: PracticeStartStore;
}) {
  const [coordinator] = useState(() => store.acquire(port, newKey));
  const [sending, setSending] = useState(() => coordinator.pending !== null);
  const [outcome, setOutcome] = useState<StartOutcome | null>(() => coordinator.pending === null ? coordinator.lastOutcome : null);
  const navigated = useRef(false);
  const mounted = useRef(true);
  const running = useRef(false);
  const [requestedCount, setRequestedCount] = useState(() => coordinator.requestedCount);
  const unresolved = outcome?.kind === "unknown";
  const count = sending || unresolved ? requestedCount : ownedSelection(selected, owned).length;
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);

  function receive(result: StartOutcome) {
    // 떠나 있으면 결과를 남겨 두고 돌아온 화면이 받는다
    if (!mounted.current) return;
    store.settled(coordinator, result);
    setOutcome(result);
    onLockedChange?.(result.kind !== "rejected");
    if (result.kind === "rejected") onRejected?.(result.code);
    if (result.kind === "accepted" && !navigated.current) { navigated.current = true; onAccepted(result.sessionId); }
  }

  // 화면에 돌아왔을 때 준비 중인 요청이 있으면 잠근 채 그 결과를 기다리고, 떠나 있는 동안 끝난 결과는 그대로 받는다
  useEffect(() => {
    const pending = coordinator.pending;
    if (pending !== null) {
      running.current = true;
      onLockedChange?.(true);
      void pending.then(receive).finally(() => { running.current = false; if (mounted.current) setSending(false); });
    } else if (coordinator.lastOutcome?.kind === "rejected") {
      // 떠나 있는 동안 거절됐다: 처음 그릴 때 이미 거절 문구를 보였으니 받은 것으로 치고 다음 시작은 새 요청으로 한다
      store.settled(coordinator, coordinator.lastOutcome);
      onRejected?.(coordinator.lastOutcome.code);
    }
    // 처음 그릴 때 한 번만 이어 받는다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function submit() {
    if (running.current || outcome?.kind === "accepted" || (!unresolved && (count === 0 || count > MAX_PRACTICE_GOALS || blocked))) return;
    running.current = true;
    if (!unresolved) setRequestedCount(count);
    onSubmitting?.();
    onLockedChange?.(true);
    setSending(true);
    try {
      receive(await coordinator.submit(selected, owned));
    } finally { running.current = false; if (mounted.current) setSending(false); }
  }
  let message = "";
  if (sending) message = U5B_COPY.sending;
  else if (outcome?.kind === "unknown") message = U5B_COPY.unknown;
  else if (outcome?.kind === "rejected") message = U5B_COPY.rejected[outcome.code as keyof typeof U5B_COPY.rejected] ?? U5B_COPY.otherRejected;
  return <div className="mj-practice__start">
    <button type="button" className={`mj-button mj-button--primary mj-button--block${sending ? " mj-practice__start-busy" : ""}`}
      data-testid="practice-start" aria-busy={sending} aria-label={sending ? PRACTICE_COPY.start : undefined}
      disabled={sending || (!unresolved && (count === 0 || count > MAX_PRACTICE_GOALS || blocked)) || outcome?.kind === "accepted"}
      onClick={() => { void submit(); }}>
      {/* 상황 준비는 1분까지 걸린다(FT-1030-a): 기다리는 동안 문구 없이 회전 표시만 두고, 이름은 aria-label로 남긴다 */}
      {sending ? <span className="mj-spinner" data-testid="practice-start-spinner" aria-hidden="true" />
        : count ? PRACTICE_COPY.start : PRACTICE_COPY.choose}
    </button>
    <p className="mj-practice__notice" role="status" aria-live="polite">{message}</p>
  </div>;
}
