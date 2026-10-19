import type { LiveGap } from "../api/activity";
import type { Bubble } from "../api/types";
import { gapLine, unconfirmedGapLine, ACTIVITY_PROPOSED_COPY } from "@/shared/copy/activity";
import { formatHhmm } from "@/shared/copy/timeText";

// TranscriptList가 말풍선과 끊김 · 미확인 구간을 시간 순으로 섞어 그릴 때 쓰는 값 계산 (U4a · AC3.2.10 · C.S-12.12.7 · mockups 9-15 · 9-25)
// 12(live)와 27(records)이 함께 쓰는 TranscriptList의 부품이라 공통에 둔다(공통 → 기능 import 없음).

/** 흐름 한 칸: 말풍선 또는 구간. */
export type TimelineEntry =
  | { readonly kind: "bubble"; readonly bubble: Bubble }
  | { readonly kind: "gap"; readonly gap: LiveGap; readonly key: string };

/**
 * 말풍선(seq 순) 사이에 구간을 시작 시각 순으로 끼운다: 구간은 그보다 늦게 시작한 첫 말풍선 앞에 놓이고, 시각을 모르는 말풍선은 앞
 * 말풍선 뒤에 그대로 둔다. 남은 구간은 끝에 붙는다(진행 중 끊김은 늘 맨 뒤다).
 */
export function buildTimeline(items: readonly Bubble[], gaps: readonly LiveGap[]): TimelineEntry[] {
  const pending = [...gaps].sort((left, right) => Date.parse(left.from) - Date.parse(right.from));
  const entries: TimelineEntry[] = [];
  for (const bubble of items) {
    const startedAt = Date.parse(bubble.startedAt);
    while (pending.length > 0 && !Number.isNaN(startedAt) && Date.parse(pending[0].from) < startedAt) {
      const gap = pending.shift() as LiveGap;
      entries.push({ kind: "gap", gap, key: `${gap.kind}-${gap.from}` });
    }
    entries.push({ kind: "bubble", bubble });
  }
  for (const gap of pending) {
    entries.push({ kind: "gap", gap, key: `${gap.kind}-${gap.from}` });
  }
  return entries;
}

/** 구간 글: 끝난 끊김 'hh:mm ~ hh:mm 잠깐 끊김', 끝난 미확인 'hh:mm ~ hh:mm 확인되지 않은 구간', 진행 중이면 '대화 연결을 다시 잇고 있어요'. */
export function gapText(gap: LiveGap): string {
  const from = formatHhmm(gap.from);
  const to = formatHhmm(gap.to);
  if (gap.to === null || from === null || to === null) {
    return ACTIVITY_PROPOSED_COPY.live.recovering;
  }
  return gap.kind === "DISCONNECT" ? gapLine(from, to) : unconfirmedGapLine(from, to);
}
