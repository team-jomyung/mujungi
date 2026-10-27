"use client";

import type { AdminDataSource } from "../../data/types";
import { CollectionBadge } from "../../common/Badge";
import { ADMIN_COPY as C } from "../../common/copy";
import { duration, money, number } from "../../common/format";
import { useAdminQuery } from "../../common/queries";
import { ResourceState } from "../../common/ResourceState";

export function CostDetail({ source, id, close }: { source: AdminDataSource; id: string; close: () => void }) {
  const query = useAdminQuery(["costDetail", id], (signal) => source.costDetail(id, signal));
  const detail = query.data;
  return (
    <section className="mj-admin-cost-detail" aria-label={C.costDetail}>
      <div className="mj-admin-panel-heading">
        <div>
          <span className="mj-admin-eyebrow">{detail ? detail.activityId ? C.detailScope : C.otherDetailScope : C.costDetail}</span>
          <h2>{detail ? `${detail.childName ?? detail.purpose} · ${detail.activityId ?? detail.id}` : C.costDetail}</h2>
        </div>
        <button type="button" className="mj-admin-button" onClick={close}>{C.close}</button>
      </div>
      {!detail ? <ResourceState loading={query.loading} error={query.error} retry={query.reload} /> : (
        <>
          <div className="mj-admin-cost-summary">
            <div><span>{C.estimated}</span><strong>{money(detail.estimatedUsd)}</strong></div>
            <p>{detail.scope}</p>
            <CollectionBadge status={detail.status} />
          </div>
          <h3>{C.breakdown}</h3>
          <div className="mj-admin-phase-list">
            {detail.phases.map((phase, index) => (
              <section className="mj-admin-phase" key={`${phase.phase}-${index}`}>
                <div className="mj-admin-row">
                  <div><h4>{C.phaseNames[phase.phase]}</h4><small>{phase.model}</small></div>
                  <strong className="mj-admin-money">{money(phase.estimatedUsd)}</strong>
                </div>
                <dl className="mj-admin-usage-facts">
                  <div><dt>{C.calls}</dt><dd>{number(phase.calls)}</dd></div>
                  {phase.phase === "VOICE" ? (
                    <div><dt>{C.voice}</dt><dd>{duration(phase.voiceSeconds)}</dd></div>
                  ) : (
                    <>
                      <div><dt>{C.input}</dt><dd>{number(phase.inputTokens)}</dd></div>
                      <div><dt>{C.output}</dt><dd>{number(phase.outputTokens)}</dd></div>
                      <div><dt>{C.cacheRead}</dt><dd>{number(phase.cachedInputTokens)}</dd></div>
                      <div><dt>{C.cacheWrite}</dt><dd>{number(phase.cacheWriteTokens)}</dd></div>
                      <div><dt>{C.reasoning}</dt><dd>{number(phase.reasoningTokens)}</dd></div>
                    </>
                  )}
                  <div><dt>{C.collectionStatus}</dt><dd>{C.collection[phase.status]}</dd></div>
                </dl>
                <p className="mj-admin-calculation">{phase.calculation ?? C.noCalculation}</p>
              </section>
            ))}
          </div>
          <p className="mj-admin-note">{C.inputHint}</p>
          <p className="mj-admin-note">{C.noVoiceTokens}</p>
        </>
      )}
    </section>
  );
}
