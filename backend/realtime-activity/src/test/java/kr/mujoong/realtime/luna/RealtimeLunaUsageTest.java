package kr.mujoong.realtime.luna;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.testing.TestEventLoggers;
import kr.mujoong.realtime.usage.AiUsageCollector;
import kr.mujoong.realtime.usage.AiUsageRecord;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** HTTP 응답과 업무 판정 결과를 분리하는 연결 시험. 실제 공급자를 호출하지 않는다. */
@Tag("ai-usage")
class RealtimeLunaUsageTest {
    private final MutableClock clock = new MutableClock();
    private final LunaTransport transport = mock(LunaTransport.class);
    private final CompletableFuture<LunaTransport.RawResponse> response = new CompletableFuture<>();

    private RealtimeLunaClient client(AiUsageCollector collector) {
        var json = JsonCodec.shared();
        when(transport.rejectBeforeSend(any())).thenReturn(Optional.empty());
        when(transport.send(any(), any())).thenReturn(response);
        return new RealtimeLunaClient(RealtimeLunaTestFixtures.properties(URI.create("http://localhost")),
                Duration.ofSeconds(4), LunaSchemas.loadDefault(json), new LunaInputSanitizer(json), transport,
                TestEventLoggers.strict(clock, LogCapture.detached(), RealtimeMetrics.inMemory()), clock, json, collector);
    }

    private LunaRequest request() {
        return new LunaRequest(LunaPurpose.JUDGMENT_LIVE, "synthetic", "합성 판정",
                RealtimeLunaTestFixtures.judgmentInput("합성 발화"), Map.of(), clock.now().plusSeconds(10));
    }

    @Test void anIncompleteBusinessReplyStillRecordsItsActualUsageWithoutAnExtraCall() throws Exception {
        var captured = new CompletableFuture<AiUsageRecord>();
        UUID turn = UUID.randomUUID();
        var result = client(r -> { captured.complete(r); return true; }).call(request(), turn).toCompletableFuture();
        response.complete(new LunaTransport.RawResponse(200, """
                {"id":"synthetic-response","model":"synthetic-model","status":"incomplete",
                 "usage":{"input_tokens":123,"output_tokens":45,
                   "input_tokens_details":{"cached_tokens":20},"output_tokens_details":{"reasoning_tokens":10}}}
                """, Optional.empty()));
        assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(LunaException.class);
        AiUsageRecord record = captured.get(2, TimeUnit.SECONDS);
        assertThat(record.turnId()).isEqualTo(turn);
        assertThat(record.inputTokens()).isEqualTo(123L);
        assertThat(record.outputTokens()).isEqualTo(45L);
        assertThat(record.reasoningTokens()).isEqualTo(10L);
        assertThat(record.status()).isEqualTo(AiUsageRecord.Status.COMPLETE);
        verify(transport, times(1)).send(any(), any());
    }

    @Test void cancellationRecordsUnknownRatherThanInventingZeroTokens() throws Exception {
        var captured = new CompletableFuture<AiUsageRecord>();
        var result = client(r -> { captured.complete(r); return true; }).call(request(), UUID.randomUUID()).toCompletableFuture();
        assertThat(result.cancel(true)).isTrue();
        assertThat(response.isCancelled()).isTrue();
        AiUsageRecord record = captured.get(2, TimeUnit.SECONDS);
        assertThat(record.status()).isEqualTo(AiUsageRecord.Status.UNKNOWN);
        assertThat(record.inputTokens()).isNull();
        assertThat(record.outputTokens()).isNull();
        verify(transport, times(1)).send(any(), any());
    }

    @Test void collectorFailureDoesNotReplaceTheOriginalBusinessFailure() {
        var result = client(r -> { throw new IllegalStateException("synthetic collector failure"); })
                .call(request(), UUID.randomUUID()).toCompletableFuture();
        response.complete(new LunaTransport.RawResponse(200, "{\"status\":\"incomplete\",\"model\":\"synthetic\"}", Optional.empty()));
        assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .satisfies(failure -> assertThat(((LunaException) failure.getCause()).reason()).isEqualTo(LunaFailureReason.LENGTH_EXCEEDED));
    }
}
