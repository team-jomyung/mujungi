package kr.mujoong.contract.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.CoreConstants;
import ch.qos.logback.core.LayoutBase;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import kr.mujoong.contract.trace.TraceIds;

/**
 * 로그 한 줄 = JSON 객체 하나(OD-1 · NFR9.15). 최상위 키는 이 순서로 고정이다:
 * {@code ts · level · traceId · component · event · ownerHash · attrs}. message · 예외 · 스레드 같은 다른 최상위 키는 없다.
 *
 * <p>이 클래스는 이미 검사된 값만 받아 문자열을 만든다(키 · 형식 검사는 {@link EventLog}). 줄바꿈 · 따옴표 · 제어 문자는 JSON
 * 이스케이프한다(OD-3 2번). 제3자 로그를 APP_ERROR 한 줄로 바꾸는 Logback 레이아웃은 {@link AppErrorLayout}이다(OD-3 3번).
 */
public final class StructuredLogFormatter {

    /** ts 형식: ISO 8601 UTC, 밀리초, 끝 Z. */
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    /** errorKind 허용 형식(OD-2). */
    private static final Pattern ERROR_KIND = Pattern.compile("^[A-Za-z][A-Za-z0-9_.$]{0,99}$");
    /** LABEL 값 형식(모델 이름 · 프롬프트 판, U9 SD6). */
    private static final Pattern LABEL = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    /** NAME_LIST의 이름 하나 형식(U9 SD6). */
    private static final Pattern FIELD_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");
    /** NAME_LIST 이름 수 상한(U9 SD6). */
    static final int MAX_NAME_LIST_SIZE = 32;

    private StructuredLogFormatter() {
    }

    /** UTC 밀리초 시각 문자열. */
    public static String formatTimestamp(Instant instant) {
        return TS.format(instant);
    }

    /**
     * 사건 한 줄을 만든다.
     *
     * @param attrs 키 순서대로 쓴다. 값은 Long · Integer(숫자) · Boolean · String(열거 · 식별자) · Instant(시각) · UUID 중 하나다.
     */
    public static String format(
            Instant ts,
            EventCode.Level level,
            String traceId,
            String component,
            EventCode event,
            String ownerHash,
            Map<AttrKey, Object> attrs) {
        StringBuilder json = new StringBuilder(160);
        json.append("{\"ts\":");
        appendString(json, formatTimestamp(ts));
        json.append(",\"level\":");
        appendString(json, level.name());
        json.append(",\"traceId\":");
        appendString(json, traceId);
        json.append(",\"component\":");
        appendString(json, component);
        json.append(",\"event\":");
        appendString(json, event.name());
        json.append(",\"ownerHash\":");
        if (ownerHash == null) {
            json.append("null");
        } else {
            appendString(json, ownerHash);
        }
        json.append(",\"attrs\":{");
        boolean first = true;
        for (Map.Entry<AttrKey, Object> attr : attrs.entrySet()) {
            if (attr.getValue() == null) {
                continue;
            }
            if (!first) {
                json.append(',');
            }
            first = false;
            appendString(json, attr.getKey().key());
            json.append(':');
            appendValue(json, attr.getValue());
        }
        json.append("}}");
        return json.toString();
    }

    /**
     * APP_ERROR 한 줄(OD-3 3번). 메시지 · 스택은 받지 않고 errorKind(예외 클래스 단순 이름)와 repeat만 쓴다. errorKind 형식이 틀리면
     * 키를 빼고, traceId 형식이 틀리면 새로 만든다.
     */
    public static String formatAppError(
            Instant ts, EventCode.Level level, String traceId, String component, String errorKind, Long repeat) {
        Map<AttrKey, Object> attrs = new java.util.LinkedHashMap<>();
        if (errorKind != null && ERROR_KIND.matcher(errorKind).matches()) {
            attrs.put(AttrKey.ERROR_KIND, errorKind);
        }
        if (repeat != null) {
            attrs.put(AttrKey.REPEAT, repeat);
        }
        return format(ts, level, TraceIds.orNew(traceId), component, EventCode.APP_ERROR, null, attrs);
    }

