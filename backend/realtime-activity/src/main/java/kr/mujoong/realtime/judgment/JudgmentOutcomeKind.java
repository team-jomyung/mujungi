package kr.mujoong.realtime.judgment;

/** 판정 한 번의 끝 (U11 entities JudgeOutcome.kind). 확정 · 보류 · 장애 취소 · 중단 중 하나다. */
public enum JudgmentOutcomeKind {
    CONFIRMED,
    ABSTAINED,
    FAULT_CANCELLED,
    STOPPED,
    SUPERSEDED,
    SESSION_EXITED
}
