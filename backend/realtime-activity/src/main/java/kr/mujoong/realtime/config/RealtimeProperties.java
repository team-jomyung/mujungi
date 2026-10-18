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
 *
 * <p>묶음마다 타입은 {@code Realtime<묶음>Properties} 파일로 나눠 이 패키지에 둔다. 이 파일에는 루트 레코드와 묶음들이 함께 쓰는 검증
 * 도우미만 남긴다.
 */
@ConfigurationProperties(prefix = "mujoong")
public record RealtimeProperties(
        RealtimeTimingProperties timing,
        RealtimeOutboxProperties outbox,
        RealtimeInboxProperties inbox,
        RealtimeRetentionProperties retention,
        RealtimeCleanupProperties cleanup,
        RealtimeSessionProperties session,
        RealtimeDeviceProperties device,
        RealtimeInternalHttpProperties internalHttp,
        RealtimeProviderProperties provider,
        RealtimeHealthProperties health,
        RealtimeLimitsProperties limits,
        RealtimeGateProperties gate,
        RealtimeRecordingProperties recording,
        RealtimeTranscriptProperties transcript,
        RealtimeSecurityProperties security,
        RealtimeMetricsProperties metrics,
        RealtimeObservabilityProperties observability,
        RealtimeJudgmentProperties judgment,
        RealtimePracticeFlowProperties practiceFlow) {

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
        required(judgment, "mujoong.judgment");
        required(practiceFlow, "mujoong.practice-flow");

        if (internalHttp.verifyTokenTimeout().compareTo(timing.device().helloTimeout()) >= 0) {
            throw invalid("불변식 I-3: mujoong.internal-http.verify-token-timeout는 "
                    + "mujoong.timing.device.hello-timeout보다 짧아야 한다");
        }
        if (provider.connectTimeout().compareTo(timing.session().deviceAckTimeout()) > 0) {
            throw invalid("불변식 I-10: mujoong.provider.connect-timeout는 "
                    + "mujoong.timing.session.device-ack-timeout 이하여야 한다");
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

    static void unitInterval(Double value, String key) {
        required(value, key);
        if (value.isNaN() || value < 0.0 || value > 1.0) {
            throw invalid("설정 키는 0 이상 1 이하여야 한다: " + key);
        }
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
