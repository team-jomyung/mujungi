package kr.mujoong.core.messaging;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.common.scaling.SingleInstanceAssumption;
import kr.mujoong.core.config.CoreProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 메시징 보존 정리 (U1 3.5 Step 20 · BR5.11 · RD-8 · SQ-18).
 *
 * <p>maintenance 스레드가 {@code mujoong.cleanup.messaging-interval}(24시간)마다 부른다. 만들어진(받은) 지 {@code
 * mujoong.retention.failed-payload}(7일)가 지난 Outbox FAILED · Inbox DEAD 행의 payload를 {@code {}}로 비우고 purged_at을 채운다(행 ·
 * 해시 · 상태는 남는다). PUBLISHED · PROCESSED · IGNORED는 그 상태가 될 때 이미 비웠다. 한 번에 {@code mujoong.cleanup.batch-size}건씩
 * 다 할 때까지 되풀이하고 각 문장은 멱등이다. 비운 FAILED 제어 이벤트는 재무장 대상에서 빠진다(A1: 다시 보낼 수 없다).
 */
@Component
@SingleInstanceAssumption(si = "SI-7", reason = "인스턴스마다 하루 1회 정리가 돌아 같은 행을 두고 경합한다(문장이 멱등이라 결과는 같다)")
public class MessagingCleanupJob {

    private final CoreOutboxRepository outbox;
    private final CoreInboxRepository inbox;
    private final MujoongClock clock;
    private final Duration retention;
    private final int batchSize;

    @Autowired
    public MessagingCleanupJob(
            CoreOutboxRepository outbox, CoreInboxRepository inbox, MujoongClock clock, CoreProperties properties) {
        this(outbox, inbox, clock, properties.retention().failedPayload(), properties.cleanup().batchSize());
    }

    public MessagingCleanupJob(
            CoreOutboxRepository outbox, CoreInboxRepository inbox, MujoongClock clock, Duration retention, int batchSize) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retention = Objects.requireNonNull(retention, "retention");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize는 0보다 커야 한다");
        }
        this.batchSize = batchSize;
    }

    /** 한 번 정리한다. */
    public Result runOnce() {
        Instant now = clock.now();
        int outboxPurged = 0;
        int changed;
        do {
            changed = outbox.purgeFailedOlderThan(now, retention, batchSize);
            outboxPurged += changed;
        } while (changed == batchSize);
        int inboxPurged = 0;
        do {
            changed = inbox.purgeDeadOlderThan(now, retention, batchSize);
            inboxPurged += changed;
        } while (changed == batchSize);
        return new Result(outboxPurged, inboxPurged);
    }

    /** 비운 행 수. */
    public record Result(int outboxFailedPurged, int inboxDeadPurged) {
    }
}
