import { describe, expect, it } from "vitest";
import {
  deviceReducer,
  displayStateOf,
  initialDeviceState,
  isPoweredOn,
  pendingEffect,
  type DeviceEvent,
  type DeviceState,
} from "@/device/machine/deviceMachine";
import { DEVICE_ID } from "../support/fixtures";

// 아이 기기 웹 상태 기계 시험 (U1 3.5 Step 41 · BR9.1 모든 간선 · J56 · 편차 (a)(b) · BR9.3 · BR9.4 · BR9.7 · PD-13 · BR3.7)
// 상태 기계는 순수 함수이고 결과 · 타이머 사건은 자기 stepId일 때만 받는다(단일 비행 · 늦은 응답 무시).

const CODE = "7F3K92QD";

/** 지금 단계에 맞는 결과 사건을 넣는다. */
function step(state: DeviceState, make: (stepId: number) => DeviceEvent): DeviceState {
  return deviceReducer(state, make(state.stepId));
}

function run(state: DeviceState, ...events: DeviceEvent[]): DeviceState {
  return events.reduce(deviceReducer, state);
}

const me = (result: Extract<DeviceEvent, { type: "ME_RESULT" }>["result"]) => (stepId: number): DeviceEvent => ({ type: "ME_RESULT", stepId, result });
const status = (result: Extract<DeviceEvent, { type: "STATUS_RESULT" }>["result"]) => (stepId: number): DeviceEvent => ({
  type: "STATUS_RESULT",
  stepId,
  result,
});
const bootstrap = (result: Extract<DeviceEvent, { type: "BOOTSTRAP_RESULT" }>["result"]) => (stepId: number): DeviceEvent => ({
  type: "BOOTSTRAP_RESULT",
  stepId,
  result,
});
const claim = (result: Extract<DeviceEvent, { type: "CLAIM_RESULT" }>["result"]) => (stepId: number): DeviceEvent => ({
  type: "CLAIM_RESULT",
  stepId,
  result,
});
const timer = (stepId: number): DeviceEvent => ({ type: "TIMER_FIRED", stepId });

/** D0을 지나 코드가 보이는 상태. */
function withCode(): DeviceState {
  let state = initialDeviceState();
  state = step(state, me({ kind: "unregistered" }));
  state = step(state, status({ kind: "notFound" }));
  return step(state, bootstrap({ kind: "issued", code: CODE, expiresAt: "2026-10-16T01:00:00Z" }));
}

/** T19 켜짐(대기). */
function poweredOn(): DeviceState {
  let state = step(initialDeviceState(), me({ kind: "registered", deviceId: DEVICE_ID }));
  state = run(state, { type: "TURN_ON" }, { type: "MIC_RESULT", granted: true });
  return step(state, (stepId) => ({ type: "WS_READY", stepId }));
}

