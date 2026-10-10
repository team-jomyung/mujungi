package kr.mujoong.realtime.messaging;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.health.ReadinessState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Realtime Inbox 재호출 폴러 (U1 3.5 Step 26 · BR5.7 · BR5.8 · BR5.12 · RD-9 · LC-11).
 *
 * <p>{@code inbox-repoll} 스레드가 {@code mujoong.inbox.poll-interval}마다 {@link #runOnce}를 부른다. 준비 완료일 때만 RECEIVED이고
 * next_attempt_at이 지난 행을 next_attempt_at, received_at 순으로 {@code batch-size}건 골라 처리 풀에 제출한다. 실제 처리 여부는
 * 디스패처의 선점 UPDATE가 정하므로(행 수 1만 처리) 수신 직후 제출과 겹쳐도 한 번만 처리된다.
 */
@Component
public class RealtimeInboxRepoller {

    private final RealtimeInboxRepository inbox;
    private final RealtimeInboxDispatcher dispatcher;
    private final BooleanSupplier ready;
    private final MujoongClock clock;
    private final int batchSize;

    @Autowired
    public RealtimeInboxRepoller(
            RealtimeInboxRepository inbox,
            RealtimeInboxDispatcher dispatcher,
            ReadinessState readiness,
            MujoongClock clock,
            RealtimeProperties properties) {
        this(inbox, dispatcher, readiness::isReady, clock, properties.inbox().batchSize());
    }

    public RealtimeInboxRepoller(
            RealtimeInboxRepository inbox,
            RealtimeInboxDispatcher dispatcher,
            BooleanSupplier ready,
            MujoongClock clock,
            int batchSize) {
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.ready = Objects.requireNonNull(ready, "ready");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize는 0보다 커야 한다");
        }
        this.batchSize = batchSize;
    }

    /**
     * 한 번 돈다.
     *
     * @return 제출한 행 수
     */
    public int runOnce() {
        if (!ready.getAsBoolean()) {
            return 0;
        }
        int submitted = 0;
        for (InboxEventRow row : inbox.findDue(clock.now(), batchSize)) {
            if (!dispatcher.submit(row.eventId())) {
                break;
            }
            submitted++;
        }
        return submitted;
    }
}
