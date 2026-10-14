import type { PracticeProgress, ProgressIssue } from "./practiceProgress";
import { U5B_COPY } from "./u5bCopy";

export function PracticeHeader({ progress, issue = null }: { progress: PracticeProgress | null; issue?: ProgressIssue }) {
  if (!progress && !issue) return null;
  return <section aria-label="연습 진행" aria-live="polite">
    {progress && <><h2>{U5B_COPY.heading(progress.goalIndex, progress.goalCount, progress.goalName)}</h2>
      <p>{U5B_COPY.stages[progress.stage]}</p></>}
    {issue && <p role="status">{issue === "fetch" ? U5B_COPY.fetchFailed : U5B_COPY.invalidProgress}</p>}
  </section>;
}
