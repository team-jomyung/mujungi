"use client";

import { Fragment, useState } from "react";
import type { Quality } from "../../data/types";
import { StatusBadge } from "../../common/Badge";
import { ADMIN_COPY as C } from "../../common/copy";
import { dateTime, duration, number, times } from "../../common/format";
import { Pagination } from "../../common/Pagination";
import { EmptyState } from "../../common/ResourceState";
import { SceneResults } from "../../common/SceneResults";

export function PracticeRecords({ quality }: { quality: Quality }) {
  const [page, setPage] = useState(0);
  const [expanded, setExpanded] = useState<string | null>(null);
  const pageSize = 8;
  // 현재 제안 DTO는 전체 기록 배열이다. 서버 페이지 계약은 연결 전에 별도로 확정한다.
  const rows = quality.records.slice(page * pageSize, (page + 1) * pageSize);

  return (
    <section className="mj-admin-panel mj-admin-list-panel">
      <div className="mj-admin-panel-heading">
        <h2>{C.practices}</h2>
        <span className="mj-admin-muted">{C.qualityAll}</span>
      </div>
      {rows.length ? (
        <>
          <div className="mj-admin-table-scroll">
            <table className="mj-admin-data-table">
              <caption className="mj-admin-sr-only">{C.practiceCaption}</caption>
              <thead>
                <tr>
                  <th scope="col">{C.child}</th>
                  <th scope="col">{C.date}</th>
                  <th scope="col">{C.duration}</th>
                  <th scope="col">{C.scenes}</th>
                  <th scope="col">{C.helpRecords}</th>
                  <th scope="col">{C.status}</th>
                  <th scope="col"><span className="mj-admin-sr-only">{C.detail}</span></th>
                </tr>
              </thead>
              <tbody>
                {rows.map((record) => (
                  <Fragment key={record.activity.id}>
                    <tr>
                      <th scope="row">
                        <strong>{record.activity.childName}</strong>
                        <small>{record.activity.deviceId}</small>
                      </th>
                      <td>{dateTime(record.activity.startedAt)}</td>
                      <td>{duration(record.activity.durationSeconds)}</td>
                      <td>{number(record.scenes.length)}</td>
                      <td>{times(record.scenes.reduce((sum, scene) => sum + scene.helpRecords.length, 0))}</td>
                      <td><StatusBadge status={record.activity.status} /></td>
                      <td>
                        <button
                          type="button"
                          className="mj-admin-text-button"
                          aria-expanded={expanded === record.activity.id}
                          aria-controls={`practice-${record.activity.id}`}
                          onClick={() => setExpanded(expanded === record.activity.id ? null : record.activity.id)}
                        >
                          {expanded === record.activity.id ? C.close : C.detail}
                        </button>
                      </td>
                    </tr>
                    {expanded === record.activity.id && (
                      <tr>
                        <td className="mj-admin-expanded-cell" colSpan={7}>
                          <section id={`practice-${record.activity.id}`} className="mj-admin-practice-detail">
                            <h3>{record.activity.childName}{C.detailSeparator}{record.activity.id}</h3>
                            <SceneResults scenes={record.scenes} showHelp />
                          </section>
                        </td>
                      </tr>
                    )}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination
            page={page}
            pageSize={pageSize}
            total={quality.records.length}
            onPage={(next) => {
              setExpanded(null);
              setPage(next);
            }}
          />
        </>
      ) : <EmptyState />}
    </section>
  );
}