describe("D0 — 시작 순서 me → status → bootstrap(BR9.1)", () => {
  it("처음에는 /device/me를 묻고 '연결을 확인하는 중'이다", () => {
    const state = initialDeviceState();
    expect(pendingEffect(state)).toEqual({ kind: "request", step: "meCheck" });
    expect(displayStateOf(state)).toBe("d0-checking");
    expect(state.trail).toEqual(["d0-checking"]);
  });

  it("① me 200이면 T19 꺼짐으로 간다(J56)", () => {
    const state = step(initialDeviceState(), me({ kind: "registered", deviceId: DEVICE_ID }));
    expect(state.screen).toBe("t19");
    expect(displayStateOf(state)).toBe("off");
    expect(pendingEffect(state)).toEqual({ kind: "none" });
  });

  it("정상 경로: 401 → status 401(쿠키 없음, BR9.3) → 새 코드 → 2초 폴링 → REGISTERED → claim → 「등록되었어요」 3초 → T19 꺼짐", () => {
    let state = step(initialDeviceState(), me({ kind: "unregistered" }));
    expect(pendingEffect(state)).toEqual({ kind: "request", step: "statusCheck" });
    state = step(state, status({ kind: "notFound" }));
    expect(pendingEffect(state)).toEqual({ kind: "request", step: "bootstrap" });
    state = step(state, bootstrap({ kind: "issued", code: CODE, expiresAt: "2026-10-16T01:00:00Z" }));
    expect(displayStateOf(state)).toBe("d0-code");
    expect(state.screen === "d0" && state.code?.code).toBe(CODE);
    expect(pendingEffect(state)).toEqual({ kind: "delay", delay: "statusPoll" });
    state = step(state, timer);
    expect(pendingEffect(state)).toEqual({ kind: "request", step: "statusCheck" });
    state = step(state, status({ kind: "pending" }));
    expect(pendingEffect(state)).toEqual({ kind: "delay", delay: "statusPoll" });
    expect(displayStateOf(state)).toBe("d0-code");
    state = step(step(state, timer), status({ kind: "registered" }));
    expect(pendingEffect(state)).toEqual({ kind: "request", step: "claim" });
    state = step(state, claim({ kind: "claimed", deviceId: DEVICE_ID }));
    expect(displayStateOf(state)).toBe("d0-registered");
    expect(pendingEffect(state)).toEqual({ kind: "delay", delay: "registeredDisplay" });
    state = step(state, timer);
    expect(displayStateOf(state)).toBe("off");
    expect(state.trail).toEqual(["d0-checking", "d0-code", "d0-registered", "off"]);
  });

  it("③ 코드가 아직 없는데 PENDING이면 bootstrap으로 같은 코드를 다시 받는다(BR1.3 · AC6.1.5)", () => {
    let state = step(initialDeviceState(), me({ kind: "unregistered" }));
    state = step(state, status({ kind: "pending" }));
    expect(pendingEffect(state)).toEqual({ kind: "request", step: "bootstrap" });
  });

  it("④ 만료(EXPIRED)면 보이던 코드를 지우고 조용히 새 코드를 받는다(BR1.4)", () => {
    let state = step(withCode(), timer);
    state = step(state, status({ kind: "expired" }));
    expect(pendingEffect(state)).toEqual({ kind: "request", step: "bootstrap" });
    expect(state.screen === "d0" && state.code).toBeNull();
  });

  it("(a) bootstrap 409 ALREADY_REGISTERED면 claim으로 간다", () => {
    let state = step(initialDeviceState(), me({ kind: "unregistered" }));
    state = step(state, status({ kind: "notFound" }));
    state = step(state, bootstrap({ kind: "alreadyRegistered" }));
    expect(pendingEffect(state)).toEqual({ kind: "request", step: "claim" });
  });

  it("(b) claim 401 BOOTSTRAP_NOT_FOUND면 코드를 지우고 새 코드를 받는다", () => {
    let state = step(step(withCode(), timer), status({ kind: "registered" }));
    state = step(state, claim({ kind: "notFound" }));
    expect(pendingEffect(state)).toEqual({ kind: "request", step: "bootstrap" });
    expect(displayStateOf(state)).toBe("d0-checking");
  });

  it("claim 410이면 /device/me로 확인하고 claim 409면 2초 뒤 status를 다시 묻는다", () => {
    const registered = step(step(withCode(), timer), status({ kind: "registered" }));
    const closed = step(registered, claim({ kind: "closed" }));
    expect(pendingEffect(closed)).toEqual({ kind: "request", step: "meCheck" });
    expect(displayStateOf(step(closed, me({ kind: "registered", deviceId: DEVICE_ID })))).toBe("off");
    const notYet = step(registered, claim({ kind: "notRegisteredYet" }));
    expect(pendingEffect(notYet)).toEqual({ kind: "delay", delay: "statusPoll" });
    expect(pendingEffect(step(notYet, timer))).toEqual({ kind: "request", step: "statusCheck" });
  });
});

