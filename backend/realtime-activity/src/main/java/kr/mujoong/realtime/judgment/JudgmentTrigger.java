package kr.mujoong.realtime.judgment;

/** 판정 턴을 연 계기 (U11 entities JudgmentTurn.trigger). J1은 JOIN_UNCLEAR, J6은 STOP_CONFIRM_CANDIDATE, J2 ~ J5는 EXTENSION_REQUEST다. */
public enum JudgmentTrigger {
    JOIN_UNCLEAR,
    STOP_CONFIRM_CANDIDATE,
    EXTENSION_REQUEST
}
