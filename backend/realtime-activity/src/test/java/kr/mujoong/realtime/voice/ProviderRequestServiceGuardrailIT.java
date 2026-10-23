package kr.mujoong.realtime.voice;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.realtime.dialog.InstructionTemplates;
import kr.mujoong.realtime.session.ChildContext;
import kr.mujoong.testsupport.provider.FakeProviderWsServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("service-guardrail")
class ProviderRequestServiceGuardrailIT {
    @Test
    void productionDecodedNullIdentityTimelineCannotReacceptPreNoticeSpeech() throws Exception {
        var h = kr.mujoong.realtime.session.ServiceGuardrailFixtures.harness();
        h.rawAudioConsent = true;
        h.boot(); h.ackAndReady();
        var inputs = new java.util.concurrent.LinkedBlockingQueue<ProviderPort.ProviderEvent.InputTranscriptDelta>();
        try (var server = FakeProviderWsServer.start()) {
            var client = new ProviderClient(server.uri(), null, JsonCodec.shared(), Duration.ofSeconds(2));
            try (var session = client.open(event -> {
                if (event instanceof ProviderPort.ProviderEvent.InputTranscriptDelta delta) inputs.add(delta);
            }, Duration.ofSeconds(2))) {
                var factory = new ProviderRequestFactory();
                session.start(factory.sessionStart(new ChildContext("별", 8, List.of()), factory.servicePolicy(ActivityType.FREE_TALK)));
                assertThat(server.await("session.start", 1, Duration.ofSeconds(5))).hasSize(1);
                server.emitInputTranscript("응", 10, 30);
                var before = inputs.poll(5, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(before).isNotNull();
                assertThat(before.itemId()).isNull();
                assertThat(before.startMs()).isEqualTo(10);
                h.provider.last().emit(before); h.drain();
                h.advanceAlive(h.settings.inputTranscriptQuiet());
                h.completeNotice();
                server.emitInputTranscript("응", 10, 50);
                var tail = inputs.poll(5, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(tail).isNotNull();
                h.provider.last().emit(tail); h.drain(); h.advanceAlive(h.settings.inputTranscriptQuiet());
                assertThat(h.executor.status()).isEqualTo(kr.mujoong.contract.status.SessionStatus.WAITING_CHILD);
                assertThat(h.recordings.opened()).isEmpty();
                server.emitInputTranscript("응", 60, 80);
                var fresh = inputs.poll(5, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(fresh).isNotNull();
                h.provider.last().emit(fresh); h.drain(); h.advanceAlive(h.settings.inputTranscriptQuiet());
                assertThat(h.executor.status()).isEqualTo(kr.mujoong.contract.status.SessionStatus.ACTIVE);
                assertThat(h.recordings.opened()).hasSize(1);
            } finally { client.destroy(); }
        }
    }

    @Test
    void productionClientSendsPinnedPolicyAndScopedInstructionToLocalWire() throws Exception {
        try (var server = FakeProviderWsServer.start()) {
            var client = new ProviderClient(server.uri(), null, JsonCodec.shared(), Duration.ofSeconds(2));
            try (var session = client.open(event -> { }, Duration.ofSeconds(2))) {
                var factory = new ProviderRequestFactory();
                var policy = factory.servicePolicy(ActivityType.FREE_TALK);
                session.start(factory.sessionStart(new ChildContext("별", 8, List.of("블록")), policy));
                session.appendInstructions(factory.responseStart("synthetic-event", InstructionTemplates.ResponseStart.FIRST,
                        ActivityType.FREE_TALK));
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (server.received().size() < 2 && System.nanoTime() < deadline) Thread.sleep(10);
                assertThat(server.received()).hasSize(2);
                assertThat(server.received().getFirst().path("session").path("instructions").asString())
                        .startsWith(policy.instructions());
                assertThat(server.received().get(1).path("delegation_id").isNull()).isTrue();
                assertThat(server.received().get(1).path("content").asString()).contains("참여 수락");
                assertThat(server.received().get(1).path("content").asString())
                        .contains("녹음의 실제 재생이 완료", "시작 인사·자기소개", "반복하지 않는다");
            } finally {
                client.destroy();
            }
        }
    }
}
