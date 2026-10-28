"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useHomeQuery } from "../api/home";
import { isInProgressStatus } from "../api/activity";
import type { TabKey } from "../components/BottomTabBar";
import { goToLogin, isUnauthenticated, lockToastMessage, PARENT_ROUTES, routeForTab } from "../navigation";
import { PROPOSED_COPY, TEMP_COPY } from "@/shared/copy/ko";
import { useToast } from "@/shared/toast/ToastProvider";

/** Preparation and records screens share the existing home SSE invalidation and server verdict. */
export function useActivityEntry() {
  const home = useHomeQuery(true);
  const router = useRouter();
  const toast = useToast();
  const { data, error } = home;
  useEffect(() => {
    if (isUnauthenticated(error)) goToLogin(router, toast);
  }, [error, router, toast]);

  const session = data?.currentSession ?? null;
  const activeTarget = error === null && session !== null && isInProgressStatus(session.status)
    ? session.status === "CLOSING" ? PARENT_ROUTES.sessionDone(session.sessionId) : PARENT_ROUTES.session(session.sessionId)
    : null;
  const lockMessage = data === undefined || error !== null
    ? TEMP_COPY.lockToastStatusUnknown
    : activeTarget !== null ? null
      : data.lock.locked ? lockToastMessage(data.lock.reasonCode)
        : !data.device.verdict.canStart ? lockToastMessage(data.device.verdict.reasonCode) : null;
  const canStart = lockMessage === null && activeTarget === null;
  const startMessage = activeTarget !== null ? PROPOSED_COPY.free.rejectSessionInProgress : lockMessage;
  const onLockedPress = (message: string) => {
    toast.show({ message });
    router.push(PARENT_ROUTES.device);
  };
  const navigate = (tab: TabKey) => {
    if (tab === "free" || tab === "practice") {
      if (activeTarget !== null) { router.push(activeTarget); return; }
      if (lockMessage !== null) { onLockedPress(lockMessage); return; }
    }
    router.push(routeForTab(tab));
  };
  return { canStart, startMessage, lockMessage, navigate, onLockedPress };
}
