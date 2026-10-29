"use client";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useRouter, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { previewReport, createReport } from "./api";
import { useApiClient } from "../../common/api/queries";
import { useReportPolling } from "./useReportPolling";
import { MonthCalendar, addDays, seoulDate, shortDate, validDate } from "../../common/components/MonthCalendar";
import { LiveRegion } from "../../common/components/LiveRegion";
import { PARENT_ROUTES } from "../../common/navigation";
import { RESULT_TEMP_COPY as C } from "@/shared/copy/result";
import { ACTIVITY_SELECTION_COPY as S, reportSelectionCount, reportActivityPosition, reportActivityLabel, reportActivityPage } from "@/shared/copy/result";
import { ActivityTypeFilter, type ActivityTypeFilterValue } from "../../common/components/ActivityTypeFilter";
import { formatHhmm } from "@/shared/copy/timeText";
const PAGE_SIZE = 10;
type Mode = "RANGE" | "GENERATING" | "FAILED";
export function ReportRangeScreen() {
  const api = useApiClient(), router = useRouter(), params = useSearchParams(), today = seoulDate();
  const initialFrom = params.get("from"), initialTo = params.get("to");
  const [range, setRange] = useState(() => { const restored = validDate(initialFrom) && validDate(initialTo) && initialFrom <= initialTo && initialTo <= today; return { from: restored ? initialFrom : addDays(today, -13), to: restored ? initialTo : today }; });
  const [month, setMonth] = useState(today.slice(0, 7)), [quick, setQuick] = useState("recent2w"), [anchor, setAnchor] = useState<string | null>(null);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [activityType, setActivityType] = useState<ActivityTypeFilterValue>("ALL");
  const [page, setPage] = useState(0);
  const [mode, setMode] = useState<Mode>("RANGE"), [active, setActive] = useState<{ id: string; started: number } | null>(null);
  const key = useRef<string | null>(null), submitting = useRef(false), generation = useRef(0);
  const preview = useQuery({ queryKey: ["report-preview", range.from, range.to], queryFn: ({ signal }) => previewReport(api, range.from, range.to, signal), enabled: mode === "RANGE", retry: false, placeholderData: keepPreviousData });
  const selected = useMemo(() => preview.data?.activities.filter(a => selectedIds.has(a.sessionId)) ?? [], [preview.data, selectedIds]);
  const filtered = preview.data?.activities.filter(a => activityType === "ALL" || a.activityType === activityType) ?? [];
  const pageCount = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const currentPage = Math.min(page, pageCount - 1);
  const visible = filtered.slice(currentPage * PAGE_SIZE, (currentPage + 1) * PAGE_SIZE);
  const selectionUnavailable = preview.isFetching || preview.isError;
  const polling = useReportPolling(mode === "GENERATING" ? active?.id ?? null : null, active?.started ?? null);
  const failed = mode === "FAILED" || (mode === "GENERATING" && (polling.expired || ["FAILED", "DISCARDED"].includes(polling.data?.status ?? "")));
  useEffect(() => { if (mode === "GENERATING" && polling.data?.status === "READY" && active) router.push(PARENT_ROUTES.report(active.id)); }, [mode, polling.data?.status, active, router]);
  function change(from: string, to: string) { setRange({ from, to }); setSelectedIds(new Set()); setPage(0); key.current = null; }
  function toggle(sessionId: string) {
    if (selectionUnavailable) return;
    key.current = null;
    setSelectedIds(previous => {
      const next = new Set(preview.data?.activities.filter(a => previous.has(a.sessionId)).map(a => a.sessionId));
      if (next.has(sessionId)) next.delete(sessionId);
      else next.add(sessionId);
      return next;
    });
  }
  function chooseQuick(value: string) { setQuick(value); setAnchor(null); const first = preview.data?.firstRecordDate ?? today; const from = value === "recent2w" ? addDays(today, -13) : value === "month" ? `${today.slice(0, 7)}-01` : first; change(from, today); setMonth(value === "custom" ? from.slice(0, 7) : today.slice(0, 7)); }
  function chooseDate(date: string) { setQuick("custom"); if (anchor === null) { setAnchor(date); change(date, date); } else { change(date < anchor ? date : anchor, date < anchor ? anchor : date); setAnchor(null); } }
  function cancel() { generation.current += 1; submitting.current = false; setActive(null); setMode("RANGE"); }
  const create = useCallback(async (fresh = false) => {
    if (submitting.current || selected.length === 0 || selectionUnavailable) return;
    submitting.current = true;
    const token = ++generation.current;
    if (fresh || key.current === null) key.current = crypto.randomUUID();
    setMode("GENERATING"); setActive(null);
    try {
      const result = await createReport(api, { ...range, sessionIds: selected.map(a => a.sessionId), idempotencyKey: key.current });
      if (generation.current !== token) return;
      if (result.status === "READY" || result.status === "SAVED") router.push(PARENT_ROUTES.report(result.reportId));
      else if (result.status === "FAILED" || result.status === "DISCARDED") setMode("FAILED");
      else setActive({ id: result.reportId, started: Date.now() });
    } catch { if (generation.current === token) setMode("FAILED"); }
    finally { if (generation.current === token) submitting.current = false; }
  }, [api, range, router, selected, selectionUnavailable]);
  const back = () => { if (mode === "GENERATING") cancel(); else router.push(PARENT_ROUTES.records); };
  return <div className="mj-screen"><header className="mj-header" data-testid="report-range-head"><button type="button" aria-label={C.back} onClick={back}>{C.backArrow}</button><h1>{C.report}</h1></header><main className="mj-screen__body">
    {failed ? <LiveRegion testId="report-range-failed"><h2>{C.reportFailed}</h2><p>{C.reportFailedDetail}</p><button type="button" data-testid="report-range-reset" onClick={cancel}>{C.rangeAgain}</button><button type="button" data-testid="report-range-retry" onClick={() => void create(true)}>{C.retry}</button></LiveRegion> : mode === "GENERATING" ? <LiveRegion testId="report-range-generating"><h2>{C.generating}</h2><p>{C.generatingDetail}</p><button type="button" data-testid="report-range-cancel" onClick={cancel}>{C.cancel}</button></LiveRegion> : <>
      <div className="mj-segmented">{[["recent2w", "최근 2주"], ["month", "이번 달"], ["all", "전체"], ["custom", "직접"]].map(([value, label]) => <button type="button" key={value} data-testid={`report-range-quick-${value}`} aria-pressed={quick === value} onClick={() => chooseQuick(value)}>{label}</button>)}</div>
      <MonthCalendar month={month} today={today} from={range.from} to={range.to} prefix="report-range" onSelect={chooseDate} onMonth={setMonth} />
      <LiveRegion testId="report-range-summary">{anchor ? `시작 ${shortDate(anchor)} · 끝나는 날을 누르세요` : `${shortDate(range.from)} ~ ${shortDate(range.to)} · 활동 ${selected.length}개`}</LiveRegion>
      {preview.isError ? <p role="alert">{C.activitiesReadFailed} <button onClick={() => void preview.refetch()}>{C.retry}</button></p> : null}
      <ActivityTypeFilter id="report-activity-type" value={activityType} onChange={value => { setActivityType(value); setPage(0); }} />
      <section aria-label={S.selectionLabel} aria-busy={preview.isFetching}>
        <p>{S.instruction}</p>
        <div className="mj-report-selection-summary">
          <strong role="status" data-testid="report-selection-count">{reportSelectionCount(selected.length)}</strong>
          <button type="button" disabled={selected.length === 0 || selectionUnavailable} onClick={() => { setSelectedIds(new Set()); key.current = null; }}>{S.clear}</button>
        </div>
        {preview.isFetching ? <p role="status">{C.readingActivities}</p> : null}
        {filtered.length > 0 ? <p>{reportActivityPosition(filtered.length, currentPage * PAGE_SIZE + 1, Math.min((currentPage + 1) * PAGE_SIZE, filtered.length))}</p> : null}
        <div className="mj-report-activity-list">
          {visible.map(a => {
            const checked = selectedIds.has(a.sessionId);
            return <button type="button" role="checkbox" aria-checked={checked} key={a.sessionId}
              disabled={selectionUnavailable}
              className="mj-card mj-report-activity" data-testid={`report-range-item-${a.sessionId}`} onClick={() => toggle(a.sessionId)}>
              <span className="mj-report-activity__check" aria-hidden="true">{checked ? S.check : ""}</span>
              <span className="mj-report-activity__body">
                <span>{reportActivityLabel(shortDate(a.date), formatHhmm(a.startedAt), a.activityType === "PRACTICE" ? S.practice : S.freeTalk)}</span>
                {a.summary !== null ? <span className="mj-record-summary" data-testid={`report-range-item-summary-${a.sessionId}`}>{a.summary}</span> : null}
              </span>
            </button>;
          })}
        </div>
        {filtered.length === 0 && (preview.data?.activities.length ?? 0) > 0 ? <p role="status">{S.emptyFilter}</p> : null}
        {pageCount > 1 ? <nav className="mj-report-pagination" aria-label={S.pages}>
          <button type="button" disabled={currentPage === 0 || selectionUnavailable} onClick={() => setPage(currentPage - 1)}>{S.previous}</button>
          <span>{reportActivityPage(currentPage + 1, pageCount)}</span>
          <button type="button" disabled={currentPage + 1 >= pageCount || selectionUnavailable} onClick={() => setPage(currentPage + 1)}>{S.next}</button>
        </nav> : null}
      </section>
      {preview.data?.activities.length === 0 ? <div data-testid="report-range-empty"><p>{C.emptyRange}</p><p>{C.emptyRangeDetail}</p></div> : null}
      <button type="button" className="mj-button mj-button--primary mj-button--block" data-testid="report-range-create" disabled={selected.length === 0 || selectionUnavailable} onClick={() => void create()}>{selected.length === 0 ? "리포트 만들기" : `활동 ${selected.length}개로 리포트 만들기`}</button>
    </>}
  </main></div>;
}
