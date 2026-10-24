package kr.mujoong.realtime.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.usage.AiUsageRecord;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("ai-usage")
class ProviderUsageListenerTest {
    private final List<AiUsageRecord> samples = new ArrayList<>();
    private final List<ProviderPort.ProviderEvent> events = new ArrayList<>();
    private final ProviderUsageListener usage = new ProviderUsageListener(UUID.randomUUID(), "synthetic-model",
            events::add, record -> { samples.add(record); return true; }, new MutableClock());

    @Test void cumulativeSnapshotsAndFinalClosedUsageAreCapturedBeforeTheCloseEvent() {
        var reader = new ProviderClient.Reader(usage, JsonCodec.shared());
        WebSocket socket = mock(WebSocket.class);
        reader.onText(socket, "{\"type\":\"session.started\",\"session\":{\"id\":\"synthetic-session\"}}", true);
        reader.onText(socket, "{\"type\":\"session.usage.updated\",\"usage\":{\"seconds\":10}}", true);
        reader.onText(socket, "{\"type\":\"session.usage.updated\",\"usage\":{\"seconds\":9}}", true);
        reader.onText(socket, "{\"type\":\"session.closed\",\"usage\":{\"seconds\":12.5}}", true);
        assertThat(samples.getLast().voiceSeconds()).isEqualTo(12.5);
        assertThat(samples.getLast().status()).isEqualTo(AiUsageRecord.Status.COMPLETE);
        assertThat(samples.stream().map(AiUsageRecord::id).distinct()).hasSize(1);
        assertThat(events).hasSize(2);
        assertThat(events.getLast()).isInstanceOf(ProviderPort.ProviderEvent.Closed.class);
    }

    @Test void transportLossAfterASnapshotIsPartialAndNoUsageIsUnknown() {
        usage.onEvent(new ProviderPort.ProviderEvent.UsageUpdated(5));
        usage.onEvent(new ProviderPort.ProviderEvent.Closed());
        assertThat(samples.getLast().status()).isEqualTo(AiUsageRecord.Status.PARTIAL);
        assertThat(samples.getLast().voiceSeconds()).isEqualTo(5.0);
        List<AiUsageRecord> empty = new ArrayList<>();
        new ProviderUsageListener(UUID.randomUUID(), "synthetic-model", e -> { }, r -> { empty.add(r); return true; },
                new MutableClock()).onEvent(new ProviderPort.ProviderEvent.Closed());
        assertThat(empty.getLast().status()).isEqualTo(AiUsageRecord.Status.UNKNOWN);
        assertThat(empty.getLast().voiceSeconds()).isNull();
    }

    @Test void localCloseKeepsTheLastSnapshotWithoutWaitingForProviderFinalUsage() {
        var json = JsonCodec.shared();
        var reader = new ProviderClient.Reader(usage, json);
        WebSocket socket = mock(WebSocket.class);
        when(socket.sendText(any(CharSequence.class), eq(true))).thenReturn(CompletableFuture.completedFuture(socket));
        when(socket.sendClose(WebSocket.NORMAL_CLOSURE, "")).thenReturn(CompletableFuture.completedFuture(socket));
        reader.onText(socket, "{\"type\":\"session.usage.updated\",\"usage\":{\"seconds\":5}}", true);
        var session = new ProviderClient.Session(socket, reader, json, Duration.ofSeconds(2));

        // 송신은 완료하지만 공급자의 session.closed는 보내지 않는다. 사용량 대기를 되살리면 실패한다.
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> session.close(new ProviderRequestFactory().sessionClose()));

        verify(socket).sendClose(WebSocket.NORMAL_CLOSURE, "");
        verify(socket).abort();
        assertThat(samples.getLast().status()).isEqualTo(AiUsageRecord.Status.PARTIAL);
        assertThat(samples.getLast().voiceSeconds()).isEqualTo(5.0);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isInstanceOf(ProviderPort.ProviderEvent.Closed.class);
    }

    @Test void aBrokenCollectorDoesNotChangeAudioOrClosePropagation() {
        List<ProviderPort.ProviderEvent> forwarded = new ArrayList<>();
        var listener = new ProviderUsageListener(UUID.randomUUID(), "synthetic-model", forwarded::add,
                r -> { throw new IllegalStateException("synthetic failure"); }, new MutableClock());
        var audio = new ProviderPort.ProviderEvent.OutputAudioDelta(new byte[] {1, 2});
        listener.onEvent(audio);
        listener.onEvent(new ProviderPort.ProviderEvent.Closed());
        assertThat(forwarded.getFirst()).isSameAs(audio);
        assertThat(forwarded.getLast()).isInstanceOf(ProviderPort.ProviderEvent.Closed.class);
    }

    @Test void aLateOrRepeatedStartDoesNotSplitOneConnectionsUsageIntoMultipleRows() {
        usage.onEvent(new ProviderPort.ProviderEvent.UsageUpdated(3));
        usage.onEvent(new ProviderPort.ProviderEvent.Started("synthetic-session"));
        usage.onEvent(new ProviderPort.ProviderEvent.Started("synthetic-session"));
        usage.finalUsage(5.0);
        usage.onEvent(new ProviderPort.ProviderEvent.Closed());
        assertThat(samples.stream().map(AiUsageRecord::id).distinct()).hasSize(1);
        assertThat(samples.getLast().providerSessionId()).isEqualTo("synthetic-session");
        assertThat(samples.getLast().voiceSeconds()).isEqualTo(5.0);
    }
}
