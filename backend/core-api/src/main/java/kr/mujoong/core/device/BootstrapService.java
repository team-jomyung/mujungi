package kr.mujoong.core.device;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.status.RegistrationStatus;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.device.DeviceJdbcRepository.DeviceRecord;
import kr.mujoong.core.device.DeviceJdbcRepository.PendingDevice;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.security.ratelimit.RateLimiterConfig.RateLimiters;
import kr.mujoong.core.security.ratelimit.SlidingWindowRateLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 기기 부트스트랩: 코드 받기 · 등록 상태 조회 (U1 3.5 Step 19 · BR1.1 ~ BR1.7 · 편차 (a)(c)(k) · AC6.1.1 · AC6.1.2 · AC6.1.5).
 *
 * <p>코드 받기({@link #bootstrap}):
 *
 * <ol>
 *   <li>쿠키의 bootstrap 비밀 해시로 행을 찾는다. UNREGISTERED이고 만료 전이면 같은 코드 · 만료 시각을 돌려준다(BR1.3, 새 쿠키 없음, 한도
 *       미가산 (k)). REGISTERED(claim 전)이면 409 ALREADY_REGISTERED (a).
 *   <li>쿠키가 없거나 모르는 값이거나 행이 EXPIRED · RELEASED · 만료이면 새 발급이다. 새 발급만 IP 한도(1시간 20회)를 소비하고 넘으면 429
 *       RATE_LIMITED(BR1.6). 한도 소비는 이 서비스 한 곳이 한다(같은 쿠키 반복은 세지 않는다, (k)).
 *   <li>새 비밀(128비트) · 새 행(UNREGISTERED) · 새 코드(5회 추첨, 모두 겹치면 503 CODE_ISSUE_FAILED (c)). 비밀 원문은 응답 쿠키로만 간다.
 * </ol>
 *
 * <p>상태 조회({@link #status}): 상태와 만료 시각만 돌려주고 코드를 다시 보내지 않는다(BR1.7). RELEASED는 EXPIRED로 답한다. 쿠키가 없거나
 * 모르는 값이면 401 BOOTSTRAP_NOT_FOUND. 만료가 지난 UNREGISTERED 행은 EXPIRED로 바꾼다(BR1.4).
 */
@Service
public class BootstrapService {

    private final DeviceJdbcRepository devices;
    private final CodeGenerator codeGenerator;
    private final DeviceSecrets secrets;
    private final SlidingWindowRateLimiter ipLimiter;
    private final EventLogger events;
    private final MujoongClock clock;
    private final Duration codeTtl;

    @Autowired
    public BootstrapService(
            DeviceJdbcRepository devices,
            CodeGenerator codeGenerator,
            DeviceSecrets secrets,
            RateLimiters limiters,
            EventLogger events,
            MujoongClock clock,
            CoreProperties properties) {
        this(devices, codeGenerator, secrets, limiters.bootstrapIp(), events, clock,
                properties.timing().bootstrap().codeTtl());
    }

    public BootstrapService(
            DeviceJdbcRepository devices,
            CodeGenerator codeGenerator,
            DeviceSecrets secrets,
            SlidingWindowRateLimiter ipLimiter,
            EventLogger events,
            MujoongClock clock,
            Duration codeTtl) {
        this.devices = Objects.requireNonNull(devices, "devices");
        this.codeGenerator = Objects.requireNonNull(codeGenerator, "codeGenerator");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.ipLimiter = Objects.requireNonNull(ipLimiter, "ipLimiter");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.codeTtl = Objects.requireNonNull(codeTtl, "codeTtl");
    }

    /**
     * D0 코드 받기(POST /api/device/bootstrap).
     *
     * @param cookieSecret MUJOONG_DEVICE_BOOTSTRAP 쿠키 값(없으면 null)
     * @param remoteAddress 연결 원격 주소(신뢰 프록시를 거친 경우 Tomcat RemoteIp가 X-Forwarded-For로 바꾼 값, BR1.6 · BR8.9)
     * @throws CoreApiException ALREADY_REGISTERED(409) · RATE_LIMITED(429) · CODE_ISSUE_FAILED(503)
     */
    public BootstrapResult bootstrap(String cookieSecret, String remoteAddress) {
        Instant now = clock.now();
        Optional<DeviceRecord> existing = findByCookie(cookieSecret);
        if (existing.isPresent()) {
            DeviceRecord row = existing.get();
            if (row.registrationStatus() == RegistrationStatus.REGISTERED) {
                throw CoreApiException.of(ErrorCode.ALREADY_REGISTERED);
            }
            if (row.registrationStatus() == RegistrationStatus.UNREGISTERED) {
                if (row.isCodeValidAt(now)) {
                    return new BootstrapResult(RegistrationCode.issued(row.registrationCode()), row.codeExpiresAt(),
                            Optional.empty());
                }
                devices.markExpiredIfDue(row.id(), now);
            }
        }
        if (!ipLimiter.tryAcquire(SlidingWindowRateLimiter.ipKey(remoteAddress))) {
            events.log(EventCode.SECURITY_RATE_LIMITED, EventLog.Component.DEVICE_REGISTRY, null, entry -> entry
                    .attr(AttrKey.LIMITER, SlidingWindowRateLimiter.Limiter.BOOTSTRAP_IP.name())
                    .attr(AttrKey.STATUS_CODE, ErrorCode.RATE_LIMITED.httpStatus()));
            throw CoreApiException.of(ErrorCode.RATE_LIMITED);
        }
        return issue(now);
    }

    /**
     * D0 등록 확인(GET /api/device/bootstrap/status, BR1.7).
     *
     * @throws CoreApiException BOOTSTRAP_NOT_FOUND(401) — 쿠키가 없거나 모르는 값
     */
    public BootstrapStatus status(String cookieSecret) {
        Instant now = clock.now();
        DeviceRecord row = findByCookie(cookieSecret).orElseThrow(() -> CoreApiException.of(ErrorCode.BOOTSTRAP_NOT_FOUND));
        return switch (row.registrationStatus()) {
            case UNREGISTERED -> {
                if (row.isCodeValidAt(now)) {
                    yield new BootstrapStatus(BootstrapStatus.Status.PENDING, row.codeExpiresAt());
                }
                devices.markExpiredIfDue(row.id(), now);
                yield new BootstrapStatus(BootstrapStatus.Status.EXPIRED, row.codeExpiresAt());
            }
            case REGISTERED -> new BootstrapStatus(BootstrapStatus.Status.REGISTERED, row.codeExpiresAt());
            case RELEASED, EXPIRED, UNRECOGNIZED -> new BootstrapStatus(BootstrapStatus.Status.EXPIRED, row.codeExpiresAt());
        };
    }

    private BootstrapResult issue(Instant now) {
        DeviceSecrets.Secret secret = secrets.newBootstrapSecret();
        String secretHash = secret.hash();
        UUID deviceId = UUID.randomUUID();
        Instant expiresAt = now.plus(codeTtl);
        RegistrationCode code = codeGenerator.issue(candidate -> devices.insertPending(
                new PendingDevice(deviceId, candidate.value(), secretHash, now, expiresAt))
                        ? Optional.of(candidate)
                        : Optional.empty());
        return new BootstrapResult(code, expiresAt, Optional.of(secret));
    }

    private Optional<DeviceRecord> findByCookie(String cookieSecret) {
        if (!DeviceSecrets.looksLikeSecret(cookieSecret)) {
            return Optional.empty();
        }
        return devices.findByBootstrapHash(DeviceSecrets.hash(cookieSecret));
    }

    /**
     * 코드 받기 결과(201 {code, expiresAt}).
     *
     * @param newCookieSecret 새 발급이면 Set-Cookie MUJOONG_DEVICE_BOOTSTRAP에 실을 비밀(Max-Age = code-ttl), 같은 코드 재전송이면 빈 값
     */
    public record BootstrapResult(RegistrationCode code, Instant expiresAt, Optional<DeviceSecrets.Secret> newCookieSecret) {

        public BootstrapResult {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(expiresAt, "expiresAt");
            Objects.requireNonNull(newCookieSecret, "newCookieSecret");
        }

        /** 새 쿠키가 생겼는가. */
        public boolean issuedNewCookie() {
            return newCookieSecret.isPresent();
        }
    }

    /** 등록 확인 결과(200 {status, expiresAt}). */
    public record BootstrapStatus(Status status, Instant expiresAt) {

        /** 기기 웹이 보는 상태(BR1.7). */
        public enum Status {
            PENDING,
            REGISTERED,
            EXPIRED
        }
    }
}
