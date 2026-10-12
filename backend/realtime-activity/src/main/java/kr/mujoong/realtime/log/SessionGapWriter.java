package kr.mujoong.realtime.log;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.common.db.DbRetry;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.session.SessionGapPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 끊김 구간 기록 장치 (U3 L2 · ConversationLog · U3-R11 · U3-J18).
 *
 * <p>세션 실행기 스레드에서 짧은 트랜잭션 하나로 연다 · 닫는다. 열기는 멱등이 아니라 재시도하지 않고(실패하면 구간 없이 간다 — 기록은 대화를
 * 멈추게 하지 않는다), 닫기는 조건부 UPDATE라 DB 재시도를 쓴다. 실패는 앱 오류 한 줄로 남긴다. created_at은 Clock 값이다(R2).
 */
@Component
public class SessionGapWriter implements SessionGapPort {

    private final SessionGapRepository gaps;
    private final TransactionTemplate transactions;
    private final DbRetry dbRetry;
    private final EventLogger events;
    private final MujoongClock clock;

    public SessionGapWriter(SessionGapRepository gaps, PlatformTransactionManager transactionManager, DbRetry dbRetry,
            EventLogger events, MujoongClock clock) {
        this.gaps = Objects.requireNonNull(gaps, "gaps");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.dbRetry = Objects.requireNonNull(dbRetry, "dbRetry");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public UUID open(UUID sessionId, SessionGapRepository.Kind kind, Instant startedAt) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(startedAt, "startedAt");
        UUID id = UUID.randomUUID();
        try {
            transactions.executeWithoutResult(status -> gaps.open(id, sessionId, kind, startedAt, clock.now()));
            return id;
        } catch (RuntimeException e) {
            events.appError(EventLog.Component.SESSION_LIFECYCLE, e);
            return null;
        }
    }

    @Override
    public void close(UUID gapId, Instant endedAt) {
        if (gapId == null) {
            return;
        }
        Objects.requireNonNull(endedAt, "endedAt");
        try {
            dbRetry.execute(DbRetry.Idempotency.IDEMPOTENT,
                    () -> transactions.execute(status -> gaps.close(gapId, endedAt)));
        } catch (RuntimeException e) {
            events.appError(EventLog.Component.SESSION_LIFECYCLE, e);
        }
    }
}
