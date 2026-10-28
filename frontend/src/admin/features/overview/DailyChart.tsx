import type { Overview } from "../../data/types";
import { ADMIN_COPY as C } from "../../common/copy";
import { number } from "../../common/format";
import { EmptyState } from "../../common/ResourceState";

export function DailyChart({ data }: { data: Overview }) {
  const max = Math.max(1, ...data.daily.map((day) => day.freeTalk + day.practice));
  return (
    <section className="mj-admin-panel">
      <div className="mj-admin-panel-heading">
        <div><h2>{C.daily}</h2><p>{C.dailyHint}</p></div>
        <div className="mj-admin-legend">
          <span><i className="mj-admin-dot" />{C.freeTalk}</span>
          <span><i className="mj-admin-dot purple" />{C.practice}</span>
        </div>
      </div>
      {data.activityCount ? (
        <>
          <div className="mj-admin-chart" aria-hidden="true">
            {data.daily.map((day) => (
              <div className="mj-admin-chart-column" key={day.date}>
                <span className="mj-admin-chart-total">{number(day.freeTalk + day.practice)}</span>
                <div className="mj-admin-chart-bars">
                  <div className="practice" style={{ height: `${day.practice / max * 150}px` }} />
                  <div style={{ height: `${day.freeTalk / max * 150}px` }} />
                </div>
                <span>{day.date.slice(5).replace("-", ".")}</span>
              </div>
            ))}
          </div>
          <details className="mj-admin-chart-data">
            <summary>{C.dailyAccessible}</summary>
            <div className="mj-admin-table-wrap">
              <table>
                <thead><tr><th>{C.start}</th><th>{C.freeTalk}</th><th>{C.practice}</th></tr></thead>
                <tbody>
                  {data.daily.map((day) => (
                    <tr key={day.date}>
                      <td>{day.date}</td>
                      <td>{number(day.freeTalk)}</td>
                      <td>{number(day.practice)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </details>
        </>
      ) : <EmptyState />}
    </section>
  );
}
