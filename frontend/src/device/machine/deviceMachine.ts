import type { BootstrapResult, BootstrapStatusResult, ClaimResult, MeResult } from "../api/deviceApi";
import { actionForClose } from "@/shared/protocol/closeCodes";
import type { SessionView } from "@/shared/protocol/messages";

// 아이 기기 웹 상태 기계 (U1 3.5 Step 38 · PD-13 · BR9.1 ~ BR9.7 · BR3.7 · J56 · performance-design 2.9 상태 기계 표)
//
// 순수 함수(useReducer용)다. 요청 · 타이머 · 소켓 같은 부수 효과는 DeviceApp이 상태의 '할 일'(pendingEffect)을 보고 한다.
// 단계가 바뀔 때마다 stepId가 하나 늘고, 결과 · 타이머 사건은 자기 stepId가 지금과 같을 때만 받는다(늦은 응답 무시, 단일 비행).
//
// D0: meCheck → (200) T19 꺼짐 | (401) statusCheck
//     statusCheck → REGISTERED: claim | PENDING · EXPIRED · 401: bootstrap (코드가 이미 보이면 PENDING은 2초 뒤 다시 확인)
//     bootstrap → 201: 코드 표시 + 2초 뒤 status | 409: claim | 429 · 503 · 네트워크: '연결을 확인하는 중' + 5초 뒤 bootstrap
//     claim → 200: 「등록되었어요」 3초 → T19 꺼짐(J56) | 401: bootstrap | 410: meCheck | 409: 2초 뒤 status | 오류: 5초 뒤 claim
//     그 밖의 요청 실패는 마지막 화면을 두고 5초 뒤 같은 단계(BR9.4).
// T19: 꺼짐 →(켜기) 마이크 요청 →(허용) 연결 중 →(HELLO_OK) 켜짐(대기) / (거부) 마이크 허용 안 됨
//     닫기 4401 → meCheck(→ 401이면 D0), 4001 · 「끄기」 → 꺼짐, 그 밖(1001 · 1006 · 1011 · 1013 · 4400 · 4408) → 5초 뒤 재접속.
//     재접속 주기(reconnectWait 5초 대기 → reconnecting 접속 시도 → 실패면 다시 대기)는 내내 마지막 화면을 둔다(BR9.7):
//     이번 「켜기」 뒤 HELLO_OK를 받은 적이 있으면 표정 'reconnecting' · 마지막 view 라벨(J360), 한 번도 받지 못했으면 마지막 화면이
//     '연결 중'이므로 표정 'connecting' · 빈 라벨(BR9.4 · J367). reconnecting은 처음 연결(connecting)과 전이가 같고 화면만 다르다.
//     켜짐 안의 표시는 SESSION_STATE.view(대기 · 듣는 중 · 생각 중 · 마무리)이고 '말하는 중'은 실제 재생 중일 때만이다(BR9.6).

export type DelayKind = "retry" | "statusPoll" | "registeredDisplay";

export type D0RequestStep = "meCheck" | "statusCheck" | "bootstrap" | "claim";

export type D0Step =
  | { readonly name: D0RequestStep }
  | { readonly name: "waiting"; readonly next: D0RequestStep; readonly delay: DelayKind }
  | { readonly name: "registered" };

export type PowerState = "off" | "micRequest" | "micDenied" | "connecting" | "on" | "reconnectWait" | "reconnecting";

export interface CodeDisplay {
  readonly code: string;
  readonly expiresAt: string;
}

/** 디버그 표시용 최근 화면 이름(?debug=1, BR9.6). 마지막 값이 지금 화면이다. */
export type DebugTrail = readonly string[];

