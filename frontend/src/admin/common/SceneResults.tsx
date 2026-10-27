import type { SceneResult } from "../data/types";
import { Badge } from "./Badge";
import { ADMIN_COPY as C, consentLabel, helpKind, judgmentLabel } from "./copy";
import { responseTiming, times } from "./format";

export function SceneResults({ scenes, showHelp = false }: { scenes: SceneResult[]; showHelp?: boolean }) {
  if (!scenes.length) return <p className="mj-admin-muted">{C.noScenes}</p>;
  return (
    <div className="mj-admin-scene-list">
      {scenes.map((scene) => (
        <article className="mj-admin-scene-card" key={scene.id}>
          <div className="mj-admin-scene-heading">
            <span className="mj-admin-eyebrow">{scene.kind === "BASE" ? C.base : C.variant}</span>
            <Badge tone={scene.judgment === "SUCCESS" ? "green" : "amber"}>{judgmentLabel[scene.judgment]}</Badge>
          </div>
          <h4>{scene.title}</h4>
          <dl className="mj-admin-scene-facts">
            <div><dt>{C.rationale}</dt><dd>{scene.reason ?? C.noneRecorded}</dd></div>
            <div><dt>{C.timing}</dt><dd>{responseTiming(scene.responseTiming)}</dd></div>
            <div><dt>{C.responses}</dt><dd>{consentLabel[scene.helpResponse ?? "NONE"]}</dd></div>
            {scene.abstainReason && <div><dt>{C.abstain}</dt><dd>{scene.abstainReason}</dd></div>}
            {scene.variantSkipReason && <div><dt>{C.skipped}</dt><dd>{scene.variantSkipReason}</dd></div>}
            <div><dt>{C.retries}</dt><dd>{times(scene.retryCount)}</dd></div>
            <div><dt>{C.reExplanation}</dt><dd>{times(scene.reExplanationCount)}</dd></div>
          </dl>
          {showHelp && (
            <div className="mj-admin-help-records">
              <h5>{C.helpRecords}</h5>
              {scene.helpRecords.length ? (
                <ul>
                  {scene.helpRecords.map((help) => (
                    <li key={help.id}>
                      <strong>{helpKind[help.kind]}</strong>
                      <span>{C.helpResponse}{C.colon}{consentLabel[help.consentAnswer ?? "NONE"]}</span>
                      {help.kind === "FOCUS_HELP" && <span>{C.example}{C.colon}{help.exampleGiven ? C.yes : C.no}</span>}
                    </li>
                  ))}
                </ul>
              ) : <p className="mj-admin-muted">{C.noHelp}</p>}
            </div>
          )}
        </article>
      ))}
    </div>
  );
}
