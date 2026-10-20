package kr.mujoong.realtime.messaging;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import kr.mujoong.contract.event.EventType;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.trace.TraceIds;
import kr.mujoong.realtime.common.scaling.SingleInstanceAssumption;
import kr.mujoong.realtime.config.RealtimeOutboxProperties;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.health.ReadinessState;
import kr.mujoong.realtime.internalhttp.InternalHttpClient;
import kr.mujoong.realtime.internalhttp.InternalHttpClient.CallException;
import kr.mujoong.realtime.internalhttp.InternalHttpClient.Response;
import kr.mujoong.realtime.observability.EventLogger;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Realtime Outbox 발송기 (U1 3.5 Step 26 · LC-11).
 *
 * <p>{@code outbox-sender} 전용 스레드 하나가 {@code mujoong.outbox.poll-interval}마다 {@link #runCycle}을 부른다. 한 사이클:
 * 준비 완료이고 정지 요청이 없을 때만, PENDING이고 available_at이 지난 행을 {@code batch-size}건 SKIP LOCKED로 선점(available_at =
 * now + claim-lease)한 뒤 한 건씩 직렬로 Core {@code POST /internal/events}에 보낸다(DB 트랜잭션을 쥐지 않음, 제한
 * {@code event-publish-timeout}). 봉투는 행의 열에서 다시 만들고 X-Trace-Id는 행의 trace_id다(OD-7).
 *
 * <p>결과: 200 → PUBLISHED(payload 비움). 409 → FAILED(EVENT_CONFLICT) · 400 → FAILED(INVALID_ENVELOPE), 즉시 · 경고. 그 밖(401 ·
 * 시간 초과 · 연결 실패 · 나머지 4xx · 5xx) → retry_count + 1, available_at = now + 대기표(1 · 2 · 4 · 8 · 16 · 30초, 마지막 값 반복).
 * retry_count가 이미 max-retries(20)인 행이 또 실패하면 FAILED(retry_count 20 유지). 가득 찬 배치면 쉬지 않고 한 번 더 고른다. 정지
 * 요청이 오면 아직 보내지 않은 선점 행의 available_at을 now로 되돌리고 멈춘다.
 */
@Component
@SingleInstanceAssumption(si = "SI-6",
        reason = "두 인스턴스의 발송 폴러가 같은 PENDING 행을 보낼 수 있다(SKIP LOCKED 선점이 줄이지만 수신 쪽 멱등이 흡수할 중복 전송이 남는다)")
public class RealtimeOutboxSender {

    /** 발송 결정(표). */
    public enum Decision {
        PUBLISHED,
        FAILED_IMMEDIATE,
        RETRY,
        FAILED_EXHAUSTED
    }

    private final RealtimeOutboxRepository outbox;
    private final InternalHttpClient http;
    private final JsonCodec json;
    private final BooleanSupplier ready;
    private final EventLogger events;
    private final MujoongClock clock;
    private final int batchSize;
    private final List<Duration> retryBackoff;
    private final int maxRetries;
    private final Duration claimLease;
    private final Duration publishTimeout;

    private volatile boolean stopping;

    /** U4b: PUBLISHED 뒤 뒷일(예: DeletionCompleted 발행 뒤 메시징 삭제 참가자 한 번 더). */
    private volatile List<OutboxPublishedListener> publishedListeners = List.of();

    @Autowired
    public RealtimeOutboxSender(
            RealtimeOutboxRepository outbox,
            InternalHttpClient http,
            JsonCodec json,
            ReadinessState readiness,
            EventLogger events,
            MujoongClock clock,
            RealtimeProperties properties,
            org.springframework.beans.factory.ObjectProvider<OutboxPublishedListener> listeners) {
        this(outbox, http, json, readiness::isReady, events, clock, properties.outbox(),
                properties.internalHttp().eventPublishTimeout());
        this.publishedListeners = listeners.orderedStream().toList();
    }

    /** PUBLISHED 뒤 뒷일을 바꾼다(시험). */
    public void publishedListeners(List<OutboxPublishedListener> listeners) {
        this.publishedListeners = List.copyOf(listeners);
    }

    public RealtimeOutboxSender(
            RealtimeOutboxRepository outbox,
            InternalHttpClient http,
            JsonCodec json,
            BooleanSupplier ready,
            EventLogger events,
            MujoongClock clock,
            RealtimeOutboxProperties config,
            Duration publishTimeout) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.http = Objects.requireNonNull(http, "http");
        this.json = Objects.requireNonNull(json, "json");
        this.ready = Objects.requireNonNull(ready, "ready");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.batchSize = config.batchSize();
        this.retryBackoff = List.copyOf(config.retryBackoff());
        this.maxRetries = config.maxRetries();
        this.claimLease = config.claimLease();
        this.publishTimeout = Objects.requireNonNull(publishTimeout, "publishTimeout");
    }

    /**
     * 한 사이클을 돈다.
     *
     * @return 이번 사이클에 결과를 기록한 행 수
     */
    public int runCycle() {
        int handled = 0;
        while (!stopping && ready.getAsBoolean()) {
            List<OutboxEventRow> claimed = outbox.claimDue(clock.now(), batchSize, claimLease);
            if (claimed.isEmpty()) {
                return handled;
            }
            for (int i = 0; i < claimed.size(); i++) {
                if (stopping) {
                    releaseRest(claimed.subList(i, claimed.size()));
                    return handled;
                }
                sendOne(claimed.get(i));
                handled++;
            }
            if (claimed.size() < batchSize) {
                return handled;
            }
        }
        return handled;
    }

    /** 정지 요청(T4): 진행 중인 한 건은 마치고, 남은 선점은 되돌린다. */
    public void requestStop() {
        stopping = true;
    }

    /** 정지 요청을 받았는가. */
    public boolean isStopping() {
        return stopping;
    }

    /** 실패 순번(1부터)의 대기 시간. 표 길이를 넘으면 마지막 값을 되풀이한다(재시도 표). */
    public static Duration backoff(List<Duration> table, int failureNumber) {
        if (table.isEmpty() || failureNumber < 1) {
            throw new IllegalArgumentException("대기표가 비었거나 실패 순번이 1보다 작다");
        }
        return table.get(Math.min(failureNumber, table.size()) - 1);
    }

    /** 응답(없으면 null)과 지금 retry_count로 결정한다. */
    public static Decision decide(Integer status, int retryCount, int maxRetries) {
        if (status != null && status >= 200 && status < 300) {
            return Decision.PUBLISHED;
        }
        if (status != null && (status == 409 || status == 400)) {
            return Decision.FAILED_IMMEDIATE;
        }
        return retryCount >= maxRetries ? Decision.FAILED_EXHAUSTED : Decision.RETRY;
    }

    /** last_error: 409 · 400은 상대가 답한 계약 코드, 그 밖은 상태 코드만. */
    static String lastErrorFor(int status) {
        return switch (status) {
            case 409 -> LastError.EVENT_CONFLICT;
            case 400 -> LastError.INVALID_ENVELOPE;
            default -> LastError.http(status);
        };
    }

    private void sendOne(OutboxEventRow row) {
        String previousTrace = MDC.get(TraceIds.MDC_KEY);
        String traceId = row.traceId() != null ? row.traceId() : TraceIds.newTraceId();
        MDC.put(TraceIds.MDC_KEY, traceId);
        long start = clock.monotonicNanos();
        try {
            Integer status = null;
            String lastError;
            try {
                String body = json.encodeEventEnvelope(row.toEnvelope());
                Response response = http.send(InternalHttpClient.Target.EVENT_PUBLISH,
                        new InternalHttpClient.Call("POST", "/internal/events", body, publishTimeout, row.retryCount() + 1,
                                row.aggregateId(), traceId));
                status = response.status();
                lastError = lastErrorFor(status);
            } catch (CallException e) {
                lastError = e.outcome().name();
            }
            apply(row, status, lastError, clock.elapsedSince(start));
        } finally {
            if (previousTrace == null) {
                MDC.remove(TraceIds.MDC_KEY);
            } else {
                MDC.put(TraceIds.MDC_KEY, previousTrace);
            }
        }
    }

    private void apply(OutboxEventRow row, Integer status, String lastError, Duration duration) {
        Instant now = clock.now();
        Decision decision = decide(status, row.retryCount(), maxRetries);
        boolean changed;
        String deliveryStatus;
        int retryCountAfter = row.retryCount();
        switch (decision) {
            case PUBLISHED -> {
                changed = outbox.markPublished(row.eventId(), now);
                deliveryStatus = "PUBLISHED";
                if (changed) {
                    notifyPublished(row);
                }
            }
            case FAILED_IMMEDIATE, FAILED_EXHAUSTED -> {
                changed = outbox.markFailed(row.eventId(), lastError);
                deliveryStatus = "FAILED";
            }
            default -> {
                retryCountAfter = row.retryCount() + 1;
                changed = outbox.scheduleRetry(row.eventId(), row.retryCount(),
                        now.plus(backoff(retryBackoff, retryCountAfter)), lastError);
                deliveryStatus = "RETRY";
            }
        }
        if (!changed) {
            // 그 사이 삭제 참가자가 행을 지웠거나 다른 경로가 상태를 바꿨다(조건부 UPDATE 0행, RD-8 4번)
            return;
        }
        long retryCount = retryCountAfter;
        events.log(EventCode.EVENT_DELIVERY, EventLog.Component.REALTIME_MESSAGING, row.aggregateId(), entry -> {
            entry.attr(AttrKey.EVENT_ID, row.eventId()).attr(AttrKey.STATUS, deliveryStatus)
                    .attr(AttrKey.RETRY_COUNT, retryCount).attr(AttrKey.DURATION_MS, duration.toMillis());
            EventType type = EventType.fromWire(row.eventType());
            if (type != EventType.UNRECOGNIZED) {
                entry.attr(AttrKey.EVENT_TYPE, type.wire());
            }
        });
    }

    /** U4b: 발행 뒤 뒷일. 실패는 앱 오류 한 줄로 남기고 발행 결과는 바꾸지 않는다. */
    private void notifyPublished(OutboxEventRow row) {
        for (OutboxPublishedListener listener : publishedListeners) {
            try {
                listener.published(row);
            } catch (RuntimeException e) {
                events.appError(EventLog.Component.REALTIME_MESSAGING, e);
            }
        }
    }

    private void releaseRest(List<OutboxEventRow> rest) {
        List<UUID> ids = new ArrayList<>(rest.size());
        for (OutboxEventRow row : rest) {
            ids.add(row.eventId());
        }
        outbox.releaseClaims(ids, clock.now());
    }
}
