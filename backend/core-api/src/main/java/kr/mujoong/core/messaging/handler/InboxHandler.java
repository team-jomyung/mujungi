package kr.mujoong.core.messaging.handler;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import kr.mujoong.contract.event.EventType;
import kr.mujoong.core.messaging.CoreInboxRepository.InboxEvent;

/**
 * Core Inbox 처리기 등록 표의 한 칸 (U1 3.5 Step 20 · BR5.7 · RD-9 · ADR-010 2.4).
 *
 * <p>디스패처는 처리기를 이름으로 알지 않고 {@link #eventType()}으로 찾는다. {@link #handle}은 디스패처가 연 트랜잭션 안에서 불리고, 그
 * 트랜잭션이 업무 변경과 PROCESSED 표시를 함께 커밋한다. 상대 앱 호출(BR6.14 재취소)처럼 트랜잭션 밖에서 해야 할 일은
 * {@link Result#then}으로 돌려주고, 디스패처가 커밋 뒤 실행해 성공하면 짧은 트랜잭션으로 PROCESSED를 표시한다(실패하면 예외 → BR5.8
 * 재호출). 같은 이벤트를 두 번 불러도 결과가 같아야 한다(두 번째 방어).
 */
public interface InboxHandler {

    /** 이 처리기가 맡는 이벤트 종류. */
    EventType eventType();

    /**
     * 업무 변경을 한다(트랜잭션 안). 예외는 BR5.8 재호출 표를 탄다.
     *
     * @param event 선점한 Inbox 행(payload 포함)
     * @param now 처리 시각(Clock)
     */
    Result handle(InboxEvent event, Instant now);

    /** 트랜잭션 밖에서 할 뒷일(상대 앱 호출). 실패는 예외로 알린다. */
    @FunctionalInterface
    interface FollowUp {
        void run() throws Exception;
    }

    /** 처리 결과: 뒷일이 없으면 같은 트랜잭션에서 PROCESSED, 있으면 뒷일 성공 뒤 PROCESSED. */
    record Result(Optional<FollowUp> followUp) {

        private static final Result DONE = new Result(Optional.empty());

        public Result {
            Objects.requireNonNull(followUp, "followUp");
        }

        /** 뒷일 없음. */
        public static Result done() {
            return DONE;
        }

        /** 커밋 뒤 할 일. */
        public static Result then(FollowUp followUp) {
            return new Result(Optional.of(Objects.requireNonNull(followUp, "followUp")));
        }
    }
}
