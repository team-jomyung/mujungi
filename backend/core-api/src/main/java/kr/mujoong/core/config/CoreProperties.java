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
 */
@ConfigurationProperties(prefix = "mujoong")
public record CoreProperties(
        Timing timing,
        Outbox outbox,
        Inbox inbox,
        Retention retention,
        Cleanup cleanup,
        Session session,
        Bootstrap bootstrap,
        InternalHttp internalHttp,
        Health health,
        Limits limits,
        Security security,
        DevLogin devLogin,
        LoadTest loadTest,
        Metrics metrics,
        Observability observability,
        Guardian guardian) {

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

    /** 계약 7절 시간 키와 J80이 허용한 7절 밖 시간 키(cache). */
    public record Timing(
            SessionTiming session,
            CoreTiming core,
            BootstrapTiming bootstrap,
            DeviceTiming device,
            LoginTiming login,
            CacheTiming cache,
            AccountTiming account,
            PracticeTiming practice,
            GoalTiming goal,
            ResultTiming result,
            ReportTiming report,
            PushTiming push) {

        public Timing {
            required(session, "mujoong.timing.session");
            required(core, "mujoong.timing.core");
            required(bootstrap, "mujoong.timing.bootstrap");
            required(device, "mujoong.timing.device");
            required(login, "mujoong.timing.login");
            required(cache, "mujoong.timing.cache");
            required(account, "mujoong.timing.account");
            required(practice, "mujoong.timing.practice");
            required(goal, "mujoong.timing.goal");
            required(result, "mujoong.timing.result");
            required(report, "mujoong.timing.report");
            required(push, "mujoong.timing.push");
        }
    }

    /** U6 BR1 · BR2: 작업 재시도 · 실행 · 누락 판정의 시간 계약. */
    public record ResultTiming(Integer jobAttempts, List<Duration> jobRetryBackoff, Duration jobTick,
            Duration attemptTimeout, Duration reconcileInterval, Duration undecidedLimit) {
        public ResultTiming {
            positive(jobAttempts, "mujoong.timing.result.job-attempts");
            positiveList(jobRetryBackoff, "mujoong.timing.result.job-retry-backoff");
            if (jobAttempts != 3 || jobRetryBackoff.size() != jobAttempts - 1) {
                throw invalid("mujoong.timing.result 시도 수와 재시도 간격이 계약과 다르다");
            }
            jobRetryBackoff = List.copyOf(jobRetryBackoff);
            positive(jobTick, "mujoong.timing.result.job-tick");
            positive(attemptTimeout, "mujoong.timing.result.attempt-timeout");
            positive(reconcileInterval, "mujoong.timing.result.reconcile-interval");
            positive(undecidedLimit, "mujoong.timing.result.undecided-limit");
        }
    }

    /** U6 BR9.4 · BR10.4: 리포트 절대 마감과 미저장 보존. */
    public record ReportTiming(Duration readyTarget, Duration unsavedRetention) {
        public ReportTiming {
            positive(readyTarget, "mujoong.timing.report.ready-target");
            positive(unsavedRetention, "mujoong.timing.report.unsaved-retention");
        }
    }

    public record PushTiming(Duration dispatchPoll, List<Duration> retryBackoff,
            Duration sendTimeout, Duration deviceStatusCheck) {
        public PushTiming {
            positive(dispatchPoll, "mujoong.timing.push.dispatch-poll");
            positiveList(retryBackoff, "mujoong.timing.push.retry-backoff");
            positive(sendTimeout, "mujoong.timing.push.send-timeout");
            positive(deviceStatusCheck, "mujoong.timing.push.device-status-check");
            retryBackoff = List.copyOf(retryBackoff);
        }
    }

    /** U5a C10 상황 생성 전체 예산(생성 · 재생성 · 기술 재시도 포함, 요청 선점 시한도 같다)(timing.md 9절 · U5a-J18). */
    public record PracticeTiming(Duration scenarioRequestTimeout) {
        public PracticeTiming {
            positive(scenarioRequestTimeout, "mujoong.timing.practice.scenario-request-timeout");
        }
    }

    /** U5a 목표 삭제 뒤 서버가 복구를 받아 주는 시한(C4 · timing.md 9절 · U5a-J3). */
    public record GoalTiming(Duration restoreWindow) {
        public GoalTiming {
            positive(restoreWindow, "mujoong.timing.goal.restore-window");
        }
    }

    /**
     * U2 계정 삭제 지휘 주기 · 진행 세션 끝 대기(AC1.6.3) · 구글 서명 열쇠 캐시 바닥 · 상한(2차 리뷰 4 · 3차 리뷰 3)(timing.md 8절). 열쇠 캐시는
     * 응답의 max-age만큼 두되 바닥보다 짧게 두지 않고 상한을 넘지 않는다.
     */
    public record AccountTiming(
            Duration deletionTick, Duration deletionSessionWait, Duration googleKeysMinAge, Duration googleKeysMaxAge) {
        public AccountTiming {
            positive(deletionTick, "mujoong.timing.account.deletion-tick");
            positive(deletionSessionWait, "mujoong.timing.account.deletion-session-wait");
            positive(googleKeysMinAge, "mujoong.timing.account.google-keys-min-age");
            positive(googleKeysMaxAge, "mujoong.timing.account.google-keys-max-age");
            if (googleKeysMinAge.compareTo(googleKeysMaxAge) > 0) {
                throw new IllegalArgumentException(
                        "mujoong.timing.account.google-keys-min-age가 mujoong.timing.account.google-keys-max-age보다 길다");
            }
        }
    }

    /** BR6.12 ① PREPARING 10초 점검. */
    public record SessionTiming(Duration preparingTimeout) {
        public SessionTiming {
            positive(preparingTimeout, "mujoong.timing.session.preparing-timeout");
        }
    }

    /** BR4.3 · RD-6 사본 신선도 기준. */
    public record CoreTiming(Duration deviceStatusStale) {
        public CoreTiming {
            positive(deviceStatusStale, "mujoong.timing.core.device-status-stale");
        }
    }

    /** BR1.4 코드 TTL(bootstrap 쿠키 Max-Age도 이 값) · BR1.6 IP 발급 한도. */
    public record BootstrapTiming(Duration codeTtl, RateLimit rateLimit) {
        public BootstrapTiming {
            positive(codeTtl, "mujoong.timing.bootstrap.code-ttl");
            required(rateLimit, "mujoong.timing.bootstrap.rate-limit");
            rateLimit.validate("mujoong.timing.bootstrap.rate-limit");
        }
    }

    /**
     * BR2.6 등록 실패 한도 · BR3.1 기기 쿠키 수명. {@code statusReportInterval}은 Realtime이 소유하는
     * 계약 7절 값이고 Core는 I-6 기동 검증에만 읽는다.
     */
    public record DeviceTiming(RateLimit registerFailureLimit, Duration cookieMaxAge, Duration statusReportInterval) {
        public DeviceTiming {
            required(registerFailureLimit, "mujoong.timing.device.register-failure-limit");
            registerFailureLimit.validate("mujoong.timing.device.register-failure-limit");
            positive(cookieMaxAge, "mujoong.timing.device.cookie-max-age");
            positive(statusReportInterval, "mujoong.timing.device.status-report-interval");
        }
    }

    /** NFR9.2 보호자 세션 유휴 만료. */
    public record LoginTiming(Duration sessionIdle) {
        public LoginTiming {
            positive(sessionIdle, "mujoong.timing.login.session-idle");
        }
    }

    /** PD-7 승인 문구 · 정책 버전 캐시(7절 밖 시간 키, J80). */
    public record CacheTiming(Duration approvedContentTtl, Duration policyVersionTtl) {
        public CacheTiming {
            positive(approvedContentTtl, "mujoong.timing.cache.approved-content-ttl");
            positive(policyVersionTtl, "mujoong.timing.cache.policy-version-ttl");
        }
    }

    /** 미끄러지는 창 한도(max 회 / window). */
    public record RateLimit(Integer max, Duration window) {
        void validate(String key) {
            positive(max, key + ".max");
            positive(window, key + ".window");
        }
    }

    /** RD-8 · BR5.2 · BR5.3 Outbox 발송과 A1 제어 이벤트 정책. */
    public record Outbox(
            Duration pollInterval,
            Integer batchSize,
            List<Duration> retryBackoff,
            Integer maxRetries,
            Duration claimLease,
            ControlEventPolicy controlEventPolicy) {

        public Outbox {
            positive(pollInterval, "mujoong.outbox.poll-interval");
            positive(batchSize, "mujoong.outbox.batch-size");
            positiveList(retryBackoff, "mujoong.outbox.retry-backoff");
            positive(maxRetries, "mujoong.outbox.max-retries");
            positive(claimLease, "mujoong.outbox.claim-lease");
            required(controlEventPolicy, "mujoong.outbox.control-event-policy");
            retryBackoff = List.copyOf(retryBackoff);
        }

        /** A1 · RD-10. RETRY_FOREVER는 보류 옵션이며 기동 거부는 재무장 장치가 맡는다(J124). */
        public enum ControlEventPolicy {
            REARM_ON_START,
            RETRY_FOREVER
        }
    }

    /** RD-9 · BR5.5 ~ BR5.8 Inbox 처리. */
    public record Inbox(
            Duration pollInterval,
            List<Duration> retryBackoff,
            Integer maxRetries,
            Duration claimLease,
            Integer handlerThreads,
            Integer batchSize) {

        public Inbox {
            positive(pollInterval, "mujoong.inbox.poll-interval");
            positiveList(retryBackoff, "mujoong.inbox.retry-backoff");
            positive(maxRetries, "mujoong.inbox.max-retries");
            positive(claimLease, "mujoong.inbox.claim-lease");
            positive(handlerThreads, "mujoong.inbox.handler-threads");
            positive(batchSize, "mujoong.inbox.batch-size");
            retryBackoff = List.copyOf(retryBackoff);
        }
    }

    /** BR5.11 FAILED 행 본문 보존. */
    public record Retention(Duration failedPayload) {
        public Retention {
            positive(failedPayload, "mujoong.retention.failed-payload");
        }
    }

    /** BR5.11 · BR1.4 정리 작업. */
    public record Cleanup(
            Duration messagingInterval,
            Duration deviceExpiryInterval,
            Duration expiredDeviceRetention,
            Integer batchSize) {

        public Cleanup {
            positive(messagingInterval, "mujoong.cleanup.messaging-interval");
            positive(deviceExpiryInterval, "mujoong.cleanup.device-expiry-interval");
            positive(expiredDeviceRetention, "mujoong.cleanup.expired-device-retention");
            positive(batchSize, "mujoong.cleanup.batch-size");
        }
    }

    /** Core ① DB 점검 주기(reliability-design.md 3.6). */
    public record Session(Duration timerTick) {
        public Session {
            positive(timerTick, "mujoong.session.timer-tick");
        }
    }

    /** BR1.2 코드 추첨 시도 수. */
    public record Bootstrap(Integer codeDrawAttempts) {
        public Bootstrap {
            positive(codeDrawAttempts, "mujoong.bootstrap.code-draw-attempts");
        }
    }

    /** RD-14 · NFR4.12 Core → Realtime 호출 제한과 session_start 차단기(R3-2 · J74). 주소 키는 J109. */
    public record InternalHttp(
            URI realtimeBaseUrl,
            Duration connectTimeout,
            Duration sessionStartTimeout,
            Integer sessionStartRetries,
            Duration sessionEndTimeout,
            Duration sessionViewTimeout,
            Duration eventPublishTimeout,
            Integer breakerFailureThreshold,
            Duration breakerOpenDuration) {

        public InternalHttp {
            httpUrl(realtimeBaseUrl, "mujoong.internal-http.realtime-base-url");
            positive(connectTimeout, "mujoong.internal-http.connect-timeout");
            positive(sessionStartTimeout, "mujoong.internal-http.session-start-timeout");
            nonNegative(sessionStartRetries, "mujoong.internal-http.session-start-retries");
            positive(sessionEndTimeout, "mujoong.internal-http.session-end-timeout");
            positive(sessionViewTimeout, "mujoong.internal-http.session-view-timeout");
            positive(eventPublishTimeout, "mujoong.internal-http.event-publish-timeout");
            positive(breakerFailureThreshold, "mujoong.internal-http.breaker-failure-threshold");
            positive(breakerOpenDuration, "mujoong.internal-http.breaker-open-duration");
        }
    }

    /** RD-16 · OD-11 준비 상태 DB 점검. */
    public record Health(Duration dbCheckInterval, Integer dbFailureThreshold) {
        public Health {
            positive(dbCheckInterval, "mujoong.health.db-check-interval");
            positive(dbFailureThreshold, "mujoong.health.db-failure-threshold");
        }
    }

    /** BR1.6 · (r) · BR3.9 · RD-22 한도. */
    public record Limits(
            Integer rateCounterMaxKeys,
            DataSize restBodyMax,
            DataSize internalEventBodyMax,
            Integer backgroundDbConcurrency,
            Duration dbLockTimeout,
            Duration dbStatementTimeout,
            Integer dbRetryAttempts,
            Integer resultWorkers) {

        public Limits {
            positive(rateCounterMaxKeys, "mujoong.limits.rate-counter-max-keys");
            positive(restBodyMax, "mujoong.limits.rest-body-max");
            positive(internalEventBodyMax, "mujoong.limits.internal-event-body-max");
            positive(backgroundDbConcurrency, "mujoong.limits.background-db-concurrency");
            positive(dbLockTimeout, "mujoong.limits.db-lock-timeout");
            positive(dbStatementTimeout, "mujoong.limits.db-statement-timeout");
            nonNegative(dbRetryAttempts, "mujoong.limits.db-retry-attempts");
            positive(resultWorkers, "mujoong.limits.result-workers");
            if (resultWorkers != 2 || resultWorkers > backgroundDbConcurrency) {
                throw invalid("mujoong.limits.result-workers는 전용 스레드 둘이며 DB 동시성 이하여야 한다");
            }
        }
    }

    /** SD-5 세션 쿠키 수명 · SD-6 허용 origin(환경 변수 MUJOONG_SECURITY_ALLOWED_ORIGIN) · OD-3 보안 사건 상한. */
    public record Security(Duration sessionCookieMaxAge, String allowedOrigin, Integer eventLogRateCap) {
        public Security {
            positive(sessionCookieMaxAge, "mujoong.security.session-cookie-max-age");
            allowedOrigin = origin(allowedOrigin, "mujoong.security.allowed-origin");
            positive(eventLogRateCap, "mujoong.security.event-log-rate-cap");
        }
    }

    /** BR8.3 · SD-14 시험 로그인 스위치(어느 프로필 파일에도 true로 두지 않는다). */
    public record DevLogin(Boolean enabled) {
        public DevLogin {
            required(enabled, "mujoong.dev-login.enabled");
        }
    }

    /** A6 부하 시험 스위치(application-load-test.yml에서만 true). */
    public record LoadTest(Boolean enabled) {
        public LoadTest {
            required(enabled, "mujoong.load-test.enabled");
        }
    }

    /** NFR7.8 풀 대기 경고 기준. */
    public record Metrics(Duration dbPoolWaitWarn) {
        public Metrics {
            positive(dbPoolWaitWarn, "mujoong.metrics.db-pool-wait-warn");
        }
    }

    /**
     * OD-4 ownerHash 비밀 · OD-6 지표 스냅샷 · OD-3 반복 병합. 비밀이 비었을 때의 처리(demo · prod 기동 실패,
     * dev 임시 값)는 ownerHash 설정 장치가 맡는다. 문자열 표현에 비밀 값을 내지 않는다.
     *
     * <p>{@code systemSampleInterval}(3.6 수정 · J326)은 OD-6의 '앱 안 5초 표본'(CPU 평균 · 메모리 최댓값 · 적체 게이지 갱신) 주기다. 스냅샷
     * 주기보다 길면 구간에 표본이 없을 수 있어 기동을 거부한다.
     */
    public record Observability(
            String ownerHashSecret,
            Duration metricSnapshotInterval,
            Duration logMergeWindow,
            Duration systemSampleInterval) {

        public Observability {
            ownerHashSecret = ownerHashSecret == null ? "" : ownerHashSecret;
            positive(metricSnapshotInterval, "mujoong.observability.metric-snapshot-interval");
            positive(logMergeWindow, "mujoong.observability.log-merge-window");
            positive(systemSampleInterval, "mujoong.observability.system-sample-interval");
            if (systemSampleInterval.compareTo(metricSnapshotInterval) > 0) {
                throw invalid("mujoong.observability.system-sample-interval은 "
                        + "mujoong.observability.metric-snapshot-interval 이하여야 한다");
            }
        }

        @Override
        public String toString() {
            return "Observability[ownerHashSecret=" + (ownerHashSecret.isEmpty() ? "<empty>" : "<redacted>")
                    + ", metricSnapshotInterval=" + metricSnapshotInterval
                    + ", logMergeWindow=" + logMergeWindow
                    + ", systemSampleInterval=" + systemSampleInterval + "]";
        }
    }

    /** U2 보호자 계정 설정(구글 OAuth, U2-J1). */
    public record Guardian(Google google) {
        public Guardian {
            required(google, "mujoong.guardian.google");
        }
    }

    /**
     * 구글 OAuth 클라이언트(U2-J1 · U2-J2). client-id · client-secret · redirect-uri는 환경 변수 {@code GOOGLE_OAUTH_CLIENT_ID} ·
     * {@code GOOGLE_OAUTH_CLIENT_SECRET} · {@code GOOGLE_OAUTH_REDIRECT_URI}에서만 채우고 비어 있어도 기동은 막지 않는다(로그인이 401).
     * 토큰 엔드포인트와 서명 열쇠(JWKS) 주소는 https만 받고 평문 http는 루프백 시험 주소만 받는다 — 어긋나면 기동을 거부한다.
     * 문자열 표현에 비밀 값을 내지 않는다.
     */
    public record Google(
            String clientId, String clientSecret, String redirectUri, URI tokenUri, URI jwksUri, Duration exchangeTimeout) {

        public Google {
            clientId = clientId == null ? "" : clientId.trim();
            clientSecret = clientSecret == null ? "" : clientSecret;
            redirectUri = redirectUri == null ? "" : redirectUri.trim();
            secureUrl(tokenUri, "mujoong.guardian.google.token-uri");
            secureUrl(jwksUri, "mujoong.guardian.google.jwks-uri");
            positive(exchangeTimeout, "mujoong.guardian.google.exchange-timeout");
        }

        /** 세 값이 모두 있어 로그인을 받을 수 있는가. */
        public boolean configured() {
            return !clientId.isEmpty() && !clientSecret.isEmpty() && !redirectUri.isEmpty();
        }

        @Override
        public String toString() {
            return "Google[clientId=" + (clientId.isEmpty() ? "<empty>" : "<set>")
                    + ", clientSecret=" + (clientSecret.isEmpty() ? "<empty>" : "<redacted>")
                    + ", redirectUri=" + (redirectUri.isEmpty() ? "<empty>" : "<set>")
                    + ", tokenUri=" + tokenUri + ", jwksUri=" + jwksUri + ", exchangeTimeout=" + exchangeTimeout + "]";
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
