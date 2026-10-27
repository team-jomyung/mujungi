"use client";

import type { AdminDataSource, AdminFilter } from "../../data/types";
import { ADMIN_COPY as C } from "../../common/copy";
import { countText } from "../../common/format";
import { useAdminQuery } from "../../common/queries";
import { EmptyState, ResourceState } from "../../common/ResourceState";
import { DailyChart } from "./DailyChart";
import { OverviewMetrics } from "./OverviewMetrics";
import { RecentActivities } from "./RecentActivities";

export function OverviewScreen({ source, filter }: { source: AdminDataSource; filter: AdminFilter }) {
  const query = useAdminQuery(["overview", filter], (signal) => source.overview(filter, signal));
  if (!query.data) {
    return <ResourceState loading={query.loading} error={query.error} retry={query.reload} />;
  }
  const data = query.data;
  const endTotal = data.endReasons.reduce((sum, item) => sum + item.count, 0);

  return (
    <div className="mj-admin-stack">
      <OverviewMetrics data={data} filter={filter} />
      <div className="mj-admin-overview-grid">
        <DailyChart data={data} />
        <section className="mj-admin-panel">
          <div className="mj-admin-panel-heading"><h2>{C.endings}</h2></div>
          <div className="mj-admin-endings">
            {endTotal ? data.endReasons.map((item) => (
              <div key={item.code}>
                <div className="mj-admin-row"><span>{item.label}</span><strong>{countText(item.count)}</strong></div>
                <div className="mj-admin-track"><span style={{ width: `${100 * item.count / endTotal}%` }} /></div>
              </div>
            )) : <EmptyState />}
          </div>
        </section>
      </div>
      <RecentActivities activities={data.recentActivities} />
    </div>
  );
}
