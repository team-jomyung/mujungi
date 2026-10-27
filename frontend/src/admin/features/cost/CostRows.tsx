"use client";

import { Fragment, useId } from "react";
import type { AdminDataSource, CostSummary } from "../../data/types";
import { Badge, CollectionBadge, TypeBadge } from "../../common/Badge";
import { ADMIN_COPY as C } from "../../common/copy";
import { dateTime, detailLabel, duration, money, number } from "../../common/format";
import { CostDetail } from "./CostDetail";

interface CostRowsProps {
  items: CostSummary[];
  source: AdminDataSource;
  group: "activities" | "other";
  selected: string | null;
  select: (key: string | null) => void;
}

export function CostRows({ items, source, group, selected, select }: CostRowsProps) {
  const tableId = useId();
  return (
    <div className="mj-admin-table-wrap">
      <table>
        <caption className="mj-admin-sr-only">{group === "activities" ? C.costList : C.otherCostList}</caption>
        <thead>
          <tr>
            <th scope="col">{C.child}</th>
            <th scope="col">{C.observed}</th>
            <th scope="col">{C.collectionStatus}</th>
            <th scope="col">{C.voice}</th>
            <th scope="col" className="mj-admin-numeric">{C.input}</th>
            <th scope="col" className="mj-admin-numeric">{C.output}</th>
            <th scope="col" className="mj-admin-money">{C.estimated}</th>
            <th scope="col"><span className="mj-admin-sr-only">{C.detail}</span></th>
          </tr>
        </thead>
        <tbody>
          {items.map((row) => {
            const key = `${group}:${row.id}`;
            const expanded = selected === key;
            const detailId = `${tableId}-${group}-${encodeURIComponent(row.id)}`;
            return (
              <Fragment key={row.id}>
                <tr data-selected={expanded}>
                  <th scope="row">
                    <strong>{row.childName ?? row.purpose}</strong>
                    <small>{row.activityId ?? row.id}</small>
                    {row.type ? <TypeBadge type={row.type} /> : <Badge>{C.categoryNames[row.category]}</Badge>}
                    {row.category === "FAILED_TO_START" && <Badge tone="amber">{C.failed}</Badge>}
                  </th>
                  <td>{dateTime(row.observedAt)}</td>
                  <td><CollectionBadge status={row.status} /></td>
                  <td>{duration(row.voiceSeconds)}</td>
                  <td className="mj-admin-numeric">{number(row.inputTokens)}</td>
                  <td className="mj-admin-numeric">{number(row.outputTokens)}</td>
                  <td className="mj-admin-money">{money(row.estimatedUsd)}</td>
                  <td>
                    <button type="button" className="mj-admin-text-button" aria-expanded={expanded} aria-controls={detailId} aria-label={detailLabel(row.childName ?? row.purpose, row.id)} onClick={() => select(expanded ? null : key)}>
                      {expanded ? C.close : C.detail}
                    </button>
                  </td>
                </tr>
                {expanded && (
                  <tr>
                    <td colSpan={8} className="mj-admin-expanded-cell">
                      <div id={detailId}><CostDetail source={source} id={row.id} close={() => select(null)} /></div>
                    </td>
                  </tr>
                )}
              </Fragment>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
