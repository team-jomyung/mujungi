package kr.mujoong.core.device;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.event.payload.DeviceReleased;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.status.RegistrationStatus;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.common.db.DbRetry;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.device.DeviceJdbcRepository.ActiveDeviceConflictException;
import kr.mujoong.core.device.DeviceJdbcRepository.DeviceRecord;
import kr.mujoong.core.identity.Guardian;
import kr.mujoong.core.identity.GuardianRepository;
import kr.mujoong.core.messaging.CoreOutboxWriter;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.security.ratelimit.RateLimiterConfig.RateLimiters;
import kr.mujoong.core.security.ratelimit.SlidingWindowRateLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 보호자 기기 등록 05a (U1 3.5 Step 19 · BR2.1 ~ BR2.7 · J46 · 편차 (d)(m) · AC2.2.1 ~ AC2.2.8 · AC6.1.3).
 *
 * <p>트랜잭션 밖 빠른 거절: 보호자 삭제 접수(403) → 실패 한도 10분 10회(429 TOO_MANY_ATTEMPTS, BR2.6) → 코드 정규화(영숫자 8자가 아니면 400
 * VALIDATION_FAILED, 발급 알파벳 밖이면 404 CODE_NOT_FOUND).
 *
 * <p>한 트랜잭션(락 순서 보호자 → 코드 → 옛 기기, BR2.2 · BR2.5):
 *
 * <ol>
 *   <li>보호자 행 FOR SHARE, ACTIVE가 아니면 403 ACCOUNT_DELETION_IN_PROGRESS.
 *   <li>코드의 UNREGISTERED · 만료 전 행 FOR UPDATE. 없으면 같은 코드의 REGISTERED · RELEASED 행을 본다: 같은 보호자의 REGISTERED면 첫 성공과 같은
 *       201(실패 미가산, (m)), 그 밖은 409 CODE_ALREADY_REGISTERED, 나머지는 404 CODE_NOT_FOUND(BR2.1 ② ③).
 *   <li>같은 보호자의 옛 REGISTERED 기기 FOR UPDATE · 사본으로 재판정(BR2.4): STABLE · UNSTABLE이면 409 DEVICE_ALREADY_ACTIVE, 그 밖은 교체.
 *   <li>(교체) 옛 행 RELEASED(REREGISTERED) 조건부 UPDATE(1행이어야 함) + DeviceReleased Outbox(A4 guardian_id). 보호자당 REGISTERED 하나의
 *       색인은 지연할 수 없어 이 단계가 먼저다(BR2.5).
 *   <li>새 행 REGISTERED 조건부 UPDATE(BR2.3). 0행이면 전체를 되돌리고 행을 다시 읽어 2의 분기로 답한다. 활성 기기 색인 위반(23505)은 409
 *       DEVICE_ALREADY_ACTIVE.
 * </ol>
 *
 * <p>400 · 404 · 409로 끝난 요청은 실패 한도에 더하고 SECURITY_AUTH_FAILED(kind REGISTER_CODE)를 남긴다. 성공하면 한도를 지운다. 코드는 로그에
 * 남기지 않는다(앞 4자도, 3.3 추가 동작).
 */
@Service
public class RegistrationService {

    private final GuardianRepository guardians;
    private final DeviceJdbcRepository devices;
    private final DeviceViewAssembler viewAssembler;
    private final CoreOutboxWriter outbox;
    private final SlidingWindowRateLimiter failureLimiter;
    private final EventLogger events;
    private final DbRetry dbRetry;
    private final TransactionTemplate transactions;
    private final MujoongClock clock;

    @Autowired
    public RegistrationService(
            GuardianRepository guardians,
            DeviceJdbcRepository devices,
            DeviceViewAssembler viewAssembler,
            CoreOutboxWriter outbox,
            RateLimiters limiters,
            EventLogger events,
            DbRetry dbRetry,
            PlatformTransactionManager transactionManager,
            MujoongClock clock) {
        this(guardians, devices, viewAssembler, outbox, limiters.registerFailure(), events, dbRetry, transactionManager,
                clock);
    }

