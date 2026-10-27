import type { Quality } from "../../data/types";
import { ADMIN_COPY as C, consentLabel, helpKind } from "../../common/copy";
import { percent, times } from "../../common/format";

export function QualityCharts({ quality }: { quality: Quality }) {
  const reactions = [
    { label: consentLabel.ACCEPT, count: quality.acceptance.accepted, tone: "green" },
    { label: consentLabel.DECLINE, count: quality.acceptance.declined, tone: "amber" },
    { label: consentLabel.REQUESTED, count: quality.acceptance.requested, tone: "purple" },
    { label: consentLabel.UNCLEAR, count: quality.acceptance.unclear, tone: "gray" },
    { label: consentLabel.NONE, count: quality.acceptance.unanswered, tone: "gray" },
  ];
  const reactionTotal = reactions.reduce((sum, reaction) => sum + reaction.count, 0);
  const maxHelp = Math.max(1, ...quality.helpTypes.map((help) => help.count));

  return (
    <div className="mj-admin-quality-charts">
      <section className="mj-admin-panel">
        <div className="mj-admin-panel-heading"><h2>{C.helpTitle}</h2></div>
        <p className="mj-admin-muted">{C.helpDescription}</p>
        <div className="mj-admin-horizontal-bars">
          {quality.helpTypes.map((help) => (
            <div key={help.kind}>
              <div><span>{helpKind[help.kind]}</span><strong>{times(help.count)}</strong></div>
              <progress max={maxHelp} value={help.count} aria-label={helpKind[help.kind]} />
            </div>
          ))}
        </div>
        <div className="mj-admin-quality-note">
          <div><span>{C.focusExample}</span><strong>{times(quality.focusExamples)}</strong></div>
          <p>{C.focusExampleHint}</p>
        </div>
      </section>
      <section className="mj-admin-panel">
        <div className="mj-admin-panel-heading"><h2>{C.responses}</h2></div>
        <p className="mj-admin-muted">{C.responsesDescription}</p>
        <div className="mj-admin-reaction-list">
          {reactions.map((reaction) => (
            <div key={reaction.label}>
              <span><i className="mj-admin-color-dot" data-tone={reaction.tone} aria-hidden="true" />{reaction.label}</span>
              <strong>{times(reaction.count)}</strong>
              <span className="mj-admin-muted">
                {reactionTotal === 0 ? C.dash : percent(reaction.count / reactionTotal)}
              </span>
            </div>
          ))}
        </div>
        <p className="mj-admin-quality-note">{C.acceptanceDescription}</p>
      </section>
    </div>
  );
}