describe("D0 — 실패는 화면을 두고 5초 뒤 같은 요청(BR9.4)", () => {
  it("코드를 받기 전 bootstrap 실패(429 · 503 · 네트워크)는 '연결을 확인하는 중' + 5초 뒤 bootstrap", () => {
    let state = step(initialDeviceState(), me({ kind: "unregistered" }));
    state = step(state, status({ kind: "notFound" }));
    state = step(state, bootstrap({ kind: "failed" }));
    expect(displayStateOf(state)).toBe("d0-checking");
    expect(pendingEffect(state)).toEqual({ kind: "delay", delay: "retry" });
    expect(pendingEffect(step(state, timer))).toEqual({ kind: "request", step: "bootstrap" });
  });

  it("코드가 보이는 중 status 실패는 코드를 그대로 두고 5초 뒤 status", () => {
    const state = step(step(withCode(), timer), status({ kind: "failed" }));
    expect(displayStateOf(state)).toBe("d0-code");
    expect(pendingEffect(state)).toEqual({ kind: "delay", delay: "retry" });
    expect(pendingEffect(step(state, timer))).toEqual({ kind: "request", step: "statusCheck" });
  });

  it("me · claim 실패도 5초 뒤 같은 단계다", () => {
    const meFailed = step(initialDeviceState(), me({ kind: "failed" }));
    expect(pendingEffect(meFailed)).toEqual({ kind: "delay", delay: "retry" });
    expect(pendingEffect(step(meFailed, timer))).toEqual({ kind: "request", step: "meCheck" });
    const claimFailed = step(step(step(withCode(), timer), status({ kind: "registered" })), claim({ kind: "failed" }));
    expect(displayStateOf(claimFailed)).toBe("d0-code");
    expect(pendingEffect(step(claimFailed, timer))).toEqual({ kind: "request", step: "claim" });
  });

  it("늦게 온 결과 · 지난 단계의 타이머 · 다른 단계의 결과는 무시한다(단일 비행, PD-13)", () => {
    const state = step(initialDeviceState(), me({ kind: "unregistered" }));
    expect(deviceReducer(state, { type: "ME_RESULT", stepId: state.stepId - 1, result: { kind: "registered", deviceId: DEVICE_ID } })).toBe(state);
    expect(deviceReducer(state, { type: "BOOTSTRAP_RESULT", stepId: state.stepId, result: { kind: "alreadyRegistered" } })).toBe(state);
    expect(deviceReducer(state, { type: "TIMER_FIRED", stepId: state.stepId - 1 })).toBe(state);
    expect(deviceReducer(state, { type: "TIMER_FIRED", stepId: state.stepId })).toBe(state);
    expect(deviceReducer(state, { type: "TURN_ON" })).toBe(state);
  });
});

