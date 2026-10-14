package kr.mujoong.core.notification;

import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.activity.ActivityCopyRepository;
import kr.mujoong.core.activity.StaleEvaluator;
import kr.mujoong.core.common.db.DbRetry;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.device.DeviceJdbcRepository;
import kr.mujoong.core.identity.GuardianWriteLock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class PushStaleScanner {
    private final PushEventRepository events;
    private final ActivityCopyRepository activities;
    private final DeviceJdbcRepository devices;
    private final GuardianWriteLock guardians;
    private final StaleEvaluator stale;
    private final MujoongClock clock;
    private final DbRetry retry;
    private final TransactionTemplate transactions;
    private final int batchSize;

    public PushStaleScanner(PushEventRepository events, ActivityCopyRepository activities,
            DeviceJdbcRepository devices, GuardianWriteLock guardians, StaleEvaluator stale,
            MujoongClock clock, DbRetry retry, PlatformTransactionManager manager, CoreProperties properties) {
        this.events = events;
        this.activities = activities;
        this.devices = devices;
        this.guardians = guardians;
        this.stale = stale;
        this.clock = clock;
        this.retry = retry;
        this.transactions = new TransactionTemplate(manager);
        this.batchSize = properties.cleanup().batchSize();
    }

    public void runOnce() {
        UUID cursor = null;
        List<PushEventRepository.Candidate> batch;
        do {
            batch = events.candidatesAfter(cursor, batchSize);
            for (var candidate : batch) {
                retry.run(DbRetry.Idempotency.IDEMPOTENT,
                        () -> transactions.executeWithoutResult(ignored -> inspect(candidate)));
                cursor = candidate.sessionId();
            }
        } while (batch.size() == batchSize && !Thread.currentThread().isInterrupted());
    }

    private void inspect(PushEventRepository.Candidate candidate) {
        if (guardians.statusForShare(candidate.guardianId()).filter("ACTIVE"::equals).isEmpty()
                || !events.lockActivity(candidate.sessionId())) {
            return;
        }
        var copy = activities.findById(candidate.sessionId()).orElse(null);
        if (copy == null || !ActivityCopyRepository.IN_PROGRESS.contains(copy.status())) {
            return;
        }
        var device = devices.findById(copy.deviceId()).orElse(null);
        if (device == null || !"REGISTERED".equals(device.registrationStatus().name())
                || !copy.guardianId().equals(device.guardianId())) {
            return;
        }
        var now = clock.now();
        var baseline = device.reportedAt() == null ? copy.requestedAt() : device.reportedAt();
        if (stale.isStale(baseline, now)) {
            events.enqueue(copy, PushEventType.DEVICE_STATUS_UNKNOWN, copy.sessionId() + ":STALE", now);
        }
    }
}
