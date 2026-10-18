package kr.mujoong.core.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Core 앱이 읽는 {@code mujoong.*} 설정 트리 (U1 3.5 Step 3 · RD-24 · BR8.11 · NFR6.1).
 *
 * <p>값의 정본은 reliability-design.md {@code ## 3}과 {@code contracts/timing.md}이고, 값은
 * {@code application*.yml}에만 둔다. 코드에는 기본값이 없어 키가 빠지면 기동이 실패한다. 비밀은 이 트리의
 * 키가 아니라 환경 변수 이름으로만 받는다(예외: {@code observability.owner-hash-secret}은 환경 변수
 * {@code MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET}에서 채우며 문자열 표현에 값을 내지 않는다).
 *
 * <p>기동 검증(RD-24): 모든 시간 · 건수 값이 양수, {@code security.session-cookie-max-age ≤
 * timing.login.session-idle}, 불변식 I-6 {@code timing.core.device-status-stale ≥
 * timing.device.status-report-interval × 3}, 허용 origin 형식. 오류 메시지에는 키 이름만 쓴다.
 * DB 풀 상한(I-7 · NFR7.6)은 {@link CorePropertiesConfig}가 확인한다.
 *
 * <p>묶음마다 타입은 {@code Core<묶음>Properties} 파일로 나눠 이 패키지에 둔다. 이 파일에는 루트 레코드와 묶음들이 함께 쓰는 검증
 * 도우미만 남긴다.
 */
@ConfigurationProperties(prefix = "mujoong")
public record CoreProperties(
        CoreTimingProperties timing,
        CoreOutboxProperties outbox,
        CoreInboxProperties inbox,
        CoreRetentionProperties retention,
        CoreCleanupProperties cleanup,
        CoreSessionProperties session,
        CoreBootstrapProperties bootstrap,
        CoreInternalHttpProperties internalHttp,
        CoreHealthProperties health,
        CoreLimitsProperties limits,
        CoreSecurityProperties security,
        CoreDevLoginProperties devLogin,
        CoreLoadTestProperties loadTest,
        CoreMetricsProperties metrics,
        CoreObservabilityProperties observability,
        CoreGuardianProperties guardian) {

    /** I-6: Core의 '확인 불가' 판정 시한은 Realtime 상태 보고 주기 세 번 이상이다(보고 두 번 유실로 stale 아님). */
    static final int STALE_TO_REPORT_RATIO = 3;

    public CoreProperties {
        required(timing, "mujoong.timing");
        required(outbox, "mujoong.outbox");
        required(inbox, "mujoong.inbox");
        required(retention, "mujoong.retention");
        required(cleanup, "mujoong.cleanup");
        required(session, "mujoong.session");
        required(bootstrap, "mujoong.bootstrap");
        required(internalHttp, "mujoong.internal-http");
        required(health, "mujoong.health");
        required(limits, "mujoong.limits");
        required(security, "mujoong.security");
        required(devLogin, "mujoong.dev-login");
        required(loadTest, "mujoong.load-test");
        required(metrics, "mujoong.metrics");
        required(observability, "mujoong.observability");
        required(guardian, "mujoong.guardian");

        if (security.sessionCookieMaxAge().compareTo(timing.login().sessionIdle()) > 0) {
            throw invalid("mujoong.security.session-cookie-max-age는 mujoong.timing.login.session-idle 이하여야 한다");
        }
        Duration minimumStale = timing.device().statusReportInterval().multipliedBy(STALE_TO_REPORT_RATIO);
        if (timing.core().deviceStatusStale().compareTo(minimumStale) < 0) {
            throw invalid("불변식 I-6: mujoong.timing.core.device-status-stale는 "
                    + "mujoong.timing.device.status-report-interval의 3배 이상이어야 한다");
        }
    }

    // ---- 검증 도우미: 메시지에는 키 이름만 쓴다(값 미출력) ----

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    static <T> T required(T value, String key) {
        if (value == null) {
            throw invalid("필수 설정 키가 없다: " + key);
        }
        return value;
    }

    static void positive(Duration value, String key) {
        required(value, key);
        if (value.isZero() || value.isNegative()) {
            throw invalid("설정 키는 0보다 커야 한다: " + key);
        }
    }

    static void positive(Integer value, String key) {
        required(value, key);
        if (value <= 0) {
            throw invalid("설정 키는 0보다 커야 한다: " + key);
        }
    }

    static void nonNegative(Integer value, String key) {
        required(value, key);
        if (value < 0) {
            throw invalid("설정 키는 0 이상이어야 한다: " + key);
        }
    }

    static void positive(DataSize value, String key) {
        required(value, key);
        if (value.toBytes() <= 0) {
            throw invalid("설정 키는 0보다 커야 한다: " + key);
        }
    }

    static void positiveList(List<Duration> values, String key) {
        required(values, key);
        if (values.isEmpty()) {
            throw invalid("설정 키에 값이 하나 이상 있어야 한다: " + key);
        }
        for (Duration value : values) {
            positive(value, key);
        }
    }

    static void httpUrl(URI value, String key) {
        required(value, key);
        String scheme = value.getScheme() == null ? "" : value.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || value.getHost() == null) {
            throw invalid("설정 키는 http(s) 주소여야 한다: " + key);
        }
    }

    /** 비밀 · 토큰을 주고받는 바깥 주소: https만, 평문 http는 루프백 호스트(시험 대역)일 때만. */
    static void secureUrl(URI value, String key) {
        required(value, key);
        String scheme = value.getScheme() == null ? "" : value.getScheme().toLowerCase(Locale.ROOT);
        String host = value.getHost() == null ? "" : value.getHost().toLowerCase(Locale.ROOT);
        boolean allowed = !host.isEmpty()
                && (scheme.equals("https") || (scheme.equals("http") && LOOPBACK_HOSTS.contains(host)));
        if (!allowed) {
            throw invalid("설정 키는 https 주소여야 한다(평문 http는 루프백 시험 주소만): " + key);
        }
    }

    /**
     * 허용 origin은 {@code scheme://host[:port]} 하나다(경로 · 질의 · 사용자 정보 없음). https만 허용하고,
     * http는 루프백 호스트(dev의 Secure Context 예외)일 때만 허용한다.
     */
    static String origin(String value, String key) {
        if (value == null || value.isBlank()) {
            throw invalid("필수 설정 키가 비어 있다: " + key + " (환경 변수 MUJOONG_SECURITY_ALLOWED_ORIGIN)");
        }
        URI uri;
        try {
            uri = new URI(value.trim());
        } catch (URISyntaxException e) {
            throw invalid("설정 키가 origin 형식이 아니다: " + key);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean bareOrigin = !host.isEmpty()
                && uri.getRawUserInfo() == null
                && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null;
        boolean allowedScheme = scheme.equals("https") || (scheme.equals("http") && LOOPBACK_HOSTS.contains(host));
        if (!bareOrigin || !allowedScheme) {
            throw invalid("설정 키가 허용 origin 형식(https://host[:port])이 아니다: " + key);
        }
        return value.trim();
    }

    static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
