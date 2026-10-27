"use client";

import { useState } from "react";
import type { AdminFilter } from "../data/types";
import { ADMIN_COPY as C } from "./copy";

export function AdminToolbar({ filter, effectiveFilter, quality, onChange }: {
  filter: AdminFilter;
  effectiveFilter: AdminFilter;
  quality: boolean;
  onChange: (filter: AdminFilter) => void;
}) {
  const [search, setSearch] = useState(filter.query ?? "");
  return (
    <div className="mj-admin-toolbar" data-search-only={quality || undefined}>
      {!quality && (
        <div className="mj-admin-type-filter" role="group" aria-label={C.type}>
          {([["ALL", C.allTypes], ["FREE_TALK", C.freeTalk], ["PRACTICE", C.practice]] as const).map(([value, label]) => (
            <button type="button" key={value} aria-pressed={effectiveFilter.type === value} onClick={() => onChange({ ...filter, type: value })}>
              {label}
            </button>
          ))}
        </div>
      )}
      <form className="mj-admin-search" onSubmit={(event) => {
        event.preventDefault();
        onChange({ ...filter, query: search.trim() || undefined });
      }}>
        <input type="search" aria-label={C.search} placeholder={C.searchPlaceholder} value={search} onChange={(event) => {
          setSearch(event.target.value);
          if (!event.target.value) onChange({ ...filter, query: undefined });
        }} />
        <button className="mj-admin-button" type="submit">{C.searchAction}</button>
      </form>
    </div>
  );
}
