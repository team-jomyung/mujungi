"use client";

import { useRouter } from "next/navigation";
import { useCallback, useEffect, useId, useRef, useState } from "react";
import { ApiError } from "../api/apiClient";
import { deletePushSubscription, fetchPushConfig, putPushSubscription, type PushConfig } from "../api/push";
import { useApiClient } from "../api/queries";
import { goToLogin, isUnauthenticated } from "../navigation";
import { browserPush, type BrowserSubscription } from "../push/browserPush";
import { PROPOSED_COPY, PUSH_TEMP_COPY, TEMP_COPY } from "@/shared/copy/ko";
import { useTiming } from "@/shared/timing/TimingProvider";
import { useToast } from "@/shared/toast/ToastProvider";

// U7 SET0: 조회는 등록하지 않는다. 서버 PUT 성공과 브라우저 구독 존재를 구별한다.
// 해제는 endpoint DELETE 다음 unsubscribe. 서버 성공/브라우저 실패는 꺼짐을 유지하고 정리를 재시도한다.
interface PushState {
  readonly config: PushConfig | null;
  readonly subscription: BrowserSubscription | null;
  readonly registered: boolean;
  readonly loaded: boolean;
  readonly permission: NotificationPermission;
  readonly notice: string | null;
  readonly conflict: boolean;
}

