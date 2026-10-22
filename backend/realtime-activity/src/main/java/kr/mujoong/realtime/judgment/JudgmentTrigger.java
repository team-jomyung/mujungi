package kr.mujoong.realtime.judgment;

/** 판정 턴을 연 계기 (U11 entities JudgmentTurn.trigger). J1은 JOIN_UNCLEAR, J6은 STOP_CONFIRM_CANDIDATE, J2 ~ J5는 EXTENSION_REQUEST다.
 * 실행부는 2026-10-22부터 J1 · J6을 열지 않는다(contracts/deviations.md FT-1022-b). 두 값은 이미 쓴 판정 기록을 읽으려고 남긴다. */
public enum JudgmentTrigger {
    JOIN_UNCLEAR,
    STOP_CONFIRM_CANDIDATE,
    EXTENSION_REQUEST
}
