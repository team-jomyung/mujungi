"use client";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { ApiError } from "../../common/api/apiClient";
import { useApiClient } from "../../common/api/queries";
import { fetchRecord, fetchResult, resultKey, resultStatusKey, retryResult } from "../../common/api/results";
import { useResultStatusPolling } from "../done/useResultStatusPolling";
import { EndReasonCard } from "./EndReasonCard";
import { SceneChip } from "./SceneChip";
import { LiveRegion } from "../../common/components/LiveRegion";
import { TranscriptList } from "../../common/components/TranscriptList";
import { dateLabel, seoulDate } from "../../common/components/MonthCalendar";
import { selectGoal, deleteGoal } from "../practice/api";
import { PARENT_ROUTES, goToLogin, isUnauthenticated } from "../../common/navigation";
import { formatHhmm, minutesBetween } from "@/shared/copy/timeText";
import { RESULT_TEMP_COPY as C, recordTimeLine } from "@/shared/copy/result";
import { messageForErrorCode } from "@/shared/errors/codeToCopy";
import { useToast } from "@/shared/toast/ToastProvider";
export function RecordDetailScreen({ sessionId }: { sessionId: string }) {
  const router = useRouter(), api = useApiClient(), client = useQueryClient(), toast = useToast(); const [tab, setTab] = useState("transcript"), [busy, setBusy] = useState(false); const action = useRef(false);
  const status = useResultStatusPolling(sessionId), job = status.data?.jobStatus;
  const record = useQuery({ queryKey: ["record", sessionId], queryFn: ({ signal }) => fetchRecord(api, sessionId, signal), retry: false });
  const result = useQuery({ queryKey: resultKey(sessionId), queryFn: ({ signal }) => fetchResult(api, sessionId, signal), enabled: job === "DONE" || job === "FAILED" || job === "NONE", retry: false });
  // 기록이 먼저 404였어도 DONE 뒤 다시 읽어 머리와 전사를 복구한다.
  useEffect(() => { if (job === "DONE") void client.invalidateQueries({ queryKey: ["record", sessionId] }); }, [client, sessionId, job]);
  useEffect(() => { if (isUnauthenticated(status.error) || isUnauthenticated(record.error)) goToLogin(router, toast); }, [status.error, record.error, router, toast]);
  async function retry() { if (action.current) return; action.current = true; setBusy(true); try { await retryResult(api, sessionId); } catch (e) { if (!(e instanceof ApiError && e.status === 409)) { toast.show({ message: messageForErrorCode(e instanceof ApiError ? e.code : "UNKNOWN"), kind: "error" }); return; } } finally { action.current = false; setBusy(false); } client.setQueryData(resultStatusKey(sessionId), { ...status.data, jobStatus: "REQUESTED" }); void status.refetch(); }
  async function toggle(id: string) { if (action.current) return; const candidate = result.data?.candidates.find(c => c.recommendationId === id); if (!candidate) return; action.current = true; setBusy(true); try { if (candidate.selected && candidate.goalSelectionId) await deleteGoal(api, candidate.goalSelectionId); else await selectGoal(api, id); await result.refetch(); } catch (e) { toast.show({ message: messageForErrorCode(e instanceof ApiError ? e.code : "UNKNOWN"), kind: "error" }); } finally { action.current = false; setBusy(false); } }
  const detail = record.data, data = result.data, date = detail ? seoulDate(detail.session.startedAt ?? detail.session.requestedAt ?? detail.session.endedAt ?? new Date().toISOString()) : seoulDate();
  const missing = status.error instanceof ApiError && status.error.status === 404;
  const stateText = job === "FAILED" ? C.failed : job === "DONE" && data?.resultLevel === "INSUFFICIENT" ? C.insufficient : job === "NONE" || job === "DONE" ? null : C.cleaning;
  return <div className="mj-screen"><header className="mj-header" data-testid="record-head"><button type="button" aria-label={C.back} onClick={() => router.push(PARENT_ROUTES.recordsOn(date))}>{C.backArrow}</button>{detail ? <><h1>{dateLabel(date)} {detail.session.activityType === "PRACTICE" ? "연습" : "자유대화"}</h1><p>{recordTimeLine(formatHhmm(detail.session.startedAt) ?? "시각 미확인", formatHhmm(detail.session.endedAt) ?? "시각 미확인", minutesBetween(detail.session.startedAt, detail.session.endedAt) ?? 0)}{detail.session.activityType === "PRACTICE" ? ` · 목표 ${detail.goals.length}개` : ""}</p></> : null}</header><main className="mj-screen__body">
    {missing ? <p role="alert">{C.notFound}</p> : stateText ? <LiveRegion testId="record-state">{stateText}{job === "FAILED" ? <button type="button" data-testid="record-retry" disabled={busy} onClick={() => void retry()}>{C.retry}</button> : null}</LiveRegion> : null}
    {detail ? <EndReasonCard record={detail} /> : null}
    {job === "DONE" && detail?.session.activityType === "PRACTICE" ? data?.practice?.goals.map(goal => <section className="mj-card" key={goal.goalSelectionId} data-testid={`record-goal-${goal.order}`}><h2>{goal.displayName}</h2>{goal.scenes.map(scene => <SceneChip key={scene.sceneNo} scene={scene} testId={`record-goal-${goal.order}-scene-${scene.sceneNo}`} />)}{data.sentences.filter(s => s.goalSelectionId === goal.goalSelectionId).map((s, i) => <p key={i} data-testid={`record-goal-${goal.order}-sentence`}>{s.text}</p>)}<p data-testid={`record-goal-${goal.order}-help`}>{C.helpPrefix}{goal.help.count === 0 ? "도움 없음" : `도움 ${goal.help.count}회(${({ ACCEPTED: "동의함", DECLINED: "거절함", NO_RESPONSE: "답 없음", NONE: "답 없음" } as Record<string, string>)[goal.help.consent] ?? "답 없음"})`}</p></section>) : null}
    <div role="tablist" data-testid="record-tabs">{[["transcript", "대화 기록"], ["tips", `생활 팁 ${data?.tips.length ?? 0}`], ["goals", `연습 목표 ${data?.candidates.length ?? 0}`]].map(([key, label]) => <button key={key} role="tab" type="button" aria-selected={tab === key} data-testid={`record-tab-${key}`} onClick={() => setTab(key)}>{label}</button>)}</div>
    {tab === "transcript" ? <div data-testid="record-transcript">{detail ? <TranscriptList items={detail.utterances.map(u => u.uncertain ? { ...u, text: `${u.text} (말이 분명하지 않아요)` } : u)} gaps={detail.gaps} childName={null} /> : null}</div> : null}
    {tab === "tips" ? <div>{data?.tips.map(t => <section className="mj-card" key={t.supportItemId} data-testid={`record-tip-${t.supportItemId}`}><h2>{t.title ?? t.content.split(/[.!?]/)[0]}</h2><p>{t.content}</p><p>{C.tipConditionPrefix}{t.condition}</p><p>{C.limitationPrefix}{t.limitation}</p></section>)}{!data?.tips.length ? <p data-testid="record-tips-empty">{C.noTips}</p> : null}</div> : null}
    {tab === "goals" ? <div>{data?.candidates.map(c => { const at = detail?.utterances.find(u => u.seq === c.evidence[0]?.utteranceSeq)?.startedAt; return <section className="mj-card" key={c.recommendationId} data-testid={`record-candidate-${c.recommendationId}`}><h2>{c.displayName}</h2><button type="button" disabled={busy} data-testid={`record-candidate-${c.recommendationId}-select`} onClick={() => void toggle(c.recommendationId)}>{c.selected ? "담김 ✓" : "담기"}</button><p>{C.evidencePrefix}{c.reasonText}{at ? ` (${formatHhmm(at)})` : ""}</p></section>; })}{!data?.candidates.length ? <p data-testid="record-candidates-empty">{C.noCandidates}</p> : null}</div> : null}
  </main></div>;
}
