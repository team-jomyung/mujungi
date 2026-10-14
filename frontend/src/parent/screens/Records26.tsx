"use client";
import { useQuery } from "@tanstack/react-query";
import { useRouter, useSearchParams } from "next/navigation";
import { useState } from "react";
import { fetchRecordDays, fetchRecords } from "../api/results";
import { useApiClient } from "../api/queries";
import { BottomTabBar } from "../components/BottomTabBar";
import { MonthCalendar, dateLabel, seoulDate, validDate } from "../components/MonthCalendar";
import { PARENT_ROUTES, routeForTab } from "../navigation";
import { formatHhmm, minutesBetween } from "@/shared/copy/timeText";
import { RESULT_TEMP_COPY as C, durationLabel } from "@/shared/copy/result";
export function Records26() {
  const router = useRouter(), params = useSearchParams(), api = useApiClient(), today = seoulDate();
  const initial = params.get("date"); const [selected, setSelected] = useState(validDate(initial) && initial <= today ? initial : today); const [month, setMonth] = useState(selected.slice(0, 7));
  const days = useQuery({ queryKey: ["record-days", month], queryFn: ({ signal }) => fetchRecordDays(api, month, signal) });
  const items = useQuery({ queryKey: ["records", selected], queryFn: ({ signal }) => fetchRecords(api, selected, signal) });
  const select = (date: string) => { setSelected(date); router.replace(PARENT_ROUTES.recordsOn(date)); };
  return <div className="mj-screen"><header className="mj-header"><h1>{C.records}</h1><button type="button" data-testid="records-report" onClick={() => router.push(PARENT_ROUTES.reportNew())}>{C.reportDownload}</button></header><main className="mj-screen__body"><MonthCalendar month={month} selected={selected} today={today} counts={days.data} prefix="records" onSelect={select} onMonth={setMonth} /><h2 data-testid="records-day-title">{dateLabel(selected)} {new Intl.DateTimeFormat("ko-KR", { weekday: "long", timeZone: "UTC" }).format(new Date(`${selected}T00:00:00Z`))}</h2>
    {days.isError || items.isError ? <p role="alert">{C.recordReadFailed} <button onClick={() => { void days.refetch(); void items.refetch(); }}>{C.retry}</button></p> : null}
    {items.data?.length === 0 ? <p data-testid="records-empty-day">{C.emptyDay}</p> : null}{days.data?.length === 0 && items.data?.length === 0 ? <p data-testid="records-empty">{C.emptyRecords}</p> : null}
    {items.data?.map(item => { const kind = item.activityType === "PRACTICE" ? "연습" : "자유대화"; const time = formatHhmm(item.startedAt) ?? "시각 미확인"; if (!item.linkable || item.finalStatus === "DECLINED") return <p key={item.sessionId} data-testid={`records-item-declined-${item.sessionId}`} aria-disabled="true">{time}　{kind}{C.declinedSuffix}</p>; return <button type="button" className="mj-card mj-button--block" key={item.sessionId} data-testid={`records-item-${item.sessionId}`} onClick={() => router.push(PARENT_ROUTES.recordDetail(item.sessionId))}>{time}　{kind}{item.activityType === "PRACTICE" ? ` · 목표 ${item.goalCount}개` : ""}{durationLabel(minutesBetween(item.startedAt, item.endedAt) ?? 0)}{item.interrupted || item.finalStatus === "FAILED" ? " · 중간에 끊김" : ""}</button>; })}
  </main><BottomTabBar active="records" lockMessage={null} onLockedPress={() => {}} onNavigate={tab => router.push(routeForTab(tab))} /></div>;
}
