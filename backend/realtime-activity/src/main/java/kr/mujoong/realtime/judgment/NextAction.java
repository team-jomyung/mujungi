package kr.mujoong.realtime.judgment;

/**
 * 판정 기록에 남기는 다음 행동 (U11 entities JudgmentLiveRecord.nextAction). J1 · J6 몫인 값(START_JOIN ~ ASK_STOP_CONFIRM 등)은 이미 쓴 판정
 * 기록을 읽으려고 남긴다(contracts/deviations.md FT-1022-c).
 */
public enum NextAction {
    NONE,
    START_JOIN,
    DECLINE_JOIN,
    REPROMPT_JOIN,
    CLOSE,
    ASK_STOP_CONFIRM,
    RESUME,
    HOLD_REASK,
    REST_ACK_AND_RETURN,
    REST_ACK_ONLY,
    DELEGATED_TO_EXTENSION
}
