"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { freeFailedRoute } from "../free/failure";
import { liveOutcome } from "./presentation";
import { ApiError } from "../../common/api/apiClient";
import { endSession, fetchHome, fetchLiveView, mergeLiveView, queryKeys, useApiClient } from "../../common/api/queries";
import { isTerminalStatus, type LiveView, type SessionStatus } from "../../common/api/types";
import type { TabKey } from "../../common/components/BottomTabBar";
import { useLivePolling } from "./useLivePolling";
import { PARENT_ROUTES, goToLogin, isUnauthenticated, routeForTab } from "../../common/navigation";
import { PROPOSED_COPY, TEMP_COPY } from "@/shared/copy/ko";
import { messageForErrorCode } from "@/shared/errors/codeToCopy";
import { useToast } from "@/shared/toast/ToastProvider";

// 12 진행 화면의 요청 · 폴링 · 오류 분기 · 화면 이동을 맡는 흐름 훅(BR2.1). 화면은 값과 핸들러만 쓴다.
// 동작 규칙(2초 폴링 · 401 · 404 · P1 끝내기 · 같은 requestId 재시도)은 LiveScreen에 있던 그대로다(BR1.1).

/** 연속 실패가 이 횟수가 되면 토스트를 보인다(PD-12). */
const POLL_FAILURE_TOAST_THRESHOLD = 3;

function keepPollingOnLive(status: SessionStatus): boolean {
  return status !== "CLOSING" && !isTerminalStatus(status);
}

