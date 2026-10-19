package kr.mujoong.core.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import kr.mujoong.contract.event.EventType;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.trace.TraceIds;
import kr.mujoong.core.config.CoreOutboxProperties;
import kr.mujoong.core.config.CoreOutboxProperties.ControlEventPolicy;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.internalhttp.InternalHttpClient;
import kr.mujoong.core.internalhttp.InternalHttpClient.CallException;
import kr.mujoong.core.internalhttp.InternalHttpClient.Outcome;
import kr.mujoong.core.internalhttp.InternalHttpClient.Response;
import kr.mujoong.core.internalhttp.InternalHttpClient.Target;
import kr.mujoong.core.messaging.CoreOutboxRepository.OutboxEvent;
import kr.mujoong.core.messaging.CoreOutboxSender.Decision;
import kr.mujoong.core.support.TestEvents;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.MDC;

/**
 * Outbox 발송 결정 · 대기표 · 재무장 조건 (BR5.2 · BR5.3 · RD-8 · RD-10 · A1 · OD-7 · J124). 409/400 즉시 FAILED, 401 재시도, 21번째 실패
 * FAILED, 재무장 대상 · 제외 조건, RETRY_FOREVER 기동 실패.
 */
@Tag("skeleton")
class CoreOutboxSenderTest {

