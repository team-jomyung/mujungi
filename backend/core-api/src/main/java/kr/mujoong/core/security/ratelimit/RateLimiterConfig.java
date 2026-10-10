package kr.mujoong.core.security.ratelimit;

import java.util.Objects;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.observability.CoreMetrics;
import kr.mujoong.core.observability.EventLogger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 한도기 두 개의 설정 (U1 3.5 Step 19 · SD-10 · BR1.6 · BR2.6 · A6).
 *
 * <p>값은 설정 키에서만 읽는다: bootstrap 발급 {@code mujoong.timing.bootstrap.rate-limit}(20 · 1시간, 상향 600 · 1시간은
 * {@code application-load-test.yml}에만), 등록 실패 {@code mujoong.timing.device.register-failure-limit}(10 · 10분), 키 상한
 * {@code mujoong.limits.rate-counter-max-keys}. 밀어내기 · 내부 오류는 지표와 사건 로그(APP_ERROR WARN 성격, errorKind)로 알린다.
 */
@Configuration(proxyBeanMethods = false)
public class RateLimiterConfig {

    @Bean
    RateLimiters rateLimiters(CoreProperties properties, MujoongClock clock, CoreMetrics metrics, EventLogger events) {
        return RateLimiters.create(properties, clock, listener(metrics, events));
    }

    /** 지표 · 로그로 알리는 처리기. */
    static SlidingWindowRateLimiter.Listener listener(CoreMetrics metrics, EventLogger events) {
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(events, "events");
        return new SlidingWindowRateLimiter.Listener() {
            @Override
            public void evicted(SlidingWindowRateLimiter.Limiter limiter) {
                metrics.rateLimiterEviction(limiter.name());
            }

            @Override
            public void failedOpen(SlidingWindowRateLimiter.Limiter limiter, RuntimeException error) {
                metrics.rateLimiterError(limiter.name());
                events.appError(EventLog.Component.SECURITY, error);
            }
        };
    }

    /** Core의 한도기 묶음. */
    public record RateLimiters(SlidingWindowRateLimiter bootstrapIp, SlidingWindowRateLimiter registerFailure) {

        public RateLimiters {
            Objects.requireNonNull(bootstrapIp, "bootstrapIp");
            Objects.requireNonNull(registerFailure, "registerFailure");
        }

        /** 설정 값으로 두 한도기를 만든다. */
        public static RateLimiters create(
                CoreProperties properties, MujoongClock clock, SlidingWindowRateLimiter.Listener listener) {
            CoreProperties.RateLimit bootstrap = properties.timing().bootstrap().rateLimit();
            CoreProperties.RateLimit failures = properties.timing().device().registerFailureLimit();
            int maxKeys = properties.limits().rateCounterMaxKeys();
            return new RateLimiters(
                    new SlidingWindowRateLimiter(SlidingWindowRateLimiter.Limiter.BOOTSTRAP_IP, bootstrap.max(),
                            bootstrap.window(), maxKeys, clock, listener),
                    new SlidingWindowRateLimiter(SlidingWindowRateLimiter.Limiter.REGISTER_FAILURE, failures.max(),
                            failures.window(), maxKeys, clock, listener));
        }
    }
}
