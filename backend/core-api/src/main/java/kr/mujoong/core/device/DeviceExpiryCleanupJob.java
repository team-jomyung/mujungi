package kr.mujoong.core.device;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.config.CoreProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 등록 코드 만료 정리 작업 (U1 3.5 Step 19 · BR1.4 · RD-1 '주기 작업' · SQ-18 · SCD 정리 배치).
 *
 * <p>maintenance 스레드가 {@code mujoong.cleanup.device-expiry-interval}(10분)마다 {@link #runOnce}를 부른다. 만료된 UNREGISTERED 행을
 * EXPIRED로 바꾸고(같은 코드를 새 발급이 다시 뽑을 수 있게 부분 유일 색인에서 빠진다), 만료 뒤 {@code mujoong.cleanup.expired-device-retention}
 * (7일)이 지난 EXPIRED 행을 지운다. 한 번에 {@code mujoong.cleanup.batch-size}(1,000)건씩 다 할 때까지 되풀이한다. 각 문장은 멱등이다.
 */
@Component
public class DeviceExpiryCleanupJob {

    private final DeviceJdbcRepository devices;
    private final MujoongClock clock;
    private final Duration retention;
    private final int batchSize;

    @Autowired
    public DeviceExpiryCleanupJob(DeviceJdbcRepository devices, MujoongClock clock, CoreProperties properties) {
        this(devices, clock, properties.cleanup().expiredDeviceRetention(), properties.cleanup().batchSize());
    }

    public DeviceExpiryCleanupJob(DeviceJdbcRepository devices, MujoongClock clock, Duration retention, int batchSize) {
        this.devices = Objects.requireNonNull(devices, "devices");
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
        int expired = 0;
        int changed;
        do {
            changed = devices.expireDueCodes(now, batchSize);
            expired += changed;
        } while (changed == batchSize);
        int deleted = 0;
        do {
            changed = devices.deleteExpired(now, retention, batchSize);
            deleted += changed;
        } while (changed == batchSize);
        return new Result(expired, deleted);
    }

    /** 정리 건수. */
    public record Result(int expired, int deleted) {
    }
}
