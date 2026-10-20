"use client";
import { useQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { discardReport, fetchReport, fetchReportPdf, reportKey, saveReport } from "./api";
import { useApiClient } from "../../common/api/queries";
import { shortDate, seoulDate } from "../../common/components/MonthCalendar";
import { LiveRegion } from "../../common/components/LiveRegion";
import { PARENT_ROUTES } from "../../common/navigation";
import { formatHhmm } from "@/shared/copy/timeText";
import { RESULT_TEMP_COPY as C, reportSummary, periodLine, activityCountLine, citationCountLine, sectionTitle } from "@/shared/copy/result";
import { useTiming } from "@/shared/timing/TimingProvider";
import { SAVED_REPORT_COPY } from "@/shared/copy/result";
import { useToast } from "@/shared/toast/ToastProvider";
export function ReportScreen({ reportId }: { reportId: string }) {
  const api = useApiClient(), router = useRouter(), timing = useTiming(), toast = useToast(); const [sheet, setSheet] = useState(false), [savedHere, setSavedHere] = useState(false), [busy, setBusy] = useState(false);
  const blob = useRef<Blob | null>(null), pending = useRef<Promise<Blob> | null>(null), urls = useRef<string[]>([]), action = useRef(false);
  const query = useQuery({ queryKey: reportKey(reportId), queryFn: ({ signal }) => fetchReport(api, reportId, signal), retry: false }), report = query.data;
  const saved = savedHere || report?.status === "SAVED";
  const filename = report ? `무중_상담용리포트_${report.child.displayName ?? "아이"}_${report.periodFrom.slice(5).replace("-", "")}-${report.periodTo.slice(5).replace("-", "")}.pdf` : "무중_상담용리포트.pdf";
  const obtainPdf = useCallback(async () => { if (blob.current) return blob.current; if (!pending.current) pending.current = fetchReportPdf(reportId, timing.parentRequestTimeoutMs).then(value => { blob.current = value; return value; }).finally(() => { pending.current = null; }); return pending.current; }, [reportId, timing.parentRequestTimeoutMs]);
  useEffect(() => { if (report && !["READY", "SAVED"].includes(report.status)) router.replace(PARENT_ROUTES.reportNew(report.periodFrom, report.periodTo)); }, [report, router]);
  // 새로고침 뒤에도 공유 동작 때 사용자 활성 상태를 유지하도록 저장한 PDF를 다시 준비한다.
  useEffect(() => { if (saved) void obtainPdf().catch(() => undefined); }, [saved, obtainPdf]);
  useEffect(() => { const allocated = urls.current; return () => allocated.forEach(url => URL.revokeObjectURL(url)); }, []);
  function urlFor(value: Blob) { const url = URL.createObjectURL(value); urls.current.push(url); return url; }
  async function download() {
    if (action.current) return;
    action.current = true; setBusy(true);
    try { const value = await obtainPdf(); const link = document.createElement("a"); link.href = urlFor(value); link.download = filename; document.body.appendChild(link); link.click(); link.remove(); }
    catch { toast.show({ message: C.saveFailed, kind: "error" }); }
    finally { action.current = false; setBusy(false); }
  }
  async function save() { if (action.current) return; action.current = true; setBusy(true); try { const value = await obtainPdf(); const link = document.createElement("a"); link.href = urlFor(value); link.download = filename; document.body.appendChild(link); link.click(); link.remove(); await saveReport(api, reportId); setSheet(false); setSavedHere(true); } catch { toast.show({ message: C.saveFailed, kind: "error" }); } finally { action.current = false; setBusy(false); } }
  async function leave(back: boolean) { if (!report) return; if (!saved) await discardReport(api, reportId).catch(() => undefined); router.push(!saved && back ? PARENT_ROUTES.reportNew(report.periodFrom, report.periodTo) : PARENT_ROUTES.records); }
  async function share() { try { const value = blob.current ?? await obtainPdf(); await navigator.share({ files: [new File([value], filename, { type: "application/pdf" })] }); } catch (error) { if (!(error instanceof DOMException && error.name === "AbortError")) toast.show({ message: C.saveFailed, kind: "error" }); } }
  async function open() { const tab = window.open("", "_blank"); if (tab) tab.opener = null; try { const value = blob.current ?? await obtainPdf(); if (tab) tab.location.href = urlFor(value); else window.open(urlFor(value), "_blank", "noopener,noreferrer"); } catch { tab?.close(); toast.show({ message: C.saveFailed, kind: "error" }); } }
  const canShare = typeof navigator !== "undefined" && typeof navigator.canShare === "function" && navigator.canShare({ files: [new File([], filename, { type: "application/pdf" })] });
  return <div className="mj-screen"><header className="mj-header" data-testid="report-head"><button type="button" aria-label={C.back} onClick={() => void leave(true)}>{C.backArrow}</button><h1>{C.report}</h1>{report ? <p>{reportSummary(shortDate(report.periodFrom), shortDate(report.periodTo), report.activityCount)}</p> : null}</header><main className="mj-screen__body">
    {query.isError ? <p role="alert">{C.reportReadFailed} <button onClick={() => void query.refetch()}>{C.retry}</button></p> : null}
    {report ? <><p className="mj-card" data-testid="report-notice">{report.notice}</p>{report.sections.map(section => <section className="mj-card" key={section.sectionNo} data-testid={`report-section-${section.sectionNo}`}><h2>{sectionTitle(section.sectionNo, section.title)}{section.missing ? <span data-testid={`report-section-${section.sectionNo}-missing`}>{C.missingSuffix}</span> : null}</h2><p className="mj-report-text">{section.text}</p>{section.citations.map((c, i) => <p key={i}>{c.quote !== null ? <>{`“${c.quote}”`} {c.at ? `(${shortDate(seoulDate(c.at))} ${formatHhmm(c.at)})` : ""}</> : null}{section.sectionNo === 5 ? <span data-testid="report-section-5-source">{c.source === "GUARDIAN_INPUT" ? "보호자 입력" : "아이 회상"}</span> : null}</p>)}</section>)}</> : null}
    {report && !saved ? <><button type="button" data-testid="report-later" onClick={() => void leave(false)}>{C.later}</button><button type="button" className="mj-button mj-button--primary" data-testid="report-save" onClick={() => setSheet(true)}>{C.savePdf}</button></> : null}
    {saved ? <LiveRegion testId="report-saved"><h2>{C.saved}</h2><p>{filename}</p>{canShare ? <button type="button" data-testid="report-share" onClick={() => void share()}>{C.share}</button> : null}<button type="button" data-testid="report-open" onClick={() => void open()}>{C.openFile}</button><button type="button" className="mj-button mj-button--primary" disabled={busy} data-testid="report-download-again" onClick={() => void download()}>{SAVED_REPORT_COPY.download}</button><p>{C.delivery}</p><button type="button" data-testid="report-to-records" onClick={() => router.push(PARENT_ROUTES.records)}>{C.toRecords}</button></LiveRegion> : null}
    {sheet && report ? <section className="mj-card" role="dialog" aria-modal="true" aria-label={C.saveQuestion} data-testid="report-save-sheet"><h2>{C.saveQuestion}</h2><p>{periodLine(shortDate(report.periodFrom), shortDate(report.periodTo))}</p><p>{activityCountLine(report.activityCount)}</p><p>{citationCountLine(report.citationCount)}</p><p>{C.childInfo}</p><button type="button" disabled={busy} data-testid="report-save-cancel" onClick={() => setSheet(false)}>{C.cancel}</button><button type="button" disabled={busy} data-testid="report-save-confirm" onClick={() => void save()}>{C.save}</button></section> : null}
  </main></div>;
}
