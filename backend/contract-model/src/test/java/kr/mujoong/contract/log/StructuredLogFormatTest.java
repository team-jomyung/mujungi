package kr.mujoong.contract.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.trace.TraceIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** 사건 로그 한 줄 형식(OD-1 · OD-2 · OD-3 · OD-4 · NFR9.15 · BR8.7). */
@Tag("skeleton")
class StructuredLogFormatTest {

    private static final Instant NOW = Instant.parse("2026-10-15T09:00:00.123456Z");
    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    private final List<String> lines = new ArrayList<>();
    private final List<String> invalidKeys = new ArrayList<>();
    private OwnerHasher hasher;

    @BeforeEach
    void setUp() {
        hasher = OwnerHasher.ephemeral();
    }

    private EventLog log(EventLog.App app, EventLog.Strictness strictness, String suppliedTrace) {
        return new EventLog(app, new FixedClock(NOW), hasher, () -> suppliedTrace, (level, line) -> lines.add(line),
                strictness, (event, key) -> invalidKeys.add(event + ":" + key));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String line) {
        return JsonCodec.shared().read(line, LinkedHashMap.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attrs(String line) {
        return (Map<String, Object>) parse(line).get("attrs");
    }

    @Test
    void oneLineIsOneJsonObjectWithTheSevenTopLevelKeysInFixedOrder() {
        UUID sessionId = UUID.randomUUID();

        log(EventLog.App.REALTIME, EventLog.Strictness.STRICT, TRACE)
                .event(EventCode.SESSION_TRANSITION, EventLog.Component.SESSION_LIFECYCLE)
                .owner(sessionId)
                .attr(AttrKey.FROM, "PREPARING")
                .attr(AttrKey.TO, "ACTIVE")
                .attr(AttrKey.STATUS_VERSION, 3)
                .attr(AttrKey.CONFIRMED, true)
                .emit();

        assertThat(lines).hasSize(1);
        String line = lines.getFirst();
        assertThat(line).doesNotContain("\n");
        Map<String, Object> json = parse(line);
        assertThat(json.keySet()).containsExactly("ts", "level", "traceId", "component", "event", "ownerHash", "attrs");
        assertThat(json.get("ts")).isEqualTo("2026-10-15T09:00:00.123Z");
        assertThat(json.get("level")).isEqualTo("INFO");
        assertThat(json.get("traceId")).isEqualTo(TRACE);
        assertThat(json.get("component")).isEqualTo("realtime/SessionLifecycle");
        assertThat(json.get("event")).isEqualTo("SESSION_TRANSITION");
        assertThat(json.get("ownerHash")).isEqualTo(hasher.hash(sessionId));
        assertThat(attrs(line)).containsExactly(
                Map.entry("from", "PREPARING"), Map.entry("to", "ACTIVE"), Map.entry("statusVersion", 3),
                Map.entry("confirmed", true));
    }

    @Test
    void thereAreExactly22EventCodesAndNoFreeTextAttrKeys() {
        // U9 SD6: 로그 계약 변경(LUNA_CALL · LUNA_STARTUP)과 같은 커밋에서 정본 목록을 늘린다(R-M10). 정확 일치 단언은 그대로다
        assertThat(Arrays.stream(EventCode.values()).map(Enum::name)).containsExactly(
                "SESSION_TRANSITION", "TIMEOUT_FIRED", "LATENCY_TURN", "SESSION_ITEMS_SERVED", "PLAYBACK_MARK_STOPPED",
                "PRACTICE_AUDIO_STAGE", "OUTPUT_DISCARDED", "DEVICE_CONNECTION", "INTERNAL_HTTP", "EVENT_DELIVERY", "STARTUP_RECOVERY", "METRIC_SNAPSHOT",
                "SECURITY_AUTH_FAILED", "SECURITY_RATE_LIMITED", "SECURITY_ORIGIN_DENIED", "SECURITY_DEV_LOGIN_BLOCKED",
                "SECURITY_INTERNAL_TOKEN_FAILED", "APP_ERROR", "QUEUE_PRESSURE", "LUNA_CALL", "LUNA_STARTUP",
                "GOAL_CANDIDATE_DROPPED", "ADMIN_RECORD_VIEWED");
        Set<String> keyNames = Arrays.stream(AttrKey.values()).map(AttrKey::key).collect(Collectors.toSet());
        assertThat(keyNames).doesNotContain(
                "message", "text", "transcript", "audio", "token", "cookie", "code", "email", "password", "secret", "input",
                "displayName", "stack", "exception");
        assertThat(Arrays.stream(AttrKey.values()).filter(key -> key.valueType() == AttrKey.ValueType.UUID))
                .containsExactly(AttrKey.EVENT_ID);
        assertThat(AttrKey.fromKey("statusVersion")).contains(AttrKey.STATUS_VERSION);
        assertThat(AttrKey.fromKey("message")).isEmpty();
    }

    @Test
    void allowedKeysFollowTheOd2TableForEachCode() {
        Map<EventCode, Set<String>> table = new EnumMap<>(EventCode.class);
        for (EventCode code : EventCode.values()) {
            table.put(code, code.allowedKeys().stream().map(AttrKey::key).collect(Collectors.toSet()));
        }

        assertThat(table.get(EventCode.SESSION_TRANSITION)).containsExactlyInAnyOrder(
                "from", "to", "statusVersion", "endReason", "failureCode", "confirmed", "phase", "origin");
        assertThat(table.get(EventCode.TIMEOUT_FIRED)).containsExactlyInAnyOrder("type", "thresholdMs", "elapsedMs");
        assertThat(table.get(EventCode.DEVICE_CONNECTION)).containsExactlyInAnyOrder("from", "to", "closeCode");
        assertThat(table.get(EventCode.INTERNAL_HTTP)).containsExactlyInAnyOrder(
                "target", "outcome", "statusCode", "durationMs", "attempt", "repeat");
        assertThat(table.get(EventCode.EVENT_DELIVERY)).containsExactlyInAnyOrder(
                "eventId", "eventType", "status", "retryCount", "durationMs");
        assertThat(table.get(EventCode.STARTUP_RECOVERY)).containsExactlyInAnyOrder(
                "recoveredSessions", "reopenedInboxRows", "resetConnections", "rearmedControlEvents");
        assertThat(table.get(EventCode.SECURITY_AUTH_FAILED)).containsExactlyInAnyOrder("kind", "statusCode", "repeat");
        assertThat(table.get(EventCode.SECURITY_DEV_LOGIN_BLOCKED)).containsExactlyInAnyOrder("profile", "statusCode", "repeat");
        assertThat(table.get(EventCode.APP_ERROR)).containsExactlyInAnyOrder("errorKind", "repeat");
        assertThat(table.get(EventCode.QUEUE_PRESSURE)).containsExactlyInAnyOrder("queue", "dropped", "repeat");
        assertThat(table.get(EventCode.ADMIN_RECORD_VIEWED)).containsExactly("viewerHash");
        assertThat(table.get(EventCode.LATENCY_TURN)).hasSize(17).contains("tSpeechEnd", "clockOffsetMs", "unobservedReason");
        assertThat(table.get(EventCode.METRIC_SNAPSHOT)).hasSize(18).contains("outboxPending", "breakerOpenSec");
        assertThat(Arrays.stream(EventCode.values()).filter(EventCode::isSecurityEvent)).hasSize(5);
        assertThat(Arrays.stream(EventCode.values()).filter(EventCode::isMergeable)).containsExactlyInAnyOrder(
                EventCode.INTERNAL_HTTP, EventCode.SECURITY_AUTH_FAILED, EventCode.SECURITY_RATE_LIMITED,
                EventCode.SECURITY_ORIGIN_DENIED, EventCode.SECURITY_DEV_LOGIN_BLOCKED,
                EventCode.SECURITY_INTERNAL_TOKEN_FAILED, EventCode.APP_ERROR);
    }

    @Test
    void unregisteredEnumValuesAreWrittenAsUnknown() {
        String line = log(EventLog.App.REALTIME, EventLog.Strictness.STRICT, TRACE)
                .event(EventCode.SESSION_TRANSITION, EventLog.Component.SESSION_LIFECYCLE)
                .attr(AttrKey.FROM, "TELEPORTING")
                .attr(AttrKey.TO, "active")
                .attr(AttrKey.END_REASON, "GUARDIAN_STOP\",\"x\":\"y")
                .render();

        assertThat(attrs(line)).containsEntry("from", "UNKNOWN").containsEntry("to", "UNKNOWN")
                .containsEntry("endReason", "UNKNOWN").doesNotContainKey("x");
    }

    @Test
    void deviceConnectionUsesTheConnectionStateSetForFromAndTo() {
        String line = log(EventLog.App.REALTIME, EventLog.Strictness.STRICT, TRACE)
                .event(EventCode.DEVICE_CONNECTION, EventLog.Component.DEVICE_GATEWAY)
                .attr(AttrKey.FROM, "CONNECTED")
                .attr(AttrKey.TO, "ACTIVE")
                .attr(AttrKey.CLOSE_CODE, 4401)
                .render();

        assertThat(attrs(line)).containsEntry("from", "CONNECTED").containsEntry("to", "UNKNOWN").containsEntry("closeCode", 4401);
    }

    @Test
    void strictModeRejectsKeysOutsideTheTableWrongValueTypesAndForeignComponents() {
        EventLog strict = log(EventLog.App.CORE_API, EventLog.Strictness.STRICT, TRACE);

        assertThatThrownBy(() -> strict.event(EventCode.APP_ERROR, EventLog.Component.CORE_MAINTENANCE)
                        .attr(AttrKey.STATUS_VERSION, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("statusVersion");
        assertThatThrownBy(() -> strict.event(EventCode.INTERNAL_HTTP, EventLog.Component.CORE_MESSAGING)
                        .attr(AttrKey.STATUS_CODE, "200"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> strict.event(EventCode.SESSION_TRANSITION, EventLog.Component.SESSION_LIFECYCLE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("component");
        assertThat(lines).isEmpty();
    }

    @Test
    void lenientModeDropsInvalidKeysReportsOnlyTheKeyNameAndStillEmits() {
        log(EventLog.App.CORE_API, EventLog.Strictness.LENIENT, TRACE)
                .event(EventCode.INTERNAL_HTTP, EventLog.Component.CORE_MESSAGING)
                .attr(AttrKey.TARGET, "SESSION_START")
                .attr(AttrKey.OUTCOME, "OK")
                .attr(AttrKey.SPEAKER, "CHILD")
                .attr(AttrKey.DURATION_MS, true)
                .emit();

        assertThat(invalidKeys).containsExactly("INTERNAL_HTTP:speaker", "INTERNAL_HTTP:durationMs");
        assertThat(attrs(lines.getFirst())).containsOnlyKeys("target", "outcome");
    }

    @Test
    void levelFollowsTheOd2ExceptionRules() {
        EventLog rt = log(EventLog.App.REALTIME, EventLog.Strictness.STRICT, TRACE);
        EventLog core = log(EventLog.App.CORE_API, EventLog.Strictness.STRICT, TRACE);

        assertThat(rt.event(EventCode.SESSION_TRANSITION, EventLog.Component.SESSION_LIFECYCLE).attr(AttrKey.TO, "FAILED")
                .effectiveLevel()).isEqualTo(EventCode.Level.WARN);
        assertThat(rt.event(EventCode.SESSION_TRANSITION, EventLog.Component.SESSION_LIFECYCLE).attr(AttrKey.TO, "ACTIVE")
                .effectiveLevel()).isEqualTo(EventCode.Level.INFO);
        assertThat(rt.event(EventCode.SESSION_TRANSITION, EventLog.Component.SESSION_LIFECYCLE).effectiveLevel())
                .isEqualTo(EventCode.Level.INFO);
        assertThat(rt.event(EventCode.DEVICE_CONNECTION, EventLog.Component.DEVICE_GATEWAY).attr(AttrKey.TO, "UNRESPONSIVE")
                .effectiveLevel()).isEqualTo(EventCode.Level.WARN);
        assertThat(core.event(EventCode.INTERNAL_HTTP, EventLog.Component.CORE_MESSAGING).attr(AttrKey.OUTCOME, "TIMEOUT")
                .effectiveLevel()).isEqualTo(EventCode.Level.WARN);
        assertThat(core.event(EventCode.INTERNAL_HTTP, EventLog.Component.CORE_MESSAGING).attr(AttrKey.OUTCOME, "OK")
                .effectiveLevel()).isEqualTo(EventCode.Level.INFO);
        assertThat(core.event(EventCode.EVENT_DELIVERY, EventLog.Component.CORE_MESSAGING).attr(AttrKey.STATUS, "DEAD")
                .effectiveLevel()).isEqualTo(EventCode.Level.WARN);
        assertThat(core.event(EventCode.EVENT_DELIVERY, EventLog.Component.CORE_MESSAGING).attr(AttrKey.STATUS, "DUPLICATE")
                .effectiveLevel()).isEqualTo(EventCode.Level.DEBUG);
        assertThat(core.event(EventCode.EVENT_DELIVERY, EventLog.Component.CORE_MESSAGING).effectiveLevel())
                .isEqualTo(EventCode.Level.INFO);
        assertThat(rt.event(EventCode.QUEUE_PRESSURE, EventLog.Component.VOICE_RELAY).attr(AttrKey.QUEUE, "RECORDER")
                .effectiveLevel()).isEqualTo(EventCode.Level.ERROR);
        assertThat(rt.event(EventCode.QUEUE_PRESSURE, EventLog.Component.VOICE_RELAY).attr(AttrKey.QUEUE, "INPUT_FRAMES")
                .effectiveLevel()).isEqualTo(EventCode.Level.WARN);
        assertThat(core.event(EventCode.SECURITY_AUTH_FAILED, EventLog.Component.SECURITY).level(EventCode.Level.INFO)
                .effectiveLevel()).isEqualTo(EventCode.Level.INFO);
        assertThat(core.event(EventCode.APP_ERROR, EventLog.Component.HEALTH).event()).isEqualTo(EventCode.APP_ERROR);
    }

    @Test
    void timestampUuidNumberAndIdentifierValuesUseTheirOd1Formats() {
        UUID eventId = UUID.randomUUID();
        EventLog core = log(EventLog.App.CORE_API, EventLog.Strictness.STRICT, TRACE);

        String delivery = core.event(EventCode.EVENT_DELIVERY, EventLog.Component.CORE_MESSAGING)
                .attr(AttrKey.EVENT_ID, eventId)
                .attr(AttrKey.EVENT_TYPE, "DeviceReleased")
                .attr(AttrKey.STATUS, kr.mujoong.contract.log.AttrKey.ValueType.ENUM)
                .attr(AttrKey.RETRY_COUNT, 2L)
                .attr(AttrKey.EVENT_ID, (UUID) null)
                .render();
        String latency = log(EventLog.App.REALTIME, EventLog.Strictness.STRICT, TRACE)
                .event(EventCode.LATENCY_TURN, EventLog.Component.VOICE_RELAY)
                .attr(AttrKey.T_SPEECH_END, Instant.parse("2026-10-15T09:00:01.987654Z"))
                .attr(AttrKey.T_GATE_PASS, (Instant) null)
                .attr(AttrKey.CLOCK_OFFSET_MS, -35)
                .render();
        String appError = core.event(EventCode.APP_ERROR, EventLog.Component.HEALTH)
                .attr(AttrKey.ERROR_KIND, "SQLTransientConnectionException")
                .render();
        String badKind = core.event(EventCode.APP_ERROR, EventLog.Component.HEALTH)
                .attr(AttrKey.ERROR_KIND, "bad kind with spaces")
                .attr(AttrKey.REPEAT, 3)
                .render();

        assertThat(attrs(delivery)).containsEntry("eventId", eventId.toString()).containsEntry("eventType", "DeviceReleased")
                .containsEntry("status", "UNKNOWN").containsEntry("retryCount", 2);
        assertThat(attrs(latency)).containsEntry("tSpeechEnd", "2026-10-15T09:00:01.987Z").containsEntry("clockOffsetMs", -35)
                .doesNotContainKey("tGatePass");
        assertThat(attrs(appError)).containsEntry("errorKind", "SQLTransientConnectionException");
        assertThat(attrs(badKind)).containsEntry("errorKind", "UNKNOWN").containsEntry("repeat", 3);
        assertThat(parse(delivery).get("ownerHash")).isNull();
    }

    @Test
    void traceIdComesFromTheSupplierOnlyWhenWellFormedAndCanBeOverridden() {
        String fromBadSupplier = log(EventLog.App.CORE_API, EventLog.Strictness.STRICT, "invalid\ntrace")
                .event(EventCode.STARTUP_RECOVERY, EventLog.Component.CORE_MAINTENANCE)
                .render();
        String sessionTrace = TraceIds.newTraceId();
        String overridden = log(EventLog.App.CORE_API, EventLog.Strictness.STRICT, TRACE)
                .event(EventCode.STARTUP_RECOVERY, EventLog.Component.CORE_MAINTENANCE)
                .traceId(sessionTrace)
                .render();
        String badOverride = log(EventLog.App.CORE_API, EventLog.Strictness.STRICT, TRACE)
                .event(EventCode.STARTUP_RECOVERY, EventLog.Component.CORE_MAINTENANCE)
                .traceId("nope")
                .render();

        assertThat((String) parse(fromBadSupplier).get("traceId")).matches("^[0-9a-f]{32}$");
        assertThat(fromBadSupplier).doesNotContain("invalid");
        assertThat(parse(overridden).get("traceId")).isEqualTo(sessionTrace);
        assertThat(parse(badOverride).get("traceId")).isEqualTo(TRACE);
    }

    @Test
    void lowLevelFormatterEscapesQuotesNewlinesAndControlCharacters() {
        Map<AttrKey, Object> attrs = new LinkedHashMap<>();
        attrs.put(AttrKey.TARGET, "a\"b\\c\nd\re\tf\bg\fh\u0001i j");
        attrs.put(AttrKey.OUTCOME, null);

        String line = StructuredLogFormatter.format(NOW, EventCode.Level.INFO, TRACE, "core-api/Health",
                EventCode.INTERNAL_HTTP, null, attrs);

        assertThat(line).doesNotContain("\n", "\r", "\t", "\u0001", " ");
        assertThat(attrs(line)).containsExactly(Map.entry("target", "a\"b\\c\nd\re\tf\bg\fh\u0001i j"));
        assertThatThrownBy(() -> StructuredLogFormatter.format(NOW, EventCode.Level.INFO, TRACE, "c", EventCode.APP_ERROR,
                null, Map.of(AttrKey.REPEAT, 1.5))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void appErrorLineKeepsOnlyErrorKindAndRepeat() {
        String line = StructuredLogFormatter.formatAppError(NOW, EventCode.Level.ERROR, "x", "core-api/Maintenance",
                "IllegalStateException", 4L);
        String noKind = StructuredLogFormatter.formatAppError(NOW, EventCode.Level.WARN, TRACE, "core-api/Maintenance",
                "java.lang Illegal", null);

        assertThat(parse(line).get("event")).isEqualTo("APP_ERROR");
        assertThat((String) parse(line).get("traceId")).matches("^[0-9a-f]{32}$");
        assertThat(attrs(line)).containsExactly(Map.entry("errorKind", "IllegalStateException"), Map.entry("repeat", 4));
        assertThat(attrs(noKind)).isEmpty();
        assertThat(StructuredLogFormatter.isValidErrorKind(null)).isFalse();
        assertThat(StructuredLogFormatter.formatTimestamp(Instant.EPOCH)).isEqualTo("1970-01-01T00:00:00.000Z");
    }

    @Test
    void appErrorLayoutDropsMessageAndStackAndKeepsExceptionSimpleName() {
        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger("org.example.Library");
        String sensitive = "child said something private";
        LoggingEvent event = new LoggingEvent("fqcn", logger, Level.ERROR, "failed: " + sensitive,
                new IllegalStateException(sensitive), null);
        event.setMDCPropertyMap(Map.of(TraceIds.MDC_KEY, TRACE));
        LoggingEvent warnWithoutThrowable = new LoggingEvent("fqcn", logger, Level.WARN, sensitive, null, null);
        warnWithoutThrowable.setMDCPropertyMap(Map.of());
        StructuredLogFormatter.AppErrorLayout layout = new StructuredLogFormatter.AppErrorLayout();
        layout.setComponent("realtime/Maintenance");
        layout.setContext(context);
        layout.start();

        String line = layout.doLayout(event).strip();
        String warnLine = layout.doLayout(warnWithoutThrowable).strip();

        assertThat(line).doesNotContain(sensitive, "failed", "at ");
        assertThat(parse(line)).containsEntry("level", "ERROR").containsEntry("traceId", TRACE)
                .containsEntry("component", "realtime/Maintenance").containsEntry("event", "APP_ERROR");
        assertThat(attrs(line)).containsExactly(Map.entry("errorKind", "IllegalStateException"));
        assertThat(parse(warnLine)).containsEntry("level", "WARN");
        assertThat(attrs(warnLine)).isEmpty();
        assertThat(warnLine).doesNotContain(sensitive);
    }

    @Test
    void slf4jSinkWritesToTheMujoongEventLoggerAtTheLineLevel() {
        Logger eventLogger = (Logger) LoggerFactory.getLogger(EventLog.LOGGER_NAME);
        Level previous = eventLogger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(eventLogger.getLoggerContext());
        appender.start();
        eventLogger.addAppender(appender);
        eventLogger.setLevel(Level.DEBUG);
        MDC.put(TraceIds.MDC_KEY, TRACE);
        try {
            EventLog.Slf4jSink sink = new EventLog.Slf4jSink();
            for (EventCode.Level level : EventCode.Level.values()) {
                sink.write(level, "{\"level\":\"" + level + "\"}");
            }
        } finally {
            MDC.remove(TraceIds.MDC_KEY);
            eventLogger.detachAppender(appender);
            eventLogger.setLevel(previous);
        }

        assertThat(appender.list).extracting(ILoggingEvent::getLevel)
                .containsExactly(Level.ERROR, Level.WARN, Level.INFO, Level.DEBUG);
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("{\"level\":\"ERROR\"}", "{\"level\":\"WARN\"}", "{\"level\":\"INFO\"}", "{\"level\":\"DEBUG\"}");
    }

    @Test
    void componentValuesArePrefixedWithTheApp() {
        assertThat(EventLog.Component.CORE_MAINTENANCE.value()).isEqualTo("core-api/Maintenance");
        assertThat(EventLog.Component.REALTIME_MAINTENANCE.value()).isEqualTo("realtime/Maintenance");
        assertThat(EventLog.Component.OUTPUT_GATE.app()).isEqualTo(EventLog.App.REALTIME);
        assertThat(EventLog.App.CORE_API.prefix()).isEqualTo("core-api");
    }

    /** 고정 Clock(시험용). */
    private record FixedClock(Instant instant) implements MujoongClock {
        @Override
        public Instant now() {
            return instant;
        }

        @Override
        public long monotonicNanos() {
            return 0L;
        }
    }
}