export function useLiveScreen(sessionId: string) {
  const router = useRouter();
  const toast = useToast();
  const api = useApiClient();
  const queryClient = useQueryClient();
  const polling = useLivePolling(sessionId, { keepPolling: keepPollingOnLive });
  // 아이 이름은 04에서 받아 둔 홈 응답을 쓴다(12는 진행 조회 말고 따로 부르지 않는다).
  const homeCache = useQuery({ queryKey: queryKeys.home, queryFn: ({ signal }) => fetchHome(api, signal), enabled: false });
  const childName = homeCache.data?.child?.displayName ?? null;
  const [dialogOpen, setDialogOpen] = useState(false);
  const [ending, setEnding] = useState(false);
  const requestIdRef = useRef<string | null>(null);

  const view = polling.view;
  const status = view?.status;
  const { error, failureStreak, refetch } = polling;

  // 상태에 따른 화면 이동(functional-spec 4절 표 · J120, U4a: 연결 끊김 종료는 P2 → 17, 무응답은 P3 → 04, 서버 재시작 FAILED는 17 — U4a-J12 · J16).
  const outcome = view === undefined ? null : liveOutcome(view);
  const outcomeKind = outcome?.kind;
  const failedCode = outcome?.kind === "startFailed" ? outcome.failureCode : null;
  const isPractice = view?.activityType === "PRACTICE";
  useEffect(() => {
    if (outcomeKind === "startFailed") {
      router.replace(isPractice ? `${PARENT_ROUTES.practice}?failed=1&reason=${encodeURIComponent(failedCode ?? "DEVICE_NO_ACK")}` : freeFailedRoute(failedCode));
    } else if (outcomeKind === "done") {
      router.replace(PARENT_ROUTES.sessionDone(sessionId));
    }
  }, [router, sessionId, outcomeKind, failedCode, isPractice]);

  // 401 → 안내 토스트 뒤 01(AC1.1.9, 세션은 계속), 404(남의 · 없는 세션) → 홈.
  useEffect(() => {
    if (isUnauthenticated(error)) {
      goToLogin(router, toast);
    } else if (error instanceof ApiError && error.status === 404) {
      toast.show({ message: messageForErrorCode("NOT_FOUND"), kind: "error" });
      router.replace(PARENT_ROUTES.home);
    }
  }, [error, router, toast]);

  // 연속 3회 실패하면 한 번 알린다(PD-12, 폴링은 계속).
  useEffect(() => {
    if (failureStreak === POLL_FAILURE_TOAST_THRESHOLD) {
      toast.show({
        message: PROPOSED_COPY.live.pollingTrouble,
        kind: "error",
        action: { label: PROPOSED_COPY.retry, onPress: refetch },
      });
    }
  }, [failureStreak, refetch, toast]);

  const rereadAfterConflict = async () => {
    const previous = queryClient.getQueryData<LiveView>(queryKeys.session(sessionId));
    const fresh = await fetchLiveView(api, sessionId, previous?.nextSeq ?? 0);
    queryClient.setQueryData<LiveView>(queryKeys.session(sessionId), mergeLiveView(previous, fresh));
    // 이동은 위 효과(liveOutcome)가 한다. 아직 시작 중이면 머물며 알린다
    if (liveOutcome(fresh).kind === "stay") {
      toast.show({ message: TEMP_COPY.livePreparingEndToast });
    }
  };

  const confirmEnd = async () => {
    if (ending) {
      return;
    }
    // 같은 P1 안의 재시도는 같은 requestId(BR6.9 · RD-21).
    const requestId = requestIdRef.current ?? crypto.randomUUID();
    requestIdRef.current = requestId;
    setEnding(true);
    try {
      await endSession(api, sessionId, requestId);
      requestIdRef.current = null;
      setDialogOpen(false);
      router.push(PARENT_ROUTES.sessionDone(sessionId));
    } catch (failure) {
      setDialogOpen(false);
      if (failure instanceof ApiError && failure.status === 409 && failure.code === "SESSION_NOT_ACTIVE") {
        requestIdRef.current = null;
        try {
          await rereadAfterConflict();
        } catch {
          toast.show({ message: PROPOSED_COPY.endSendFailed, kind: "error" });
        }
        return;
      }
      if (failure instanceof ApiError && failure.status === 404) {
        toast.show({ message: messageForErrorCode("NOT_FOUND"), kind: "error" });
        router.replace(PARENT_ROUTES.home);
        return;
      }
      // 503 · 401 · 403 · 네트워크 · 시간 초과: 전송하지 못함(종료 요청됨으로 표시하지 않음, 세션은 그대로).
      toast.show({ message: PROPOSED_COPY.endSendFailed, kind: "error" });
      if (isUnauthenticated(failure)) {
        // '전송하지 못함' 토스트를 남긴 채 01로(AC1.1.9 · AC3.3.6)
        router.replace(PARENT_ROUTES.login);
      }
    } finally {
      setEnding(false);
    }
  };

  const handleNavigate = (tab: TabKey) => {
    // 진행 중에는 자유대화 · 연습 탭을 눌러도 12에 머문다(mockups S-12 TABS).
    if (tab === "home" || tab === "records") {
      router.push(routeForTab(tab));
    }
  };

  // 앱만 오프라인: 마지막 폴링이 응답 없음으로 실패(U4a-J17 · AC3.2.12). 마지막으로 받은 시각을 보인다
  const offline = error instanceof ApiError && error.isDeliveryUnknown && failureStreak > 0;
  // 종료 가능 상태는 WAITING_CHILD · ACTIVE(AC3.3.8 · C.S-12.11.5 참여 기다리는 중 그림의 「이야기 끝내기」 · U4b 3.2 표)
  const canEnd = (status === "ACTIVE" || status === "WAITING_CHILD") && !ending;

  return {
    view,
    status,
    error,
    childName,
    isPractice,
    outcomeKind,
    offline,
    canEnd,
    ending,
    dialogOpen,
    openEndDialog: () => setDialogOpen(true),
    closeEndDialog: () => setDialogOpen(false),
    confirmEnd: () => void confirmEnd(),
    handleNavigate,
    onLockedPress: (message: string) => toast.show({ message }),
    goToDone: () => router.replace(PARENT_ROUTES.sessionDone(sessionId)),
    goToHome: () => router.replace(PARENT_ROUTES.home),
  };
}
