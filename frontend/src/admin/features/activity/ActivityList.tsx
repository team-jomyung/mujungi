"use client";

import { Fragment, useState } from "react";
import { StatusBadge, TypeBadge } from "../../common/Badge";
import { ADMIN_COPY as C } from "../../common/copy";
import { dateTime, detailLabel, duration, number, totalRecords } from "../../common/format";
import { Pagination } from "../../common/Pagination";
import { useAdminQuery } from "../../common/queries";
import { EmptyState, ResourceState } from "../../common/ResourceState";
import { ActivityDetailView } from "./ActivityDetailView";
import type { ActivityScreenProps } from "./types";

export function ActivityList({ source, filter }: ActivityScreenProps) {
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<string | null>(null);
  const query = useAdminQuery(["activities", filter, page], (signal) => source.activities(filter, page, signal));
  if (query.loading || query.error) {
    return <ResourceState loading={query.loading} error={query.error} retry={query.reload} />;
  }
  if (!query.data?.items.length) return <EmptyState />;
  const data = query.data;

  return (
    <div className="mj-admin-panel mj-admin-list-panel">
      <div className="mj-admin-panel-heading">
        <h2>{C.allActivities}</h2>
        <span className="mj-admin-muted">{totalRecords(data.total)}</span>
      </div>
      <div className="mj-admin-table-scroll">
        <table className="mj-admin-data-table">
          <caption className="mj-admin-sr-only">{C.activityCaption}</caption>
          <thead>
            <tr>
              <th scope="col">{C.child}</th>
              <th scope="col">{C.type}</th>
              <th scope="col">{C.date}</th>
              <th scope="col" className="mj-admin-numeric">{C.duration}</th>
              <th scope="col" className="mj-admin-numeric">{C.childTurns}</th>
              <th scope="col" className="mj-admin-numeric">{C.aiTurns}</th>
              <th scope="col">{C.status}</th>
              <th scope="col"><span className="mj-admin-sr-only">{C.detail}</span></th>
            </tr>
          </thead>
          <tbody>
            {data.items.map((activity) => (
              <Fragment key={activity.id}>
                <tr data-selected={selected === activity.id}>
                  <th scope="row"><strong>{activity.childName}</strong><small>{activity.deviceId}</small></th>
                  <td><TypeBadge type={activity.type} /></td>
                  <td>{dateTime(activity.startedAt)}</td>
                  <td className="mj-admin-numeric">{duration(activity.durationSeconds)}</td>
                  <td className="mj-admin-numeric">{number(activity.childTurns)}</td>
                  <td className="mj-admin-numeric">{number(activity.aiTurns)}</td>
                  <td><StatusBadge status={activity.status} /></td>
                  <td>
                    <button
                      type="button"
                      className="mj-admin-text-button"
                      aria-label={detailLabel(activity.childName, activity.id)}
                      aria-expanded={selected === activity.id}
                      aria-controls={`activity-${activity.id}`}
                      onClick={() => setSelected(selected === activity.id ? null : activity.id)}
                    >
                      {selected === activity.id ? C.close : C.detail}
                    </button>
                  </td>
                </tr>
                {selected === activity.id && (
                  <tr>
                    <td colSpan={8} className="mj-admin-expanded-cell">
                      <div id={`activity-${activity.id}`}>
                        <ActivityDetailView source={source} id={activity.id} />
                      </div>
                    </td>
                  </tr>
                )}
              </Fragment>
            ))}
          </tbody>
        </table>
      </div>
      <Pagination
        page={data.page}
        pageSize={data.pageSize}
        total={data.total}
        onPage={(next) => {
          setSelected(null);
          setPage(next);
        }}
      />
    </div>
  );
}
