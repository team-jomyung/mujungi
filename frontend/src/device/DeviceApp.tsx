"use client";

import { useCallback, useEffect, useReducer, useRef, useState } from "react";
import { createDeviceApi, type DeviceApi } from "./api/deviceApi";
import { createAudioContextInGesture } from "./audio/audioContext";
import { D0View, type D0Display } from "./components/D0View";
import { DebugLog } from "./components/DebugLog";
import { T19View, type T19Display } from "./components/T19View";
import {
  deviceReducer,
  displayStateOf,
  initialDeviceState,
  isPoweredOn,
  pendingEffect,
  type DelayKind,
  type DeviceEvent,
  type DeviceState,
  type PendingEffect,
} from "./machine/deviceMachine";
import type { DeviceRuntime } from "./runtime/deviceRuntime";
import { COPY } from "@/shared/copy/ko";
import type { Timing } from "@/shared/timing/timing";
import { useTiming } from "@/shared/timing/TimingProvider";

// /device 아이 기기 웹 (U1 3.5 Step 38 · BR9.1 ~ BR9.7 · PD-13 · PD-14 · PD-16 · NFR10.4)
// 상태 기계(deviceMachine)를 useReducer로 돌리고, 상태가 바라는 부수 효과(요청 · 대기 · 접속)를 여기서 한다.
// 마이크 · 오디오 · WSS 코드는 「켜기」 클릭 뒤 동적 import로 적재하고(꺼짐 화면에서는 미리 받기만), getUserMedia는
// 클릭 핸들러가 부른 micPermission.ts에서만 부른다(클릭 전 0건, PD-16). 쿠키 · 토큰은 JS가 보지 않는다(BR9.1).

type EffectKey = `request:${string}` | `delay:${DelayKind}` | "connect" | "none";

function effectKeyOf(effect: PendingEffect): EffectKey {
  switch (effect.kind) {
    case "request":
      return `request:${effect.step}`;
    case "delay":
      return `delay:${effect.delay}`;
    default:
      return effect.kind;
  }
}

function delayMs(delay: DelayKind, timing: Timing): number {
  switch (delay) {
    case "statusPoll":
      return timing.bootstrapStatusPollMs;
    case "registeredDisplay":
      return timing.deviceWebRegisteredDisplayMs;
    default:
      return timing.deviceWebRetryIntervalMs;
  }
}

async function runRequest(api: DeviceApi, step: string, stepId: number): Promise<DeviceEvent | null> {
  switch (step) {
    case "meCheck":
      return { type: "ME_RESULT", stepId, result: await api.me() };
    case "statusCheck":
      return { type: "STATUS_RESULT", stepId, result: await api.status() };
    case "bootstrap":
      return { type: "BOOTSTRAP_RESULT", stepId, result: await api.bootstrap() };
    case "claim":
      return { type: "CLAIM_RESULT", stepId, result: await api.claim() };
    default:
      return null;
  }
}

function t19Label(state: DeviceState): string | null {
  if (state.screen !== "t19" || (state.power !== "on" && state.power !== "reconnectWait")) {
    return null;
  }
  if (state.power === "on" && state.speaking) {
    return COPY.device.t19Speaking;
  }
  switch (state.view) {
    case "LISTENING":
      return COPY.device.t19Listening;
    case "THINKING":
      return COPY.device.t19Thinking;
    case "CLOSING":
      return COPY.device.t19Closing;
    default:
      return COPY.device.t19Standby;
  }
}

/** 실행부 보관함. 컴포넌트 밖 함수로 다뤄 정리(cleanup)에서 최신 값을 읽는다. */
interface RuntimeHolder {
  current: DeviceRuntime | null;
}

function releaseRuntime(holder: RuntimeHolder, closeSocket: boolean): void {
  const runtime = holder.current;
  holder.current = null;
  runtime?.shutdown(closeSocket);
}

function prefetchOnIdle(): () => void {
  const load = () => {
    void import("./runtime/deviceRuntime");
    void import("./mic/micPermission");
  };
  if (typeof window.requestIdleCallback === "function") {
    const handle = window.requestIdleCallback(load);
    return () => window.cancelIdleCallback(handle);
  }
  const handle = window.setTimeout(load);
  return () => window.clearTimeout(handle);
}

export interface DeviceAppProps {
  readonly debug: boolean;
  /** 시험 대역 주입용. 기본은 같은 origin의 /api/device/*. */
  readonly api?: DeviceApi;
}

