package kr.mujoong.realtime.session;

import java.util.UUID;

/** 실행기가 없는 종료 연습을 저장 원본에서 복구한다. 확정할 수 없으면 예외로 재무장한다. */
public interface PracticeRecordRecovery {
    Object recover(UUID sessionId);
}