export type DeviceState =
  | {
      readonly screen: "d0";
      readonly step: D0Step;
      readonly code: CodeDisplay | null;
      readonly stepId: number;
      readonly trail: DebugTrail;
    }
  | {
      readonly screen: "t19";
      readonly power: PowerState;
      readonly view: SessionView;
      readonly speaking: boolean;
      /** 이번 「켜기」 뒤 HELLO_OK를 한 번이라도 받았는지. 재접속 주기의 '마지막 화면'을 가른다(J367). 꺼짐 · 「켜기」에서 처음으로 돌아간다. */
      readonly connectedOnce: boolean;
      readonly stepId: number;
      readonly trail: DebugTrail;
    };

export type DeviceEvent =
  | { readonly type: "ME_RESULT"; readonly stepId: number; readonly result: MeResult }
  | { readonly type: "STATUS_RESULT"; readonly stepId: number; readonly result: BootstrapStatusResult }
  | { readonly type: "BOOTSTRAP_RESULT"; readonly stepId: number; readonly result: BootstrapResult }
  | { readonly type: "CLAIM_RESULT"; readonly stepId: number; readonly result: ClaimResult }
  | { readonly type: "TIMER_FIRED"; readonly stepId: number }
  | { readonly type: "TURN_ON" }
  | { readonly type: "MIC_RESULT"; readonly granted: boolean }
  | { readonly type: "WS_READY"; readonly stepId: number }
  | { readonly type: "WS_CLOSED"; readonly code: number; readonly closedByDevice: boolean }
  | { readonly type: "SESSION_VIEW"; readonly view: SessionView }
  | { readonly type: "SPEAKING"; readonly speaking: boolean }
  | { readonly type: "TURN_OFF" };

/** 화면 이름(시험 data-state · 디버그 로그와 같은 값). */
export type DisplayState =
  | "d0-checking"
  | "d0-code"
  | "d0-registered"
  | "off"
  | "mic-request"
  | "mic-denied"
  | "connecting"
  | "standby"
  | "listening"
  | "thinking"
  | "speaking"
  | "closing"
  | "reconnecting";

const TRAIL_LIMIT = 20;

/** 접속 단계(설계 표의 WsConnect)인지. 처음 연결과 재접속 시도는 같은 전이를 한다(J360). */
function isConnectAttempt(power: PowerState): boolean {
  return power === "connecting" || power === "reconnecting";
}

/** 재접속 주기(5초 대기 · 접속 시도)인지. */
function isReconnectCycle(power: PowerState): boolean {
  return power === "reconnectWait" || power === "reconnecting";
}

/** 켜짐(연결을 쥔) 상태인지. 이 밖으로 나가면 마이크 · 재생 · 소켓을 정리한다. */
export function isPoweredOn(state: DeviceState): boolean {
  return state.screen === "t19" && (isConnectAttempt(state.power) || state.power === "on" || state.power === "reconnectWait");
}

/**
 * 라벨에 view(대기 · 듣는 중 · 생각 중 · 마무리)를 보이는 상태인지. 켜짐과, 한 번 연결됐던 기기의 재접속 주기(마지막 view 라벨, J360)다.
 * 처음 연결 중과 한 번도 연결되지 않은 기기의 재접속 주기는 마지막 화면이 '연결 중'이라 비운다(BR9.4 · J367).
 */
export function showsViewLabel(state: DeviceState): boolean {
  return state.screen === "t19" && (state.power === "on" || (isReconnectCycle(state.power) && state.connectedOnce));
}

function viewDisplay(view: SessionView, speaking: boolean): DisplayState {
  if (speaking) {
    return "speaking";
  }
  switch (view) {
    case "LISTENING":
      return "listening";
    case "THINKING":
      return "thinking";
    case "CLOSING":
      return "closing";
    default:
      return "standby";
  }
}

