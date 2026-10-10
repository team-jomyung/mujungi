package kr.mujoong.testsupport.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

/**
 * 사건 로그 줄 수집기(OD-1 ~ OD-3 · NFR9.15 시험용). 두 가지로 쓴다.
 *
 * <ul>
 *   <li>{@link #attach()}: Logback의 {@value EventLog#LOGGER_NAME} 로거에 붙어 앱이 실제로 낸 줄을 모은다(Spring 시험).
 *   <li>{@link #detached()}: {@link EventLog.Sink}로 직접 넘겨 로거 없이 모은다(단위 시험).
 * </ul>
 *
 * <p>모은 줄은 원문 그대로({@link #lines()}) 또는 JSON 객체로({@link #events()}) 본다. JSON 객체는 키 순서를 보존한다(OD-1 키 순서 확인).
 * 한 줄이 JSON 객체 하나가 아니면 {@link #events()}가 실패한다 — 그 자체가 OD-1 위반이다.
 */
public final class LogCapture implements EventLog.Sink, AutoCloseable {

    private final List<String> lines;
    private final Logger logger;
    private final Level previousLevel;
    private final AppenderBase<ILoggingEvent> appender;

    private LogCapture(List<String> lines, Logger logger, Level previousLevel, AppenderBase<ILoggingEvent> appender) {
        this.lines = lines;
        this.logger = logger;
        this.previousLevel = previousLevel;
        this.appender = appender;
    }

    /** {@value EventLog#LOGGER_NAME} 로거에 붙인다. 로거 수준은 그대로 둔다. */
    public static LogCapture attach() {
        return attach(false);
    }

    /**
     * {@value EventLog#LOGGER_NAME} 로거에 붙인다.
     *
     * @param includeDebug true면 닫을 때까지 로거 수준을 DEBUG로 내린다(DEBUG 사건까지 확인할 때)
     */
    public static LogCapture attach(boolean includeDebug) {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext context)) {
            throw new IllegalStateException("Logback이 SLF4J 구현이 아니다: " + factory.getClass().getName());
        }
        Logger logger = context.getLogger(EventLog.LOGGER_NAME);
        Level previous = logger.getLevel();
        List<String> lines = new CopyOnWriteArrayList<>();
        AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                lines.add(event.getFormattedMessage());
            }
        };
        appender.setContext(context);
        appender.setName("mujoong-log-capture-" + UUID.randomUUID());
        appender.start();
        LogCapture capture = new LogCapture(lines, logger, previous, appender);
        if (includeDebug) {
            logger.setLevel(Level.DEBUG);
        }
        logger.addAppender(appender);
        return capture;
    }

    /** 로거에 붙지 않은 수집기({@link EventLog}의 Sink로 넘긴다). */
    public static LogCapture detached() {
        return new LogCapture(new CopyOnWriteArrayList<>(), null, null, null);
    }

    @Override
    public void write(EventCode.Level level, String line) {
        Objects.requireNonNull(line, "line");
        lines.add(line);
    }

    /** 모은 줄(원문, 도착 순서). */
    public List<String> lines() {
        return List.copyOf(lines);
    }

    /** 모은 줄을 JSON 객체로(키 순서 보존). */
    public List<Map<String, Object>> events() {
        List<Map<String, Object>> parsed = new ArrayList<>();
        for (String line : lines) {
            parsed.add(parse(line));
        }
        return parsed;
    }

    /** 사건 코드가 {@code code}인 줄만. */
    public List<Map<String, Object>> events(EventCode code) {
        Objects.requireNonNull(code, "code");
        return events().stream().filter(event -> code.name().equals(event.get("event"))).toList();
    }

    /** 사건 코드가 {@code code}인 마지막 줄. */
    public Optional<Map<String, Object>> last(EventCode code) {
        List<Map<String, Object>> matching = events(code);
        return matching.isEmpty() ? Optional.empty() : Optional.of(matching.getLast());
    }

    /** {@code event}의 attrs(없으면 빈 맵). */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> attrs(Map<String, Object> event) {
        Object attrs = event.get("attrs");
        return attrs instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /** 어느 줄이든 {@code needle}을 담는가(비밀 · 원문 유출 확인용). */
    public boolean anyLineContains(String needle) {
        Objects.requireNonNull(needle, "needle");
        return lines.stream().anyMatch(line -> line.contains(needle));
    }

    /** 모은 줄을 비운다. */
    public void clear() {
        lines.clear();
    }

    /** 로거에서 떼고 수준을 되돌린다. detached면 아무것도 하지 않는다. */
    @Override
    public void close() {
        if (logger == null) {
            return;
        }
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String line) {
        Object value = JsonCodec.shared().read(line, LinkedHashMap.class);
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalStateException("로그 줄이 JSON 객체가 아니다(OD-1)");
        }
        return (Map<String, Object>) value;
    }
}
