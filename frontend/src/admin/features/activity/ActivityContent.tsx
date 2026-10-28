"use client";

import { useState } from "react";
import type { ActivityStatus } from "../../data/types";
import { ADMIN_COPY as C, activityStatus } from "../../common/copy";
import { ActivityList } from "./ActivityList";
import { GuardianList } from "./GuardianList";
import type { ActivityScreenProps, ActivityTab, SelectedChild } from "./types";

interface ActivityContentProps extends ActivityScreenProps {
  tab: ActivityTab;
  onTab: (tab: ActivityTab) => void;
}

export function ActivityContent({ source, filter, tab, onTab }: ActivityContentProps) {
  const [child, setChild] = useState<SelectedChild | null>(null);
  const [status, setStatus] = useState<"ALL" | ActivityStatus>(filter.status ?? "ALL");
  const scopedFilter = {
    ...filter,
    status,
    ...(child ? { childId: child.id, guardianId: child.guardianId, deviceId: child.deviceId } : {}),
  };

  return (
    <>
      <p className="mj-admin-muted mj-admin-section-note">
        {tab === "all" ? C.allHint : C.guardianHint}
      </p>
      {tab === "all" && (
        <div className="mj-admin-list-controls">
          {child ? (
            <div className="mj-admin-child-context">
              <span>{C.selectedChild}</span>
              <strong>{child.name}</strong>
              <span>{child.deviceId}</span>
              <button type="button" className="mj-admin-text-button" onClick={() => setChild(null)}>
                {C.clearChild}
              </button>
            </div>
          ) : <span />}
          <label className="mj-admin-field">
            <span>{C.resultFilter}</span>
            <select value={status} onChange={(event) => setStatus(event.target.value as typeof status)}>
              <option value="ALL">{C.allStatuses}</option>
              {Object.entries(activityStatus).map(([value, label]) => (
                <option key={value} value={value}>{label}</option>
              ))}
            </select>
          </label>
        </div>
      )}
      <div className="mj-admin-view-switch" role="group" aria-label={C.viewMode}>
        <button type="button" aria-pressed={tab === "all"} onClick={() => onTab("all")}>
          {C.allActivities}
        </button>
        <button type="button" aria-pressed={tab === "guardians"} onClick={() => onTab("guardians")}>
          {C.guardians}
        </button>
      </div>
      {tab === "guardians" ? (
        <GuardianList
          source={source}
          filter={filter}
          onChild={(selected) => {
            setChild(selected);
            onTab("all");
          }}
        />
      ) : <ActivityList key={JSON.stringify(scopedFilter)} source={source} filter={scopedFilter} />}
    </>
  );
}
