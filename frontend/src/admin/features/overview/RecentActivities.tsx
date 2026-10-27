import Link from "next/link";
import type { ActivitySummary } from "../../data/types";
import { StatusBadge, TypeBadge } from "../../common/Badge";
import { ADMIN_COPY as C } from "../../common/copy";
import { dateTime, duration } from "../../common/format";
import { Icon } from "../../common/Icon";
import { EmptyState } from "../../common/ResourceState";

export function RecentActivities({ activities }: { activities: ActivitySummary[] }) {
  return (
    <section className="mj-admin-panel">
      <div className="mj-admin-panel-heading">
        <h2>{C.recent}</h2>
        <Link href="/admin/activities" className="mj-admin-text-link">
          {C.allActivities}<Icon name="arrow" />
        </Link>
      </div>
      {activities.length ? (
        <div className="mj-admin-table-wrap">
          <table>
            <caption className="mj-admin-sr-only">{C.recent}</caption>
            <thead>
              <tr><th>{C.child}</th><th>{C.type}</th><th>{C.start}</th><th>{C.duration}</th><th>{C.status}</th></tr>
            </thead>
            <tbody>
              {activities.map((activity) => (
                <tr key={activity.id}>
                  <td><strong>{activity.childName}</strong><small>{activity.id}</small></td>
                  <td><TypeBadge type={activity.type} /></td>
                  <td>{dateTime(activity.startedAt)}</td>
                  <td>{duration(activity.durationSeconds)}</td>
                  <td><StatusBadge status={activity.status} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : <EmptyState />}
    </section>
  );
}
