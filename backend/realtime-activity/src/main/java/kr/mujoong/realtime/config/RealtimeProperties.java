package kr.mujoong.realtime.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Realtime 앱이 읽는 {@code mujoong.*} 설정 트리 (U1 3.5 Step 3 · RD-24 · BR8.11 · NFR6.1).
 *
 * <p>값의 정본은 reliability-design.md {@code ## 3}과 {@code contracts/timing.md}이고, 값은
 * {@code application*.yml}에만 둔다. 코드에는 기본값이 없어 키가 빠지면 기동이 실패한다. Core와 같은 이름의
 * 묶음(outbox · inbox 등)도 앱마다 따로 둔다(LC-11: Boot 설정은 공유하지 않음).
 *
 * <p>{@code provider.url}은 {@code application.yml}에 없고 demo · prod 프로필 파일에만 있다(J141). 비었을 때
 * 기동 거부 · 허용 호스트 검사 · test 프로필 루프백 검사는 기동 점검(S1b)이 맡는다.
 *
 * <p>기동 검증(RD-24): 모든 시간 · 건수 값이 양수, 불변식 I-3 {@code internal-http.verify-token-timeout <
 * timing.device.hello-timeout}, I-10 {@code provider.connect-timeout ≤ timing.session.device-ack-timeout},
 * 허용 origin 형식. 오류 메시지에는 키 이름만 쓴다. DB 풀 상한은 {@link RealtimePropertiesConfig}가 확인한다.
 */
@ConfigurationProperties(prefix = "mujoong")
public record RealtimeProperties(
        Timing timing,
        Outbox outbox,
        Inbox inbox,
        Retention retention,
        Cleanup cleanup,
        Session session,
        Device device,
        InternalHttp internalHttp,
        Provider provider,
        Health health,
        Limits limits,
        Gate gate,
        Security security,
        Metrics metrics,
        Observability observability) {

    public RealtimeProperties {
        required(timing, "mujoong.timing");
        required(outbox, "mujoong.outbox");
        required(inbox, "mujoong.inbox");
        required(retention, "mujoong.retention");
        required(cleanup, "mujoong.cleanup");
        required(session, "mujoong.session");
        required(device, "mujoong.device");
        required(internalHttp, "mujoong.internal-http");
        required(provider, "mujoong.provider");
        required(health, "mujoong.health");
        required(limits, "mujoong.limits");
        required(gate, "mujoong.gate");
        required(security, "mujoong.security");
        required(metrics, "mujoong.metrics");
        required(observability, "mujoong.observability");

        if (internalHttp.verifyTokenTimeout().compareTo(timing.device().helloTimeout()) >= 0) {
            throw invalid("불변식 I-3: mujoong.internal-http.verify-token-timeout는 "
                    + "mujoong.timing.device.hello-timeout보다 짧아야 한다");
        }
        if (provider.connectTimeout().compareTo(timing.session().deviceAckTimeout()) > 0) {
            throw invalid("불변식 I-10: mujoong.provider.connect-timeout는 "
                    + "mujoong.timing.session.device-ack-timeout 이하여야 한다");
        }
    }

    /** 계약 7절 시간 키와 J80이 허용한 7절 밖 시간 키(recorder). */
    public record Timing(SessionTiming session, DeviceTiming device, RecorderTiming recorder) {
        public Timing {
            required(session, "mujoong.timing.session");
            required(device, "mujoong.timing.device");
            required(recorder, "mujoong.timing.recorder");
        }
    }

    /** BR6.12 ② 기기 응답 20초((h) PROVIDER_UNAVAILABLE에도) · ③ 마무리 확인 30초. */
    public record SessionTiming(Duration deviceAckTimeout, Duration closingConfirm) {
        public SessionTiming {
            positive(deviceAckTimeout, "mujoong.timing.session.device-ack-timeout");
            positive(closingConfirm, "mujoong.timing.session.closing-confirm");
        }
    }

    /** BR4.8 끊김 40초 · HEARTBEAT 주기(HELLO_OK로 내려줌) · HELLO 5초 · 상태 보고 10초 · 출력 대기열 20초 분량. */
    public record DeviceTiming(
            Duration disconnect,
            Duration heartbeatInterval,
            Duration helloTimeout,
            Duration statusReportInterval,
            Duration outputQueueMax) {

        public DeviceTiming {
            positive(disconnect, "mujoong.timing.device.disconnect");
            positive(heartbeatInterval, "mujoong.timing.device.heartbeat-interval");
            positive(helloTimeout, "mujoong.timing.device.hello-timeout");
            positive(statusReportInterval, "mujoong.timing.device.status-report-interval");
            positive(outputQueueMax, "mujoong.timing.device.output-queue-max");
        }
    }

    /** PD-5 기록기 플러시 주기(7절 밖 시간 키, J80). */
    public record RecorderTiming(Duration flushInterval) {
        public RecorderTiming {
            positive(flushInterval, "mujoong.timing.recorder.flush-interval");
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

    /** BR5.11 메시징 정리. */
    public record Cleanup(Duration messagingInterval, Integer batchSize) {
        public Cleanup {
            positive(messagingInterval, "mujoong.cleanup.messaging-interval");
            positive(batchSize, "mujoong.cleanup.batch-size");
        }
    }

    /** BR6.7 묘비 10분 · R3-6 고아 세션 점검 · 타이머 전이 실패 재시도 간격. */
    public record Session(Duration tombstoneTtl, Duration orphanCheckInterval, Duration timerTick) {
        public Session {
            positive(tombstoneTtl, "mujoong.session.tombstone-ttl");
            positive(orphanCheckInterval, "mujoong.session.orphan-check-interval");
            positive(timerTick, "mujoong.session.timer-tick");
        }
    }

    /** BR4.8 DISCONNECTED 점검 주기. */
    public record Device(Duration disconnectCheckInterval) {
        public Device {
            positive(disconnectCheckInterval, "mujoong.device.disconnect-check-interval");
        }
    }

    /** RD-14 Realtime → Core 호출 제한과 상태 보고 라운드 건너뛰기(R3-2). 주소 키는 J109. */
    public record InternalHttp(
            URI coreBaseUrl,
            Duration connectTimeout,
            Duration verifyTokenTimeout,
            Duration deviceStatusTimeout,
            Duration eventPublishTimeout,
            Integer statusRoundSkipThreshold) {

        public InternalHttp {
            httpUrl(coreBaseUrl, "mujoong.internal-http.core-base-url");
            positive(connectTimeout, "mujoong.internal-http.connect-timeout");
            positive(verifyTokenTimeout, "mujoong.internal-http.verify-token-timeout");
            positive(deviceStatusTimeout, "mujoong.internal-http.device-status-timeout");
            positive(eventPublishTimeout, "mujoong.internal-http.event-publish-timeout");
            positive(statusRoundSkipThreshold, "mujoong.internal-http.status-round-skip-threshold");
        }
    }

    /** RD-5 · J110 공급자 연결. url은 demo · prod 프로필 파일 또는 환경 변수 MUJOONG_PROVIDER_URL(J141). */
    public record Provider(URI url, Duration connectTimeout) {
        public Provider {
            positive(connectTimeout, "mujoong.provider.connect-timeout");
        }
    }

    /** RD-16 · OD-11 준비 상태 DB 점검. */
    public record Health(Duration dbCheckInterval, Integer dbFailureThreshold) {
        public Health {
            positive(dbCheckInterval, "mujoong.health.db-check-interval");
            positive(dbFailureThreshold, "mujoong.health.db-failure-threshold");
        }
    }

    /** NFR9.9 · BR3.9 · PD-2 ~ PD-5 · RD-22 한도. */
    public record Limits(
            DataSize wsTextFrameMax,
            DataSize internalEventBodyMax,
            Integer sessionInputQueueFrames,
            Integer sessionControlQueue,
            Integer deviceControlQueue,
            Integer recorderQueue,
            Integer recorderFlushConcurrency,
            Integer backgroundDbConcurrency,
            Duration dbLockTimeout,
            Duration dbStatementTimeout,
            Integer dbRetryAttempts) {

        public Limits {
            positive(wsTextFrameMax, "mujoong.limits.ws-text-frame-max");
            positive(internalEventBodyMax, "mujoong.limits.internal-event-body-max");
            positive(sessionInputQueueFrames, "mujoong.limits.session-input-queue-frames");
            positive(sessionControlQueue, "mujoong.limits.session-control-queue");
            positive(deviceControlQueue, "mujoong.limits.device-control-queue");
            positive(recorderQueue, "mujoong.limits.recorder-queue");
            positive(recorderFlushConcurrency, "mujoong.limits.recorder-flush-concurrency");
            positive(backgroundDbConcurrency, "mujoong.limits.background-db-concurrency");
            positive(dbLockTimeout, "mujoong.limits.db-lock-timeout");
            positive(dbStatementTimeout, "mujoong.limits.db-statement-timeout");
            nonNegative(dbRetryAttempts, "mujoong.limits.db-retry-attempts");
        }
    }

    /** BR7.3 출력 구간 조용 시간. */
    public record Gate(Duration segmentQuiet) {
        public Gate {
            positive(segmentQuiet, "mujoong.gate.segment-quiet");
        }
    }

    /** BR3.6 · SD-7 WSS Origin 정확 일치(환경 변수 MUJOONG_SECURITY_ALLOWED_ORIGIN) · OD-3 보안 사건 상한. */
    public record Security(String allowedOrigin, Integer eventLogRateCap) {
        public Security {
            allowedOrigin = origin(allowedOrigin, "mujoong.security.allowed-origin");
            positive(eventLogRateCap, "mujoong.security.event-log-rate-cap");
        }
    }

    /** NFR7.8 풀 대기 경고 기준. */
    public record Metrics(Duration dbPoolWaitWarn) {
        public Metrics {
            positive(dbPoolWaitWarn, "mujoong.metrics.db-pool-wait-warn");
        }
    }

    /**
     * OD-4 ownerHash 비밀 · OD-8 지연 로그 표본률 · OD-6 지표 스냅샷 · OD-3 반복 병합. 문자열 표현에 비밀 값을
     * 내지 않는다.
     */
    public record Observability(
            String ownerHashSecret,
            Integer latencyLogSampleRate,
            Duration metricSnapshotInterval,
            Duration logMergeWindow) {

        static final int MAX_PERCENT = 100;

        public Observability {
            ownerHashSecret = ownerHashSecret == null ? "" : ownerHashSecret;
            nonNegative(latencyLogSampleRate, "mujoong.observability.latency-log-sample-rate");
            if (latencyLogSampleRate > MAX_PERCENT) {
                throw invalid("설정 키는 0 이상 100 이하여야 한다: mujoong.observability.latency-log-sample-rate");
            }
            positive(metricSnapshotInterval, "mujoong.observability.metric-snapshot-interval");
            positive(logMergeWindow, "mujoong.observability.log-merge-window");
        }

        @Override
        public String toString() {
            return "Observability[ownerHashSecret=" + (ownerHashSecret.isEmpty() ? "<empty>" : "<redacted>")
                    + ", latencyLogSampleRate=" + latencyLogSampleRate
                    + ", metricSnapshotInterval=" + metricSnapshotInterval
                    + ", logMergeWindow=" + logMergeWindow + "]";
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
