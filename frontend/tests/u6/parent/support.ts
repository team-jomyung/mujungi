import { vi } from "vitest";
import { SESSION_ID, liveViewBody } from "../../u1/support/fixtures";
export { SESSION_ID, jsonResponse, errorResponse, deferred } from "../../u1/support/fixtures";
export { renderParent } from "../../u1/support/parentHarness";
export { router, resetRouter } from "../../u1/support/nextNavigation";
export const navigation = { search: new URLSearchParams() };
export const REPORT_ID = "00000000-0000-4000-8000-000000000090";
export const GOAL_ID = "00000000-0000-4000-8000-000000000091";
export const CANDIDATE_ID = "00000000-0000-4000-8000-000000000092";
export function status(jobStatus = "DONE") { return { jobStatus, sessionStatus: "ENDED", confirmed: true }; }
export function result(extra: Record<string, unknown> = {}) { return { jobStatus: "DONE", resultLevel: "FULL", sentences: [], practice: null, tips: [], candidates: [], ...extra }; }
export function record(extra: Record<string, unknown> = {}) { return { session: liveViewBody("ENDED", { confirmed: true, endReason: "CHILD_STOP", endedAt: "2026-10-15T01:03:00Z" }), goals: [], utterances: [{ seq: 1, speaker: "CHILD", text: "같이 블록 쌓자", final: true, uncertain: false, startedAt: "2026-10-15T01:01:00Z", endedAt: null }], gaps: [], recordScope: { from: "2026-10-15T01:00:00Z", to: "2026-10-15T01:03:00Z" }, endConfirmed: true, ...extra }; }
export function preview() { return { firstRecordDate: "2026-09-01", activities: [{ sessionId: SESSION_ID, date: "2026-10-15", activityType: "FREE_TALK", startedAt: "2026-10-15T01:00:00Z" }] }; }
export function report(reportStatus = "READY") { return { status: reportStatus, level: "PARTIAL", periodFrom: "2026-10-01", periodTo: "2026-10-15", activityCount: 3, citationCount: 2, child: { displayName: "하늘", age: 7 }, notice: "이 리포트는 진단 결과가 아니에요.", sections: Array.from({ length: 10 }, (_, i) => ({ sectionNo: i + 1, title: `항목 ${i + 1}`, text: i === 8 ? "자료 부족" : "함께 이야기했어요.", missing: i === 8, citations: i === 4 ? [{ sessionId: SESSION_ID, source: "GUARDIAN_INPUT" }, { sessionId: SESSION_ID, utteranceSeq: 1, quote: "같이 블록 쌓자", at: "2026-10-15T01:01:00Z", source: "CHILD_RECALL" }] : [] })) }; }
export function clock() { navigation.search = new URLSearchParams(); vi.setSystemTime(new Date("2026-10-15T01:30:00Z")); }
