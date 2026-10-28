"use client";

import { Fragment, useState } from "react";
import { ADMIN_COPY as C } from "../../common/copy";
import { childrenCount, devicesCount, times, totalRecords } from "../../common/format";
import { Pagination } from "../../common/Pagination";
import { useAdminQuery } from "../../common/queries";
import { EmptyState, ResourceState } from "../../common/ResourceState";
import { GuardianDevices } from "./GuardianDevices";
import type { ActivityScreenProps, SelectedChild } from "./types";

interface GuardianListProps extends ActivityScreenProps {
  onChild: (child: SelectedChild) => void;
}

export function GuardianList({ source, filter, onChild }: GuardianListProps) {
  const [page, setPage] = useState(0);
  const [expanded, setExpanded] = useState<string | null>(null);
  const query = useAdminQuery(["guardians", filter, page], (signal) => source.guardians(filter, page, signal));
  if (query.loading || query.error) {
    return <ResourceState loading={query.loading} error={query.error} retry={query.reload} />;
  }
  if (!query.data?.items.length) return <EmptyState />;
  const data = query.data;

  return (
    <div className="mj-admin-panel mj-admin-list-panel">
      <div className="mj-admin-panel-heading">
        <h2>{C.rosterTitle}</h2>
        <span className="mj-admin-muted">{totalRecords(data.total)}</span>
      </div>
      <div className="mj-admin-table-scroll">
        <table className="mj-admin-data-table">
          <caption className="mj-admin-sr-only">{C.guardianCaption}</caption>
          <thead>
            <tr>
              <th scope="col">{C.guardian}</th>
              <th scope="col">{C.registeredDevices}</th>
              <th scope="col">{C.children}</th>
              <th scope="col">{C.activityCount}</th>
              <th scope="col"><span className="mj-admin-sr-only">{C.detail}</span></th>
            </tr>
          </thead>
          <tbody>
            {data.items.map((guardian) => (
              <Fragment key={guardian.id}>
                <tr>
                  <th scope="row"><strong>{guardian.name}</strong><small>{guardian.id}</small></th>
                  <td>{devicesCount(guardian.devices.length)}</td>
                  <td>{childrenCount(new Set(guardian.devices.flatMap((device) => device.children.map((child) => child.id))).size)}</td>
                  <td>{times(guardian.activityCount)}</td>
                  <td>
                    <button
                      className="mj-admin-text-button"
                      type="button"
                      aria-expanded={expanded === guardian.id}
                      aria-controls={`guardian-${guardian.id}`}
                      onClick={() => setExpanded(expanded === guardian.id ? null : guardian.id)}
                    >
                      {expanded === guardian.id ? C.close : C.viewChildren}
                    </button>
                  </td>
                </tr>
                {expanded === guardian.id && (
                  <tr>
                    <td colSpan={5} className="mj-admin-expanded-cell">
                      <GuardianDevices guardian={guardian} onChild={onChild} />
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
          setExpanded(null);
          setPage(next);
        }}
      />
    </div>
  );
}
