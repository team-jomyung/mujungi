package kr.mujoong.realtime.voice;

import static kr.mujoong.realtime.testing.RealtimeItEnvironment.WAIT;
import static kr.mujoong.realtime.testing.RealtimeItEnvironment.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.EndRequestReason;
import kr.mujoong.contract.status.FailureCode;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.WsEnvelope;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.message.CloseTransport;
import kr.mujoong.realtime.runtime.TaskLauncher;
import kr.mujoong.realtime.session.ActivitySessionRepository;
import kr.mujoong.realtime.session.ChildContext;
import kr.mujoong.realtime.session.EndRequestHandler;
import kr.mujoong.realtime.session.SessionMailbox;
import kr.mujoong.realtime.testing.RealtimeItEnvironment;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import kr.mujoong.realtime.voice.ProviderPort.ProviderUnavailableException;
import kr.mujoong.testsupport.device.DeviceSimulatorClient;
import kr.mujoong.testsupport.provider.FakeProviderWsServer;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 공급자 연결 통합 시험 (U1 3.5 Step 30 · RD-5 · NFR4.2 · J110 · BR7.2 · PD-1 생명주기 2 (다)).
 *
 * <p>실제 JDK WebSocket 클라이언트와 가짜 공급자 WS 서버로 선 형식(J110)을 주고받고, 거부 · 연결 단계 무응답이 PROVIDER_UNAVAILABLE 한
 * 번으로 끝나는지, 연결이 걸려 있는 동안에도 Core 취소가 2초 안에 처리되는지 본다.
 */
@Tag("u1")
@Tag("db")
class ProviderClientIT {

    private static final JsonCodec JSON = JsonCodec.shared();

    @Test
    void theWireFormatRoundTripsEveryEventTypeAndSendsTheKeyOnlyAsABearerHeader() throws Exception {
        try (FakeProviderWsServer server = FakeProviderWsServer.start()) {
            ProviderClient client = new ProviderClient(server.uri(), TestSecrets.base64Url(24), JSON, Duration.ofSeconds(2));
            List<ProviderEvent> events = new CopyOnWriteArrayList<>();
            ProviderPort.ProviderSession session = client.open(events::add, Duration.ofSeconds(2));
            ProviderRequestFactory factory = new ProviderRequestFactory();

            session.start(factory.sessionStart(new ChildContext("하늘", 8, List.of("공룡"))));
            awaitTrue(() -> events.stream().anyMatch(ProviderEvent.Started.class::isInstance), "session.started");
            byte[] pcm = new byte[960];
            pcm[10] = 42;
            session.sendInputAudio(factory.inputAudio(pcm));
            server.await("session.input_audio.append", 1, WAIT);
            byte[] out = {1, 2, 3, 4};
            server.emitTurn("item-1", "안녕", "반가워", List.of(out));
            server.emitUsage(3.0);
            server.emitError("invalid_request_error", "invalid_value", "type");
            server.emit(new FakeProviderWsServer.Event("unknown.type", null, null, null));
            awaitTrue(() -> events.stream().anyMatch(ProviderEvent.Error.class::isInstance), "error");

            // 응답 끝 · 말 끝 · 입력 전사 완료 사건이 없다(실호출 미관측) — 음성은 delta로 오고 모르는 type은 이벤트가 되지 않는다
            assertThat(events).extracting(event -> event.getClass().getSimpleName()).containsExactly("Started",
                    "InputTranscriptDelta", "OutputTranscriptDelta", "OutputAudioDelta", "UsageUpdated", "Error");
            ProviderEvent.OutputAudioDelta audio = (ProviderEvent.OutputAudioDelta) events.get(3);
            assertThat(audio.audio()).isEqualTo(out);
            assertThat(((ProviderEvent.Error) events.get(5)).code()).isEqualTo("invalid_value");
            assertThat(((ProviderEvent.InputTranscriptDelta) events.get(1)).delta()).isEqualTo("안녕");
            assertThat(((ProviderEvent.InputTranscriptDelta) events.get(1)).startMs()).isGreaterThanOrEqualTo(0L);
            assertThat(((ProviderEvent.OutputTranscriptDelta) events.get(2)).endMs())
                    .isGreaterThanOrEqualTo(((ProviderEvent.OutputTranscriptDelta) events.get(2)).startMs());
            assertThat(server.bearerHeaders()).containsExactly(true);
            assertThat(server.audioHashes()).hasSize(1);
            assertThat(Base64.getDecoder().decode(server.received("session.input_audio.append").get(0)
                    .get("audio").asString())).isEqualTo(pcm);

            session.close(factory.sessionClose());
            server.await("session.close", 1, WAIT);
            assertThat(session.isOpen()).isFalse();
            awaitTrue(() -> events.stream().anyMatch(ProviderEvent.Closed.class::isInstance), "closed");
            client.destroy();
        }
    }

