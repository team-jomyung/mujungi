import type { ReactNode } from "react";
import type { PracticeProgress, ProgressIssue } from "../../common/api/practiceProgress";
import { U5B_COPY } from "./copy";

export function PracticeHeader({ progress, issue = null, primary = false, children }: {
  progress: PracticeProgress | null; issue?: ProgressIssue; primary?: boolean; children?: ReactNode;
}) {
  if (!progress && !issue && !primary) return null;
  const heading = progress ? U5B_COPY.heading(progress.goalIndex, progress.goalCount, progress.goalName) : U5B_COPY.title;
  return <section aria-label={U5B_COPY.region} aria-live="polite">
    {primary ? <h1 className="mj-header__title">{heading}</h1> : progress ? <h2>{heading}</h2> : null}
    {children}
    {progress ? <p className="mj-header__meta">{U5B_COPY.stages[progress.stage]}</p> : null}
    {issue && <p role="status">{issue === "fetch" ? U5B_COPY.fetchFailed : U5B_COPY.invalidProgress}</p>}
  </section>;
}
