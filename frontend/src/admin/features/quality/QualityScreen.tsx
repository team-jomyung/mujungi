"use client";

import type { AdminDataSource, AdminFilter } from "../../data/types";
import { ADMIN_COPY as C } from "../../common/copy";
import { number, percent } from "../../common/format";
import { useAdminQuery } from "../../common/queries";
import { ResourceState } from "../../common/ResourceState";
import { PracticeRecords } from "./PracticeRecords";
import { QualityCharts } from "./QualityCharts";

export function QualityScreen({ source, filter }: { source: AdminDataSource; filter: AdminFilter }) {
  const query = useAdminQuery(["quality", filter], (signal) => source.quality(filter, signal));
  if (query.loading || query.error) {
    return <ResourceState loading={query.loading} error={query.error} retry={query.reload} />;
  }
  const quality = query.data;
  if (!quality) return null;

  return (
    <section className="mj-admin-quality">
      <p className="mj-admin-muted mj-admin-section-note">{C.qualityHint}</p>
      <div className="mj-admin-quality-kpis">
        <QualityMetric title={C.startedPractices} value={number(quality.startedPractices)} hint={C.startedHint} />
        <QualityMetric title={C.helpExecutions} value={number(quality.helpExecutionCount)} hint={C.helpHint} />
        <QualityMetric title={C.acceptanceRate} value={percent(quality.acceptance.rate)} hint={C.acceptanceHint} />
        <QualityMetric title={C.retryCount} value={number(quality.retryCount)} hint={C.retryHint} />
      </div>
      <QualityCharts quality={quality} />
      <PracticeRecords key={JSON.stringify(filter)} quality={quality} />
      <details className="mj-admin-criteria">
        <summary>{C.sourceNote}</summary>
        <p>{C.qualityCriteria}</p>
      </details>
    </section>
  );
}

function QualityMetric({ title, value, hint }: { title: string; value: string; hint: string }) {
  return (
    <section className="mj-admin-panel mj-admin-quality-kpi">
      <h2>{title}</h2>
      <strong>{value}</strong>
      <p>{hint}</p>
    </section>
  );
}