export function PushSettingsRow() {
  const api = useApiClient();
  const router = useRouter();
  const toast = useToast();
  const timing = useTiming();
  const descriptionId = useId();
  const [supported] = useState(() => browserPush.supported());
  const [state, setState] = useState<PushState>(() => ({
    config: null, subscription: null, registered: false, loaded: false,
    permission: browserPush.permission(), notice: null, conflict: false,
  }));
  const [busy, setBusy] = useState(false);
  const locked = useRef(false);
  const mounted = useRef(false);
  const loadRevision = useRef(0);
  const subscriptionRevision = useRef(0);
  const primaryButton = useRef<HTMLButtonElement>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const restoreFocus = useRef(false);

  const load = useCallback(async (signal?: AbortSignal) => {
    if (!supported || signal?.aborted) return;
    const revision = ++loadRevision.current;
    const subscriptionAtStart = subscriptionRevision.current;
    // 두 조회를 독립시킨다: 키 조회가 실패하거나 꺼져 있어도 기존 endpoint를 해제할 수 있다.
    const [config, subscription] = await Promise.allSettled([fetchPushConfig(api, signal), browserPush.getSubscription()]);
    if (!mounted.current || signal?.aborted || revision !== loadRevision.current) return;
    const failure = config.status === "rejected" ? config.reason : subscription.status === "rejected" ? subscription.reason : null;
    if (isUnauthenticated(failure)) {
      goToLogin(router, toast);
      return;
    }
    setState((previous) => {
      const nextConfig = config.status === "fulfilled" ? config.value : null;
      // 키 조회는 남기되, 그사이 켜거나 끈 구독을 오래된 조회로 덮지 않는다.
      if (subscriptionAtStart !== subscriptionRevision.current) return { ...previous, config: nextConfig };
      return {
        config: nextConfig,
        subscription: subscription.status === "fulfilled" ? subscription.value : null,
        registered: false, loaded: true, permission: browserPush.permission(), conflict: false,
        notice: failure === null ? null : PUSH_TEMP_COPY.loadFailed,
      };
    });
  }, [api, router, supported, toast]);

  useEffect(() => {
    mounted.current = true;
    const controller = new AbortController();
    void Promise.resolve().then(() => load(controller.signal));
    return () => { mounted.current = false; controller.abort(); };
  }, [load]);

  useEffect(() => {
    if (restoreFocus.current && !busy && state.subscription === null) {
      restoreFocus.current = false;
      if (primaryButton.current?.disabled) heading.current?.focus();
      else primaryButton.current?.focus();
    }
  }, [busy, state.subscription]);

  const start = () => {
    if (locked.current) return false;
    locked.current = true;
    subscriptionRevision.current++;
    setBusy(true);
    return true;
  };
  const finish = () => {
    locked.current = false;
    if (mounted.current) setBusy(false);
  };
  const setFailure = (failure: unknown, message: string) => {
    if (!mounted.current) return;
    if (isUnauthenticated(failure)) {
      goToLogin(router, toast);
      return;
    }
    const conflict = failure instanceof ApiError && failure.code === "PUSH_SUBSCRIPTION_CONFLICT";
    setState((previous) => ({
      ...previous, conflict, notice: conflict ? TEMP_COPY.errors.PUSH_SUBSCRIPTION_CONFLICT : message,
    }));
  };

  const enable = async (replace: boolean) => {
    const key = state.config?.enabled ? state.config.publicKey : null;
    if (key === null || !start()) return;
    try {
      // 사용자 조작과 같은 호출 스택에서 요청한다(키는 시트 조회 때 이미 받았다).
      const permission = browserPush.permission() === "granted" ? "granted" : await browserPush.requestPermission();
      if (!mounted.current) return;
      setState((previous) => ({ ...previous, permission, notice: null }));
      if (permission !== "granted") {
        setState((previous) => ({ ...previous, notice: permission === "denied" ? PUSH_TEMP_COPY.denied : PUSH_TEMP_COPY.dismissed }));
        return;
      }
      let subscription = state.subscription;
      if (replace && subscription !== null) {
        await subscription.unsubscribe();
        subscription = null;
        if (mounted.current) setState((previous) => ({ ...previous, subscription: null, registered: false }));
      }
      if (subscription === null) subscription = await browserPush.subscribe(key, timing.parentRequestTimeoutMs);
      if (!mounted.current) return;
      setState((previous) => ({ ...previous, subscription }));
      const keys = subscription.toJSON().keys;
      if (!keys?.p256dh || !keys.auth) throw new Error("PUSH_KEYS_MISSING");
      await putPushSubscription(api, { endpoint: subscription.endpoint, keys: { p256dh: keys.p256dh, auth: keys.auth }, platform: "WEB" });
      if (mounted.current) setState((previous) => ({ ...previous, registered: true, conflict: false, notice: null }));
    } catch (failure) {
      setFailure(failure, PUSH_TEMP_COPY.enableFailed);
    } finally {
      finish();
    }
  };

  const disable = async () => {
    const subscription = state.subscription;
    if (subscription === null || !start()) return;
    restoreFocus.current = document.activeElement?.getAttribute("data-testid") === "push-disable";
    let serverRevoked = false;
    try {
      await deletePushSubscription(api, subscription.endpoint);
      serverRevoked = true;
      if (mounted.current) setState((previous) => ({ ...previous, registered: false, conflict: false, notice: null }));
      // 시작한 해제는 시트가 닫혀도 마친다. endpoint를 잃기 전에 서버 해제를 확인한다.
      await subscription.unsubscribe();
      if (mounted.current) setState((previous) => ({ ...previous, subscription: null }));
    } catch (failure) {
      setFailure(failure, serverRevoked ? PUSH_TEMP_COPY.cleanupFailed : PUSH_TEMP_COPY.disableFailed);
    } finally {
      finish();
    }
  };

  const notice = !supported ? PUSH_TEMP_COPY.unsupported
    : state.notice ?? (!state.loaded ? PUSH_TEMP_COPY.checking
      : state.config?.enabled === false ? PUSH_TEMP_COPY.unavailable
        : state.permission === "denied" ? PUSH_TEMP_COPY.denied
          : state.registered ? PUSH_TEMP_COPY.on
            : state.subscription !== null ? PUSH_TEMP_COPY.unverified : PUSH_TEMP_COPY.off);
  const canEnable = supported && state.loaded && state.config?.enabled === true && state.permission !== "denied";

  return (
    <section className="mj-push-settings" aria-busy={busy} aria-labelledby={descriptionId + "-heading"}>
      <h3 className="mj-card__heading" id={descriptionId + "-heading"} ref={heading} tabIndex={-1}>{PUSH_TEMP_COPY.heading}</h3>
      <p id={descriptionId} role="status" aria-atomic="true" data-testid="push-status"
        data-state={state.registered ? "on" : state.subscription === null ? "off" : "unverified"} className="mj-push-settings__status">
        {notice}
      </p>
      <div className="mj-push-settings__actions">
        <button ref={primaryButton} type="button" className="mj-button mj-button--secondary"
          data-testid={state.registered ? "push-disable" : state.conflict ? "push-reregister" : "push-enable"}
          aria-describedby={descriptionId} disabled={busy || (!state.registered && !canEnable)}
          onClick={() => void (state.registered ? disable() : enable(state.conflict))}>
          {state.registered ? PUSH_TEMP_COPY.disable : state.conflict ? PUSH_TEMP_COPY.reregister : PUSH_TEMP_COPY.enable}
        </button>
        {state.subscription !== null && !state.registered ? (
          <button type="button" className="mj-button mj-button--secondary" data-testid="push-disable"
            aria-describedby={descriptionId} disabled={busy} onClick={() => void disable()}>
            {PUSH_TEMP_COPY.disable}
          </button>
        ) : null}
        {supported && state.loaded && state.notice === PUSH_TEMP_COPY.loadFailed ? (
          <button type="button" className="mj-link-button" data-testid="push-retry-config" disabled={busy}
            onClick={() => void load()}>{PROPOSED_COPY.retry}</button>
        ) : null}
      </div>
    </section>
  );
}
