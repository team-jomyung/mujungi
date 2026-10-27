"use client";

import { useState } from "react";
import { ADMIN_COPY as C } from "../../common/copy";
import { ActivityContent } from "./ActivityContent";
import type { ActivityScreenProps, ActivityTab } from "./types";

export function ActivityScreen({ source, filter }: ActivityScreenProps) {
  const [tab, setTab] = useState<ActivityTab>("all");
  return (
    <section className="mj-admin-activity">
      <ActivityContent
        key={JSON.stringify(filter)}
        source={source}
        filter={filter}
        tab={tab}
        onTab={setTab}
      />
      <details className="mj-admin-criteria">
        <summary>{C.sourceNote}</summary>
        <p>{C.activityCriteria}</p>
      </details>
    </section>
  );
}