export function displayStateOf(state: DeviceState): DisplayState {
  if (state.screen === "d0") {
    if (state.step.name === "registered") {
      return "d0-registered";
    }
    return state.code === null ? "d0-checking" : "d0-code";
  }
  switch (state.power) {
    case "off":
      return "off";
    case "micRequest":
      return "mic-request";
    case "micDenied":
      return "mic-denied";
    case "connecting":
      return "connecting";
    case "reconnectWait":
    case "reconnecting":
      // 마지막 화면을 둔다: 한 번도 연결되지 않았으면 '연결 중'(BR9.4 · J367), 연결됐던 기기는 'reconnecting'(J360)
      return state.connectedOnce ? "reconnecting" : "connecting";
    default:
      return viewDisplay(state.view, state.speaking);
  }
}

function withTrail<S extends DeviceState>(next: S): S {
  const display = displayStateOf(next);
  const last = next.trail[next.trail.length - 1];
  if (last === display) {
    return next;
  }
  const trail = [...next.trail, display].slice(-TRAIL_LIMIT);
  return { ...next, trail };
}

export function initialDeviceState(): DeviceState {
  return withTrail({ screen: "d0", step: { name: "meCheck" }, code: null, stepId: 1, trail: [] });
}

function toD0(state: DeviceState, step: D0Step, code: CodeDisplay | null): DeviceState {
  return withTrail({ screen: "d0", step, code, stepId: state.stepId + 1, trail: state.trail });
}

function toPower(state: DeviceState, power: PowerState, view: SessionView = "STANDBY", connectedOnce = false): DeviceState {
  return withTrail({ screen: "t19", power, view, speaking: false, connectedOnce, stepId: state.stepId + 1, trail: state.trail });
}

function wait(state: DeviceState, next: D0RequestStep, delay: DelayKind, code: CodeDisplay | null): DeviceState {
  return toD0(state, { name: "waiting", next, delay }, code);
}

function onMeResult(state: DeviceState & { screen: "d0" }, result: MeResult): DeviceState {
  switch (result.kind) {
    case "registered":
      return toPower(state, "off");
    case "unregistered":
      return toD0(state, { name: "statusCheck" }, state.code);
    default:
      return wait(state, "meCheck", "retry", state.code);
  }
}

function onStatusResult(state: DeviceState & { screen: "d0" }, result: BootstrapStatusResult): DeviceState {
  switch (result.kind) {
    case "registered":
      return toD0(state, { name: "claim" }, state.code);
    case "pending":
      // 코드가 이미 보이면 2초 뒤 다시 확인하고, 아직 없으면 같은 코드를 받으러 간다(BR1.3).
      return state.code === null
        ? toD0(state, { name: "bootstrap" }, null)
        : wait(state, "statusCheck", "statusPoll", state.code);
    case "expired":
    case "notFound":
      // 만료 · 쿠키 없음이면 조용히 새 코드(BR1.4 · BR9.3).
      return toD0(state, { name: "bootstrap" }, null);
    default:
      return wait(state, "statusCheck", "retry", state.code);
  }
}

function onBootstrapResult(state: DeviceState & { screen: "d0" }, result: BootstrapResult): DeviceState {
  switch (result.kind) {
    case "issued":
      return wait(state, "statusCheck", "statusPoll", { code: result.code, expiresAt: result.expiresAt });
    case "alreadyRegistered":
      return toD0(state, { name: "claim" }, state.code);
    default:
      return wait(state, "bootstrap", "retry", null);
  }
}

function onClaimResult(state: DeviceState & { screen: "d0" }, result: ClaimResult): DeviceState {
  switch (result.kind) {
    case "claimed":
      return toD0(state, { name: "registered" }, state.code);
    case "notFound":
      return toD0(state, { name: "bootstrap" }, null);
    case "closed":
      return toD0(state, { name: "meCheck" }, state.code);
    case "notRegisteredYet":
      return wait(state, "statusCheck", "statusPoll", state.code);
    default:
      return wait(state, "claim", "retry", state.code);
  }
}

function onTimer(state: DeviceState): DeviceState {
  if (state.screen === "d0") {
    if (state.step.name === "waiting") {
      return toD0(state, { name: state.step.next }, state.code);
    }
    if (state.step.name === "registered") {
      return toPower(state, "off");
    }
    return state;
  }
  return state.power === "reconnectWait" ? toPower(state, "reconnecting", state.view, state.connectedOnce) : state;
}

