import type { ApiClient } from "./apiClient";
import { parseSessionSummary, parseLiveExtras, type SessionSummary, type LiveGap } from "./activity";
import { ResponseShapeError, type Bubble } from "./types";

/** C5 공개 DTO. 내부 판정·배경·매칭 칸을 화면에 전달하지 않는다. */
export type JobStatus = "NONE" | "REQUESTED" | "RUNNING" | "DONE" | "FAILED";
export interface ResultStatus { jobStatus: JobStatus; sessionStatus: string; confirmed: boolean }
export interface Scene { sceneNo: number; exposureType: string; sceneChip: string; chipText: string; notDoneReason?: string | null; timing?: "IMMEDIATE" | "AFTER_REASK" | "AFTER_HELP" | null }
export interface ResultGoal { goalSelectionId: string; displayName: string; order: number; scenes: Scene[]; help: { count: number; consent: string } }
export interface Candidate { recommendationId: string; displayName: string; reasonText: string; evidence: { utteranceSeq: number; kind: string }[]; selected: boolean; goalSelectionId: string | null }
export interface SessionResult { jobStatus: JobStatus; resultLevel: string | null; sentences: { text: string; goalSelectionId: string | null }[]; practice: { goals: ResultGoal[] } | null; tips: { supportItemId: string; title: string | null; condition: string; content: string; limitation: string }[]; candidates: Candidate[] }
export interface RecordItem { sessionId: string; activityType: string; startedAt: string | null; endedAt: string | null; finalStatus: string; linkable: boolean; goalCount: number; interrupted: boolean }
export interface RecordDetail { session: SessionSummary; goals: { displayName: string; order: number }[]; utterances: (Bubble & { uncertain: boolean })[]; gaps: LiveGap[]; recordScope: { from: string; to: string } | null; endConfirmed: boolean }
export function object(value: unknown): Record<string, unknown> { if (typeof value !== "object" || value === null || Array.isArray(value)) throw new ResponseShapeError("result"); return value as Record<string, unknown>; }
export function rows(value: unknown): Record<string, unknown>[] { if (!Array.isArray(value)) throw new ResponseShapeError("items"); return value.map(object); }
export function text(value: unknown): string { if (typeof value !== "string") throw new ResponseShapeError("text"); return value; }
export function nullableText(value: unknown): string | null { return value == null ? null : text(value); }
export function count(value: unknown): number { if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) throw new ResponseShapeError("count"); return value; }
function job(value: unknown): JobStatus { if (!["NONE", "REQUESTED", "RUNNING", "DONE", "FAILED"].includes(String(value))) throw new ResponseShapeError("jobStatus"); return value as JobStatus; }
function timing(value: unknown): Scene["timing"] { if (value === null) return null; if (value === "IMMEDIATE" || value === "AFTER_REASK" || value === "AFTER_HELP") return value; throw new ResponseShapeError("timing"); }
export function parseResultStatus(value: unknown): ResultStatus { const r = object(value); return { jobStatus: job(r.jobStatus), sessionStatus: text(r.sessionStatus), confirmed: r.confirmed === true }; }
export function parseResult(value: unknown): SessionResult {
  const r = object(value);
  return { jobStatus: job(r.jobStatus), resultLevel: nullableText(r.resultLevel), sentences: rows(r.sentences).map(s => ({ text: text(s.text), goalSelectionId: nullableText(s.goalSelectionId) })),
    practice: r.practice == null ? null : { goals: rows(object(r.practice).goals).map(g => ({ goalSelectionId: text(g.goalSelectionId), displayName: text(g.displayName), order: count(g.order), scenes: rows(g.scenes).map(s => ({ sceneNo: count(s.sceneNo), exposureType: text(s.exposureType), sceneChip: text(s.sceneChip), chipText: text(s.chipText), notDoneReason: nullableText(s.notDoneReason), ...(s.timing === undefined ? {} : { timing: timing(s.timing) }) })), help: { count: count(object(g.help).count), consent: text(object(g.help).consent) } })) },
    tips: rows(r.tips).map(t => ({ supportItemId: text(t.supportItemId), title: nullableText(t.title), condition: text(t.condition), content: text(t.content), limitation: text(t.limitation) })),
    candidates: rows(r.candidates).map(c => ({ recommendationId: text(c.recommendationId), displayName: text(c.displayName), reasonText: text(c.displayEvidence ?? c.reasonText), selected: c.selected === true, goalSelectionId: nullableText(c.goalSelectionId), evidence: rows(c.evidence).map(e => ({ utteranceSeq: count(e.utteranceSeq), kind: text(e.kind) })) })) };
}
const resultPath = (id: string) => `/api/sessions/${encodeURIComponent(id)}/result`;
export const resultStatusKey = (id: string) => ["result-status", id] as const;
export const resultKey = (id: string) => ["result", id] as const;
export function fetchResultStatus(api: ApiClient, id: string, signal?: AbortSignal) { return api.request({ method: "GET", path: `${resultPath(id)}/status`, parse: parseResultStatus, csrf: false, signal }); }
export function fetchResult(api: ApiClient, id: string, signal?: AbortSignal) { return api.request({ method: "GET", path: resultPath(id), parse: parseResult, csrf: false, signal }); }
export function retryResult(api: ApiClient, id: string) { return api.request({ method: "POST", path: `${resultPath(id)}/retry`, parse: value => job(object(value).jobStatus), csrf: true }); }
export function fetchRecordDays(api: ApiClient, month: string, signal?: AbortSignal) { return api.request({ method: "GET", path: `/api/records?month=${encodeURIComponent(month)}`, parse: value => rows(object(value).days).map(d => ({ date: text(d.date), count: count(d.count) })), csrf: false, signal }); }
export function fetchRecords(api: ApiClient, date: string, signal?: AbortSignal): Promise<RecordItem[]> { return api.request({ method: "GET", path: `/api/records?date=${encodeURIComponent(date)}`, parse: value => rows(object(value).items).map(r => ({ sessionId: text(r.sessionId), activityType: text(r.activityType), startedAt: nullableText(r.startedAt), endedAt: nullableText(r.endedAt), finalStatus: text(r.finalStatus), linkable: r.linkable === true, goalCount: count(r.goalCount), interrupted: r.interrupted === true })), csrf: false, signal }); }
export function fetchRecord(api: ApiClient, id: string, signal?: AbortSignal): Promise<RecordDetail> { return api.request({ method: "GET", path: `/api/records/${encodeURIComponent(id)}`, csrf: false, signal, parse: value => { const r = object(value); const session = parseSessionSummary(r.session); if (session === null) throw new ResponseShapeError("session"); return { session, goals: rows(r.goals).map(g => ({ displayName: text(g.displayName), order: count(g.order) })), utterances: rows(r.utterances).filter(u => u.speaker === "AI" || u.speaker === "CHILD").map(u => ({ seq: count(u.seq), speaker: u.speaker as "AI" | "CHILD", text: text(u.text), final: true, uncertain: u.uncertain === true, startedAt: text(u.startedAt), endedAt: nullableText(u.endedAt) })), gaps: parseLiveExtras(r).gaps as LiveGap[], recordScope: r.recordScope == null ? null : { from: text(object(r.recordScope).from), to: text(object(r.recordScope).to) }, endConfirmed: r.endConfirmed === true }; } }); }