    private static final Instant NOW = Instant.parse("2001-03-01T07:00:00Z");
    private static final List<Duration> TABLE = List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4),
            Duration.ofSeconds(8), Duration.ofSeconds(16), Duration.ofSeconds(30));

    private final MutableClock clock = new MutableClock(NOW);
    private final TestEvents events = new TestEvents(clock);
    private final CoreOutboxRepository outbox = mock(CoreOutboxRepository.class);
    private final InternalHttpClient http = mock(InternalHttpClient.class);
    private final AtomicBoolean ready = new AtomicBoolean(true);

    private CoreOutboxSender sender(int batchSize) {
        CoreOutboxProperties config = new CoreOutboxProperties(Duration.ofMillis(500), batchSize, TABLE, 20,
                Duration.ofSeconds(30), ControlEventPolicy.REARM_ON_START);
        return new CoreOutboxSender(outbox, http, JsonCodec.shared(), ready::get, events.logger(), clock, config,
                Duration.ofSeconds(3));
    }

    @ParameterizedTest(name = "실패 {0}번째 → {1}초")
    @CsvSource({"1, 1", "2, 2", "3, 4", "4, 8", "5, 16", "6, 30", "7, 30", "20, 30"})
    void backoffTableRepeatsLastValue(int failure, long seconds) {
        assertThat(CoreOutboxSender.backoff(TABLE, failure)).isEqualTo(Duration.ofSeconds(seconds));
    }

    @Test
    void decisionTableFollowsBr53() {
        assertThat(CoreOutboxSender.decide(200, 5, 20)).isEqualTo(Decision.PUBLISHED);
        assertThat(CoreOutboxSender.decide(409, 0, 20)).isEqualTo(Decision.FAILED_IMMEDIATE);
        assertThat(CoreOutboxSender.decide(400, 0, 20)).isEqualTo(Decision.FAILED_IMMEDIATE);
        assertThat(CoreOutboxSender.decide(401, 0, 20)).isEqualTo(Decision.RETRY);
        assertThat(CoreOutboxSender.decide(503, 19, 20)).isEqualTo(Decision.RETRY);
        assertThat(CoreOutboxSender.decide(null, 19, 20)).isEqualTo(Decision.RETRY);
        assertThat(CoreOutboxSender.decide(503, 20, 20)).isEqualTo(Decision.FAILED_EXHAUSTED);
        assertThat(CoreOutboxSender.decide(null, 20, 20)).isEqualTo(Decision.FAILED_EXHAUSTED);
        assertThatThrownBy(() -> CoreOutboxSender.backoff(TABLE, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void responsesMapToRowUpdatesWithCodesOnly() throws Exception {
        OutboxEvent published = row(0, null);
        OutboxEvent conflict = row(0, null);
        OutboxEvent invalid = row(0, null);
        OutboxEvent unauthorized = row(0, null);
        OutboxEvent timedOut = row(3, null);
        OutboxEvent exhausted = row(20, null);
        when(outbox.claimDue(NOW, Duration.ofSeconds(30), 10))
                .thenReturn(List.of(published, conflict, invalid, unauthorized, timedOut, exhausted));
        when(http.send(eq(Target.EVENT_PUBLISH), any()))
                .thenReturn(new Response(200, "{}"), new Response(409, "{}"), new Response(400, "{}"),
                        new Response(401, "{}"))
                .thenThrow(new CallException(Target.EVENT_PUBLISH, Outcome.TIMEOUT, "HttpTimeoutException"))
                .thenReturn(new Response(503, "{}"));
        when(outbox.markPublished(any(), any())).thenReturn(1);
        when(outbox.markFailed(any(), anyString())).thenReturn(1);
        when(outbox.recordRetry(any(), anyInt(), any(), anyString())).thenReturn(1);

        assertThat(sender(10).runCycle()).isEqualTo(6);

        verify(outbox).markPublished(published.eventId(), NOW);
        verify(outbox).markFailed(conflict.eventId(), "EVENT_CONFLICT");
        verify(outbox).markFailed(invalid.eventId(), "INVALID_ENVELOPE");
        verify(outbox).recordRetry(unauthorized.eventId(), 0, NOW.plusSeconds(1), "HTTP_401");
        verify(outbox).recordRetry(timedOut.eventId(), 3, NOW.plusSeconds(8), "TIMEOUT");
        verify(outbox).markFailed(exhausted.eventId(), "HTTP_503");
        assertThat(events.capture().anyLineContains("payload-marker")).isFalse();
    }

    @Test
    void envelopeIsRebuiltFromRowAndTraceIdSurvivesRetries() throws Exception {
        String traceId = TraceIds.newTraceId();
        OutboxEvent retried = row(4, traceId);
        when(outbox.claimDue(any(), any(), anyInt())).thenReturn(List.of(retried));
        List<String> tracesSeen = new ArrayList<>();
        when(http.send(eq(Target.EVENT_PUBLISH), any())).thenAnswer(call -> {
            tracesSeen.add(MDC.get(TraceIds.MDC_KEY));
            return new Response(200, "{}");
        });
        when(outbox.markPublished(any(), any())).thenReturn(1);

        sender(10).runCycle();

        assertThat(tracesSeen).containsExactly(traceId);
        assertThat(MDC.get(TraceIds.MDC_KEY)).isNull();
        verify(http).send(eq(Target.EVENT_PUBLISH), argThat(call -> call.path().equals("/internal/events")
                && call.attempt() == 5 && call.timeout().equals(Duration.ofSeconds(3))
                && call.body().contains("\"traceId\":\"" + traceId + "\"")
                && call.body().contains("\"eventId\":\"" + retried.eventId() + "\"")
                && call.body().contains("\"payload\":{\"deviceId\"")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void notReadySendsNothingAndFullBatchClaimsAgain() throws Exception {
        ready.set(false);
        assertThat(sender(2).runCycle()).isZero();
        verify(outbox, never()).claimDue(any(), any(), anyInt());

        ready.set(true);
        when(outbox.claimDue(any(), any(), eq(2))).thenReturn(List.of(row(0, null), row(0, null)), List.of(row(0, null)));
        when(http.send(eq(Target.EVENT_PUBLISH), any())).thenReturn(new Response(200, "{}"));
        when(outbox.markPublished(any(), any())).thenReturn(1);

        assertThat(sender(2).runCycle()).isEqualTo(3);
        verify(outbox, times(2)).claimDue(any(), any(), eq(2));
    }

    @Test
    void stopRequestReleasesUnsentClaims() throws Exception {
        OutboxEvent first = row(0, null);
        OutboxEvent second = row(0, null);
        OutboxEvent third = row(0, null);
        CoreOutboxSender sender = sender(10);
        when(outbox.claimDue(any(), any(), anyInt())).thenReturn(List.of(first, second, third));
        when(http.send(eq(Target.EVENT_PUBLISH), any())).thenAnswer(call -> {
            sender.requestStop();
            return new Response(200, "{}");
        });
        when(outbox.markPublished(any(), any())).thenReturn(1);

        assertThat(sender.runCycle()).isEqualTo(1);

        verify(outbox).releaseClaims(List.of(second.eventId(), third.eventId()), NOW);
        assertThat(sender.isStopping()).isTrue();
    }

    @Test
    void rearmTargetsCoreControlEventsAndExcludesTerminalErrors() {
        ControlEventRearm rearm = new ControlEventRearm(outbox, ControlEventPolicy.REARM_ON_START);
        when(outbox.rearmFailedControlEvents(any(), any(), any())).thenReturn(2);

        assertThat(rearm.rearmOnStart(NOW)).isEqualTo(2);

        verify(outbox).rearmFailedControlEvents(EnumSet.of(EventType.DEVICE_RELEASED, EventType.DELETION_REQUESTED),
                Set.of("EVENT_CONFLICT", "INVALID_ENVELOPE", "ACCOUNT_DELETED"), NOW);
        assertThat(ControlEventRearm.CORE_CONTROL_EVENT_TYPES).doesNotContain(EventType.SESSION_RECORD_CAPTURED,
                EventType.SESSION_STATE_CHANGED, EventType.DEVICE_STATUS_CHANGED);
        UUID guardian = UUID.randomUUID();
        rearm.rearmForGuardian(guardian, NOW);
        verify(outbox).rearmFailedControlEventsForGuardian(eq(guardian), any(), eq(ControlEventRearm.EXCLUDED_LAST_ERRORS),
                eq(NOW));
    }

    @Test
    void retryForeverPolicyRefusesStartupNamingOnlyTheKey() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream original = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            assertThatThrownBy(() -> new ControlEventRearm(outbox, ControlEventPolicy.RETRY_FOREVER))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("mujoong.outbox.control-event-policy");
        } finally {
            System.setErr(original);
        }
        String printed = err.toString(StandardCharsets.UTF_8);
        assertThat(printed).contains("\"event\":\"APP_ERROR\"").contains("ControlEventPolicyUnsupported")
                .contains("MUJOONG_STARTUP_REJECTED");
    }

    private static OutboxEvent row(int retryCount, String traceId) {
        UUID deviceId = UUID.randomUUID();
        UUID guardianId = UUID.randomUUID();
        String payload = "{\"deviceId\":\"" + deviceId + "\",\"guardianId\":\"" + guardianId
                + "\",\"reason\":\"REREGISTERED\",\"note\":\"payload-marker\"}";
        return new OutboxEvent(UUID.randomUUID(), "DeviceReleased", 1, NOW.minusSeconds(60), "Device", deviceId, guardianId,
                payload, "hash", "PENDING", retryCount, NOW, null, null, null, NOW.minusSeconds(60), traceId);
    }
}
