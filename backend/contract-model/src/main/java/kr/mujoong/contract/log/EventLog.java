package kr.mujoong.contract.log;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.trace.TraceIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 사건 로그의 타입 있는 기록 API(OD-1 ~ OD-4 · NFR9.15). 사건 코드별 허용 키만 받고 자유 문자열 매개변수가 없다(OD-3 1번).
 *
 * <pre>{@code
 * eventLog.event(EventCode.SESSION_TRANSITION, EventLog.Component.SESSION_LIFECYCLE)
 *         .owner(sessionId)
 *         .attr(AttrKey.FROM, "PREPARING").attr(AttrKey.TO, "ACTIVE").attr(AttrKey.STATUS_VERSION, 3)
 *         .emit();
 * }</pre>
 *
 * <p>규칙: 허용 표에 없는 키 · 형식이 틀린 값은 {@link Strictness#STRICT}(시험)이면 예외, {@link Strictness#LENIENT}(운영)이면 버리고
 * {@link InvalidAttrListener}에 알린다(앱이 {@code mujoong_log_invalid_attr_total}을 올리고 APP_ERROR 한 줄을 남긴다). 등록되지 않은
 * 열거값은 {@code UNKNOWN}으로 바꾼다(OD-1). ts는 주입한 Clock, traceId는 공급자(MDC)가 형식에 맞을 때만 쓰고 아니면 새로 만든다,
 * ownerHash는 {@link OwnerHasher}가 만든다. 반복 병합 · 보안 사건 상한 · 지표는 앱의 EventLogger가 이 위에 얹는다(Step 21 · 26).
 */
public final class EventLog {

    /** 사건 전용 로거 이름(logback-spring.xml의 MUJOONG_EVENT, OD-3 3번). */
    public static final String LOGGER_NAME = "MUJOONG_EVENT";

    private static final Pattern ENUM_FORMAT = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");

    /** 잘못된 키 · 값의 처리 방식. */
    public enum Strictness {
        /** 시험 프로필: 예외를 던진다. */
        STRICT,
        /** 운영: 버리고 알린다. */
        LENIENT
    }

    /** 발행 앱(component 접두). */
    public enum App {
        CORE_API("core-api"),
        REALTIME("realtime");

        private final String prefix;

        App(String prefix) {
            this.prefix = prefix;
        }

        /** component 접두. */
        public String prefix() {
            return prefix;
        }
    }

    /** U1 컴포넌트 목록(OD-1 component 열거). 후속 유닛이 더한다. */
    public enum Component {
        DEVICE_REGISTRY(App.CORE_API, "DeviceRegistry"),
        CORE_MESSAGING(App.CORE_API, "CoreMessaging"),
        ACTIVITY_COORDINATOR(App.CORE_API, "ActivityCoordinator"),
        SECURITY(App.CORE_API, "Security"),
        HEALTH(App.CORE_API, "Health"),
        CORE_MAINTENANCE(App.CORE_API, "Maintenance"),
        SESSION_LIFECYCLE(App.REALTIME, "SessionLifecycle"),
        DEVICE_GATEWAY(App.REALTIME, "DeviceGateway"),
        VOICE_RELAY(App.REALTIME, "VoiceRelay"),
        OUTPUT_GATE(App.REALTIME, "OutputGate"),
        REALTIME_MESSAGING(App.REALTIME, "RealtimeMessaging"),
        REALTIME_MAINTENANCE(App.REALTIME, "Maintenance"),
        /** U9 Luna 호출부(core-api/LunaClient, BR6.3). 두 앱이 같은 이름을 각자 쓴다(코드 공유 없음, R-F5). */
        CORE_LUNA_CLIENT(App.CORE_API, "LunaClient"),
        /** U9 Luna 호출부(realtime/LunaClient, BR6.3). */
        REALTIME_LUNA_CLIENT(App.REALTIME, "LunaClient"),
        /** JIRA-280 관리자 조회(core-api/admin). */
        ADMIN_QUERY(App.CORE_API, "AdminQuery");

        private final App app;
        private final String name;

        Component(App app, String name) {
            this.app = app;
            this.name = name;
        }

        /** 이 컴포넌트의 앱. */
        public App app() {
            return app;
        }

        /** 로그 component 값({@code <앱>/<컴포넌트>}). */
        public String value() {
            return app.prefix() + "/" + name;
        }
    }

    /** 완성된 한 줄을 내보내는 곳. */
    @FunctionalInterface
    public interface Sink {
        void write(EventCode.Level level, String line);
    }

    /** LENIENT 모드에서 버린 키를 알린다(값은 넘기지 않는다). */
    @FunctionalInterface
    public interface InvalidAttrListener {
        void onInvalidAttr(EventCode event, String keyName);
    }

    private final App app;
    private final MujoongClock clock;
    private final OwnerHasher ownerHasher;
    private final Supplier<String> traceIdSupplier;
    private final Sink sink;
    private final Strictness strictness;
    private final InvalidAttrListener invalidAttrListener;

    public EventLog(
            App app,
            MujoongClock clock,
            OwnerHasher ownerHasher,
            Supplier<String> traceIdSupplier,
            Sink sink,
            Strictness strictness,
            InvalidAttrListener invalidAttrListener) {
        this.app = Objects.requireNonNull(app, "app");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ownerHasher = Objects.requireNonNull(ownerHasher, "ownerHasher");
        this.traceIdSupplier = Objects.requireNonNull(traceIdSupplier, "traceIdSupplier");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.strictness = Objects.requireNonNull(strictness, "strictness");
        this.invalidAttrListener = Objects.requireNonNull(invalidAttrListener, "invalidAttrListener");
    }

    /** 사건 한 줄을 시작한다. */
    public Entry event(EventCode event, Component component) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(component, "component");
        if (component.app() != app) {
            invalid(event, "component");
        }
        return new Entry(event, component);
    }

    private void invalid(EventCode event, String keyName) {
        if (strictness == Strictness.STRICT) {
            throw new IllegalArgumentException("사건 " + event + "에 허용되지 않은 attrs: " + keyName);
        }
        invalidAttrListener.onInvalidAttr(event, keyName);
    }

    /** 사건 한 줄 작성기. 스레드 사이에 공유하지 않는다. */
    public final class Entry {

        private final EventCode event;
        private final Component component;
        private final Map<AttrKey, Object> attrs = new EnumMap<>(AttrKey.class);
        private final Map<AttrKey, String> enumAttrs = new EnumMap<>(AttrKey.class);
        private String ownerHash;
        private String traceId;
        private EventCode.Level level;

        private Entry(EventCode event, Component component) {
            this.event = event;
            this.component = component;
        }

        /** 소유 id(세션 · 기기 · 집합체 · 보호자, OD-4 표). null이면 ownerHash null. */
        public Entry owner(UUID ownerId) {
            this.ownerHash = ownerHasher.hash(ownerId);
            return this;
        }

        /** 이 줄의 traceId(세션 traceId 등). 형식이 틀리면 무시하고 공급자 값을 쓴다. */
        public Entry traceId(String value) {
            this.traceId = TraceIds.isValid(value) ? value : null;
            return this;
        }

        /** 수준을 직접 정한다(예: 쿠키 없는 WSS 업그레이드의 SECURITY_AUTH_FAILED는 INFO, OD-2). */
        public Entry level(EventCode.Level value) {
            this.level = value;
            return this;
        }

        /** 숫자 값. */
        public Entry attr(AttrKey key, long value) {
            if (accept(key, AttrKey.ValueType.NUMBER)) {
                attrs.put(key, value);
            }
            return this;
        }

        /** 불리언 값. */
        public Entry attr(AttrKey key, boolean value) {
            if (accept(key, AttrKey.ValueType.BOOLEAN)) {
                attrs.put(key, value);
            }
            return this;
        }

        /** 시각 값(null이면 키를 쓰지 않는다). */
        public Entry attr(AttrKey key, Instant value) {
            if (value != null && accept(key, AttrKey.ValueType.TIMESTAMP)) {
                attrs.put(key, value);
            }
            return this;
        }

        /** UUID 값(eventId만, null이면 키를 쓰지 않는다). */
        public Entry attr(AttrKey key, UUID value) {
            if (value != null && accept(key, AttrKey.ValueType.UUID)) {
                attrs.put(key, value);
            }
            return this;
        }

        /** 열거값(이름). 등록되지 않은 값은 UNKNOWN. null이면 키를 쓰지 않는다. */
        public Entry attr(AttrKey key, Enum<?> value) {
            return value == null ? this : attr(key, value.name());
        }

        /**
         * 열거값 · 식별자 · 라벨 · 이름 목록 문자열. 등록되지 않은 열거값과 형식이 틀린 식별자 · 라벨 · 이름 목록은 UNKNOWN(U9 SD6).
         * null이면 키를 쓰지 않는다.
         */
        public Entry attr(AttrKey key, String value) {
            if (value == null) {
                return this;
            }
            if (key.valueType() == AttrKey.ValueType.IDENTIFIER) {
                if (accept(key, AttrKey.ValueType.IDENTIFIER)) {
                    attrs.put(key, StructuredLogFormatter.isValidErrorKind(value) ? value : AttrKey.UNKNOWN);
                }
                return this;
            }
            if (key.valueType() == AttrKey.ValueType.LABEL) {
                if (accept(key, AttrKey.ValueType.LABEL)) {
                    attrs.put(key, StructuredLogFormatter.isValidLabel(value) ? value : AttrKey.UNKNOWN);
                }
                return this;
            }
            if (key.valueType() == AttrKey.ValueType.NAME_LIST) {
                if (accept(key, AttrKey.ValueType.NAME_LIST)) {
                    attrs.put(key, StructuredLogFormatter.isValidNameList(value) ? value : AttrKey.UNKNOWN);
                }
                return this;
            }
            if (accept(key, AttrKey.ValueType.ENUM)) {
                boolean registered = ENUM_FORMAT.matcher(value).matches() && event.enumValues(key).contains(value);
                String normalized = registered ? value : AttrKey.UNKNOWN;
                attrs.put(key, normalized);
                enumAttrs.put(key, normalized);
            }
            return this;
        }

        /** 이름 목록 값(쉼표로 잇는다, U9 SD6). 목록 형식이 틀리면 UNKNOWN. null이면 키를 쓰지 않는다. */
        public Entry attr(AttrKey key, java.util.List<String> names) {
            if (names == null) {
                return this;
            }
            boolean wellFormed = names.stream().noneMatch(Objects::isNull);
            return attr(key, wellFormed ? String.join(",", names) : AttrKey.UNKNOWN);
        }

        /** 완성된 줄(쓰지 않음). 병합 · 시험에 쓴다. */
        public String render() {
            Instant ts = clock.now();
            String effectiveTraceId = traceId != null ? traceId : TraceIds.orNew(traceIdSupplier.get());
            return StructuredLogFormatter.format(
                    ts, effectiveLevel(), effectiveTraceId, component.value(), event, ownerHash, attrs);
        }

        /** 이 줄의 수준. */
        public EventCode.Level effectiveLevel() {
            return level != null ? level : event.resolveLevel(enumAttrs);
        }

        /** 사건 코드. */
        public EventCode event() {
            return event;
        }

        /** 지금까지 넣은 attrs(읽기 전용 사본). 병합 식별에 쓴다. */
        public Map<AttrKey, Object> attrs() {
            return Map.copyOf(attrs);
        }

        /** 줄을 만들어 내보낸다. */
        public void emit() {
            sink.write(effectiveLevel(), render());
        }

        private boolean accept(AttrKey key, AttrKey.ValueType expected) {
            Objects.requireNonNull(key, "key");
            if (!event.allowedKeys().contains(key) || key.valueType() != expected) {
                invalid(event, key.key());
                return false;
            }
            return true;
        }
    }

    /** SLF4J 사건 로거({@value #LOGGER_NAME})로 내보내는 기본 출구. */
    public static final class Slf4jSink implements Sink {

        private final Logger logger = LoggerFactory.getLogger(LOGGER_NAME);

        @Override
        public void write(EventCode.Level level, String line) {
            switch (level) {
                case ERROR -> logger.error(line);
                case WARN -> logger.warn(line);
                case INFO -> logger.info(line);
                case DEBUG -> logger.debug(line);
            }
        }
    }
}
