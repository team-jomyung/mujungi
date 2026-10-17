package kr.mujoong.realtime.judgment;

/** 판정 보류 사유 (U11 entities JudgeOutcome.abstainReason 열한 가지, BR1.3 ~ BR1.6 · BR4.1 · BR4.3). 사유 코드만 기록한다(R-F14). */
public enum AbstainReason {
    LOW_CONFIDENCE,
    LOW_MARGIN,
    TIMEOUT,
    PROVIDER_ERROR,
    SCHEMA_INVALID,
    OPTION_OUT_OF_SET,
    DUPLICATE_OPTION,
    PROBABILITY_OUT_OF_RANGE,
    NO_PROVIDER,
    UNREADABLE_INPUT,
    RESPONSE_NOT_READY
}
