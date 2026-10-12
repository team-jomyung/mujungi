import type { ApiClient } from "../api/apiClient";
import { ResponseShapeError } from "../api/types";

/** C4 parent DTO: no scenario, evaluation or transcript fields. */
export interface GoalSelection {
  readonly goalSelectionId: string;
  readonly displayName: string;
  readonly isFavorite: boolean;
  readonly sortOrder: number;
  readonly selectedAt: string;
  readonly sourceActivityType: "FREE_TALK" | "PRACTICE" | "UNKNOWN" | null;
  readonly sourceActivityAt: string | null;
  readonly practiceCount: number | null;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function record(value: unknown): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) throw new ResponseShapeError("goalSelection");
  return value as Record<string, unknown>;
}

function text(value: unknown, field: string): string {
  if (typeof value !== "string" || value.trim().length === 0) throw new ResponseShapeError(field);
  return value;
}

function timestamp(value: unknown, field: string): string {
  const result = text(value, field);
  if (!/^\d{4}-\d{2}-\d{2}T.*(?:Z|[+-]\d{2}:\d{2})$/.test(result) || !Number.isFinite(Date.parse(result))) throw new ResponseShapeError(field);
  return result;
}

function count(value: unknown, field: string): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) throw new ResponseShapeError(field);
  return value;
}

export function parseGoalSelection(value: unknown): GoalSelection {
  const row = record(value);
  const goalSelectionId = text(row.goalSelectionId, "goalSelectionId");
  if (!UUID.test(goalSelectionId)) throw new ResponseShapeError("goalSelectionId");
  if (typeof row.isFavorite !== "boolean") throw new ResponseShapeError("isFavorite");
  const activityType = row.sourceActivityType;
  if (activityType != null && typeof activityType !== "string") throw new ResponseShapeError("sourceActivityType");
  return {
    goalSelectionId,
    displayName: text(row.displayName, "displayName"),
    isFavorite: row.isFavorite,
    sortOrder: count(row.sortOrder, "sortOrder"),
    selectedAt: timestamp(row.selectedAt, "selectedAt"),
    sourceActivityType: activityType == null ? null : activityType === "FREE_TALK" || activityType === "PRACTICE" ? activityType : "UNKNOWN",
    sourceActivityAt: row.sourceActivityAt == null ? null : timestamp(row.sourceActivityAt, "sourceActivityAt"),
    practiceCount: row.practiceCount == null ? null : count(row.practiceCount, "practiceCount"),
  };
}

export function sortGoals(items: readonly GoalSelection[]): GoalSelection[] {
  return [...items].sort((a, b) => Number(b.isFavorite) - Number(a.isFavorite) || a.sortOrder - b.sortOrder || a.goalSelectionId.localeCompare(b.goalSelectionId));
}

export function parseGoalSelections(value: unknown): GoalSelection[] {
  const envelope = record(value);
  if (!Array.isArray(envelope.items)) throw new ResponseShapeError("items");
  const rows = envelope.items.map(parseGoalSelection);
  if (new Set(rows.map((row) => row.goalSelectionId)).size !== rows.length) throw new ResponseShapeError("items.goalSelectionId");
  return sortGoals(rows);
}

export function parseDeleteResult(value: unknown): { readonly restoreUntil: string } {
  return { restoreUntil: timestamp(record(value).restoreUntil, "restoreUntil") };
}

const COLLECTION = "/api/goal-selections";
const goalPath = (id: string) => `${COLLECTION}/${encodeURIComponent(id)}`;
export const goalSelectionQueryKey = ["practice", "goal-selections"] as const;

export function fetchGoalSelections(api: ApiClient, signal?: AbortSignal): Promise<GoalSelection[]> {
  return api.request({ method: "GET", path: COLLECTION, parse: parseGoalSelections, csrf: false, signal });
}

/** Future screen27 uses the same selection resource; registering a candidate does not select it. */
export function selectGoal(api: ApiClient, recommendationId: string): Promise<GoalSelection> {
  return api.request({ method: "POST", path: COLLECTION, body: { recommendationId }, parse: parseGoalSelection, csrf: true });
}

export function updateGoalFavorite(api: ApiClient, id: string, isFavorite: boolean): Promise<GoalSelection> {
  return api.request({ method: "PATCH", path: goalPath(id), body: { isFavorite }, parse: parseGoalSelection, csrf: true });
}

export function deleteGoal(api: ApiClient, id: string): Promise<{ readonly restoreUntil: string }> {
  return api.request({ method: "DELETE", path: goalPath(id), parse: parseDeleteResult, csrf: true });
}

export function restoreGoal(api: ApiClient, id: string): Promise<GoalSelection> {
  return api.request({ method: "POST", path: `${goalPath(id)}/restore`, parse: parseGoalSelection, csrf: true });
}