describe("T19 — 켜기 · 끄기 · 닫기 코드(BR9.5 · BR9.7 · BR3.7)", () => {
  it("「켜기」 → 마이크 요청 → 허용 → 접속 → HELLO_OK 뒤 대기(AC6.2.1)", () => {
    let state = step(initialDeviceState(), me({ kind: "registered", deviceId: DEVICE_ID }));
    state = run(state, { type: "TURN_ON" });
    expect(displayStateOf(state)).toBe("mic-request");
    expect(isPoweredOn(state)).toBe(false);
    state = run(state, { type: "MIC_RESULT", granted: true });
    expect(pendingEffect(state)).toEqual({ kind: "connect" });
    expect(isPoweredOn(state)).toBe(true);
    expect(deviceReducer(state, { type: "WS_READY", stepId: state.stepId - 1 })).toBe(state);
    state = step(state, (stepId) => ({ type: "WS_READY", stepId }));
    expect(displayStateOf(state)).toBe("standby");
  });

  it("마이크를 거부하면 연결하지 않고 '마이크 허용 안 됨'이며 다시 「켜기」를 누를 수 있다(AC6.2.2)", () => {
    let state = step(initialDeviceState(), me({ kind: "registered", deviceId: DEVICE_ID }));
    state = run(state, { type: "TURN_ON" }, { type: "MIC_RESULT", granted: false });
    expect(displayStateOf(state)).toBe("mic-denied");
    expect(pendingEffect(state)).toEqual({ kind: "none" });
    expect(displayStateOf(run(state, { type: "TURN_ON" }))).toBe("mic-request");
    expect(run(state, { type: "MIC_RESULT", granted: true })).toBe(state);
    expect(run(state, { type: "TURN_OFF" })).toBe(state);
  });

  it("켜짐 안 표시는 SESSION_STATE.view이고 '말하는 중'은 실제 재생 중일 때만이다(BR9.6)", () => {
    let state = poweredOn();
    state = run(state, { type: "SESSION_VIEW", view: "LISTENING" });
    expect(displayStateOf(state)).toBe("listening");
    state = run(state, { type: "SPEAKING", speaking: true });
    expect(displayStateOf(state)).toBe("speaking");
    state = run(state, { type: "SPEAKING", speaking: false }, { type: "SESSION_VIEW", view: "THINKING" });
    expect(displayStateOf(state)).toBe("thinking");
    state = run(state, { type: "SESSION_VIEW", view: "CLOSING" });
    expect(displayStateOf(state)).toBe("closing");
    state = run(state, { type: "SESSION_VIEW", view: "STANDBY" });
    expect(displayStateOf(state)).toBe("standby");
    expect(run(state, { type: "SESSION_VIEW", view: "STANDBY" })).toBe(state);
  });

  it("켜짐이 아니면 view · 재생 신호를 받지 않고 T19에서는 D0 결과도 무시한다", () => {
    const off = step(initialDeviceState(), me({ kind: "registered", deviceId: DEVICE_ID }));
    expect(deviceReducer(off, { type: "ME_RESULT", stepId: off.stepId, result: { kind: "unregistered" } })).toBe(off);
    expect(deviceReducer(off, { type: "TIMER_FIRED", stepId: off.stepId })).toBe(off);
    expect(run(off, { type: "SPEAKING", speaking: true })).toBe(off);
    expect(run(off, { type: "SESSION_VIEW", view: "LISTENING" })).toBe(off);
    expect(run(off, { type: "WS_CLOSED", code: 1013, closedByDevice: false })).toBe(off);
  });

  it("4401이면 /device/me부터 다시 확인하고(D0, AC6.2.8) 4001 · 「끄기」면 꺼짐이다(BR3.7)", () => {
    const rechecked = run(poweredOn(), { type: "WS_CLOSED", code: 4401, closedByDevice: false });
    expect(rechecked.screen).toBe("d0");
    expect(pendingEffect(rechecked)).toEqual({ kind: "request", step: "meCheck" });
    expect(displayStateOf(step(rechecked, me({ kind: "unregistered" })))).toBe("d0-checking");
    expect(displayStateOf(run(poweredOn(), { type: "WS_CLOSED", code: 4001, closedByDevice: false }))).toBe("off");
    expect(displayStateOf(run(poweredOn(), { type: "WS_CLOSED", code: 1000, closedByDevice: true }))).toBe("off");
    expect(displayStateOf(run(poweredOn(), { type: "TURN_OFF" }))).toBe("off");
  });

  it.each([1001, 1006, 1011, 1013, 4400, 4408])("닫기 %i는 마지막 화면을 두고 5초 뒤 다시 접속한다", (code) => {
    let state = run(poweredOn(), { type: "SESSION_VIEW", view: "LISTENING" });
    state = run(state, { type: "WS_CLOSED", code, closedByDevice: false });
    expect(displayStateOf(state)).toBe("reconnecting");
    expect(state.screen === "t19" && state.view).toBe("LISTENING");
    expect(pendingEffect(state)).toEqual({ kind: "delay", delay: "retry" });
    state = step(state, timer);
    expect(pendingEffect(state)).toEqual({ kind: "connect" });
  });

  it("재접속 주기(5초 대기 → 접속 시도 → 다시 실패) 내내 화면은 'reconnecting'이고 마지막 view를 둔다(BR9.7 · J360)", () => {
    let state = run(poweredOn(), { type: "SESSION_VIEW", view: "LISTENING" }, { type: "WS_CLOSED", code: 1006, closedByDevice: false });
    const trailAtClose = state.trail;
    state = step(state, timer);
    expect(pendingEffect(state)).toEqual({ kind: "connect" });
    expect(isPoweredOn(state)).toBe(true);
    expect(displayStateOf(state)).toBe("reconnecting");
    expect(state.screen === "t19" && state.view).toBe("LISTENING");
    // 서버가 계속 거절하면(1013) 다시 5초 대기이고 화면은 그대로다.
    state = run(state, { type: "WS_CLOSED", code: 1013, closedByDevice: false });
    expect(displayStateOf(state)).toBe("reconnecting");
    expect(pendingEffect(state)).toEqual({ kind: "delay", delay: "retry" });
    state = step(state, timer);
    expect(displayStateOf(state)).toBe("reconnecting");
    expect(state.screen === "t19" && state.view).toBe("LISTENING");
    // 접속 시도 · 대기를 오가도 상태 로그에 깜박임이 남지 않는다.
    expect(state.trail).toEqual(trailAtClose);
    // 접속 시도 중의 전이는 처음 연결과 같다: 늦은 HELLO_OK 무시, HELLO_OK면 대기, 4401 → D0, 4001 · 「끄기」 → 꺼짐, view 무시.
    expect(deviceReducer(state, { type: "WS_READY", stepId: state.stepId - 1 })).toBe(state);
    expect(displayStateOf(step(state, (stepId) => ({ type: "WS_READY", stepId })))).toBe("standby");
    expect(run(state, { type: "WS_CLOSED", code: 4401, closedByDevice: false }).screen).toBe("d0");
    expect(displayStateOf(run(state, { type: "WS_CLOSED", code: 4001, closedByDevice: false }))).toBe("off");
    expect(displayStateOf(run(state, { type: "TURN_OFF" }))).toBe("off");
    expect(run(state, { type: "SESSION_VIEW", view: "THINKING" })).toBe(state);
  });

  it("「켜기」 뒤 한 번도 연결되지 않은 기기는 재접속 대기 · 재접속 시도 · 다시 실패 내내 마지막 화면 'connecting'을 둔다(BR9.4 · J367)", () => {
    let state = run(step(initialDeviceState(), me({ kind: "registered", deviceId: DEVICE_ID })), { type: "TURN_ON" }, { type: "MIC_RESULT", granted: true });
    const trailAtConnect = state.trail;
    // 첫 시도 실패 → 5초 대기
    state = run(state, { type: "WS_CLOSED", code: 1006, closedByDevice: false });
    expect(displayStateOf(state)).toBe("connecting");
    expect(pendingEffect(state)).toEqual({ kind: "delay", delay: "retry" });
    expect(isPoweredOn(state)).toBe(true);
    // 대기 끝 → 재접속 시도
    state = step(state, timer);
    expect(displayStateOf(state)).toBe("connecting");
    expect(pendingEffect(state)).toEqual({ kind: "connect" });
    // 재시도도 실패(1013) → 다시 대기
    state = run(state, { type: "WS_CLOSED", code: 1013, closedByDevice: false });
    expect(displayStateOf(state)).toBe("connecting");
    expect(pendingEffect(state)).toEqual({ kind: "delay", delay: "retry" });
    expect(state.trail).toEqual(trailAtConnect);
    // 마침내 HELLO_OK면 대기이고, 그 뒤 끊기면 한 번 연결됐던 기기라 'reconnecting' · 마지막 view(J360)
    state = step(state, timer);
    state = step(state, (stepId) => ({ type: "WS_READY", stepId }));
    expect(displayStateOf(state)).toBe("standby");
    state = run(state, { type: "SESSION_VIEW", view: "LISTENING" }, { type: "WS_CLOSED", code: 1006, closedByDevice: false });
    expect(displayStateOf(state)).toBe("reconnecting");
    expect(state.screen === "t19" && state.view).toBe("LISTENING");
  });

  it("「끄기」 뒤 다시 「켜기」는 새 연결 주기라 그 전에 연결됐던 일과 무관하게 'connecting'이다(J367)", () => {
    let state = run(poweredOn(), { type: "TURN_OFF" }, { type: "TURN_ON" }, { type: "MIC_RESULT", granted: true });
    state = run(state, { type: "WS_CLOSED", code: 1006, closedByDevice: false });
    expect(displayStateOf(state)).toBe("connecting");
    state = step(state, timer);
    expect(displayStateOf(state)).toBe("connecting");
    expect(pendingEffect(state)).toEqual({ kind: "connect" });
  });

  it("처음 연결(마이크 허용 뒤)은 재접속과 달리 'connecting'이다(BR9.4)", () => {
    const state = run(step(initialDeviceState(), me({ kind: "registered", deviceId: DEVICE_ID })), { type: "TURN_ON" }, { type: "MIC_RESULT", granted: true });
    expect(displayStateOf(state)).toBe("connecting");
    expect(pendingEffect(state)).toEqual({ kind: "connect" });
  });

  it("상태 로그(trail)는 최근 20개이고 마지막 값이 지금 화면이다(AC6.2.9)", () => {
    let state = poweredOn();
    for (let index = 0; index < 15; index += 1) {
      state = run(state, { type: "SPEAKING", speaking: true }, { type: "SPEAKING", speaking: false });
    }
    expect(state.trail).toHaveLength(20);
    expect(state.trail.at(-1)).toBe(displayStateOf(state));
  });
});
