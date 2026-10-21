"use client";

import type { ApiClient } from "./apiClient";
import {
  isTerminalStatus,
  parseEndResult,
  parseLiveView,
  ResponseShapeError,
  type Bubble,
  type EndResult,
  type LiveView,
} from "./types";

// 12 진행 · 17 활동 끝의 세션 조회 · 끝내기 요청 (U1 3.5 Step 39). 서버 호출과 응답 합치기만 한다.

/** GET /api/sessions/{id}?sinceSeq=n — 12 진행 · 17 상태. */
export function fetchLiveView(api: ApiClient, sessionId: string, sinceSeq: number, signal?: AbortSignal): Promise<LiveView> {
  const path = `/api/sessions/${encodeURIComponent(sessionId)}?sinceSeq=${sinceSeq}`;
  return api.request({ method: "GET", path, parse: (body) => {
    const view = parseLiveView(body);
    if (view.sessionId !== sessionId) throw new ResponseShapeError("sessionId");
    return view;
  }, csrf: false, signal });
}

/** POST /api/sessions/{id}/end — P1 끝내기(재시도는 같은 requestId). */
export function endSession(api: ApiClient, sessionId: string, requestId: string): Promise<EndResult> {
  const path = `/api/sessions/${encodeURIComponent(sessionId)}/end`;
  return api.request({ method: "POST", path, body: { requestId }, parse: parseEndResult, csrf: true });
}

/** 말풍선을 seq로 합친다(같은 seq는 새 값으로 덮어쓴다, 확정 전 조각). */
export function mergeBubbles(previous: readonly Bubble[], incoming: readonly Bubble[]): Bubble[] {
  if (incoming.length === 0) {
    return [...previous];
  }
  const bySeq = new Map<number, Bubble>();
  for (const bubble of previous) {
    bySeq.set(bubble.seq, bubble);
  }
  for (const bubble of incoming) {
    bySeq.set(bubble.seq, bubble);
  }
  return [...bySeq.values()].sort((left, right) => left.seq - right.seq);
}

/**
 * 연습 진행 값을 앞 응답 것으로 남길지. 진행 중에 잘못 온 값(invalid)이면 남긴다.
 * 마무리 · 종료 응답에는 진행 값이 비어 온다(realtime 실행기가 끝나면 비우고 core 사본 응답에는 없다).
 * 이때도 남겨야 12가 받은 목표 수를 17이 이어 쓴다(12는 마무리 · 종료를 받으면 바로 17로 가므로 12 표시는 그대로다).
 */
function keepsPreviousPractice(next: LiveView): boolean {
  if (next.activityType !== "PRACTICE") {
    return false;
  }
  if (next.practiceIssue === "invalid") {
    return true;
  }
  return next.practice === null && (next.status === "CLOSING" || isTerminalStatus(next.status));
}

/** 폴링 응답을 앞 응답과 합친다: 상태는 새 응답, 말풍선은 seq로 합침, nextSeq는 새 응답 값. */
export function mergeLiveView(previous: LiveView | undefined, next: LiveView): LiveView {
  if (previous === undefined || previous.sessionId !== next.sessionId) {
    return next;
  }
  if ((previous.activityType === "PRACTICE" || next.activityType === "PRACTICE") && next.statusVersion < previous.statusVersion) return previous;
  return { ...next, items: mergeBubbles(previous.items, next.items),
    practice: keepsPreviousPractice(next) ? previous.practice : next.practice };
}
