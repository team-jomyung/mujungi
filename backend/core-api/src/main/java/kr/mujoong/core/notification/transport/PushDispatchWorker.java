package kr.mujoong.core.notification.transport;

import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.health.ReadinessState;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** 주기마다 도래한 사건 한 건을 처리한다. 수신처별 커밋으로 부분 성공을 재시작 뒤에도 보존한다. */
@Component
public class PushDispatchWorker {
    private final PushDispatchRepository repository;
    private final PushDeliverySender sender;
    private final MujoongClock clock;
    private final BooleanSupplier ready;
    private final List<Duration> backoff;
    private final Duration lease;

    @Autowired
    public PushDispatchWorker(
            PushDispatchRepository repository,
            PushDeliverySender sender,
            MujoongClock clock,
            ReadinessState readiness,
            CoreProperties properties) {
        this(
                repository,
                sender,
                clock,
                readiness::isReady,
                properties.timing().push().retryBackoff(),
                properties
                        .timing()
                        .push()
                        .sendTimeout()
                        .plus(properties.timing().push().dispatchPoll()));
    }

    public PushDispatchWorker(
            PushDispatchRepository repository,
            PushDeliverySender sender,
            MujoongClock clock,
            BooleanSupplier ready,
            List<Duration> backoff,
            Duration lease) {
        this.repository = repository;
        this.sender = sender;
        this.clock = clock;
        this.ready = ready;
        this.backoff = List.copyOf(backoff);
        this.lease = lease;
    }

    public int runOnce() {
        if (!ready.getAsBoolean() || !sender.enabled()) return 0;
        var claimed = repository.claimNext(clock.now(), lease);
        if (claimed.isEmpty()) return 0;
        var row = claimed.get();
        for (var entry : row.recipients().entrySet()) {
            if (entry.getValue().pendingDelivery())
                repository.processRecipient(
                        row, UUID.fromString(entry.getKey()), sender, backoff.size() + 1, lease);
        }
        repository.finish(row, clock.now(), backoff);
        return 1;
    }
}
