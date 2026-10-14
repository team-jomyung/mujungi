"use client";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { previewReport, createReport } from "../api/reports";
import { useApiClient } from "../api/queries";
import { useReportPolling } from "../hooks/useReportPolling";
import { MonthCalendar, addDays, seoulDate, shortDate, validDate } from "../components/MonthCalendar";
import { LiveRegion } from "../components/LiveRegion";
import { PARENT_ROUTES } from "../navigation";
import { RESULT_TEMP_COPY as C } from "@/shared/copy/result";
type Mode = "RANGE" | "GENERATING" | "FAILED";
export function ReportRange28() {
  const api = useApiClient(), router = useRouter(), params = useSearchParams(), today = seoulDate();
  const initialFrom = params.get("from"), initialTo = params.get("to");
  const [range, setRange] = useState(() => { const restored = validDate(initialFrom) && validDate(initialTo) && initialFrom <= initialTo && initialTo <= today; return { from: restored ? initialFrom : addDays(today, -13), to: restored ? initialTo : today }; });
  const [month, setMonth] = useState(today.slice(0, 7)), [quick, setQuick] = useState("recent2w"), [anchor, setAnchor] = useState<string | null>(null), [excluded, setExcluded] = useState<Set<string>>(new Set());
  const [mode, setMode] = useState<Mode>("RANGE"), [active, setActive] = useState<{ id: string; started: number } | null>(null);
  const key = useRef<string | null>(null), submitting = useRef(false), generation = useRef(0);
  const preview = useQuery({ queryKey: ["report-preview", range.from, range.to], queryFn: ({ signal }) => previewReport(api, range.from, range.to, signal), enabled: mode === "RANGE", retry: false, placeholderData: keepPreviousData });
  const selected = preview.data?.activities.filter(a => !excluded.has(a.sessionId)) ?? [];
  const polling = useReportPolling(mode === "GENERATING" ? active?.id ?? null : null, active?.started ?? null);
  const failed = mode === "FAILED" || (mode === "GENERATING" && (polling.expired || ["FAILED", "DISCARDED"].includes(polling.data?.status ?? "")));
  useEffect(() => { if (mode === "GENERATING" && polling.data?.status === "READY" && active) router.push(PARENT_ROUTES.report(active.id)); }, [mode, polling.data?.status, active, router]);
  function change(from: string, to: string) { setRange({ from, to }); setExcluded(new Set()); key.current = null; }
  function chooseQuick(value: string) { setQuick(value); setAnchor(null); const first = preview.data?.firstRecordDate ?? today; const from = value === "recent2w" ? addDays(today, -13) : value === "month" ? `${today.slice(0, 7)}-01` : first; change(from, today); setMonth(value === "custom" ? from.slice(0, 7) : today.slice(0, 7)); }
  function chooseDate(date: string) { setQuick("custom"); if (anchor === null) { setAnchor(date); change(date, date); } else { change(date < anchor ? date : anchor, date < anchor ? anchor : date); setAnchor(null); } }
  function cancel() { generation.current += 1; submitting.current = false; setActive(null); setMode("RANGE"); }
  async function create(fresh = false) { if (submitting.current || selected.length === 0) return; submitting.current = true; const token = ++generation.current; if (fresh || key.current === null) key.current = crypto.randomUUID(); setMode("GENERATING"); setActive(null); try { const result = await createReport(api, { ...range, sessionIds: selected.map(a => a.sessionId), idempotencyKey: key.current }); if (generation.current !== token) return; if (result.status === "READY" || result.status === "SAVED") router.push(PARENT_ROUTES.report(result.reportId)); else if (result.status === "FAILED" || result.status === "DISCARDED") setMode("FAILED"); else setActive({ id: result.reportId, started: Date.now() }); } catch { if (generation.current === token) setMode("FAILED"); } finally { if (generation.current === token) submitting.current = false; } }
  const back = () => { if (mode === "GENERATING") cancel(); else router.push(PARENT_ROUTES.records); };
  return <div className="mj-screen"><header className="mj-header" data-testid="report-range-head"><button type="button" aria-label={C.back} onClick={back}>{C.backArrow}</button><h1>{C.report}</h1></header><main className="mj-screen__body">
    {failed ? <LiveRegion testId="report-range-failed"><h2>{C.reportFailed}</h2><p>{C.reportFailedDetail}</p><button type="button" data-testid="report-range-reset" onClick={cancel}>{C.rangeAgain}</button><button type="button" data-testid="report-range-retry" onClick={() => void create(true)}>{C.retry}</button></LiveRegion> : mode === "GENERATING" ? <LiveRegion testId="report-range-generating"><h2>{C.generating}</h2><p>{C.generatingDetail}</p><button type="button" data-testid="report-range-cancel" onClick={cancel}>{C.cancel}</button></LiveRegion> : <>
      <div>{[["recent2w", "최근 2주"], ["month", "이번 달"], ["all", "전체"], ["custom", "직접"]].map(([value, label]) => <button type="button" key={value} data-testid={`report-range-quick-${value}`} aria-pressed={quick === value} onClick={() => chooseQuick(value)}>{label}</button>)}</div>
      <MonthCalendar month={month} today={today} from={range.from} to={range.to} prefix="report-range" onSelect={chooseDate} onMonth={setMonth} />
      <LiveRegion testId="report-range-summary">{anchor ? `시작 ${shortDate(anchor)} · 끝나는 날을 누르세요` : `${shortDate(range.from)} ~ ${shortDate(range.to)} · 활동 ${selected.length}개`}</LiveRegion>
      {preview.isError ? <p role="alert">{C.activitiesReadFailed} <button onClick={() => void preview.refetch()}>{C.retry}</button></p> : null}
      {preview.data?.activities.map(a => <button type="button" role="checkbox" aria-checked={!excluded.has(a.sessionId)} key={a.sessionId} className="mj-card mj-button--block" data-testid={`report-range-item-${a.sessionId}`} onClick={() => setExcluded(previous => { const next = new Set(previous); if (next.has(a.sessionId)) next.delete(a.sessionId); else next.add(a.sessionId); key.current = null; return next; })}>{shortDate(a.date)} {a.activityType === "PRACTICE" ? "연습" : "자유대화"}</button>)}
      {preview.data?.activities.length === 0 ? <div data-testid="report-range-empty"><p>{C.emptyRange}</p><p>{C.emptyRangeDetail}</p></div> : null}
      <button type="button" className="mj-button mj-button--primary mj-button--block" data-testid="report-range-create" disabled={selected.length === 0 || preview.isFetching} onClick={() => void create()}>{selected.length === 0 ? "리포트 만들기" : `활동 ${selected.length}개로 리포트 만들기`}</button>
    </>}
  </main></div>;
}
