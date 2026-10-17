package kr.mujoong.realtime.voice;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.session.ChildContext;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import kr.mujoong.testsupport.provider.FakeProviderWsServer;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 가짜 공급자 WS 서버가 실호출 관측 형식(응답 id · 응답 끝 사건 없음, {@code delta} 음성, 지원 목록 밖 type 오류)을 내는지 운영 클라이언트로
 * 확인한다 (U11 FR12.3 · BR11.10). 실제 공급자를 부르지 않는다(R-F1).
 */
@Tag("u11")
class FakeProviderWireIT {

    private static final JsonCodec JSON = JsonCodec.shared();
    private static final Duration WAIT = Duration.ofSeconds(5);

    private static void awaitTrue(java.util.function.BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("기다림 만료: " + what);
            }
            Thread.sleep(10);
        }
    }

    @Test
    void theNewFormatRoundTripsThroughTheProductionClientWithoutAnyResponseIdOrDoneEvent() throws Exception {
        try (FakeProviderWsServer server = FakeProviderWsServer.start()) {
            ProviderClient client = new ProviderClient(server.uri(), null, JSON, Duration.ofSeconds(2));
            List<ProviderEvent> events = new CopyOnWriteArrayList<>();
            ProviderPort.ProviderSession session = client.open(events::add, Duration.ofSeconds(2));
            ProviderRequestFactory factory = new ProviderRequestFactory();

            session.start(factory.sessionStart(new ChildContext("하늘", 8, List.of())));
            awaitTrue(() -> events.stream().anyMatch(ProviderEvent.Started.class::isInstance), "started");
            server.emitTurn("item-1", "안녕", "반가워", List.of(new byte[]{1, 2}, new byte[]{3, 4}));
            awaitTrue(() -> events.stream().filter(ProviderEvent.OutputAudioDelta.class::isInstance).count() == 2, "audio");

            assertThat(events).extracting(event -> event.getClass().getSimpleName()).containsExactly("Started",
                    "InputTranscriptDelta", "OutputTranscriptDelta", "OutputAudioDelta", "OutputAudioDelta");
            assertThat(((ProviderEvent.Started) events.get(0)).providerSessionId()).isEqualTo("fake-provider-session");
            session.close(factory.sessionClose());
            client.destroy();
        }
    }

    @Test
    void aTypeOutsideTheSupportedListGetsTheObservedInvalidValueError() throws Exception {
        try (FakeProviderWsServer server = FakeProviderWsServer.start()) {
            ProviderClient client = new ProviderClient(server.uri(), null, JSON, Duration.ofSeconds(2));
            List<ProviderEvent> events = new CopyOnWriteArrayList<>();
            ProviderPort.ProviderSession session = client.open(events::add, Duration.ofSeconds(2));

            session.start(new ProviderRequestFactory().sessionStart(new ChildContext("하늘", 8, List.of())));
            awaitTrue(() -> events.stream().anyMatch(ProviderEvent.Started.class::isInstance), "started");
            // 운영 팩토리는 이 type을 만들지 못한다 — 가짜 서버 규칙만 보려고 원시로 보낸다
            session.sendInputAudio(new ProviderRequestFactory.InputAudioAppend("session.response.cancel", "AAAA"));

            awaitTrue(() -> events.stream().anyMatch(ProviderEvent.Error.class::isInstance), "error");
            assertThat(events.stream().filter(ProviderEvent.Error.class::isInstance).findFirst().orElseThrow())
                    .isEqualTo(new ProviderEvent.Error("invalid_value"));
            session.close();
            client.destroy();
        }
    }

    @Test
    void everySupportedTypeIsAcceptedAndTheOperationalFourAreSupported() throws Exception {
        assertThat(FakeProviderWsServer.SUPPORTED_TYPES).containsAll(ProviderRequestFactory.SENT_TYPES).hasSize(15);
        assertThat(ProviderRequestFactory.SENT_TYPES).hasSize(4).doesNotContain("response.create");
        try (FakeProviderWsServer server = FakeProviderWsServer.start()) {
            ProviderClient client = new ProviderClient(server.uri(), TestSecrets.base64Url(24), JSON, Duration.ofSeconds(2));
            List<ProviderEvent> events = new CopyOnWriteArrayList<>();
            ProviderPort.ProviderSession session = client.open(events::add, Duration.ofSeconds(2));
            ProviderRequestFactory factory = new ProviderRequestFactory();
            session.start(factory.sessionStart(new ChildContext("하늘", 8, List.of())));
            awaitTrue(() -> events.stream().anyMatch(ProviderEvent.Started.class::isInstance), "started");

            session.appendInstructions(factory.instructionsAppend("mj-1", kr.mujoong.realtime.dialog.DialogInstructions.Correction.PRESSURE));
            awaitTrue(() -> events.stream().anyMatch(ProviderEvent.InstructionsAppended.class::isInstance), "지시 확인 사건");

            assertThat(events).noneMatch(ProviderEvent.Error.class::isInstance);
            assertThat(server.received().stream().map(node -> node.get("type").asString()).toList())
                    .containsExactly("session.start", "session.instructions.append");
            session.close();
            client.destroy();
        }
    }

    @Test
    void aStrictProviderRefusesAnAppendWithoutDelegationIdAndAnyResponseCreate() throws Exception {
        try (FakeProviderWsServer server = FakeProviderWsServer.start()) {
            ProviderClient client = new ProviderClient(server.uri(), TestSecrets.base64Url(24), JSON, Duration.ofSeconds(2));
            List<ProviderEvent> events = new CopyOnWriteArrayList<>();
            ProviderPort.ProviderSession session = client.open(events::add, Duration.ofSeconds(2));
            ProviderRequestFactory factory = new ProviderRequestFactory();
            session.start(factory.sessionStart(new ChildContext("하늘", 8, List.of())));
            awaitTrue(() -> events.stream().anyMatch(ProviderEvent.Started.class::isInstance), "started");

            // 운영 팩토리는 이 모양을 만들지 못한다 — 가짜 서버가 실제처럼 엄격한지 보려고 type만 바꾼 입력 프레임 레코드로 보낸다(delegation_id 없음)
            session.sendInputAudio(new ProviderRequestFactory.InputAudioAppend("session.instructions.append", "AAAA"));
            session.sendInputAudio(new ProviderRequestFactory.InputAudioAppend("response.create", "AAAA"));

            awaitTrue(() -> events.stream().filter(ProviderEvent.Error.class::isInstance).count() == 2, "오류 두 건");
            assertThat(events.stream().filter(ProviderEvent.Error.class::isInstance).toList())
                    .containsExactly(new ProviderEvent.Error("missing_required_parameter"), new ProviderEvent.Error("invalid_request"));
            assertThat(events).noneMatch(ProviderEvent.InstructionsAppended.class::isInstance);
            session.close();
            client.destroy();
        }
    }

    @Test
    void errorAndUsageEventsHaveTheObservedShapeAndTheResponseBoundaryIsJustSilenceAfterTheLastChunk() throws Exception {
        try (FakeProviderWsServer server = FakeProviderWsServer.start()) {
            ProviderClient client = new ProviderClient(server.uri(), null, JSON, Duration.ofSeconds(2));
            List<ProviderEvent> events = new CopyOnWriteArrayList<>();
            ProviderPort.ProviderSession session = client.open(events::add, Duration.ofSeconds(2));
            session.start(new ProviderRequestFactory().sessionStart(new ChildContext("하늘", 8, List.of())));
            awaitTrue(() -> events.stream().anyMatch(ProviderEvent.Started.class::isInstance), "started");

            server.emitUsage(7.0);
            server.emitError("invalid_request_error", "invalid_value", "type");
            server.emitAudio(new byte[]{9, 9});
            awaitTrue(() -> events.size() == 4, "events");

            assertThat(events.subList(1, 4)).containsExactly(new ProviderEvent.UsageUpdated(7.0),
                    new ProviderEvent.Error("invalid_value"), new ProviderEvent.OutputAudioDelta(new byte[]{9, 9}));
            Thread.sleep(150);
            assertThat(events).as("조각 뒤에 응답 끝 사건이 따라오지 않는다").hasSize(4);
            session.close();
            client.destroy();
        }
    }
}
