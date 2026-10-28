import type { AdminFilter, Overview } from "../../data/types";
import { ADMIN_COPY as C } from "../../common/copy";
import { duration, number, pairText, rangeText, typeCountsText } from "../../common/format";

export function OverviewMetrics({ data, filter }: { data: Overview; filter: AdminFilter }) {
  return (
    <>
      <div className="mj-admin-stats">
        <section className="mj-admin-stat">
          <span>{C.overviewCount}</span>
          <strong>{number(data.activityCount)}<small>{C.count}</small></strong>
          <p>{typeCountsText(data.freeTalkCount, data.practiceCount)}</p>
        </section>
        <section className="mj-admin-stat">
          <span>{C.activeChildren}</span>
          <strong>{number(data.activeChildren)}<small>{C.people}</small></strong>
          <p>{rangeText(filter.from, filter.to)}</p>
        </section>
        <section className="mj-admin-stat">
          <span>{C.average}</span>
          <strong className="mj-admin-duration">{duration(data.averageSeconds.all)}</strong>
          <p>{C.averageAll}</p>
        </section>
        <section className="mj-admin-stat">
          <span>{C.utterances}</span>
          <strong>{pairText(number(data.childTurns), number(data.aiTurns))}</strong>
          <p>{C.turns}</p>
        </section>
      </div>
      <section className="mj-admin-average-strip">
        <div><span className="mj-admin-dot" />{C.averageFree}<strong>{duration(data.averageSeconds.freeTalk)}</strong></div>
        <div><span className="mj-admin-dot purple" />{C.averagePractice}<strong>{duration(data.averageSeconds.practice)}</strong></div>
        <p>{C.averageHint}</p>
      </section>
    </>
  );
}
