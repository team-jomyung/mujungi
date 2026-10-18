"use client";

import { useQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useEffect, useSyncExternalStore } from "react";
import { useApiClient } from "../api/queries";
import { parseLiveView } from "../api/types";
import { PARENT_ROUTES, goToLogin, isUnauthenticated } from "../navigation";
import { useToast } from "@/shared/toast/ToastProvider";

// U7 AC1.7.4: payload 상태를 믿지 않고 해당 세션의 최신 상태를 읽는다.
// 서버 렌더에서는 일반 홈으로 시작하며 hydration 뒤 URL 외부 저장소를 읽는다.
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const LIVE = ["REQUESTED", "PREPARING", "DEVICE_ACK", "WAITING_CHILD", "ACTIVE"];
const DONE = ["CLOSING", "ENDED", "ENDED_UNCONFIRMED", "ABORTED", "DECLINED", "FAILED"];

function subscribe(onChange: () => void): () => void {
  window.addEventListener("popstate", onChange);
  return () => window.removeEventListener("popstate", onChange);
}

function currentId(): string | null {
  const values = new URLSearchParams(window.location.search).getAll("notificationSessionId");
  return values.length === 0 ? null : values.length === 1 && UUID.test(values[0]) ? values[0].toLowerCase() : "";
}

export function useNotificationEntry(allowed: boolean, checkingAccount: boolean) {
  const id = useSyncExternalStore(subscribe, currentId, () => null);
  const api = useApiClient();
  const router = useRouter();
  const toast = useToast();
  const query = useQuery({
    queryKey: ["notification-entry", id],
    enabled: allowed && id !== null && id !== "",
    staleTime: 0,
    refetchOnMount: "always",
    queryFn: ({ signal }) => api.request({
      method: "GET", path: "/api/sessions/" + encodeURIComponent(id ?? ""), csrf: false, parse: parseLiveView, signal,
    }),
  });
  const status = query.data?.status;
  const matching = query.data?.sessionId === id;
  const target = matching && status !== undefined
    ? LIVE.includes(status) ? PARENT_ROUTES.session(id!) : DONE.includes(status) ? PARENT_ROUTES.sessionDone(id!) : null
    : null;
  const fresh = query.isFetchedAfterMount && !query.isFetching;
  const pending = id !== null && id !== "" && (checkingAccount || (allowed && !fresh));

  useEffect(() => {
    if (!allowed || !fresh || id === null) return;
    if (isUnauthenticated(query.error)) goToLogin(router, toast);
    else if (query.error === null && target !== null) router.replace(target);
  }, [allowed, fresh, id, query.error, router, target, toast]);

  return {
    active: id !== null,
    pending,
    unknown: id === "" || (id !== null && !pending && (!allowed || query.error !== null || target === null)),
    lastCheckedAt: query.dataUpdatedAt,
    retry: () => { if (allowed && id !== null && id !== "") void query.refetch(); },
  };
}
