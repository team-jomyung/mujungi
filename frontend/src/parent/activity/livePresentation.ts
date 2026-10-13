import type { LiveGap } from "../api/activity";
import { isTerminalStatus, type Bubble, type LiveView } from "../api/types";
import { gapLine, unconfirmedGapLine, ACTIVITY_PROPOSED_COPY } from "@/shared/copy/activity";
import { formatHhmm } from "@/shared/copy/timeText";

// 12 진행 표시 판단 (U4a · AC3.2.8 · AC3.2.10 · AC3.4.1 · AC3.5.1 · AC3.6.9 · U4a-J12 · J16 · J19)
// 화면이 아니라 값만 정한다: 받은 상태에 따른 다음 동작, 공백 경고 분, 말풍선과 끊김 · 미확인 구간을 시간 순으로 섞은 흐름.

const MS_PER_MINUTE = 60_000;

/** 12가 받은 상태로 할 일. */
export type LiveOutcome =
  | { readonly kind: "stay" }
  | { readonly kind: "done" }
  | { readonly kind: "unexpectedEnd" }
  | { readonly kind: "noResponse" }
  | { readonly kind: "startFailed"; readonly failureCode: string | null };

/** 진행 중에 실패한 코드(서버 재시작, A7) — 17 '기기 응답 없이 끝냈어요'(AC3.6.9). 그 밖의 FAILED는 시작 실패라 08이다. */
export const RUNNING_FAILURE_CODES: readonly string[] = ["SERVER_RESTART"];

/**
 * FAILED가 시작 실패(08로 돌아감)인지. 코드가 없으면(Core 로컬 FAILED) 시작 실패다(U1 J120과 같다). 서버 재시작이라도 시작 전
 * (startedAt 없음 — PREPARING · DEVICE_ACK에서 재시작)이면 시작 실패다(AC3.6.9는 '진행 중' 재시작만 17, AC3.1.9 · U4a 리뷰 R3).
 */
export function isStartFailure(view: Pick<LiveView, "status" | "failureCode" | "startedAt">): boolean {
  if (view.status !== "FAILED") {
    return false;
  }
  const runningFailure = view.failureCode !== null && RUNNING_FAILURE_CODES.includes(view.failureCode);
  return !runningFailure || view.startedAt === null;
}

/**
 * 상태 → 할 일: 진행 중이면 머문다, CLOSING · 종료는 17, 연결 끊김 종료는 P2 → 17, 무응답(DECLINED)은 P3 → 04, 시작 실패 FAILED는 08 사유
 * (진행 중 서버 재시작 FAILED는 17 '기기 응답 없이 끝냈어요', AC3.6.9 · U4a-J16).
 */
export function liveOutcome(view: Pick<LiveView, "status" | "endReason" | "failureCode" | "startedAt">): LiveOutcome {
  const { status } = view;
  if (status === "FAILED") {
    return isStartFailure(view) ? { kind: "startFailed", failureCode: view.failureCode } : { kind: "done" };
  }
  if (status === "DECLINED") {
    return { kind: "noResponse" };
  }
  if (status === "ABORTED" && view.endReason === "DISCONNECT") {
    return { kind: "unexpectedEnd" };
  }
  if (status === "CLOSING" || isTerminalStatus(status)) {
    return { kind: "done" };
  }
  return { kind: "stay" };
}

/** 공백 경고의 'n분째'(경고가 없거나 시각을 모르면 null). 4분 경계에서 4다. */
export function silentMinutes(view: Pick<LiveView, "silenceWarning" | "serverTime">): number | null {
  const warning = view.silenceWarning;
  if (warning === null || view.serverTime === null) {
    return null;
  }
  const since = Date.parse(warning.silentSince);
  const now = Date.parse(view.serverTime);
  if (Number.isNaN(since) || Number.isNaN(now)) {
    return null;
  }
  return Math.max(1, Math.floor((now - since) / MS_PER_MINUTE));
}

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

/**
 * 따로 복구 중 줄을 보일지: 기술적 일시 정지는 있는데 흐름에 진행 중 구간(점선 + 같은 글)이 아직 없을 때만(AC3.2.10 (a) · AC6.6.1 — 같은 글을
 * 두 번 보이지 않는다).
 */
export function showsPauseLine(view: Pick<LiveView, "technicalPause" | "gaps">): boolean {
  return view.technicalPause !== null && !view.gaps.some((gap) => gap.to === null);
}
