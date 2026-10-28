import type { ReactNode } from "react";
import type { ActivityStatus, ActivityType, CollectionStatus, EndCategory } from "../data/types";
import { ADMIN_COPY as C, activityType, endCategoryLabel } from "./copy";

export type BadgeTone = "green" | "purple" | "amber" | "gray";

export function Badge({ tone = "gray", children }: { tone?: BadgeTone; children: ReactNode }) {
  return <span className="mj-admin-badge" data-tone={tone}>{children}</span>;
}

export function TypeBadge({ type }: { type: ActivityType }) {
  return <Badge tone={type === "PRACTICE" ? "purple" : "green"}>{activityType[type]}</Badge>;
}

export function StatusBadge({ status, endCategory }: { status: ActivityStatus; endCategory: EndCategory }) {
  const tone = status === "COMPLETED" && endCategory === "ENDED" ? "green" : "amber";
  return <Badge tone={tone}>{endCategoryLabel[endCategory]}</Badge>;
}

export function CollectionBadge({ status }: { status: CollectionStatus }) {
  return <Badge tone={status === "COMPLETE" ? "green" : "amber"}>{C.collection[status]}</Badge>;
}
