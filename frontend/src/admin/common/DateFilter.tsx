"use client";

import { useState } from "react";
import { recentRange, validRange } from "../data/dates";
import type { AdminFilter } from "../data/types";
import { ADMIN_COPY as C } from "./copy";
import { rangeText } from "./format";
import { Icon } from "./Icon";

export function DateFilter({ filter, today, onChange }: {
  filter: AdminFilter;
  today: string;
  onChange: (value: AdminFilter) => void;
}) {
  const [open, setOpen] = useState(false);
  const [from, setFrom] = useState(filter.from);
  const [to, setTo] = useState(filter.to);
  const [invalid, setInvalid] = useState(false);
  function close() {
    setOpen(false);
    setInvalid(false);
  }
  return (
    <div className="mj-admin-date">
      <button type="button" className="mj-admin-button mj-admin-date-trigger" aria-expanded={open} aria-controls="admin-date-panel" onClick={() => {
        setFrom(filter.from);
        setTo(filter.to);
        setInvalid(false);
        setOpen(!open);
      }}>
        <Icon name="calendar" />
        <span><small>{C.period}</small>{rangeText(filter.from, filter.to)}</span>
        <span aria-hidden="true">{C.down}</span>
      </button>
      {open && (
        <section className="mj-admin-date-panel" id="admin-date-panel" aria-label={C.period} onKeyDown={(event) => {
          if (event.key === "Escape") close();
        }}>
          <div className="mj-admin-presets">
            {([[1, C.today], [7, C.week], [30, C.month]] as const).map(([days, label]) => (
              <button type="button" key={days} onClick={() => {
                onChange({ ...filter, ...recentRange(days, today) });
                close();
              }}>{label}</button>
            ))}
          </div>
          <h3>{C.custom}</h3>
          <small>{C.dateLimit}</small>
          <form onSubmit={(event) => {
            event.preventDefault();
            if (!validRange(from, to, today)) {
              setInvalid(true);
              return;
            }
            onChange({ ...filter, from, to });
            close();
          }}>
            <div className="mj-admin-date-fields">
              <label>
                {C.from}
                <input type="date" value={from} max={today} required onChange={(event) => setFrom(event.target.value)} aria-invalid={invalid} aria-describedby={invalid ? "admin-date-error" : undefined} />
              </label>
              <label>
                {C.to}
                <input type="date" value={to} max={today} required onChange={(event) => setTo(event.target.value)} aria-invalid={invalid} aria-describedby={invalid ? "admin-date-error" : undefined} />
              </label>
            </div>
            {invalid && <p role="alert" id="admin-date-error" className="mj-admin-error">{C.dateError}</p>}
            <div className="mj-admin-date-actions">
              <small>{C.timezone}</small>
              <button className="mj-admin-button" type="button" onClick={close}>{C.cancel}</button>
              <button className="mj-admin-button primary" type="submit">{C.apply}</button>
            </div>
          </form>
        </section>
      )}
    </div>
  );
}
