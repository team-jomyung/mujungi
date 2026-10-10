package kr.mujoong.core.support;

import java.time.Duration;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.log.OwnerHasher;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.observability.CoreMetrics;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.testsupport.log.LogCapture;

/**
 * 시험용 사건 기록기 묶음(시험 소스 전용). 줄은 {@link LogCapture}가 모으고, 허용 표 밖 키는 STRICT라 시험에서 예외가 된다(OD-3 1번).
 */
public final class TestEvents {

    private final LogCapture capture = LogCapture.detached();
    private final CoreMetrics metrics = CoreMetrics.inMemory();
    private final EventLogger logger;

    public TestEvents(MujoongClock clock) {
        this.logger = new EventLogger(clock, OwnerHasher.ephemeral(), capture, metrics, EventLog.Strictness.STRICT,
                Duration.ofSeconds(10), 60);
    }

    public EventLogger logger() {
        return logger;
    }

    public CoreMetrics metrics() {
        return metrics;
    }

    public LogCapture capture() {
        return capture;
    }
}
