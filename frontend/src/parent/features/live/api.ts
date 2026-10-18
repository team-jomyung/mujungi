"use client";

import type { ApiClient } from "../../common/api/apiClient";
import {
  parseEndResult,
  parseLiveView,
  ResponseShapeError,
  type Bubble,
  type EndResult,
  type LiveView,
} from "../../common/api/types";

// 12 진행 · 17 활동 끝의 세션 조회 · 끝내기 요청 (U1 3.5 Step 39 · BR6.9 · BR6.15 · RD-21). 서버 호출과 응답 합치기만 한다(BR2.3).

/** GET /api/sessions/{id}?sinceSeq=n — 12 진행 · 17 상태(BR6.15). */
export function fetchLiveView(api: ApiClient, sessionId: string, sinceSeq: number, signal?: AbortSignal): Promise<LiveView> {
  const path = `/api/sessions/${encodeURIComponent(sessionId)}?sinceSeq=${sinceSeq}`;
  return api.request({ method: "GET", path, parse: (body) => {
    const view = parseLiveView(body);
    if (view.sessionId !== sessionId) throw new ResponseShapeError("sessionId");
    return view;
  }, csrf: false, signal });
}

/** POST /api/sessions/{id}/end — P1 끝내기(재시도는 같은 requestId, BR6.9 · RD-21). */
export function endSession(api: ApiClient, sessionId: string, requestId: string): Promise<EndResult> {
  const path = `/api/sessions/${encodeURIComponent(sessionId)}/end`;
  return api.request({ method: "POST", path, body: { requestId }, parse: parseEndResult, csrf: true });
}

/** 말풍선을 seq로 합친다(같은 seq는 새 값으로 덮어쓴다, 확정 전 조각 J23 · J120). */
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

/** 폴링 응답을 앞 응답과 합친다: 상태는 새 응답, 말풍선은 seq로 합침, nextSeq는 새 응답 값. */
export function mergeLiveView(previous: LiveView | undefined, next: LiveView): LiveView {
  if (previous === undefined || previous.sessionId !== next.sessionId) {
    return next;
  }
  if ((previous.activityType === "PRACTICE" || next.activityType === "PRACTICE") && next.statusVersion < previous.statusVersion) return previous;
  return { ...next, items: mergeBubbles(previous.items, next.items),
    practice: next.activityType === "PRACTICE" && next.practiceIssue === "invalid" ? previous.practice : next.practice };
}
