"use client";

import { useId } from "react";
import type { AdminDataSource } from "../../data/types";
import { TypeBadge } from "../../common/Badge";
import { ADMIN_COPY as C } from "../../common/copy";
import { dateTime, duration, number } from "../../common/format";
import { useAdminQuery } from "../../common/queries";
import { ResourceState } from "../../common/ResourceState";
import { SceneResults } from "../../common/SceneResults";
import { Transcript } from "./Transcript";

export function ActivityDetailView({ source, id }: { source: AdminDataSource; id: string }) {
  const query = useAdminQuery(["activity", id], (signal) => source.activity(id, signal));
  const titleId = useId();
  if (query.loading || query.error) {
    return <ResourceState loading={query.loading} error={query.error} retry={query.reload} />;
  }
  const detail = query.data;
  if (!detail) return null;

  return (
    <section className="mj-admin-activity-detail" aria-labelledby={titleId}>
      <div className="mj-admin-detail-heading">
        <div>
          <span className="mj-admin-eyebrow">{C.detailHeader}</span>
          <h3 id={titleId}>{detail.childName}{C.detailSeparator}{detail.id}</h3>
          <p className="mj-admin-muted">
            {detail.guardianName}{C.detailSeparator}{detail.deviceId}{C.detailSeparator}{dateTime(detail.startedAt)}
          </p>
        </div>
        <TypeBadge type={detail.type} />
      </div>
      <dl className="mj-admin-detail-facts">
        <div><dt>{C.duration}</dt><dd>{duration(detail.durationSeconds)}</dd></div>
        <div><dt>{C.endReason}</dt><dd>{detail.endReason || C.unknown}</dd></div>
        <div><dt>{C.childTurns}</dt><dd>{number(detail.childTurns)}</dd></div>
        <div><dt>{C.aiTurns}</dt><dd>{number(detail.aiTurns)}</dd></div>
      </dl>
      <div className="mj-admin-detail-columns">
        <Transcript detail={detail} />
        <section className="mj-admin-scene-section">
          <h4>{C.sceneResults}</h4>
          {detail.type === "FREE_TALK"
            ? <p className="mj-admin-muted">{C.noPracticeScenes}</p>
            : <SceneResults scenes={detail.scenes} />}
        </section>
      </div>
    </section>
  );
}
