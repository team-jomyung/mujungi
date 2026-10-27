import Link from "next/link";
import { messageForErrorCode } from "@/shared/errors/codeToCopy";
import { AdminDataError } from "../data/errors";
import { ADMIN_COPY as C } from "./copy";

export function ResourceState({ loading, error, retry }: { loading: boolean; error: unknown; retry?: () => void }) {
  if (loading) {
    return (
      <div className="mj-admin-state" role="status">
        <span className="mj-admin-spinner" aria-hidden="true" /><p>{C.loading}</p>
      </div>
    );
  }
  const failure = error instanceof AdminDataError ? error : null;
  const kind = failure?.kind ?? "network";
  const [title, hint] = kind === "unauthenticated" ? [C.unauthenticated, C.loginHint]
    : kind === "forbidden" ? [C.forbidden, C.forbiddenHint]
      : kind === "notReady" ? [C.pending, C.pendingHint]
        : kind === "invalidResponse" ? [C.invalidResponse, C.invalidHint]
          : [failure && failure.code !== "UNKNOWN" ? messageForErrorCode(failure.code) : C.error, C.errorHint];
  return (
    <section className="mj-admin-state" role="alert">
      <h2>{title}</h2>
      <p>{hint}</p>
      {failure?.traceId && <p><small>{C.traceId}{C.colon}{failure.traceId}</small></p>}
      {kind === "unauthenticated" ? (
        <Link className="mj-admin-button primary" href="/parent/login">{C.login}</Link>
      ) : kind === "forbidden" ? (
        <Link className="mj-admin-button" href="/parent">{C.back}</Link>
      ) : kind !== "notReady" && retry ? (
        <button className="mj-admin-button" type="button" onClick={retry}>{C.retry}</button>
      ) : null}
    </section>
  );
}

export function EmptyState({ title = C.empty, description = C.emptyHint }: { title?: string; description?: string }) {
  return <div className="mj-admin-empty"><h3>{title}</h3><p>{description}</p></div>;
}
