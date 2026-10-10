package kr.mujoong.core.device;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.common.db.DbRetry;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.device.DeviceJdbcRepository.TokenOwner;
import kr.mujoong.core.observability.EventLogger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 기기 토큰 확인 (U1 3.5 Step 19 · BR3.2 · BR3.4 · BR3.5 · 편차 (j) · AC2.2.5 · AC6.2.8).
 *
 * <ul>
 *   <li>{@link #verify}(내부 POST /internal/devices/verify-token): 토큰 해시가 REGISTERED 행과 맞을 때만 기기 · 보호자 · 아이를 돌려주고, 첫
 *       확인이면 first_connected_at을 채워 claim을 닫는다(BR3.2). 맞는 행이 없거나 RELEASED면 401 INVALID_DEVICE_TOKEN. 결과는 캐시하지 않고
 *       연결마다 확인한다(j).
 *   <li>{@link #me}(GET /api/device/me): 유효한 토큰이면 {deviceId, registeredAt}(아이 정보 없음). 쿠키 없음 · 모르는 토큰 · RELEASED는 401
 *       DEVICE_NOT_REGISTERED → 기기 웹은 D0으로 간다. 쿠키 수명 연장(400일)은 웹 계층이 응답에 싣는다.
 * </ul>
 *
 * <p>토큰 비교는 해시 일치로 하고 원문은 저장 · 로그하지 않는다(BR3.3). 거부는 SECURITY_AUTH_FAILED(kind DEVICE_TOKEN, statusCode 401)로 센다.
 */
@Service
public class DeviceTokenVerifier {

    private final DeviceJdbcRepository devices;
    private final EventLogger events;
    private final DbRetry dbRetry;
    private final TransactionTemplate transactions;
    private final MujoongClock clock;

    public DeviceTokenVerifier(
            DeviceJdbcRepository devices,
            EventLogger events,
            DbRetry dbRetry,
            PlatformTransactionManager transactionManager,
            MujoongClock clock) {
        this.devices = Objects.requireNonNull(devices, "devices");
        this.events = Objects.requireNonNull(events, "events");
        this.dbRetry = Objects.requireNonNull(dbRetry, "dbRetry");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * WSS 핸드셰이크의 토큰 확인(BR3.4).
     *
     * @throws CoreApiException INVALID_DEVICE_TOKEN(401)
     */
    public VerifiedDevice verify(String rawToken) {
        Optional<VerifiedDevice> verified = DeviceSecrets.looksLikeSecret(rawToken)
                ? dbRetry.execute(DbRetry.Idempotency.IDEMPOTENT,
                        () -> transactions.execute(status -> verifyInTx(DeviceSecrets.hash(rawToken))))
                : Optional.empty();
        return verified.orElseThrow(() -> rejected(ErrorCode.INVALID_DEVICE_TOKEN));
    }

    /**
     * 기기 웹 시작 때 자기 등록 확인(BR3.5).
     *
     * @throws CoreApiException DEVICE_NOT_REGISTERED(401)
     */
    public MeResult me(String rawToken) {
        if (!DeviceSecrets.looksLikeSecret(rawToken)) {
            throw rejected(ErrorCode.DEVICE_NOT_REGISTERED);
        }
        TokenOwner owner = devices.findTokenOwner(DeviceSecrets.hash(rawToken))
                .orElseThrow(() -> rejected(ErrorCode.DEVICE_NOT_REGISTERED));
        return new MeResult(owner.deviceId(), owner.registeredAt());
    }

    private Optional<VerifiedDevice> verifyInTx(String tokenHash) {
        Instant now = clock.now();
        Optional<TokenOwner> owner = devices.findTokenOwner(tokenHash);
        if (owner.isEmpty()) {
            return Optional.empty();
        }
        if (owner.get().isBeforeFirstConnection()) {
            devices.markFirstConnected(owner.get().deviceId(), now);
        }
        TokenOwner found = owner.get();
        return Optional.of(new VerifiedDevice(found.deviceId(), found.guardianId(), found.childId()));
    }

    private CoreApiException rejected(ErrorCode code) {
        events.log(EventCode.SECURITY_AUTH_FAILED, EventLog.Component.DEVICE_REGISTRY, null, entry -> entry
                .attr(AttrKey.KIND, "DEVICE_TOKEN")
                .attr(AttrKey.STATUS_CODE, code.httpStatus()));
        return CoreApiException.of(code);
    }

    /** 토큰 확인 결과(200 {deviceId, guardianId, childId, registrationStatus: REGISTERED}). childId는 아이가 없으면 null. */
    public record VerifiedDevice(UUID deviceId, UUID guardianId, UUID childId) {

        public VerifiedDevice {
            Objects.requireNonNull(deviceId, "deviceId");
            Objects.requireNonNull(guardianId, "guardianId");
        }

        /** 응답의 registrationStatus는 언제나 REGISTERED다. */
        public String registrationStatus() {
            return "REGISTERED";
        }
    }

    /** 기기 등록 확인 결과(200 {deviceId, registeredAt}). */
    public record MeResult(UUID deviceId, Instant registeredAt) {

        public MeResult {
            Objects.requireNonNull(deviceId, "deviceId");
            Objects.requireNonNull(registeredAt, "registeredAt");
        }
    }
}
