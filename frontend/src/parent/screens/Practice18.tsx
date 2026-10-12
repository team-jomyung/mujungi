"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { BottomTabBar, type TabKey } from "../components/BottomTabBar";
import { PARENT_ROUTES, routeForTab } from "../navigation";
import type { GoalSelection } from "../practice/api";
import { PRACTICE_COPY, PRACTICE_TEMP_COPY, goalMeta } from "../practice/copy";
import { usePracticeGoals } from "../practice/usePracticeGoals";
import { PROPOSED_COPY } from "@/shared/copy/ko";
import "../practice/practice.css";

/** AC4.1.1/2/5–9/11/13. Starting and weighted random session order remain U5b (J4). */
export function Practice18() {
  const router = useRouter();
  const goals = usePracticeGoals();
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
  const visible = goals.items ?? [];
  const selectedCount = visible.filter((goal) => selected.has(goal.goalSelectionId)).length;

  const toggleSelected = (id: string) => setSelected((before) => {
    const after = new Set(before);
    if (after.has(id)) after.delete(id); else after.add(id);
    return after;
  });

  const navigate = (tab: TabKey) => {
    if (tab === "practice") return;
    goals.cancelPending();
    router.push(routeForTab(tab));
  };

  const group = (items: readonly GoalSelection[], favorite: boolean) => items.length === 0 ? null : (
    <section className="mj-practice__group" aria-labelledby={favorite ? "practice-favorites" : "practice-saved"}>
      <h2 id={favorite ? "practice-favorites" : "practice-saved"}>{favorite ? PRACTICE_COPY.favorites : PRACTICE_COPY.saved}</h2>
      <ul className="mj-practice__list">
        {items.map((goal) => {
          const id = goal.goalSelectionId;
          const checked = selected.has(id);
          const busy = goals.busyIds.has(id);
          return (
            <li key={id} className="mj-practice__goal" data-testid={`goal-${id}`} data-selected={checked} aria-busy={busy}>
              <label className="mj-practice__select">
                <input type="checkbox" checked={checked} onChange={() => toggleSelected(id)} aria-label={PRACTICE_TEMP_COPY.select(goal.displayName)} />
                <span className="mj-practice__text">
                  <span className="mj-practice__name">{goal.displayName}</span>
                  <span className="mj-practice__meta">{goalMeta(goal)}</span>
                </span>
              </label>
              <button type="button" className="mj-practice__icon" aria-label={PRACTICE_TEMP_COPY.favorite(goal.displayName, goal.isFavorite)} aria-pressed={goal.isFavorite} disabled={busy} onClick={() => { void goals.toggleFavorite(goal); }}>
                <span aria-hidden="true">{goal.isFavorite ? PRACTICE_COPY.favoriteIcon : PRACTICE_COPY.notFavoriteIcon}</span>
              </button>
              <button type="button" className="mj-practice__icon" aria-label={PRACTICE_TEMP_COPY.remove(goal.displayName)} disabled={busy} onClick={() => goals.scheduleDelete(goal)}>
                <span aria-hidden="true">{PRACTICE_COPY.deleteIcon}</span>
              </button>
            </li>
          );
        })}
      </ul>
    </section>
  );

  return (
    <div className="mj-screen mj-practice" data-testid="practice-screen">
      <header className="mj-header"><h1 className="mj-header__title">{PRACTICE_COPY.title}</h1></header>
      <main className="mj-screen__body" aria-busy={goals.isFetching}>
        <div role="status" aria-live="polite" className="mj-visually-hidden">{PRACTICE_TEMP_COPY.selectedCount(selectedCount)}</div>
        {goals.needsRecovery ? (
          <div className="mj-reason-card" data-testid="practice-recovery">
            <p>{PRACTICE_TEMP_COPY.verifyFailed}</p>
            <button type="button" className="mj-button" onClick={goals.retryRecovery}>{PROPOSED_COPY.retry}</button>
          </div>
        ) : null}
        {goals.items === undefined ? (
          <div className="mj-practice__loading">
            <p>{goals.hasError ? PRACTICE_TEMP_COPY.loadFailed : PRACTICE_TEMP_COPY.loading}</p>
            {goals.hasError ? <button type="button" className="mj-button" onClick={goals.refresh} disabled={goals.isFetching}>{PROPOSED_COPY.retry}</button> : null}
          </div>
        ) : visible.length === 0 ? (
          <section className="mj-card mj-practice__empty">
            <h2>{PRACTICE_COPY.empty}</h2>
            <h3>{PRACTICE_COPY.howTo}</h3>
            <ol className="mj-practice__steps">{PRACTICE_COPY.steps.map((step) => <li key={step}>{step}</li>)}</ol>
            <Link className="mj-button mj-button--primary mj-button--block" href={PARENT_ROUTES.free} onClick={goals.cancelPending}>{PRACTICE_COPY.goFree}</Link>
          </section>
        ) : <>{group(visible.filter((goal) => goal.isFavorite), true)}{group(visible.filter((goal) => !goal.isFavorite), false)}</>}
        {goals.items !== undefined && visible.length > 0 ? (
          <div className="mj-practice__start">
            <button type="button" className="mj-button mj-button--primary mj-button--block" disabled aria-describedby="practice-unavailable" data-testid="practice-start">
              {selectedCount === 0 ? PRACTICE_COPY.choose : PRACTICE_COPY.start(selectedCount)}
            </button>
            <p id="practice-unavailable" className="mj-practice__notice">{PRACTICE_TEMP_COPY.startUnavailable}</p>
          </div>
        ) : null}
      </main>
      <BottomTabBar active="practice" lockMessage={null} onNavigate={navigate} onLockedPress={() => undefined} />
    </div>
  );
}