    /** errorKind로 쓸 수 있는 형식인가. */
    public static boolean isValidErrorKind(String errorKind) {
        return errorKind != null && ERROR_KIND.matcher(errorKind).matches();
    }

    /** LABEL 값으로 쓸 수 있는 형식인가(U9 SD6). */
    public static boolean isValidLabel(String value) {
        return value != null && LABEL.matcher(value).matches();
    }

    /** NAME_LIST 값으로 쓸 수 있는 형식인가(쉼표로 이은 이름, 최대 32개, 빈 목록 허용 · U9 SD6). */
    public static boolean isValidNameList(String value) {
        if (value == null) {
            return false;
        }
        if (value.isEmpty()) {
            return true;
        }
        String[] names = value.split(",", -1);
        if (names.length > MAX_NAME_LIST_SIZE) {
            return false;
        }
        for (String name : names) {
            if (!FIELD_NAME.matcher(name).matches()) {
                return false;
            }
        }
        return true;
    }

    private static void appendValue(StringBuilder json, Object value) {
        switch (value) {
            case Long number -> json.append(number.longValue());
            case Integer number -> json.append(number.intValue());
            case Boolean bool -> json.append(bool.booleanValue());
            case Instant instant -> appendString(json, formatTimestamp(instant));
            case UUID uuid -> appendString(json, uuid.toString());
            case String text -> appendString(json, text);
            default -> throw new IllegalArgumentException("로그 attrs 값 형식이 아니다: " + value.getClass().getSimpleName());
        }
    }

    /** JSON 문자열 리터럴(따옴표 포함)을 덧붙인다. */
    static void appendString(StringBuilder json, String value) {
        json.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        json.append('"');
    }

    /**
     * 프레임워크 · 라이브러리 로그를 APP_ERROR 한 줄로 바꾸는 Logback 레이아웃(OD-3 3번). 로그 메시지 · 예외 메시지 · 스택은 버리고
     * 예외 클래스 단순 이름만 errorKind로 남긴다. traceId는 MDC {@code traceId}가 형식에 맞을 때만 쓰고 아니면 새로 만든다.
     * Logback은 앱 실행 클래스패스에만 있으므로(contract-model은 compileOnly) 이 클래스는 앱의 {@code logback-spring.xml}에서만 쓴다.
     *
     * <pre>{@code
     * <encoder class="ch.qos.logback.core.encoder.LayoutWrappingEncoder">
     *   <layout class="kr.mujoong.contract.log.StructuredLogFormatter$AppErrorLayout">
     *     <component>core-api/Maintenance</component>
     *   </layout>
     * </encoder>
     * }</pre>
     */
    public static final class AppErrorLayout extends LayoutBase<ILoggingEvent> {

        private String component = "unknown/Maintenance";

        /** 사건 줄의 component 값(예: {@code core-api/Maintenance}). */
        public void setComponent(String component) {
            this.component = component;
        }

        @Override
        public String doLayout(ILoggingEvent event) {
            String traceId = event.getMDCPropertyMap() == null ? null : event.getMDCPropertyMap().get(TraceIds.MDC_KEY);
            return formatAppError(
                            Instant.ofEpochMilli(event.getTimeStamp()),
                            toLevel(event.getLevel()),
                            traceId,
                            component,
                            errorKind(event.getThrowableProxy()),
                            null)
                    + CoreConstants.LINE_SEPARATOR;
        }

        private static EventCode.Level toLevel(Level level) {
            if (level == null) {
                return EventCode.Level.WARN;
            }
            return switch (level.toInt()) {
                case Level.ERROR_INT -> EventCode.Level.ERROR;
                case Level.WARN_INT -> EventCode.Level.WARN;
                case Level.INFO_INT -> EventCode.Level.INFO;
                default -> EventCode.Level.DEBUG;
            };
        }

        private static String errorKind(IThrowableProxy proxy) {
            if (proxy == null || proxy.getClassName() == null) {
                return null;
            }
            String className = proxy.getClassName();
            return className.substring(className.lastIndexOf('.') + 1);
        }
    }
}
