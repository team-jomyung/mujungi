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
 *
 * <p>U3(음성 대화, docs/units/u3/design.md 4절)가 더한 키: 불안정 판정 {@code timing.device.unstable-rtt} ·
 * {@code timing.device.unstable-disconnect-window} · {@code device.unstable-disconnect-count}(계약 7절 "5분 안 2회"를 창 · 횟수로
 * 나눔, U3-J3), 원음 기록 {@code recording.*}(U3-J14 · U3-J15), 원음 대기열 {@code limits.recording-queue-frames}, 불확실 전사 기준
 * {@code transcript.uncertain-below-percent}(U3-J17). 더하기만 했고 U1 키의 이름 · 뜻은 그대로다.
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
        Recording recording,
        Transcript transcript,
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
        required(recording, "mujoong.recording");
        required(transcript, "mujoong.transcript");
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

    /**
     * 계약 7절 시간 키와 J80이 허용한 7절 밖 시간 키(recorder). U8(timing.md 13절 · BR3.1)이 판정 · 가드레일 · 새 연습 흐름 묶음을 더했다.
     */
    public record Timing(
            SessionTiming session,
            DeviceTiming device,
            RecorderTiming recorder,
            JudgmentTiming judgment,
            GuardrailTiming guardrail,
            ConversationTiming conversation) {
        public Timing {
            required(session, "mujoong.timing.session");
            required(device, "mujoong.timing.device");
            required(recorder, "mujoong.timing.recorder");
            required(judgment, "mujoong.timing.judgment");
            required(guardrail, "mujoong.timing.guardrail");
            required(conversation, "mujoong.timing.conversation");
        }
    }

    /** U8(timing.md 13절): 진행 중 판정 Luna 한 번의 상한(FR3.4) · 아이 말 끝 뒤 응답 준비 대기(FR5.3). U11이 읽는다. */
    public record JudgmentTiming(Duration liveBudget, Duration responseReady) {
        public JudgmentTiming {
            positive(liveBudget, "mujoong.timing.judgment.live-budget");
            positive(responseReady, "mujoong.timing.judgment.response-ready");
        }
    }

    /** U8(timing.md 13절): 제브 가드레일 검사 한 번의 상한(FR6.2). 넘기면 막힘이다. U13이 읽는다. */
    public record GuardrailTiming(Duration jevBudget) {
        public GuardrailTiming {
            positive(jevBudget, "mujoong.timing.guardrail.jev-budget");
        }
    }

    /**
     * U8(timing.md 13절): 새 연습 흐름의 무응답 재안내 대기(AC1.9.1) · 집중 연습 상한(AC1.5.4) · 목표 진행 상한(FR4.5). U5b의
     * {@code timing.practice}와 이름 공간을 나눴다. U12가 읽는다.
     */
    public record ConversationTiming(Duration noResponseWait, Duration focusMax, Duration goalActiveLimit) {
        public ConversationTiming {
            positive(noResponseWait, "mujoong.timing.conversation.no-response-wait");
            positive(focusMax, "mujoong.timing.conversation.focus-max");
            positive(goalActiveLimit, "mujoong.timing.conversation.goal-active-limit");
        }
    }

    /**
     * BR6.12 ② 기기 응답 20초((h) PROVIDER_UNAVAILABLE에도) · ③ 마무리 확인 30초. U4b(BR11.1 · FR10.1): 참여 창 처음 20초 · 안내 뒤 20초
     * (BR2.2 · U4b-J4), 공백 경고 4분 · 종료 6분(BR5.3 · BR5.4), 종료 확인 질문 창 20초(BR4.3, 계약 7절 밖 U4b-J21), 정산 창 2초(BR8.1,
     * 계약 7절 밖 U4b-J21). 공백 경고는 공백 종료보다 짧아야 한다.
     */
    public record SessionTiming(
            Duration deviceAckTimeout,
            Duration closingConfirm,
            Duration joinWait,
            Duration joinWaitAfterReprompt,
            Duration silenceWarning,
            Duration silenceEnd,
            Duration stopConfirmWait,
            Duration recordSettle) {

        public SessionTiming {
            positive(deviceAckTimeout, "mujoong.timing.session.device-ack-timeout");
            positive(closingConfirm, "mujoong.timing.session.closing-confirm");
            positive(joinWait, "mujoong.timing.session.join-wait");
            positive(joinWaitAfterReprompt, "mujoong.timing.session.join-wait-after-reprompt");
            positive(silenceWarning, "mujoong.timing.session.silence-warning");
            positive(silenceEnd, "mujoong.timing.session.silence-end");
            positive(stopConfirmWait, "mujoong.timing.session.stop-confirm-wait");
            positive(recordSettle, "mujoong.timing.session.record-settle");
            if (silenceWarning.compareTo(silenceEnd) >= 0) {
                throw invalid("mujoong.timing.session.silence-warning은 mujoong.timing.session.silence-end보다 짧아야 한다");
            }
        }
    }

    /**
     * BR4.8 끊김 40초 · HEARTBEAT 주기(HELLO_OK로 내려줌) · HELLO 5초 · 상태 보고 10초 · 출력 대기열 20초 분량. U3: 불안정 판정의
     * 왕복 기준 1초와 끊김 횟수 창 5분(BR4.8 ①, U3-R1 · U3-J3).
     */
    public record DeviceTiming(
            Duration disconnect,
            Duration heartbeatInterval,
            Duration helloTimeout,
            Duration statusReportInterval,
            Duration outputQueueMax,
            Duration unstableRtt,
            Duration unstableDisconnectWindow,
            Duration gapDisplay) {

        /** U4b(BR6.1): 마지막 기기 메시지 뒤 이 시간이면 기술 일시정지 · 끊김 구간(점선 10초). 끊김 40초보다 짧아야 한다. */
        public DeviceTiming {
            positive(gapDisplay, "mujoong.timing.device.gap-display");
            if (gapDisplay != null && disconnect != null && gapDisplay.compareTo(disconnect) >= 0) {
                throw invalid("mujoong.timing.device.gap-display는 mujoong.timing.device.disconnect보다 짧아야 한다");
            }
            positive(disconnect, "mujoong.timing.device.disconnect");
            positive(heartbeatInterval, "mujoong.timing.device.heartbeat-interval");
            positive(helloTimeout, "mujoong.timing.device.hello-timeout");
            positive(statusReportInterval, "mujoong.timing.device.status-report-interval");
            positive(outputQueueMax, "mujoong.timing.device.output-queue-max");
            positive(unstableRtt, "mujoong.timing.device.unstable-rtt");
            positive(unstableDisconnectWindow, "mujoong.timing.device.unstable-disconnect-window");
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

    /**
     * BR6.7 묘비 10분 · R3-6 고아 세션 점검 · 타이머 전이 실패 재시도 간격. U4b(BR9.1 · U4b-J21): 계정 삭제 처리기의 진행 세션 강제 정지
     * 대기 상한 5초.
     */
    public record Session(Duration tombstoneTtl, Duration orphanCheckInterval, Duration timerTick,
            Duration deletionStopWait) {
        public Session {
            positive(tombstoneTtl, "mujoong.session.tombstone-ttl");
            positive(orphanCheckInterval, "mujoong.session.orphan-check-interval");
            positive(timerTick, "mujoong.session.timer-tick");
            positive(deletionStopWait, "mujoong.session.deletion-stop-wait");
        }
    }

    /** BR4.8 DISCONNECTED 점검 주기 · U3 불안정 판정 끊김 횟수(창 안 이 횟수 이상이면 UNSTABLE, U3-R1). */
    public record Device(Duration disconnectCheckInterval, Integer unstableDisconnectCount) {
        public Device {
            positive(disconnectCheckInterval, "mujoong.device.disconnect-check-interval");
            positive(unstableDisconnectCount, "mujoong.device.unstable-disconnect-count");
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

    /** NFR9.9 · BR3.9 · PD-2 ~ PD-5 · RD-22 한도. U3: 원음 기록 대기열 프레임 수(넘치면 버리고 센다, U3-J15). */
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
            Integer dbRetryAttempts,
            Integer recordingQueueFrames) {

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
            positive(recordingQueueFrames, "mujoong.limits.recording-queue-frames");
        }
    }

    /** BR7.3 출력 구간 조용 시간. */
    public record Gate(Duration segmentQuiet) {
        public Gate {
            positive(segmentQuiet, "mujoong.gate.segment-quiet");
        }
    }

    /**
     * U3 원음 기록(components AudioRecording · K6 · U3-J14 · U3-J15): 동의된 세션의 아이 · AI 원음을
     * {@code <directory>/<sessionId>/child.pcm} · {@code ai.pcm}(PCM16LE 24kHz 모노 raw)으로 쓴다. 세션당 바이트 상한을 넘으면 그
     * 세션의 기록을 멈춘다(대화는 계속). 디렉터리는 운영에서 볼륨 경로를 환경 변수 MUJOONG_RECORDING_DIRECTORY로 준다.
     */
    public record Recording(String directory, DataSize maxBytesPerSession) {
        public Recording {
            if (directory == null || directory.isBlank()) {
                throw invalid("필수 설정 키가 비어 있다: mujoong.recording.directory");
            }
            directory = directory.trim();
            positive(maxBytesPerSession, "mujoong.recording.max-bytes-per-session");
        }
    }

    /**
     * U3 전사 표시 기준(AC3.2.10 · U3-J17): 공급자가 준 아이 전사 신뢰도(0 ~ 100)가 이 값보다 낮으면 {@code review_needed}
     * (12 '불확실' 표시). 신뢰도를 주지 않는 공급자에서는 판정하지 않는다.
     */
    public record Transcript(Integer uncertainBelowPercent) {
        public Transcript {
            nonNegative(uncertainBelowPercent, "mujoong.transcript.uncertain-below-percent");
            if (uncertainBelowPercent > Observability.MAX_PERCENT) {
                throw invalid("설정 키는 0 이상 100 이하여야 한다: mujoong.transcript.uncertain-below-percent");
            }
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
     *
     * <p>{@code systemSampleInterval}(3.6 수정 J302, Core J326과 같은 키)은 OD-6의 '앱 안 5초 표본'(CPU 평균 · 메모리 최댓값 · 풀 사용 ·
     * 적체 · 연결 상태 게이지 갱신) 주기다. 스냅샷 주기보다 길면 구간에 표본이 없을 수 있어 기동을 거부한다.
     */
    public record Observability(
            String ownerHashSecret,
            Integer latencyLogSampleRate,
            Duration metricSnapshotInterval,
            Duration logMergeWindow,
            Duration systemSampleInterval) {

        static final int MAX_PERCENT = 100;

        public Observability {
            ownerHashSecret = ownerHashSecret == null ? "" : ownerHashSecret;
            nonNegative(latencyLogSampleRate, "mujoong.observability.latency-log-sample-rate");
            if (latencyLogSampleRate > MAX_PERCENT) {
                throw invalid("설정 키는 0 이상 100 이하여야 한다: mujoong.observability.latency-log-sample-rate");
            }
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
                    + ", latencyLogSampleRate=" + latencyLogSampleRate
                    + ", metricSnapshotInterval=" + metricSnapshotInterval
                    + ", logMergeWindow=" + logMergeWindow
                    + ", systemSampleInterval=" + systemSampleInterval + "]";
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
