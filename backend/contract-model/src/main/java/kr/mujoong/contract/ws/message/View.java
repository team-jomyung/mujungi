package kr.mujoong.contract.ws.message;

/** T19 표시 상태(SESSION_STATE · HELLO_OK.session의 view, 계약 4.2). '말하는 중'은 기기가 실제 재생으로 스스로 정한다(BR9.6). */
public enum View {
    STANDBY,
    LISTENING,
    THINKING,
    CLOSING,
    /** 모르는 값('알 수 없음'). 보내지 않는다. */
    UNRECOGNIZED
}