    public RegistrationService(
            GuardianRepository guardians,
            DeviceJdbcRepository devices,
            DeviceViewAssembler viewAssembler,
            CoreOutboxWriter outbox,
            SlidingWindowRateLimiter failureLimiter,
            EventLogger events,
            DbRetry dbRetry,
            PlatformTransactionManager transactionManager,
            MujoongClock clock) {
        this.guardians = Objects.requireNonNull(guardians, "guardians");
        this.devices = Objects.requireNonNull(devices, "devices");
        this.viewAssembler = Objects.requireNonNull(viewAssembler, "viewAssembler");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.failureLimiter = Objects.requireNonNull(failureLimiter, "failureLimiter");
        this.events = Objects.requireNonNull(events, "events");
        this.dbRetry = Objects.requireNonNull(dbRetry, "dbRetry");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 코드 등록(POST /api/device/register).
     *
     * @param guardianId 세션의 보호자(본문에서 받지 않는다, BR2.2)
     * @param rawCode 정규화 전 입력
     * @throws CoreApiException UNAUTHENTICATED · ACCOUNT_DELETION_IN_PROGRESS · TOO_MANY_ATTEMPTS · VALIDATION_FAILED · CODE_NOT_FOUND ·
     *     CODE_ALREADY_REGISTERED · DEVICE_ALREADY_ACTIVE
     */
    public RegistrationResult register(UUID guardianId, String rawCode) {
        Objects.requireNonNull(guardianId, "guardianId");
        Guardian guardian = guardians.findById(guardianId).orElseThrow(() -> CoreApiException.of(ErrorCode.UNAUTHENTICATED));
        if (guardian.isDeletionRequested()) {
            throw CoreApiException.of(ErrorCode.ACCOUNT_DELETION_IN_PROGRESS);
        }
        String limiterKey = guardianId.toString();
        if (failureLimiter.isLimited(limiterKey)) {
            events.log(EventCode.SECURITY_RATE_LIMITED, EventLog.Component.DEVICE_REGISTRY, guardianId, entry -> entry
                    .attr(AttrKey.LIMITER, SlidingWindowRateLimiter.Limiter.REGISTER_FAILURE.name())
                    .attr(AttrKey.STATUS_CODE, ErrorCode.TOO_MANY_ATTEMPTS.httpStatus()));
            throw CoreApiException.of(ErrorCode.TOO_MANY_ATTEMPTS);
        }
        RegistrationCode.Parsed parsed = RegistrationCode.parse(rawCode);
        if (parsed.problem().isPresent()) {
            throw failed(guardianId, parsed.problem().get() == RegistrationCode.Problem.INVALID_FORMAT
                    ? ErrorCode.VALIDATION_FAILED : ErrorCode.CODE_NOT_FOUND);
        }
        RegistrationCode code = parsed.code().orElseThrow();
        Outcome outcome = dbRetry.execute(DbRetry.Idempotency.IDEMPOTENT,
                () -> transactions.execute(status -> {
                    Outcome inTx = registerInTx(guardianId, code);
                    if (inTx.kind() != Kind.REGISTERED) {
                        status.setRollbackOnly();
                    }
                    return inTx;
                }));
        if (outcome.kind() == Kind.RETRY_LOOKUP) {
            outcome = lookupBound(guardianId, code);
        }
        return switch (outcome.kind()) {
            case REGISTERED, REPLAYED -> {
                failureLimiter.clear(limiterKey);
                yield outcome.result();
            }
            case DELETION_IN_PROGRESS -> throw CoreApiException.of(ErrorCode.ACCOUNT_DELETION_IN_PROGRESS);
            case GUARDIAN_MISSING -> throw CoreApiException.of(ErrorCode.UNAUTHENTICATED);
            case CODE_NOT_FOUND -> throw failed(guardianId, ErrorCode.CODE_NOT_FOUND);
            case CODE_ALREADY_REGISTERED -> throw failed(guardianId, ErrorCode.CODE_ALREADY_REGISTERED);
            case DEVICE_ALREADY_ACTIVE -> throw failed(guardianId, ErrorCode.DEVICE_ALREADY_ACTIVE);
            case RETRY_LOOKUP -> throw new IllegalStateException("재조회 뒤에도 갈래가 정해지지 않았다");
        };
    }

    private Outcome registerInTx(UUID guardianId, RegistrationCode code) {
        Instant now = clock.now();
        Optional<String> guardianStatus = devices.lockGuardianStatusForShare(guardianId);
        if (guardianStatus.isEmpty()) {
            return Outcome.of(Kind.GUARDIAN_MISSING);
        }
        if (!Guardian.STATUS_ACTIVE.equals(guardianStatus.get())) {
            return Outcome.of(Kind.DELETION_IN_PROGRESS);
        }
        Optional<DeviceRecord> pending = devices.lockPendingByCode(code.value(), now);
        if (pending.isEmpty()) {
            return lookupBound(guardianId, code);
        }
        Optional<DeviceRecord> previous = devices.lockActiveByGuardian(guardianId);
        if (previous.isPresent()) {
            DeviceRecord old = previous.get();
            if (!viewAssembler.verdictOf(old, now).allowsReplacement()) {
                return Outcome.of(Kind.DEVICE_ALREADY_ACTIVE);
            }
            if (devices.releaseRegistered(old.id(), DeviceReleased.Reason.REREGISTERED, now) != 1) {
                return Outcome.of(Kind.DEVICE_ALREADY_ACTIVE);
            }
            outbox.write(new DeviceReleased(old.id(), guardianId, DeviceReleased.Reason.REREGISTERED), guardianId, now);
        }
        int updated;
        try {
            updated = devices.register(pending.get().id(), guardianId, now);
        } catch (ActiveDeviceConflictException conflict) {
            return Outcome.of(Kind.DEVICE_ALREADY_ACTIVE);
        }
        if (updated != 1) {
            return Outcome.of(Kind.RETRY_LOOKUP);
        }
        return new Outcome(Kind.REGISTERED, new RegistrationResult(pending.get().id(), code.prefix(), now));
    }

    /** BR2.1 ② ③: 같은 코드의 REGISTERED · RELEASED 행으로 답을 정한다. */
    private Outcome lookupBound(UUID guardianId, RegistrationCode code) {
        List<DeviceRecord> bound = devices.findBoundByCode(code.value());
        for (DeviceRecord row : bound) {
            if (row.registrationStatus() == RegistrationStatus.REGISTERED && guardianId.equals(row.guardianId())) {
                return new Outcome(Kind.REPLAYED, new RegistrationResult(row.id(), row.codePrefix(), row.registeredAt()));
            }
        }
        return Outcome.of(bound.isEmpty() ? Kind.CODE_NOT_FOUND : Kind.CODE_ALREADY_REGISTERED);
    }

    /** 실패 한도 가산 + REGISTER_CODE 사건(BR2.6 · OD-13) 뒤 거절. */
    private CoreApiException failed(UUID guardianId, ErrorCode code) {
        failureLimiter.record(guardianId.toString());
        events.log(EventCode.SECURITY_AUTH_FAILED, EventLog.Component.DEVICE_REGISTRY, guardianId, entry -> entry
                .attr(AttrKey.KIND, "REGISTER_CODE")
                .attr(AttrKey.STATUS_CODE, code.httpStatus()));
        return CoreApiException.of(code);
    }

    private enum Kind {
        REGISTERED,
        REPLAYED,
        RETRY_LOOKUP,
        GUARDIAN_MISSING,
        DELETION_IN_PROGRESS,
        CODE_NOT_FOUND,
        CODE_ALREADY_REGISTERED,
        DEVICE_ALREADY_ACTIVE
    }

    private record Outcome(Kind kind, RegistrationResult result) {
        static Outcome of(Kind kind) {
            return new Outcome(kind, null);
        }
    }

    /** 등록 결과(201 {deviceId, codePrefix, registeredAt}, BR2.7). 해시 · 토큰은 없다. */
    public record RegistrationResult(UUID deviceId, String codePrefix, Instant registeredAt) {

        public RegistrationResult {
            Objects.requireNonNull(deviceId, "deviceId");
            Objects.requireNonNull(codePrefix, "codePrefix");
            Objects.requireNonNull(registeredAt, "registeredAt");
        }
    }
}
