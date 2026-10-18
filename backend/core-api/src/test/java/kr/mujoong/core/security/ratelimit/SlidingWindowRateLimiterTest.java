package kr.mujoong.core.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.config.CorePropertiesConfig;
import kr.mujoong.core.config.CoreTimingProperties;
import kr.mujoong.core.device.BootstrapService;
import kr.mujoong.core.device.CodeGenerator;
import kr.mujoong.core.device.DeviceJdbcRepository;
import kr.mujoong.core.device.DeviceSecrets;
import kr.mujoong.core.security.ratelimit.SlidingWindowRateLimiter.Limiter;
import kr.mujoong.core.support.TestEvents;
import kr.mujoong.testsupport.secret.TestSecrets;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 슬라이딩 창 한도기 (BR1.6 · BR2.6 · 편차 (k) · A6 · SD-10). bootstrap 20회 · 1시간(21번째 거절), 같은 쿠키 재요청 미계수, IPv6 /64 묶음,
 * 키 상한 LRU 밀어내기, 내부 오류는 통과(fail-open) + 알림, 등록 실패 10회 · 10분, load-test 상향 600 · 1시간은 dev+load-test 바인딩에서만.
 */
@Tag("skeleton")
class SlidingWindowRateLimiterTest {

    private static final Instant START = Instant.parse("2001-03-01T00:00:00Z");

    private final MutableClock clock = new MutableClock(START);

    @Test
    void twentyFirstIssueWithinAnHourIsRejectedAndWindowSlides() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(Limiter.BOOTSTRAP_IP, 20, Duration.ofHours(1), 100,
                clock, null);
        for (int i = 0; i < 20; i++) {
            assertThat(limiter.tryAcquire("203.0.113.7")).isTrue();
            clock.advance(Duration.ofSeconds(1));
        }

        assertThat(limiter.tryAcquire("203.0.113.7")).isFalse();
        assertThat(limiter.count("203.0.113.7")).isEqualTo(20);
        assertThat(limiter.tryAcquire("203.0.113.8")).isTrue();

