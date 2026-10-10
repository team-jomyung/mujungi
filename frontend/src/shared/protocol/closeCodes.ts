// 기기 WSS 닫기 코드와 기기 웹 동작 (U1 3.5 Step 37 · 계약 4.2 x-close-codes · BR3.6 · BR3.7 · BR9.7 · 편차 (l)(s)(n))
//
// | 코드 | 이름             | 기기 웹 동작                                                 |
// |------|------------------|--------------------------------------------------------------|
// | 1000 | DEVICE_OFF       | 기기가 「끄기」로 보낸 것 → 꺼짐                             |
// | 1001 | GOING_AWAY       | 켜짐이면 5초 뒤 재접속(device-web.retry-interval)             |
// | 1006 | ABNORMAL_CLOSURE | 같음(보내는 코드가 아니라 끊김)                               |
// | 1011 | SERVER_ERROR     | 같음                                                         |
// | 1013 | TRY_AGAIN_LATER  | 같음                                                         |
// | 4001 | REPLACED         | 꺼짐, 자동 재접속 안 함(두 창이 서로 밀어내는 반복 방지)      |
// | 4400 | PROTOCOL_ERROR   | 켜짐이면 5초 뒤 재접속                                       |
// | 4401 | UNAUTHORIZED     | GET /api/device/me로 확인 → 401이면 D0                       |
// | 4408 | HELLO_TIMEOUT    | 켜짐이면 5초 뒤 재접속                                       |
// 1 · 2 · 4초 백오프 · HELLO resume · '다시 연결하고 있어요'는 U3가 완성한다.

export const CLOSE_CODES = {
  DEVICE_OFF: 1000,
  GOING_AWAY: 1001,
  ABNORMAL_CLOSURE: 1006,
  SERVER_ERROR: 1011,
  TRY_AGAIN_LATER: 1013,
  REPLACED: 4001,
  PROTOCOL_ERROR: 4400,
  UNAUTHORIZED: 4401,
  HELLO_TIMEOUT: 4408,
} as const;

/** 「끄기」 때 보내는 닫기 이유 문자열. */
export const DEVICE_OFF_REASON = "DEVICE_OFF";

/** 닫힌 뒤 기기 웹이 할 일. */
export type CloseAction = "OFF" | "RECONNECT" | "RECHECK_ME";

/**
 * 닫기 코드에 따른 동작. 기기가 스스로 1000으로 닫은 경우만 꺼짐이고, 서버가 보낸 모르는 코드는
 * 켜짐 상태에서 재접속한다(BR9.7).
 */
export function actionForClose(code: number, closedByDevice: boolean): CloseAction {
  if (closedByDevice && code === CLOSE_CODES.DEVICE_OFF) {
    return "OFF";
  }
  if (code === CLOSE_CODES.REPLACED) {
    return "OFF";
  }
  if (code === CLOSE_CODES.UNAUTHORIZED) {
    return "RECHECK_ME";
  }
  return "RECONNECT";
}
