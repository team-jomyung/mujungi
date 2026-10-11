package kr.mujoong.realtime.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.log.OwnerHasher;
import kr.mujoong.contract.log.StructuredLogFormatter;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.trace.TraceIds;
import kr.mujoong.realtime.config.RealtimeProperties;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Realtime 사건 로그의 유일한 입구 (U1 3.5 Step 26 · OD-1 ~ OD-4 · OD-13 · SD-15 · NFR9.15 · BR8.7 · BR5.13).
 *
 * <p>계약의 타입 있는 {@link EventLog}(사건 코드별 허용 키, 자유 문자열 없음) 위에 앱 규칙을 얹는다:
 *
 * <ul>
 *   <li>ts는 주입한 Clock, traceId는 줄마다 준 값(세션 · 연결 traceId) 또는 MDC({@value TraceIds#MDC_KEY}), ownerHash는
 *       {@link OwnerHasher}.
 *   <li>허용 표 밖 키: test 프로필이면 예외(STRICT), 그 밖은 버리고 {@code mujoong_log_invalid_attr_total}과 APP_ERROR 한 줄(LENIENT).
 *   <li>반복 병합(OD-3 4번): INTERNAL_HTTP 실패 · SECURITY_* · APP_ERROR는 {@link LogMergeWindow}로 같은 식별 attrs를 합친다.
 *   <li>보안 사건 분당 상한(SD-15 ②): 상한을 넘은 줄은 쓰지 않고 지표는 실제 건수를 센다.
 * </ul>
 *
 * <p>전사 · 원음 · 토큰 · 쿠키 · 공급자 요청 · 이벤트 본문을 담을 키가 계약 허용 표에 없으므로 이 입구로는 그런 값이 로그에 갈 수 없다.
 * Core에 같은 규칙의 입구가 따로 있다(LC-11).
 */
@Component
public class EventLogger {

    /** 병합 키에서 빼는 attrs(호출마다 달라지는 값). */
    private static final List<AttrKey> NON_IDENTIFYING = List.of(AttrKey.DURATION_MS, AttrKey.REPEAT, AttrKey.ATTEMPT);

    private static final String MAINTENANCE_COMPONENT = "realtime/Maintenance";

    private final MujoongClock clock;
    private final EventLog.Sink sink;
    private final RealtimeMetrics metrics;
    private final LogMergeWindow<EventLog.Entry> mergeWindow;
    private final SecurityEventCap securityCap;
    private final EventLog eventLog;

    @Autowired
    public EventLogger(
            MujoongClock clock, OwnerHasher ownerHasher, RealtimeProperties properties, Environment environment,
            RealtimeMetrics metrics) {
        this(clock, ownerHasher, new EventLog.Slf4jSink(), metrics, strictnessFor(environment.getActiveProfiles()),
                properties.observability().logMergeWindow(), properties.security().eventLogRateCap());
    }

    /**
     * @param sink 완성된 줄을 받는 곳(운영은 SLF4J 사건 로거, 시험은 수집기)
     * @param mergeWindow 반복 병합 창 길이
     * @param securityCapPerMinute 보안 사건 type당 분당 줄 상한
     */
    public EventLogger(
            MujoongClock clock,
            OwnerHasher ownerHasher,
            EventLog.Sink sink,
            RealtimeMetrics metrics,
            EventLog.Strictness strictness,
            Duration mergeWindow,
            int securityCapPerMinute) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.mergeWindow = new LogMergeWindow<>(mergeWindow);
        this.securityCap = new SecurityEventCap(securityCapPerMinute);
        this.eventLog = new EventLog(EventLog.App.REALTIME, clock, Objects.requireNonNull(ownerHasher, "ownerHasher"),
                () -> MDC.get(TraceIds.MDC_KEY), sink, strictness, this::onInvalidAttr);
    }

    /** test 프로필이면 STRICT, 그 밖은 LENIENT(OD-3 1번). 시험용 기록기는 시험 소스(testing.TestEventLoggers)가 만든다(3.6 수정 J308). */
    public static EventLog.Strictness strictnessFor(String... activeProfiles) {
        return Arrays.asList(activeProfiles).contains("test") ? EventLog.Strictness.STRICT : EventLog.Strictness.LENIENT;
    }

    /**
     * 사건 한 줄을 남긴다(traceId는 MDC).
     *
     * @param owner ownerHash의 소유 id(세션 · 기기 · 집합체). 없으면 null
     * @param attrs 허용 키만 채우는 작성 함수
     */
    public void log(EventCode code, EventLog.Component component, UUID owner, Consumer<EventLog.Entry> attrs) {
        log(code, component, owner, null, attrs);
    }

    /**
     * 사건 한 줄을 남긴다.
     *
     * @param traceId 이 줄의 traceId(세션 · 연결 traceId). 형식이 틀리거나 null이면 MDC 값
     */
    public void log(EventCode code, EventLog.Component component, UUID owner, String traceId,
            Consumer<EventLog.Entry> attrs) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(component, "component");
        EventLog.Entry entry = eventLog.event(code, component);
        if (owner != null) {
            entry.owner(owner);
        }
        if (traceId != null) {
            entry.traceId(traceId);
        }
        if (attrs != null) {
            attrs.accept(entry);
        }
        if (code.isSecurityEvent()) {
            metrics.securityEvent(code.name());
        }
        Instant now = clock.now();
        if (isMergeTarget(entry)) {
            LogMergeWindow.Offer<EventLog.Entry> offer = mergeWindow.offer(mergeKey(entry, component), entry, now);
            writeRepeats(offer.expired());
            if (!offer.emitNow()) {
                return;
            }
        }
        if (code.isSecurityEvent() && !securityCap.allow(code.name(), now)) {
            return;
        }
        sink.write(entry.effectiveLevel(), entry.render());
    }

    /** 예외를 APP_ERROR 한 줄(errorKind = 예외 클래스 단순 이름, 메시지 · 스택 없음)로 남긴다(OD-3 3번). */
    public void appError(EventLog.Component component, Throwable error) {
        appError(component, error == null ? null : error.getClass().getSimpleName());
    }

    /** errorKind 식별자로 APP_ERROR 한 줄을 남긴다(예: {@code OrphanSessionDetected}). */
    public void appError(EventLog.Component component, String errorKind) {
        appError(component, errorKind, null);
    }

    /**
     * errorKind 식별자로 APP_ERROR 한 줄을 남긴다.
     *
     * @param level 수준을 바꿀 때(예: ACTIVE 뒤 공급자 끊김은 WARN, PD 2.1-C). null이면 ERROR
     */
    public void appError(EventLog.Component component, String errorKind, EventCode.Level level) {
        String kind = StructuredLogFormatter.isValidErrorKind(errorKind) ? errorKind : "Unknown";
        log(EventCode.APP_ERROR, component, null, entry -> {
            entry.attr(AttrKey.ERROR_KIND, kind);
            if (level != null) {
                entry.level(level);
            }
        });
    }

    /** 창이 끝난 반복 줄을 쓴다(maintenance 주기 작업 · 정상 종료). */
    public void flushMerged() {
        writeRepeats(mergeWindow.drainExpired(clock.now()));
    }

    private void writeRepeats(List<LogMergeWindow.Repeated<EventLog.Entry>> repeats) {
        for (LogMergeWindow.Repeated<EventLog.Entry> repeated : repeats) {
            EventLog.Entry entry = repeated.first();
            entry.attr(AttrKey.REPEAT, repeated.repeat());
            if (entry.event().isSecurityEvent() && !securityCap.allow(entry.event().name(), clock.now())) {
                continue;
            }
            sink.write(entry.effectiveLevel(), entry.render());
        }
    }

    private static boolean isMergeTarget(EventLog.Entry entry) {
        EventCode code = entry.event();
        if (!code.isMergeable()) {
            return false;
        }
        // INTERNAL_HTTP는 실패만 병합한다(성공 줄은 호출 쪽 규칙대로 그대로 쓴다)
        return code != EventCode.INTERNAL_HTTP || !"OK".equals(entry.attrs().get(AttrKey.OUTCOME));
    }

    private static Object mergeKey(EventLog.Entry entry, EventLog.Component component) {
        Map<AttrKey, Object> identifying = new EnumMap<>(AttrKey.class);
        identifying.putAll(entry.attrs());
        NON_IDENTIFYING.forEach(identifying::remove);
        // 수준도 식별 값이다: 쿠키 없는 업그레이드(INFO)와 무효 토큰(WARN)이 한 줄로 합쳐져 WARN이 묻히지 않게 한다(SD-15)
        return List.of(entry.event(), component, entry.effectiveLevel(), identifying);
    }

    private void onInvalidAttr(EventCode event, String keyName) {
        metrics.invalidLogAttr(event.name());
        // 키 이름은 쓰지 않는다(허용 표 밖 문자열이 로그에 섞이지 않게). 사건 종류는 지표 라벨이 남긴다.
        sink.write(EventCode.Level.ERROR, StructuredLogFormatter.formatAppError(
                clock.now(), EventCode.Level.ERROR, MDC.get(TraceIds.MDC_KEY), MAINTENANCE_COMPONENT, "InvalidLogAttr",
                null));
    }
}
