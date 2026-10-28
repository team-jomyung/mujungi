"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "../../common/api/apiClient";
import { useApiClient } from "../../common/api/queries";
import { goToLogin } from "../../common/navigation";
import { deleteGoal, fetchGoalSelections, goalSelectionQueryKey, restoreGoal, sortGoals, updateGoalFavorite, type GoalSelection } from "../../common/api/practice";
import { PRACTICE_COPY, PRACTICE_TEMP_COPY } from "./copy";
import { PROPOSED_COPY } from "@/shared/copy/ko";
import { useToast } from "@/shared/toast/ToastProvider";
import { useTiming } from "@/shared/timing/TimingProvider";

/** Deletion is sent immediately; only server acknowledgement removes a row. Undo restores it on the server. */
export function usePracticeGoals() {
  const api = useApiClient();
  const client = useQueryClient();
  const router = useRouter();
  const toast = useToast();
  const timing = useTiming();
  const mounted = useRef(true);
  const undoToastId = useRef<number | undefined>(undefined);
  const confirmedDeleted = useRef(new Set<string>());
  const locks = useRef(new Set<string>());
  const recovery = useRef(new Map<string, { goal: GoalSelection; undo: boolean }>());
  const recovering = useRef(new Set<string>());
  const favoriteEpoch = useRef(new Map<string, number>());
  const [busyIds, setBusyIds] = useState<ReadonlySet<string>>(new Set());
  const [needsRecovery, setNeedsRecovery] = useState(false);
  const [deletingIds, setDeletingIds] = useState<ReadonlySet<string>>(new Set());
  const query = useQuery({ queryKey: goalSelectionQueryKey, queryFn: ({ signal }) => fetchGoalSelections(api, signal), staleTime: 0 });
  const { refetch } = query;

  const writeRows = useCallback((update: (rows: readonly GoalSelection[]) => GoalSelection[]) => {
    client.setQueryData<GoalSelection[]>(goalSelectionQueryKey, (rows) => sortGoals(update(rows ?? []).filter(row => !confirmedDeleted.current.has(row.goalSelectionId))));
  }, [client]);

  const putRow = useCallback((goal: GoalSelection) => {
    confirmedDeleted.current.delete(goal.goalSelectionId);
    writeRows((rows) => [...rows.filter((row) => row.goalSelectionId !== goal.goalSelectionId), goal]);
  }, [writeRows]);

  const busy = useCallback((id: string, value: boolean) => {
    if (value) locks.current.add(id); else locks.current.delete(id);
    if (!value) recovery.current.delete(id);
    if (mounted.current) setBusyIds(new Set(locks.current));
    if (mounted.current) setNeedsRecovery(recovery.current.size > 0);
  }, []);

  const authenticationFailed = useCallback((error: unknown): boolean => {
    if (!(error instanceof ApiError) || !error.isUnauthenticated) return false;
    // AC1.1.9: 안내 토스트 뒤 01 로그인(다른 화면과 같은 goToLogin)
    if (mounted.current) goToLogin(router, toast);
    return true;
  }, [router, toast]);

  const dismissUndo = useCallback(() => {
    // Navigation or another notification can close undo, but cannot cancel a requested deletion.
    const previous = undoToastId.current;
    undoToastId.current = undefined;
    if (previous !== undefined) toast.dismiss(previous);
  }, [toast]);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      const previous = undoToastId.current;
      undoToastId.current = undefined;
      if (previous !== undefined) toast.dismiss(previous);
    };
  }, [toast]);

  const refresh = useCallback(() => {
    dismissUndo();
    if (locks.current.size === 0) {
      void client.cancelQueries({ queryKey: goalSelectionQueryKey }).then(() => {
        if (mounted.current && locks.current.size === 0) void refetch();
      });
    }
  }, [dismissUndo, client, refetch]);

  useEffect(() => {
    if (!query.isFetching) return;
    let noticeId: number | undefined;
    const timer = window.setTimeout(() => {
      noticeId = toast.show({ message: PRACTICE_TEMP_COPY.slowConnection, action: { label: PROPOSED_COPY.retry, onPress: refresh } });
    }, timing.parentLoadingNoticeDelayMs);
    return () => {
      window.clearTimeout(timer);
      if (noticeId !== undefined) toast.dismiss(noticeId);
    };
  }, [query.isFetching, refresh, timing.parentLoadingNoticeDelayMs, toast]);

  useEffect(() => {
    if (query.error === null || authenticationFailed(query.error)) return;
    toast.show({ message: PRACTICE_TEMP_COPY.loadFailed, kind: "error", action: { label: PROPOSED_COPY.retry, onPress: refresh } });
  }, [query.error, query.errorUpdatedAt, authenticationFailed, refresh, toast]);

  /** A reconciliation result cannot overwrite a different row's in-flight optimistic update. */
  const reconcileRows = useCallback((rows: readonly GoalSelection[], targetId: string) => {
    writeRows((current) => {
      const protectedRows = current.filter((row) => row.goalSelectionId !== targetId && locks.current.has(row.goalSelectionId));
      const protectedIds = new Set(protectedRows.map((row) => row.goalSelectionId));
      return [...rows.filter((row) => !protectedIds.has(row.goalSelectionId)), ...protectedRows];
    });
  }, [writeRows]);

  const recoverDeletion = useCallback(async function recover(goal: GoalSelection, undo = false): Promise<void> {
    const id = goal.goalSelectionId;
    if (recovering.current.has(id)) return;
    recovering.current.add(id);
    busy(id, true);
    try {
      const rows = await fetchGoalSelections(api);
      if (rows.some((row) => row.goalSelectionId === id)) {
        confirmedDeleted.current.delete(id);
        reconcileRows(rows, id);
      } else {
        try {
          const restored = await restoreGoal(api, id);
          confirmedDeleted.current.delete(id);
          reconcileRows([...rows, restored], id);
        } catch (error) {
          if (error instanceof ApiError && (error.status === 410 || error.status === 404)) {
            reconcileRows(await fetchGoalSelections(api), id);
            busy(id, false);
            if (mounted.current) toast.show({ message: PRACTICE_TEMP_COPY.restoreExpired, kind: "error" });
            return;
          }
          throw error;
        }
      }
      busy(id, false);
      if (mounted.current) toast.show(undo
        ? { message: PRACTICE_TEMP_COPY.restored(goal.displayName) }
        : { message: PRACTICE_TEMP_COPY.deleteFailed, kind: "error" });
    } catch (error) {
      if (authenticationFailed(error)) { busy(id, false); return; }
      // Keep a provisional row disabled until a later read proves whether compensation succeeded.
      putRow(goal);
      recovery.current.set(id, { goal, undo });
      if (mounted.current) setNeedsRecovery(true);
      if (mounted.current) toast.show({
        message: PRACTICE_TEMP_COPY.verifyFailed,
        kind: "error",
        action: { label: PROPOSED_COPY.retry, onPress: () => { void recover(goal, undo); } },
      });
    } finally {
      recovering.current.delete(id);
    }
  }, [api, authenticationFailed, busy, putRow, reconcileRows, toast]);

  const retryRecovery = useCallback(() => {
    for (const { goal, undo } of recovery.current.values()) void recoverDeletion(goal, undo);
  }, [recoverDeletion]);

  const undoDeletion = useCallback(async (goal: GoalSelection) => {
    const id = goal.goalSelectionId;
    if (locks.current.has(id)) return;
    busy(id, true);
    try {
      const restored = await restoreGoal(api, id);
      await client.cancelQueries({ queryKey: goalSelectionQueryKey });
      putRow(restored);
      busy(id, false);
      void client.invalidateQueries({ queryKey: goalSelectionQueryKey, refetchType: "none" });
      if (mounted.current) toast.show({ message: PRACTICE_TEMP_COPY.restored(goal.displayName) });
    } catch (error) {
      if (authenticationFailed(error)) { busy(id, false); return; }
      if (!(error instanceof ApiError) || error.isDeliveryUnknown || error.kind === "invalidResponse" || (error.status !== null && error.status >= 500)) {
        await recoverDeletion(goal, true);
      } else {
        busy(id, false);
        if (mounted.current) toast.show({
          message: error.status === 410 || error.status === 404 ? PRACTICE_TEMP_COPY.restoreUnavailable : PRACTICE_TEMP_COPY.restoreFailed,
          kind: "error",
        });
      }
    }
  }, [api, authenticationFailed, busy, client, putRow, recoverDeletion, toast]);

  const commitDeletion = useCallback(async (goal: GoalSelection) => {
    const id = goal.goalSelectionId;
    if (locks.current.has(id)) return;
    busy(id, true);
    if (mounted.current) setDeletingIds((ids) => new Set([...ids, id]));
    try {
      await client.cancelQueries({ queryKey: goalSelectionQueryKey });
      await deleteGoal(api, id);
      await client.cancelQueries({ queryKey: goalSelectionQueryKey });
      confirmedDeleted.current.add(id);
      writeRows((rows) => rows.filter((row) => row.goalSelectionId !== id));
      busy(id, false);
      // Re-entering the screen reads server truth. No refetch can overwrite concurrent writes.
      void client.invalidateQueries({ queryKey: goalSelectionQueryKey, refetchType: "none" });
      if (mounted.current) undoToastId.current = toast.show({
        message: PRACTICE_TEMP_COPY.deleted(goal.displayName),
        action: { label: PRACTICE_COPY.undo, onPress: () => { void undoDeletion(goal); } },
      });
    } catch (error) {
      putRow(goal);
      if (authenticationFailed(error)) { busy(id, false); return; }
      if (!(error instanceof ApiError) || error.isDeliveryUnknown || error.kind === "invalidResponse" || (error.status !== null && error.status >= 500)) {
        if (mounted.current) toast.show({ message: PRACTICE_TEMP_COPY.verifying });
        await recoverDeletion(goal);
      } else {
        busy(id, false);
        if (mounted.current) toast.show({ message: PRACTICE_TEMP_COPY.deleteFailed, kind: "error" });
      }
    } finally {
      if (mounted.current) setDeletingIds((ids) => { const next = new Set(ids); next.delete(id); return next; });
    }
  }, [api, authenticationFailed, busy, client, putRow, recoverDeletion, toast, undoDeletion, writeRows]);

  const scheduleDelete = useCallback((goal: GoalSelection) => {
    if (locks.current.has(goal.goalSelectionId)) return;
    dismissUndo();
    void commitDeletion(goal);
  }, [dismissUndo, commitDeletion]);

  const toggleFavorite = useCallback(async (goal: GoalSelection) => {
    const id = goal.goalSelectionId;
    if (locks.current.has(id)) return;
    const epoch = (favoriteEpoch.current.get(id) ?? 0) + 1;
    favoriteEpoch.current.set(id, epoch);
    busy(id, true);
    await client.cancelQueries({ queryKey: goalSelectionQueryKey });
    putRow({ ...goal, isFavorite: !goal.isFavorite });
    try {
      const saved = await updateGoalFavorite(api, id, !goal.isFavorite);
      putRow(saved);
      void client.invalidateQueries({ queryKey: goalSelectionQueryKey, refetchType: "none" });
    } catch (error) {
      putRow(goal);
      if (!authenticationFailed(error) && mounted.current) toast.show({
        message: PRACTICE_TEMP_COPY.favoriteFailed,
        kind: "error",
        action: { label: PROPOSED_COPY.retry, onPress: () => {
          if (mounted.current && favoriteEpoch.current.get(id) === epoch) void toggleFavorite(goal);
        } },
      });
    } finally {
      busy(id, false);
    }
  }, [api, authenticationFailed, busy, client, putRow, toast]);

  return { items: query.data, busyIds, deletingIds, needsRecovery, retryRecovery, isFetching: query.isFetching, hasError: query.isError, refresh, toggleFavorite, scheduleDelete, dismissUndo };
}