function onWsClosed(state: DeviceState, code: number, closedByDevice: boolean): DeviceState {
  if (!isPoweredOn(state)) {
    return state;
  }
  switch (actionForClose(code, closedByDevice)) {
    case "OFF":
      return toPower(state, "off");
    case "RECHECK_ME":
      return toD0(state, { name: "meCheck" }, null);
    default:
      // 마지막 화면을 두고 5초 뒤 다시 접속한다(BR9.7). 연결된 적이 있는지도 그대로 둔다(J367).
      return state.screen === "t19" ? toPower(state, "reconnectWait", state.view, state.connectedOnce) : state;
  }
}

function d0Reducer(state: DeviceState & { screen: "d0" }, event: DeviceEvent): DeviceState {
  const current = "stepId" in event && event.stepId === state.stepId;
  switch (event.type) {
    case "ME_RESULT":
      return current && state.step.name === "meCheck" ? onMeResult(state, event.result) : state;
    case "STATUS_RESULT":
      return current && state.step.name === "statusCheck" ? onStatusResult(state, event.result) : state;
    case "BOOTSTRAP_RESULT":
      return current && state.step.name === "bootstrap" ? onBootstrapResult(state, event.result) : state;
    case "CLAIM_RESULT":
      return current && state.step.name === "claim" ? onClaimResult(state, event.result) : state;
    case "TIMER_FIRED":
      return current ? onTimer(state) : state;
    default:
      return state;
  }
}

function t19Reducer(state: DeviceState & { screen: "t19" }, event: DeviceEvent): DeviceState {
  switch (event.type) {
    case "TURN_ON":
      return state.power === "off" || state.power === "micDenied" ? toPower(state, "micRequest") : state;
    case "MIC_RESULT":
      if (state.power !== "micRequest") {
        return state;
      }
      return event.granted ? toPower(state, "connecting") : toPower(state, "micDenied");
    case "WS_READY":
      return event.stepId === state.stepId && isConnectAttempt(state.power) ? toPower(state, "on", "STANDBY", true) : state;
    case "WS_CLOSED":
      return onWsClosed(state, event.code, event.closedByDevice);
    case "TIMER_FIRED":
      return event.stepId === state.stepId ? onTimer(state) : state;
    case "SESSION_VIEW":
      return state.power === "on" && state.view !== event.view ? withTrail({ ...state, view: event.view }) : state;
    case "SPEAKING":
      return state.power === "on" && state.speaking !== event.speaking ? withTrail({ ...state, speaking: event.speaking }) : state;
    case "TURN_OFF":
      return state.power === "off" || state.power === "micDenied" ? state : toPower(state, "off");
    default:
      return state;
  }
}

export function deviceReducer(state: DeviceState, event: DeviceEvent): DeviceState {
  return state.screen === "d0" ? d0Reducer(state, event) : t19Reducer(state, event);
}

/** 상태가 바라는 부수 효과(DeviceApp이 한다). */
export type PendingEffect =
  | { readonly kind: "request"; readonly step: D0RequestStep }
  | { readonly kind: "delay"; readonly delay: DelayKind }
  | { readonly kind: "connect" }
  | { readonly kind: "none" };

export function pendingEffect(state: DeviceState): PendingEffect {
  if (state.screen === "d0") {
    switch (state.step.name) {
      case "waiting":
        return { kind: "delay", delay: state.step.delay };
      case "registered":
        return { kind: "delay", delay: "registeredDisplay" };
      default:
        return { kind: "request", step: state.step.name };
    }
  }
  if (isConnectAttempt(state.power)) {
    return { kind: "connect" };
  }
  if (state.power === "reconnectWait") {
    return { kind: "delay", delay: "retry" };
  }
  return { kind: "none" };
}
