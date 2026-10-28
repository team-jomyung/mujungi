import type { ActivityDetail } from "../../data/types";
import { ADMIN_COPY as C } from "../../common/copy";
import { clockTime } from "../../common/format";

export function Transcript({ detail }: { detail: ActivityDetail }) {
  return (
    <section className="mj-admin-transcript-section">
      <h4>{C.transcript}</h4>
      {!detail.transcriptAvailable ? <p className="mj-admin-muted">{C.transcriptDenied}</p>
        : detail.transcript.length === 0 ? <p className="mj-admin-muted">{C.transcriptMissing}</p>
          : (
            <ol className="mj-admin-transcript">
              {detail.transcript.map((line) => (
                <li
                  key={line.sequence}
                  className={line.speaker === "CHILD" ? "mj-admin-transcript-child" : "mj-admin-transcript-ai"}
                >
                  <div>
                    <strong>{line.speaker === "CHILD" ? detail.childName : C.ai}</strong>
                    <time dateTime={line.at}>{clockTime(line.at)}</time>
                  </div>
                  <p>{line.text}</p>
                </li>
              ))}
            </ol>
          )}
    </section>
  );
}
