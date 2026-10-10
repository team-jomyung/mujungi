package kr.mujoong.core.device;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.event.payload.DeviceReleased;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.common.db.DbRetry;
import kr.mujoong.core.common.deletion.DeletionParticipant;
import kr.mujoong.core.device.DeviceJdbcRepository.DeviceRecord;
import kr.mujoong.core.messaging.CoreOutboxWriter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Device 계정 삭제 참가자 (U1 3.5 Step 19 · BR2.10 · J58 · SD-21 · 계약 8절 DeletionParticipant).
 *
 * <p>한 트랜잭션에서 (1) REGISTERED 행이 있으면 RELEASED(ACCOUNT_DELETED, 토큰 해시 비움)로 바꾸고 DeviceReleased(ACCOUNT_DELETED) Outbox를
 * 쓴다(A4 guardian_id) (2) guardian_id가 같은 core_device 행을 상태와 상관없이 모두 지운다(재등록으로 RELEASED가 된 옛 행 포함). 등록 전
 * UNREGISTERED · EXPIRED 행은 보호자가 없어 대상이 아니며 7일 정리 작업이 지운다. DeviceReleased 처리에 필요한 deviceId는 payload가 가지므로
 * 행이 없어도 Realtime은 연결을 닫는다. 멱등이다(두 번째 호출은 {@code {device: 0}}). 활동 행(U4 참가자)이 남아 있으면 외래 키(RESTRICT)
 * 위반으로 예외이고 지휘가 순서를 고쳐 다시 부른다. 다른 표에 보호자 근거를 채우는 일은 하지 않는다(R3-3).
 */
@Component
public class DeviceDeletionParticipant implements DeletionParticipant {

    /** 반환 지도의 키. */
    public static final String DEVICE = "device";

    private final DeviceJdbcRepository devices;
    private final CoreOutboxWriter outbox;
    private final DbRetry dbRetry;
    private final TransactionTemplate transactions;
    private final MujoongClock clock;

    public DeviceDeletionParticipant(
            DeviceJdbcRepository devices,
            CoreOutboxWriter outbox,
            DbRetry dbRetry,
            PlatformTransactionManager transactionManager,
            MujoongClock clock) {
        this.devices = Objects.requireNonNull(devices, "devices");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.dbRetry = Objects.requireNonNull(dbRetry, "dbRetry");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Map<String, Integer> deleteByGuardian(UUID guardianId, UUID deletionRequestId) {
        Objects.requireNonNull(guardianId, "guardianId");
        Objects.requireNonNull(deletionRequestId, "deletionRequestId");
        Integer deleted = dbRetry.execute(DbRetry.Idempotency.IDEMPOTENT, () -> transactions.execute(status -> {
            Instant now = clock.now();
            Optional<DeviceRecord> active = devices.lockActiveByGuardian(guardianId);
            if (active.isPresent()
                    && devices.releaseRegistered(active.get().id(), DeviceReleased.Reason.ACCOUNT_DELETED, now) == 1) {
                outbox.write(new DeviceReleased(active.get().id(), guardianId, DeviceReleased.Reason.ACCOUNT_DELETED),
                        guardianId, now);
            }
            return devices.deleteAllByGuardian(guardianId);
        }));
        return Map.of(DEVICE, deleted == null ? 0 : deleted);
    }
}
