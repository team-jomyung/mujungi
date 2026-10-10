package kr.mujoong.core.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.log.OwnerHasher;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.secret.TestSecrets;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Core 사건 기록기 (OD-1 ~ OD-4 · OD-13 · SD-15 · NFR9.15). 반복 병합(INTERNAL_HTTP 실패 100건 → 첫 줄 + repeat 한 줄), 성공 줄은 병합하지
 * 않음, 보안 사건 분당 상한(지표는 실제 건수), 허용 밖 키(STRICT 예외 · LENIENT APP_ERROR), ownerHash 비밀 규칙.
 */
@Tag("u1")
class EventLoggerTest {

    private static final Instant NOW = Instant.parse("2001-09-01T00:00:10Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final LogCapture capture = LogCapture.detached();
    private final CoreMetrics metrics = CoreMetrics.inMemory();

    private EventLogger logger(EventLog.Strictness strictness, int securityCap) {
        return new EventLogger(clock, OwnerHasher.ephemeral(), capture, metrics, strictness, Duration.ofSeconds(10),
                securityCap);
    }

    @Test
    void repeatedInternalHttpFailuresMergeIntoFirstLinePlusRepeatLine() {
        EventLogger events = logger(EventLog.Strictness.STRICT, 60);
        for (int i = 0; i < 100; i++) {
            long duration = i;
            events.log(EventCode.INTERNAL_HTTP, EventLog.Component.ACTIVITY_COORDINATOR, null, e -> e
                    .attr(AttrKey.TARGET, "SESSION_START").attr(AttrKey.OUTCOME, "TIMEOUT")
                    .attr(AttrKey.DURATION_MS, duration).attr(AttrKey.ATTEMPT, 1L));
        }
        assertThat(capture.events(EventCode.INTERNAL_HTTP)).hasSize(1);

        clock.advance(Duration.ofSeconds(10));
        events.flushMerged();

        List<Map<String, Object>> lines = capture.events(EventCode.INTERNAL_HTTP);
        assertThat(lines).hasSize(2);
        assertThat(((Number) LogCapture.attrs(lines.get(1)).get("repeat")).longValue()).isEqualTo(99L);
        assertThat(lines.get(0).get("level")).isEqualTo("WARN");
    }

    @Test
    void successLinesAndDifferentIdentifyingAttrsAreNotMerged() {
        EventLogger events = logger(EventLog.Strictness.STRICT, 60);
        for (int i = 0; i < 3; i++) {
            events.log(EventCode.INTERNAL_HTTP, EventLog.Component.ACTIVITY_COORDINATOR, UUID.randomUUID(), e -> e
                    .attr(AttrKey.TARGET, "SESSION_END").attr(AttrKey.OUTCOME, "OK").attr(AttrKey.STATUS_CODE, 202L));
        }
        events.log(EventCode.INTERNAL_HTTP, EventLog.Component.ACTIVITY_COORDINATOR, null, e -> e
                .attr(AttrKey.TARGET, "SESSION_END").attr(AttrKey.OUTCOME, "HTTP_5XX").attr(AttrKey.STATUS_CODE, 502L));
        events.log(EventCode.INTERNAL_HTTP, EventLog.Component.ACTIVITY_COORDINATOR, null, e -> e
                .attr(AttrKey.TARGET, "SESSION_END").attr(AttrKey.OUTCOME, "HTTP_5XX").attr(AttrKey.STATUS_CODE, 503L));

        assertThat(capture.events(EventCode.INTERNAL_HTTP)).hasSize(5);
        assertThat(capture.events(EventCode.INTERNAL_HTTP).get(0).get("level")).isEqualTo("INFO");
    }

    @Test
    void securityEventsAreCappedPerMinuteButCountedInMetrics() {
        EventLogger events = logger(EventLog.Strictness.STRICT, 3);
        for (int i = 0; i < 5; i++) {
            long status = 400 + i;
            events.log(EventCode.SECURITY_AUTH_FAILED, EventLog.Component.SECURITY, null, e -> e
                    .attr(AttrKey.KIND, "DEVICE_TOKEN").attr(AttrKey.STATUS_CODE, status));
        }
        assertThat(capture.events(EventCode.SECURITY_AUTH_FAILED)).hasSize(3);
        assertThat(metrics.count("mujoong.security.event", "type", "auth_failed")).isEqualTo(5.0);

        clock.advance(Duration.ofMinutes(1));
        events.log(EventCode.SECURITY_AUTH_FAILED, EventLog.Component.SECURITY, null, e -> e
                .attr(AttrKey.KIND, "DEVICE_TOKEN").attr(AttrKey.STATUS_CODE, 401L));
        assertThat(capture.events(EventCode.SECURITY_AUTH_FAILED)).hasSize(4);
    }

    @Test
    void attrOutsideAllowListFailsInTestAndIsDroppedWithAppErrorInProduction() {
        EventLogger strict = logger(EventLog.Strictness.STRICT, 60);
        assertThatThrownBy(() -> strict.log(EventCode.APP_ERROR, EventLog.Component.CORE_MAINTENANCE, null, e -> e
                .attr(AttrKey.TARGET, "SESSION_START"))).isInstanceOf(RuntimeException.class);

        EventLogger lenient = logger(EventLog.Strictness.LENIENT, 60);
        lenient.log(EventCode.TIMEOUT_FIRED, EventLog.Component.ACTIVITY_COORDINATOR, null, e -> e
                .attr(AttrKey.TYPE, "PREPARING").attr(AttrKey.TARGET, "SESSION_START"));

        assertThat(metrics.count("mujoong.log.invalid.attr", "event", "timeout_fired")).isEqualTo(1.0);
        assertThat(capture.events(EventCode.APP_ERROR)).hasSize(1);
        assertThat(LogCapture.attrs(capture.events(EventCode.APP_ERROR).get(0))).containsEntry("errorKind", "InvalidLogAttr");
        assertThat(LogCapture.attrs(capture.last(EventCode.TIMEOUT_FIRED).orElseThrow())).doesNotContainKey("target");
    }

    @Test
    void appErrorUsesClassNameOnlyAndInvalidKindBecomesUnknown() {
        EventLogger events = logger(EventLog.Strictness.STRICT, 60);
        events.appError(EventLog.Component.HEALTH, new IllegalStateException("비밀 포함 메시지 7Z"));
        events.appError(EventLog.Component.HEALTH, "not a kind!");

        List<Map<String, Object>> errors = capture.events(EventCode.APP_ERROR);
        assertThat(LogCapture.attrs(errors.get(0))).containsEntry("errorKind", "IllegalStateException");
        assertThat(LogCapture.attrs(errors.get(1))).containsEntry("errorKind", "Unknown");
        assertThat(capture.anyLineContains("7Z")).isFalse();
        assertThat(EventLogger.strictnessFor("dev", "test")).isEqualTo(EventLog.Strictness.STRICT);
        assertThat(EventLogger.strictnessFor("prod")).isEqualTo(EventLog.Strictness.LENIENT);
    }

    @Test
    void mergeWindowStopsMergingAtKeyCapAndRejectsBadWindow() {
        LogMergeWindow<String> window = new LogMergeWindow<>(Duration.ofSeconds(10));
        for (int i = 0; i < LogMergeWindow.MAX_KEYS; i++) {
            assertThat(window.offer("k" + i, "line", NOW).emitNow()).isTrue();
        }
        assertThat(window.offer("overflow", "line", NOW).emitNow()).isTrue();
        assertThat(window.offer("overflow", "line", NOW).emitNow()).isTrue();
        assertThat(window.size()).isEqualTo(LogMergeWindow.MAX_KEYS);
        assertThat(window.offer("k1", "line", NOW).emitNow()).isFalse();
        assertThat(window.drainExpired(NOW.plusSeconds(10))).hasSize(1);
        assertThat(window.size()).isZero();
        assertThatThrownBy(() -> new LogMergeWindow<String>(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ownerHashSecretIsRequiredOnlyInPublicProfiles() {
        assertThatThrownBy(() -> OwnerHashConfig.create("", "demo")).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("demo-secret");
        assertThatThrownBy(() -> OwnerHashConfig.create(null, "prod")).isInstanceOf(IllegalStateException.class);
        OwnerHasher ephemeral = OwnerHashConfig.create("", "dev");
        OwnerHasher configured = OwnerHashConfig.create(TestSecrets.ownerHashSecret(), "prod");
        UUID owner = UUID.randomUUID();

        assertThat(ephemeral.hash(owner)).hasSize(OwnerHasher.HASH_HEX_LENGTH);
        assertThat(configured.hash(owner)).isEqualTo(configured.hash(owner)).isNotEqualTo(owner.toString());
        assertThat(new SecurityEventCap(2).capPerMinute()).isEqualTo(2);
        assertThatThrownBy(() -> new SecurityEventCap(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
