package kr.mujoong.core.web.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.log.StructuredLogFormatter;
import org.slf4j.LoggerFactory;

/**
 * 앱이 표준 출력에 실제로 쓰는 줄 수집기(시험 소스 전용, SD-15 · NFR9.8 · OD-3 3번).
 *
 * <p>운영 logback-spring.xml과 같은 두 갈래를 그대로 흉내 낸다: 사건 로거({@value EventLog#LOGGER_NAME})는 메시지 원문, 루트(WARN 이상)는
 * {@link StructuredLogFormatter.AppErrorLayout}이 바꾼 APP_ERROR 한 줄. 그래서 모은 줄이 곧 컨테이너 로그에 남을 내용이다.
 */
public final class AppOutputCapture implements AutoCloseable {

    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final Logger root;
    private final Logger events;
    private final AppenderBase<ILoggingEvent> rootAppender;
    private final AppenderBase<ILoggingEvent> eventAppender;

    private AppOutputCapture() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        StructuredLogFormatter.AppErrorLayout layout = new StructuredLogFormatter.AppErrorLayout();
        layout.setContext(context);
        layout.setComponent("core-api/Maintenance");
        layout.start();
        this.root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        this.events = context.getLogger(EventLog.LOGGER_NAME);
        this.rootAppender = appender(context, event -> lines.add(layout.doLayout(event).strip()));
        this.eventAppender = appender(context, event -> lines.add(event.getFormattedMessage()));
        root.addAppender(rootAppender);
        events.addAppender(eventAppender);
    }

    /** 루트와 사건 로거에 붙인다. */
    public static AppOutputCapture attach() {
        return new AppOutputCapture();
    }

    /** 지금까지 나간 줄(도착 순서). */
    public List<String> lines() {
        return List.copyOf(lines);
    }

    @Override
    public void close() {
        root.detachAppender(rootAppender);
        events.detachAppender(eventAppender);
        rootAppender.stop();
        eventAppender.stop();
    }

    private static AppenderBase<ILoggingEvent> appender(LoggerContext context, java.util.function.Consumer<ILoggingEvent> sink) {
        AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                sink.accept(event);
            }
        };
        appender.setContext(context);
        appender.setName("mujoong-output-capture-" + UUID.randomUUID());
        appender.start();
        return appender;
    }
}
