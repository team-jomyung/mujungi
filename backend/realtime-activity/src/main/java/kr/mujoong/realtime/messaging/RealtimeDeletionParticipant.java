package kr.mujoong.realtime.messaging;

import java.util.Map;
import java.util.UUID;

/**
 * Realtime 계정 삭제 참가자 공통 계약 (U4b L6 · FR8.1 · BR9.2 · BR9.3 · FD-Q5 · functional-spec 5.2).
 *
 * <p>DeletionRequested 처리기가 {@link #order()}가 작은 것부터 부른다: 음성 100(U3) → 연습 150(U5b, 등록된 빈이 없으면 건너뜀) → 세션 200(U4b) →
 * 메시징 900(U1). 반환은 '이름 → 건수' 지도이고 멱등이며 0건도 성공이다. 예외는 처리 전체를 실패로 돌려 Inbox 재시도에 맡긴다.
 */
public interface RealtimeDeletionParticipant {

    /** 음성 대화(전사 원문 · 원음 파일 · 끊김 구간). */
    int ORDER_VOICE = 100;
    /** 연습 기록(U5b 자리). */
    int ORDER_PRACTICE = 150;
    /** 세션 행과 그에 딸린 행. */
    int ORDER_SESSION = 200;
    /** Outbox · Inbox. */
    int ORDER_MESSAGING = 900;

    /** 호출 순서 값(작을수록 먼저). */
    int order();

    /**
     * 한 보호자의 Realtime 데이터를 지운다.
     *
     * @param deletionRequestId 삭제 요청 id(기록 상관)
     * @return 표 · 파일마다 지운(표시한) 수
     */
    Map<String, Integer> deleteByGuardian(UUID guardianId, UUID deletionRequestId);
}
