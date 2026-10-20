"use client";
import { useQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useApiClient } from "../api/queries";
import { PARENT_ROUTES } from "../navigation";
import { fetchSavedReports } from "../api/reports";

import { SAVED_REPORT_COPY as C, savedReportPeriod, savedReportSummary, savedReportPage } from "@/shared/copy/result";

export function SavedReports() {
  const api = useApiClient(), router = useRouter(), [page, setPage] = useState(0);
  const query = useQuery({ queryKey: ["saved-reports", page], queryFn: ({ signal }) => fetchSavedReports(api, page, signal) });
  return <section aria-label={C.title} data-testid="saved-reports">
    <h2>{C.title}</h2>
    {query.isPending ? <p role="status">{C.loading}</p> : null}
    {query.isError ? <p role="alert">{C.failed} <button type="button" onClick={() => void query.refetch()}>{C.retry}</button></p> : null}
    {query.data?.items.length === 0 ? <p>{page === 0 ? C.empty : C.emptyPage}</p> : null}
    {query.data?.items.map(item => <article className="mj-card" key={item.reportId}>
      <h3>{savedReportPeriod(item.periodFrom, item.periodTo)}</h3>
      <p>{savedReportSummary(item.activityCount, new Intl.DateTimeFormat("ko-KR", { timeZone: "Asia/Seoul", year: "numeric", month: "numeric", day: "numeric" }).format(new Date(item.savedAt)))}</p>
      <button type="button" className="mj-button" onClick={() => router.push(PARENT_ROUTES.report(item.reportId))}>{C.open}</button>
    </article>)}
    {page > 0 || query.data?.hasMore ? <nav aria-label={C.pages}><button type="button" disabled={page === 0 || query.isFetching} onClick={() => setPage(value => value - 1)}>{C.previous}</button><span> {savedReportPage(page + 1)} </span><button type="button" disabled={!query.data?.hasMore || query.isFetching} onClick={() => setPage(value => value + 1)}>{C.next}</button></nav> : null}
  </section>;
}
