package kr.mujoong.realtime.judgment;

/** 판정 턴을 열 수 있는 세션 상태 (U11 entities JudgmentTurn.openedInStatus). 이 상태를 벗어나면 턴은 SESSION_EXITED로 닫힌다(BR5.4). */
public enum TurnStatus {
    WAITING_CHILD,
    ACTIVE
}