export function DeviceApp({ debug, api: injectedApi }: DeviceAppProps) {
  const timing = useTiming();
  const [state, dispatch] = useReducer(deviceReducer, undefined, initialDeviceState);
  const [api] = useState<DeviceApi>(() => injectedApi ?? createDeviceApi());
  const runtimeRef = useRef<DeviceRuntime | null>(null);
  const connectStepRef = useRef(0);
  const turnOnBusyRef = useRef(false);

  const stepId = state.stepId;
  const effectKey = effectKeyOf(pendingEffect(state));
  const poweredOn = isPoweredOn(state);
  const display = displayStateOf(state);

  // 상태가 바라는 요청 · 대기 · 접속을 한다. 단계(stepId)가 바뀌면 앞 단계의 대기를 지우고 늦은 결과는 버린다.
  useEffect(() => {
    let cancelled = false;
    let timer: number | undefined;
    if (effectKey.startsWith("request:")) {
      const step = effectKey.slice("request:".length);
      void runRequest(api, step, stepId).then((event) => {
        if (!cancelled && event !== null) {
          dispatch(event);
        }
      });
    } else if (effectKey.startsWith("delay:")) {
      const delay = effectKey.slice("delay:".length) as DelayKind;
      timer = window.setTimeout(() => dispatch({ type: "TIMER_FIRED", stepId }), delayMs(delay, timing));
    } else if (effectKey === "connect") {
      connectStepRef.current = stepId;
      const runtime = runtimeRef.current;
      if (runtime === null) {
        // 실행부 없이 접속 단계에 올 수 없다. 꺼짐으로 되돌린다.
        timer = window.setTimeout(() => dispatch({ type: "TURN_OFF" }));
      } else {
        runtime.connect();
      }
    }
    return () => {
      cancelled = true;
      if (timer !== undefined) {
        window.clearTimeout(timer);
      }
    };
  }, [api, effectKey, stepId, timing]);

  // 켜짐을 벗어나면(4001 · 4401 · 꺼짐) 마이크 · 재생 · 화면 켜짐 유지를 놓는다.
  useEffect(() => {
    if (!poweredOn) {
      releaseRuntime(runtimeRef, false);
    }
  }, [poweredOn]);

  // 화면을 떠나면 모두 정리한다.
  useEffect(() => () => releaseRuntime(runtimeRef, true), []);

  // 꺼짐 화면이 보이면 유휴 시간에 지연 청크를 받아 둔다(실행은 클릭 뒤, PD-14).
  useEffect(() => {
    if (display !== "off") {
      return undefined;
    }
    return prefetchOnIdle();
  }, [display]);

  const handleTurnOn = useCallback(() => {
    if (turnOnBusyRef.current) {
      return;
    }
    turnOnBusyRef.current = true;
    // 사용자 동작 안에서 AudioContext를 만들고 재개한다(J121 · 자동 재생 정책).
    const audioContext = createAudioContextInGesture();
    void audioContext?.resume().catch(() => undefined);
    dispatch({ type: "TURN_ON" });
    void (async () => {
      try {
        const [{ requestMicrophone }, { startDeviceRuntime }] = await Promise.all([
          import("./mic/micPermission"),
          import("./runtime/deviceRuntime"),
        ]);
        const mic = await requestMicrophone();
        if (!mic.granted) {
          await audioContext?.close().catch(() => undefined);
          dispatch({ type: "MIC_RESULT", granted: false });
          return;
        }
        const runtime = await startDeviceRuntime({
          audioContext,
          stream: mic.stream,
          timing,
          nowMs: () => Date.now(),
          events: {
            onReady: () => dispatch({ type: "WS_READY", stepId: connectStepRef.current }),
            onClosed: (code, closedByDevice) => dispatch({ type: "WS_CLOSED", code, closedByDevice }),
            onView: (view) => dispatch({ type: "SESSION_VIEW", view }),
            onSpeaking: (speaking) => dispatch({ type: "SPEAKING", speaking }),
          },
        });
        releaseRuntime(runtimeRef, false);
        runtimeRef.current = runtime;
        dispatch({ type: "MIC_RESULT", granted: true });
      } catch {
        await audioContext?.close().catch(() => undefined);
        dispatch({ type: "MIC_RESULT", granted: false });
      } finally {
        turnOnBusyRef.current = false;
      }
    })();
  }, [timing]);

  const handleTurnOff = useCallback(() => {
    releaseRuntime(runtimeRef, true);
    dispatch({ type: "TURN_OFF" });
  }, []);

  return (
    <main className="mj-device" data-testid="device-app">
      {state.screen === "d0" ? (
        <D0View display={display as D0Display} code={state.code?.code ?? null} />
      ) : (
        <T19View display={display as T19Display} label={t19Label(state)} onTurnOn={handleTurnOn} onTurnOff={handleTurnOff} />
      )}
      {debug ? <DebugLog trail={state.trail} /> : null}
    </main>
  );
}
