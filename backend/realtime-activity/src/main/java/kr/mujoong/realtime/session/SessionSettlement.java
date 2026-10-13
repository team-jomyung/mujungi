package kr.mujoong.realtime.session;

import java.time.Instant;
import java.util.UUID;

/**
 * 종료 세션의 결과 입력 스냅숏을 쓰는 입구 (U4b L5 · FR7.1 · BR8.1 · BR8.5).
 *
 * <p>운영 구현은 {@link RecordCaptureService}(스냅숏 행 + SessionRecordCaptured Outbox 한 트랜잭션)이다. 세션당 한 번만 쓰고 이미 있으면
 * 쓰지 않는다. DB 장애는 {@code DataAccessException} 계열로 던진다(놓친 세션은 정산 재무장이 다시 쓴다).
 */
@FunctionalInterface
public interface SessionSettlement {

    /** 정산을 하지 않는 입구(실행기 단위 시험 기본값). 실행기는 정산 창 없이 곧바로 정리한다. */
    SessionSettlement NONE = (sessionId, practice, cutoffAt) -> false;

    /**
     * 스냅숏 행과 SessionRecordCaptured를 한 트랜잭션에 쓴다.
     *
     * @param practice 연습 확장점의 practice 값(없으면 null)
     * @param cutoffAt 정산 창이 끝난 시각(이 뒤의 전사는 기록에 들어가지 않는다)
     * @return 새로 썼으면 true, 이미 있으면 false
     */
    boolean capture(UUID sessionId, Object practice, Instant cutoffAt);

    /**
     * 실행기 없이 닫힌 세션의 빈 기록 정산(practice null). 쓰기가 실패하면 예외를 올리지 않고 남긴 뒤 정산 재무장에 맡긴다(운영 구현은 앱 오류 한 줄을
     * 남긴다).
     */
    default void captureOrDefer(UUID sessionId, Instant cutoffAt) {
        capture(sessionId, null, cutoffAt);
    }

    /** 정산을 하는 입구인가(실행기가 정산 창을 열지 정한다). */
    default boolean enabled() {
        return this != NONE;
    }
}
