package kr.mujoong.core.device;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.common.db.DbRetry;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.device.DeviceJdbcRepository.DeviceRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 기기 토큰 받기 claim (U1 3.5 Step 19 · BR3.1 · BR3.2 · BR3.3 · 편차 (b) · AC6.1.4 · AC2.2.5).
 *
 * <p>쿠키의 bootstrap 비밀이 REGISTERED 행과 맞고 첫 연결(first_connected_at) 전이면 새 토큰(256비트)을 만들어 해시만 저장한다. 첫 연결 전에는
 * 다시 부를 수 있고 부를 때마다 토큰을 바꿔 앞 토큰을 무효로 한다(응답 유실 대비 회전). 행은 FOR UPDATE로 잠그고 한 트랜잭션에서 판정한다.
 *
 * <ul>
 *   <li>쿠키 없음 · 모르는 비밀 → 401 BOOTSTRAP_NOT_FOUND (b)
 *   <li>UNREGISTERED이고 만료 전(PENDING) → 409 NOT_REGISTERED_YET
 *   <li>만료 · EXPIRED · RELEASED, 또는 first_connected_at이 있음 → 410 BOOTSTRAP_CLOSED(BR3.2)
 * </ul>
 *
 * <p>토큰 원문은 결과의 {@link DeviceSecrets.Secret}으로만 나가 Set-Cookie MUJOONG_DEVICE에 실린다. 웹 계층이 bootstrap 쿠키를 지운다.
 */
@Service
public class DeviceClaimService {

    private final DeviceJdbcRepository devices;
    private final DeviceSecrets secrets;
    private final DbRetry dbRetry;
    private final TransactionTemplate transactions;
    private final MujoongClock clock;

    public DeviceClaimService(
            DeviceJdbcRepository devices,
            DeviceSecrets secrets,
            DbRetry dbRetry,
            PlatformTransactionManager transactionManager,
            MujoongClock clock) {
        this.devices = Objects.requireNonNull(devices, "devices");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.dbRetry = Objects.requireNonNull(dbRetry, "dbRetry");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * claim(POST /api/device/bootstrap/claim).
     *
     * @throws CoreApiException BOOTSTRAP_NOT_FOUND(401) · NOT_REGISTERED_YET(409) · BOOTSTRAP_CLOSED(410)
     */
    public ClaimResult claim(String cookieSecret) {
        if (!DeviceSecrets.looksLikeSecret(cookieSecret)) {
            throw CoreApiException.of(ErrorCode.BOOTSTRAP_NOT_FOUND);
        }
        String bootstrapHash = DeviceSecrets.hash(cookieSecret);
        // 다시 해도 결과가 같다: 되돌려진 시도의 토큰은 어디에도 나가지 않았다(RD-22)
        return dbRetry.execute(DbRetry.Idempotency.IDEMPOTENT, () -> transactions.execute(status -> claimInTx(bootstrapHash)));
    }

    private ClaimResult claimInTx(String bootstrapHash) {
        Instant now = clock.now();
        DeviceRecord row = devices.lockByBootstrapHash(bootstrapHash)
                .orElseThrow(() -> CoreApiException.of(ErrorCode.BOOTSTRAP_NOT_FOUND));
        switch (row.registrationStatus()) {
            case UNREGISTERED -> throw CoreApiException.of(
                    row.isCodeValidAt(now) ? ErrorCode.NOT_REGISTERED_YET : ErrorCode.BOOTSTRAP_CLOSED);
            case REGISTERED -> {
                if (row.firstConnectedAt() != null) {
                    throw CoreApiException.of(ErrorCode.BOOTSTRAP_CLOSED);
                }
                DeviceSecrets.Secret token = secrets.newDeviceToken();
                if (devices.rotateToken(row.id(), token.hash(), now) != 1) {
                    throw CoreApiException.of(ErrorCode.BOOTSTRAP_CLOSED);
                }
                return new ClaimResult(row.id(), token);
            }
            default -> throw CoreApiException.of(ErrorCode.BOOTSTRAP_CLOSED);
        }
    }

    /**
     * claim 결과(200 {deviceId}).
     *
     * @param deviceToken Set-Cookie MUJOONG_DEVICE에 실을 토큰 원문(Max-Age = device.cookie-max-age)
     */
    public record ClaimResult(UUID deviceId, DeviceSecrets.Secret deviceToken) {

        public ClaimResult {
            Objects.requireNonNull(deviceId, "deviceId");
            Objects.requireNonNull(deviceToken, "deviceToken");
        }
    }
}