    @Test
    void aRefusedOrSilentProviderFailsTheOpenWithoutHanging() throws Exception {
        ProviderClient refused = new ProviderClient(FakeProviderWsServer.refusingUri(), TestSecrets.base64Url(24), JSON,
                Duration.ofSeconds(1));
        assertThatThrownBy(() -> refused.open(event -> { }, Duration.ofSeconds(1)))
                .isInstanceOfSatisfying(ProviderUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(ProviderUnavailableException.Reason.CONNECT_FAILED));
        ProviderClient noKey = new ProviderClient(FakeProviderWsServer.refusingUri(), " ", JSON, Duration.ofSeconds(1));
        assertThatThrownBy(() -> noKey.open(event -> { }, Duration.ofSeconds(1)))
                .isInstanceOf(ProviderUnavailableException.class);

        try (FakeProviderWsServer.Silent silent = FakeProviderWsServer.silent()) {
            ProviderClient client = new ProviderClient(silent.uri(), TestSecrets.base64Url(24), JSON, Duration.ofSeconds(1));
            long started = System.nanoTime();
            assertThatThrownBy(() -> client.open(event -> { }, Duration.ofMillis(500)))
                    .isInstanceOf(ProviderUnavailableException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
            client.destroy();
        }
        refused.destroy();
        noKey.destroy();
    }

    @Test
    void theConnectorReportsOneFailureAndACancelledConnectEndsWithoutCallbacks() throws Exception {
        try (FakeProviderWsServer.Silent silent = FakeProviderWsServer.silent()) {
            ProviderClient client = new ProviderClient(silent.uri(), TestSecrets.base64Url(24), JSON, Duration.ofSeconds(1));
            ProviderConnector connector = new ProviderConnector(client, new ProviderRequestFactory(), Duration.ofMillis(500),
                    TaskLauncher.virtualThreads(), "test");
            List<ProviderUnavailableException.Reason> failures = new CopyOnWriteArrayList<>();
            AtomicInteger connected = new AtomicInteger();
            ProviderConnector.Callback callback = new ProviderConnector.Callback() {
                @Override
                public void connected(ProviderPort.ProviderSession session) {
                    connected.incrementAndGet();
                }

                @Override
                public void failed(ProviderUnavailableException.Reason reason) {
                    failures.add(reason);
                }
            };

            ProviderConnector.Attempt attempt = connector.connect(UUID.randomUUID(),
                    new ChildContext("하늘", 8, List.of()), event -> { }, callback);
            assertThat(attempt.await(Duration.ofSeconds(3))).isTrue();
            assertThat(failures).hasSize(1);

            List<ProviderUnavailableException.Reason> afterCancel = new ArrayList<>(failures);
            ProviderConnector.Attempt cancelled = connector.connect(UUID.randomUUID(),
                    new ChildContext("하늘", 8, List.of()), event -> { }, callback);
            long started = System.nanoTime();
            cancelled.cancel();
            assertThat(cancelled.await(Duration.ofSeconds(2))).isTrue();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
            assertThat(cancelled.isCancelled()).isTrue();
            assertThat(failures).isEqualTo(afterCancel);
            assertThat(connected).hasValue(0);
            client.destroy();
        }
    }

    @Test
    void aRefusingProviderFailsTheSessionOnceWithProviderUnavailable() {
        try (RealtimeItEnvironment env = RealtimeItEnvironment.start(Map.of(), FakeProviderWsServer.refusingUri())) {
            DeviceSimulatorClient device = env.connectDevice();
            UUID sessionId = UUID.randomUUID();
            env.start(sessionId);
            WsEnvelope start = device.await(WsMessageType.START_SESSION, WAIT);
            device.ack(start.opId(), sessionId);
            device.deviceReady(sessionId, true, true);

            awaitTrue(() -> status(env, sessionId) == SessionStatus.FAILED, "FAILED");
            assertThat(env.app.bean(ActivitySessionRepository.class).find(sessionId).orElseThrow().failureCode())
                    .isEqualTo(FailureCode.PROVIDER_UNAVAILABLE);
            WsEnvelope close = device.await(WsMessageType.CLOSE_TRANSPORT, WAIT);
            assertThat(((CloseTransport) close.payload()).reason()).isEqualTo(CloseTransport.Reason.FAILED);
            RealtimeItEnvironment.sleep(300);
            assertThat(device.received(WsMessageType.CLOSE_TRANSPORT)).hasSize(1);
        }
    }

    @Test
    void aCoreCancelIsHandledWithinTwoSecondsWhileTheProviderConnectHangs() {
        try (FakeProviderWsServer.Silent silent = FakeProviderWsServer.silent();
                RealtimeItEnvironment env = RealtimeItEnvironment.start(
                        Map.of("mujoong.provider.connect-timeout", "2s"), silent.uri())) {
            DeviceSimulatorClient device = env.connectDevice();
            UUID sessionId = UUID.randomUUID();
            env.start(sessionId);
            WsEnvelope start = device.await(WsMessageType.START_SESSION, WAIT);
            device.ack(start.opId(), sessionId);
            awaitTrue(() -> silent.acceptedCount() > 0, "공급자 연결 시도");

            long begin = System.nanoTime();
            SessionMailbox.EndOutcome outcome = env.app.bean(EndRequestHandler.class).end(sessionId, sessionId,
                    EndRequestReason.CORE_ABANDONED, null);
            Duration took = Duration.ofNanos(System.nanoTime() - begin);

            assertThat(outcome).isEqualTo(SessionMailbox.EndOutcome.ACCEPTED_EMPTY);
            assertThat(took).isLessThan(Duration.ofSeconds(2));
            assertThat(status(env, sessionId)).isEqualTo(SessionStatus.FAILED);
            assertThat(env.app.bean(ActivitySessionRepository.class).find(sessionId).orElseThrow().failureCode()).isNull();
        }
    }

    private static SessionStatus status(RealtimeItEnvironment env, UUID sessionId) {
        return env.app.bean(ActivitySessionRepository.class).find(sessionId).map(row -> row.status()).orElse(null);
    }
}
