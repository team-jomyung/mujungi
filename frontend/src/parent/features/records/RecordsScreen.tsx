"use client";
import { useQuery } from "@tanstack/react-query";
import { useRouter, useSearchParams } from "next/navigation";
import { useState } from "react";
import { fetchRecordDays, fetchRecords } from "../../common/api/results";
import { useApiClient } from "../../common/api/queries";
import { BottomTabBar } from "../../common/components/BottomTabBar";
import { MonthCalendar, dateLabel, seoulDate, validDate } from "../../common/components/MonthCalendar";
import { PARENT_ROUTES } from "../../common/navigation";
import { formatHhmm, minutesBetween } from "@/shared/copy/timeText";
import { RESULT_TEMP_COPY as C, durationLabel } from "@/shared/copy/result";
import { SavedReports } from "../../common/components/SavedReports";
import { ActivityTypeFilter, type ActivityTypeFilterValue } from "../../common/components/ActivityTypeFilter";
import { ACTIVITY_SELECTION_COPY, UNSTARTED_RECORD_COPY } from "@/shared/copy/result";
import { useActivityEntry } from "../../common/activity/useActivityEntry";
export function RecordsScreen() {
  const router = useRouter(), params = useSearchParams(), api = useApiClient(), today = seoulDate();
  const entry = useActivityEntry();
  const initial = params.get("date"); const [selected, setSelected] = useState(validDate(initial) && initial <= today ? initial : today); const [month, setMonth] = useState(selected.slice(0, 7));
  const days = useQuery({ queryKey: ["record-days", month], queryFn: ({ signal }) => fetchRecordDays(api, month, signal) });
  const items = useQuery({ queryKey: ["records", selected], queryFn: ({ signal }) => fetchRecords(api, selected, signal) });
  const [activityType, setActivityType] = useState<ActivityTypeFilterValue>("ALL");
  const visibleItems = items.data?.filter(item => activityType === "ALL" || item.activityType === activityType);
  const select = (date: string) => { setSelected(date); router.replace(PARENT_ROUTES.recordsOn(date)); };
  return <div className="mj-screen"><header className="mj-header mj-header--records"><h1>{C.records}</h1><button type="button" data-testid="records-report" onClick={() => router.push(PARENT_ROUTES.reportNew())}>{C.reportCreate}</button></header><main className="mj-screen__body"><MonthCalendar month={month} selected={selected} today={today} counts={days.data} prefix="records" onSelect={select} onMonth={setMonth} /><h2 data-testid="records-day-title">{dateLabel(selected)} {new Intl.DateTimeFormat("ko-KR", { weekday: "long", timeZone: "UTC" }).format(new Date(`${selected}T00:00:00Z`))}</h2>
    {days.isError || items.isError ? <p role="alert">{C.recordReadFailed} <button onClick={() => { void days.refetch(); void items.refetch(); }}>{C.retry}</button></p> : null}
    <ActivityTypeFilter id="records-activity-type" value={activityType} onChange={setActivityType} />
    {items.data?.length === 0 ? <p data-testid="records-empty-day">{C.emptyDay}</p> : null}{days.data?.length === 0 && items.data?.length === 0 ? <p data-testid="records-empty">{C.emptyRecords}</p> : null}
    {items.data && items.data.length > 0 && visibleItems?.length === 0 ? <p role="status">{ACTIVITY_SELECTION_COPY.emptyFilter}</p> : null}
    {visibleItems?.map(item => {
      const kind = item.activityType === "PRACTICE" ? ACTIVITY_SELECTION_COPY.practice : ACTIVITY_SELECTION_COPY.freeTalk;
      const startedTime = formatHhmm(item.startedAt);
      if (!item.linkable || item.finalStatus === "DECLINED") return (
        <article className="mj-card mj-record-unstarted" key={item.sessionId}
          data-testid={`records-item-declined-${item.sessionId}`}
          aria-labelledby={`record-kind-${item.sessionId}`}>
          <div className="mj-record-unstarted__heading">
            <h3 id={`record-kind-${item.sessionId}`}>
              {item.activityType === "PRACTICE" ? ACTIVITY_SELECTION_COPY.practice : ACTIVITY_SELECTION_COPY.freeTalk}
            </h3>
            <span className="mj-record-unstarted__status">{UNSTARTED_RECORD_COPY.status}</span>
          </div>
          <p className="mj-record-unstarted__time">
            {startedTime ? <>{UNSTARTED_RECORD_COPY.timeLabel} <time dateTime={item.startedAt!}>{startedTime}</time></> : UNSTARTED_RECORD_COPY.timeUnknown}
          </p>
        </article>
      );
      const time = startedTime ?? ACTIVITY_SELECTION_COPY.timeUnknown;
      // 연습 줄은 목표 수 없이 '연습대화 · n분'이다. 정리 끝 결과 문장이 있으면 아래에 한 줄 요약을 붙인다(편차 FT-1029-a).
      return <button type="button" className="mj-card mj-button--block mj-record-item" key={item.sessionId} data-testid={`records-item-${item.sessionId}`} onClick={() => router.push(PARENT_ROUTES.recordDetail(item.sessionId))}>
        <span className="mj-record-item__line">{time}　{kind}{durationLabel(minutesBetween(item.startedAt, item.endedAt) ?? 0)}{item.interrupted || item.finalStatus === "FAILED" ? " · 중간에 끊김" : ""}</span>
        {item.summary !== null ? <span className="mj-record-summary" data-testid={`records-item-summary-${item.sessionId}`}>{item.summary}</span> : null}
      </button>;
    })}
    <SavedReports />
  </main><BottomTabBar active="records" lockMessage={entry.lockMessage} onLockedPress={entry.onLockedPress} onNavigate={entry.navigate} /></div>;
}
