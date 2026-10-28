"use client";

import { useState } from "react";
import type { AdminDataSource, AdminFilter } from "../../data/types";
import { hasActivityFilter } from "../../data/filters";
import { Badge } from "../../common/Badge";
import { ADMIN_COPY as C } from "../../common/copy";
import { money, number, unknownCostText, unlinkedText } from "../../common/format";
import { Pagination } from "../../common/Pagination";
import { useAdminQuery } from "../../common/queries";
import { EmptyState, ResourceState } from "../../common/ResourceState";
import { CostRows } from "./CostRows";

export function CostScreen({ source, filter }: { source: AdminDataSource; filter: AdminFilter }) {
  // AdminView는 필터가 바뀌면 화면을 다시 마운트해 선택과 페이지를 초기화한다.
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<string | null>(null);
  const query = useAdminQuery(["costs", filter, page], (signal) => source.costs(filter, page, signal));
  const data = query.data;
  if (!data) return <ResourceState loading={query.loading} error={query.error} retry={query.reload} />;
  const otherCostsHidden = hasActivityFilter(filter);
  return (
    <div className="mj-admin-stack">
      <div className="mj-admin-cost-notice">
        <Badge tone="green">{C.costScope}</Badge><p>{C.costNote}</p><small>{C.costPeriod}</small>
      </div>
      <div className="mj-admin-stats">
        <section className="mj-admin-stat accent"><span>{C.costTotal}</span><strong>{money(data.totals.collectedUsd)}</strong><p>{C.estimated}</p></section>
        <section className="mj-admin-stat"><span>{C.costActivity}</span><strong>{money(data.totals.activityUsd)}</strong><p>{C.categoryNames.ACTIVITY}</p></section>
        <section className="mj-admin-stat"><span>{C.costFailed}</span><strong>{money(data.totals.failedStartUsd)}</strong><p>{C.failed}</p></section>
        <section className="mj-admin-stat"><span>{C.costOther}</span><strong>{C.nonActivityOutOfScope}</strong><p>{unlinkedText(money(data.totals.unlinkedUsd))}</p></section>
      </div>
      <p className="mj-admin-note">{unknownCostText(data.totals.unknownCount)}</p>
      <section className="mj-admin-panel">
        <div className="mj-admin-panel-heading">
          <h2>{C.costList}<span className="mj-admin-count">{number(data.activities.total)}</span></h2><small>{C.timezone}</small>
        </div>
        {data.activities.items.length ? <CostRows items={data.activities.items} source={source} group="activities" selected={selected} select={setSelected} /> : <EmptyState />}
        <Pagination page={page} pageSize={data.activities.pageSize} total={data.activities.total} onPage={(next) => {
          setSelected(null);
          setPage(next);
        }} />
      </section>
      <section className="mj-admin-panel">
        <div className="mj-admin-panel-heading"><div><h2>{C.otherCostList}</h2><p>{C.otherCostHint}</p></div></div>
        {otherCostsHidden ? (
          <p className="mj-admin-note" role="status">{C.otherCostsFiltered}</p>
        ) : data.otherCosts.length ? (
          <CostRows items={data.otherCosts} source={source} group="other" selected={selected} select={setSelected} />
        ) : <EmptyState />}
      </section>
      <p className="mj-admin-note">{data.scope}</p>
    </div>
  );
}
