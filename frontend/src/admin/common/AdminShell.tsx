"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useMemo, useState, type ReactNode } from "react";
import type { AdminMode } from "../config";
import { recentRange } from "../data/dates";
import type { AdminFilter } from "../data/types";
import { useAdminSession } from "./AdminSession";
import { AdminToolbar } from "./AdminToolbar";
import { Badge } from "./Badge";
import { AdminContext } from "./context";
import { ADMIN_COPY as C } from "./copy";
import { DateFilter } from "./DateFilter";
import { Icon } from "./Icon";
import { ADMIN_NAV, type PreviewState } from "./navigation";
import { ResourceState } from "./ResourceState";

export function AdminShell({ mode, today, previewState, onPreview, children }: {
  mode: AdminMode;
  today: string;
  previewState: PreviewState;
  onPreview: (value: PreviewState) => void;
  children: ReactNode;
}) {
  const path = usePathname();
  const active = ADMIN_NAV.find((item) => item.href === path)?.section ?? "overview";
  const session = useAdminSession();
  const [filter, setFilter] = useState<AdminFilter>(() => ({ ...recentRange(7, today), type: "ALL" }));
  const effectiveFilter = useMemo(
    () => active === "quality" ? { ...filter, type: "PRACTICE" as const } : filter,
    [filter, active],
  );
  const context = session.source ? { source: session.source, filter: effectiveFilter } : null;
  return (
    <div className="mj-admin">
      <a href="#admin-main" className="mj-admin-skip">{C.skip}</a>
      <aside className="mj-admin-sidebar">
        <Link className="mj-admin-brand" href="/admin">
          <span className="mj-admin-brand-mark" aria-hidden="true">{C.brandMark}</span>
          <span>{C.brand}<small>{C.brandSub}</small></span>
        </Link>
        <span className="mj-admin-nav-label">{C.workspace}</span>
        <nav aria-label={C.menu}>
          {ADMIN_NAV.map(({ section, href }) => (
            <Link key={section} href={href} className={active === section ? "active" : undefined} aria-current={active === section ? "page" : undefined}>
              <Icon name={section} /><span>{C.nav[section]}</span>
            </Link>
          ))}
        </nav>
        <div className="mj-admin-sidebar-bottom">
          <span className="mj-admin-avatar">{C.brand}</span>
          <div><strong>{session.identity?.displayName ?? C.team}</strong><small>{C.console}</small></div>
        </div>
      </aside>
      <div className="mj-admin-body">
        <div className="mj-admin-topbar">
          <span>{C.console}<span className="mj-admin-topbar-separator">{C.separator}</span><strong>{C.nav[active]}</strong></span>
          <Link href="/parent">{C.back}<Icon name="arrow" /></Link>
        </div>
        {mode === "preview" && (
          <div className="mj-admin-preview">
            <div><Badge tone="amber">{C.preview}</Badge><span>{C.previewNote}</span></div>
            <label>
              {C.errorPreview}
              <select value={previewState} onChange={(event) => onPreview(event.target.value as PreviewState)}>
                {([
                  ["normal", C.normalPreview], ["empty", C.emptyPreview],
                  ["unauthenticated", C.loginPreview], ["forbidden", C.deniedPreview],
                  ["network", C.failedPreview], ["loading", C.loadingPreview],
                ] as const).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
              </select>
            </label>
          </div>
        )}
        <main id="admin-main" tabIndex={-1}>
          <header className="mj-admin-heading">
            <div><span className="mj-admin-eyebrow">{C.workspace}</span><h1>{C.nav[active]}</h1><p>{C.subtitles[active]}</p></div>
            {context && <DateFilter filter={filter} today={today} onChange={setFilter} />}
          </header>
          {context ? (
            <>
              <AdminToolbar filter={filter} effectiveFilter={effectiveFilter} quality={active === "quality"} onChange={setFilter} />
              <AdminContext.Provider value={context}>{children}</AdminContext.Provider>
            </>
          ) : <ResourceState loading={session.loading} error={session.error} retry={session.reload} />}
          <footer className="mj-admin-footer"><span>{C.footer}</span><span>{C.footerMeta}</span></footer>
        </main>
      </div>
    </div>
  );
}