        clock.setInstant(START.plus(Duration.ofHours(1)).plusSeconds(1));
        assertThat(limiter.tryAcquire("203.0.113.7")).isTrue();
    }

    @Test
    void sameCookieReturnsSameCodeWithoutConsumingTheLimit() {
        TestEvents events = new TestEvents(clock);
        DeviceJdbcRepository devices = mock(DeviceJdbcRepository.class);
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(Limiter.BOOTSTRAP_IP, 2, Duration.ofHours(1), 100,
                clock, null);
        BootstrapService service = new BootstrapService(devices, new CodeGenerator(new Random(3), 5), new DeviceSecrets(),
                limiter, events.logger(), clock, Duration.ofHours(24));
        List<DeviceJdbcRepository.PendingDevice> stored = new ArrayList<>();
        when(devices.insertPending(any())).thenAnswer(call -> stored.add(call.getArgument(0)));
        BootstrapService.BootstrapResult first = service.bootstrap(null, "198.51.100.1");
        String cookie = first.newCookieSecret().orElseThrow().value();
        DeviceJdbcRepository.PendingDevice row = stored.get(0);
        when(devices.findByBootstrapHash(DeviceSecrets.hash(cookie))).thenReturn(Optional.of(
                new DeviceJdbcRepository.DeviceRecord(row.id(), null, row.registrationCode(), row.issuedAt(),
                        row.codeExpiresAt(), false, null, null, kr.mujoong.contract.status.RegistrationStatus.UNREGISTERED,
                        null, null, null, null, null, null, null, null, 0, row.issuedAt(), row.issuedAt(), 1L)));

        for (int i = 0; i < 5; i++) {
            BootstrapService.BootstrapResult again = service.bootstrap(cookie, "198.51.100.1");
            assertThat(again.code()).isEqualTo(first.code());
            assertThat(again.issuedNewCookie()).isFalse();
        }

        assertThat(limiter.count("198.51.100.1")).isEqualTo(1);
        service.bootstrap(null, "198.51.100.1");
        assertThatThrownBy(() -> service.bootstrap(null, "198.51.100.1")).isInstanceOfSatisfying(CoreApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.RATE_LIMITED));
        assertThat(events.capture().events(EventCode.SECURITY_RATE_LIMITED)).hasSize(1);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        "203.0.113.9, 203.0.113.9",
        "2001:db8:abcd:12:1::1, 20010db8abcd0012::/64",
        "[2001:db8:abcd:12:ffff::2], 20010db8abcd0012::/64",
        "fe80::1%eth0, fe80000000000000::/64",
        "::ffff:198.51.100.4, 198.51.100.4",
        "not-an-address, invalid",
        "example.com, invalid"
    })
    void ipKeyGroupsIpv6ByPrefix64AndNeverResolvesNames(String remote, String expected) {
        assertThat(SlidingWindowRateLimiter.ipKey(remote)).isEqualTo(expected);
    }

    @Test
    void keyCapEvictsLeastRecentlyUsedAfterPurgingExpired() {
        List<Limiter> evictions = new ArrayList<>();
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(Limiter.BOOTSTRAP_IP, 20, Duration.ofHours(1), 3,
                clock, new SlidingWindowRateLimiter.Listener() {
                    @Override
                    public void evicted(Limiter which) {
                        evictions.add(which);
                    }
                });
        limiter.tryAcquire("a");
        limiter.tryAcquire("b");
        limiter.tryAcquire("c");
        limiter.tryAcquire("a");

        limiter.tryAcquire("d");

        assertThat(limiter.keyCount()).isEqualTo(3);
        assertThat(limiter.count("b")).isZero();
        assertThat(limiter.count("a")).isEqualTo(2);
        assertThat(evictions).containsExactly(Limiter.BOOTSTRAP_IP);

        clock.advance(Duration.ofHours(2));
        limiter.tryAcquire("e");
        assertThat(evictions).hasSize(1);
        assertThat(limiter.keyCount()).isEqualTo(1);
    }

    @Test
    void internalErrorFailsOpenAndIsReported() {
        MujoongClock broken = new MujoongClock() {
            @Override
            public Instant now() {
                throw new IllegalStateException("clock broken");
            }

            @Override
            public long monotonicNanos() {
                return 0L;
            }
        };
        List<RuntimeException> errors = new ArrayList<>();
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(Limiter.REGISTER_FAILURE, 1, Duration.ofMinutes(10),
                10, broken, new SlidingWindowRateLimiter.Listener() {
                    @Override
                    public void failedOpen(Limiter which, RuntimeException error) {
                        errors.add(error);
                    }
                });

        assertThat(limiter.tryAcquire("k")).isTrue();
        assertThat(limiter.isLimited("k")).isFalse();
        limiter.record("k");
        assertThat(errors).hasSize(3);
    }

    @Test
    void registerFailuresLimitTenPerTenMinutesAndClearOnSuccess() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(Limiter.REGISTER_FAILURE, 10,
                Duration.ofMinutes(10), 100, clock, null);
        for (int i = 0; i < 9; i++) {
            limiter.record("guardian");
        }
        assertThat(limiter.isLimited("guardian")).isFalse();
        limiter.record("guardian");
        assertThat(limiter.isLimited("guardian")).isTrue();

        limiter.clear("guardian");
        assertThat(limiter.isLimited("guardian")).isFalse();
        assertThat(limiter.count("guardian")).isZero();
        assertThatThrownBy(() -> new SlidingWindowRateLimiter(Limiter.REGISTER_FAILURE, 0, Duration.ofMinutes(1), 1, clock,
                null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loadTestRaiseTo600PerHourBindsOnlyWithLoadTestProfile() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(CorePropertiesConfig.class);

        runner.withPropertyValues("spring.profiles.active=dev,load-test",
                        "MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET=" + TestSecrets.ownerHashSecret())
                .run(context -> {
                    CoreTimingProperties.RateLimit limit =context.getBean(CoreProperties.class).timing().bootstrap().rateLimit();
                    assertThat(limit.max()).isEqualTo(600);
                    assertThat(limit.window()).isEqualTo(Duration.ofHours(1));
                    assertThat(context.getBean(CoreProperties.class).loadTest().enabled()).isTrue();
                });
        runner.withPropertyValues("spring.profiles.active=dev").run(context -> {
            CoreTimingProperties.RateLimit limit =context.getBean(CoreProperties.class).timing().bootstrap().rateLimit();
            assertThat(limit.max()).isEqualTo(20);
            assertThat(context.getBean(CoreProperties.class).loadTest().enabled()).isFalse();
        });
    }

    @Test
    void anyAddressFormIsCountedUnderOneKeyPerPrefix() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(Limiter.BOOTSTRAP_IP, 2, Duration.ofHours(1), 100,
                clock, null);

        assertThat(limiter.tryAcquire(SlidingWindowRateLimiter.ipKey("2001:db8::1"))).isTrue();
        assertThat(limiter.tryAcquire(SlidingWindowRateLimiter.ipKey("2001:db8::2"))).isTrue();

        assertThat(limiter.tryAcquire(SlidingWindowRateLimiter.ipKey("2001:db8:0:0:ffff::9"))).isFalse();
        assertThat(limiter.tryAcquire(SlidingWindowRateLimiter.ipKey("2001:db9::1"))).isTrue();
    }
}
