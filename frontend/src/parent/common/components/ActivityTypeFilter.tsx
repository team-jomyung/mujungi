import { ACTIVITY_SELECTION_COPY as C } from "@/shared/copy/result";

export type ActivityTypeFilterValue = "ALL" | "FREE_TALK" | "PRACTICE";

export function ActivityTypeFilter({ id, value, onChange }: {
  id: string;
  value: ActivityTypeFilterValue;
  onChange: (value: ActivityTypeFilterValue) => void;
}) {
  return <div className="mj-activity-filter">
    <label htmlFor={id}>{C.filter}</label>
    <select id={id} value={value} onChange={event => onChange(event.target.value as ActivityTypeFilterValue)}>
      <option value="ALL">{C.all}</option>
      <option value="FREE_TALK">{C.freeTalk}</option>
      <option value="PRACTICE">{C.practice}</option>
    </select>
  </div>;
}
