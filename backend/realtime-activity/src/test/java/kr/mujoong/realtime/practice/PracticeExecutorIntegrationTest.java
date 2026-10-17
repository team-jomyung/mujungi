package kr.mujoong.realtime.practice;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.EndRequestReason;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.VoiceSessionClosed;
import kr.mujoong.realtime.session.PracticeSessionExtension;
import kr.mujoong.realtime.session.SessionMailbox;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.PracticeSpeech;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import kr.mujoong.realtime.voice.ProviderRequestFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u5b")
class PracticeExecutorIntegrationTest {
    final SessionHarness h = new SessionHarness();
    final Controlled extension = new Controlled();
    final List<Object> records = new ArrayList<>();
    PracticeExecutorIntegrationTest() {
        h.activityType = ActivityType.PRACTICE; h.practice = ignored -> extension;
        h.settlement = (session, record, at) -> records.add(record);
    }
    class Controlled implements PracticeSessionExtension {
        PracticeOutput out;
        final List<PlaybackFact> facts = new ArrayList<>();
        final List<Utterance> utterances = new ArrayList<>();
        final PracticeSpeech speech = new PracticeSpeech(UUID.randomUUID(), UUID.randomUUID(), 1,
                PracticePorts.OutputKind.SCENARIO, "친구", "내 차례야.");
        Instant deadline;
        boolean stopped;
        boolean failRecord;
        @Override public boolean controlsOutput() { return true; }
        @Override public Signal onActive(PracticeContext context, PracticeOutput output) { out = output; output.speak(speech); return Signal.NONE; }
        @Override public Signal onChildUtterance(Utterance utterance, PracticeOutput output) { utterances.add(utterance); return Signal.NONE; }
        @Override public Signal onExtensionEvent(ExtensionEvent event, PracticeOutput output) {
            if (event.name().equals("test-processing-failure")) throw new IllegalStateException("합성 처리 실패");
            if (event.value() instanceof PlaybackFact fact) facts.add(fact);
            return Signal.NONE;
        }
        @Override public Progress progress() { return new Progress(1, 2, "차례", "SCENARIO_INTRO"); }
        @Override public void onStopping() { stopped = true; }
        @Override public Duration settlementDelay(Instant now) { return deadline != null && deadline.isAfter(now) ? Duration.between(now, deadline) : Duration.ZERO; }
        @Override public void onSettle(EndContext end) { if (failRecord) throw new IllegalStateException("시험 정산 실패"); }
        @Override public Object practiceRecord() { return Map.of("goals", List.of()); }
    }
    void audio(String text) {
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta(text));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[4800]));
        h.drain();
        h.advance(Duration.ofMillis(800));
    }
    void mark(UUID id, PlaybackMark.Status status) {
        h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                new PlaybackMark(id, status, null, h.clock.now(), null, null), h.clock.now())); h.drain();
    }
    @Test void controlledPracticeRequestsOneTypedResponseAndPublishesOnlyFourProgressFields() {
        h.activate(); h.launcher.runAll("provider-"); h.drain();
        assertThat(h.provider.last().requests().stream().filter(ProviderRequestFactory.ResponseCreate.class::isInstance)).hasSize(1);
        assertThat(h.executor.liveState().practice()).isEqualTo(new PracticeSessionExtension.Progress(1, 2, "차례", "SCENARIO_INTRO"));
        var requests = h.provider.last().requests();
        assertThat(requests.stream().filter(ProviderRequestFactory.InstructionsAppend.class::isInstance)).hasSize(1);
    }
    @Test void onlyMatchingActualPlaybackMarksAreDeliveredAndDuplicatesAreIgnored() {
        h.activate(); audio("내 차례야.");
        assertThat(extension.facts).isEmpty(); assertThat(h.link.outputs()).hasSize(1);
        UUID id = h.link.outputs().getFirst().playbackId();
        mark(UUID.randomUUID(), PlaybackMark.Status.COMPLETED); assertThat(extension.facts).isEmpty();
        mark(id, PlaybackMark.Status.STARTED); mark(id, PlaybackMark.Status.COMPLETED); mark(id, PlaybackMark.Status.COMPLETED);
        assertThat(extension.facts).extracting(PracticeSessionExtension.PlaybackFact::status)
                .containsExactly(PracticePorts.Playback.STARTED, PracticePorts.Playback.COMPLETED);
        assertThat(extension.facts).allMatch(f -> f.speech().correlationId().equals(extension.speech.correlationId()));
    }
    @Test void addedAdviceIsDiscardedAndCannotBecomeScenarioExposure() {
        h.activate(); audio("내 차례야. 이렇게 말해 봐.");
        assertThat(h.link.outputs()).isEmpty();
        assertThat(extension.facts).extracting(PracticeSessionExtension.PlaybackFact::status).containsExactly(PracticePorts.Playback.FAILED);
    }
    @Test void failedDevicePlaybackRemainsFailureAndMissingCompletionTimesOut() {
        h.activate(); audio("내 차례야.");
        mark(h.link.outputs().getFirst().playbackId(), PlaybackMark.Status.FAILED);
        assertThat(extension.facts).extracting(PracticeSessionExtension.PlaybackFact::status).containsExactly(PracticePorts.Playback.FAILED);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
    }
    @Test void terminalSnapshotWaitsForOriginalEvaluationDeadlineAndDoesNotEmitNullOnFailure() {
        h.activate(); extension.deadline = h.clock.now().plusSeconds(7); extension.failRecord = true;
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP,
                new java.util.concurrent.CompletableFuture<>())); h.drain();
        assertThat(extension.stopped).isTrue();
        h.mailbox.offerGeneral(new SessionMailbox.VoiceClosed(h.link.connectionId(),
                new VoiceSessionClosed(h.clock.now(), h.clock.now()), h.clock.now())); h.drain();
        h.advance(Duration.ofSeconds(2)); assertThat(records).isEmpty();
        h.advance(Duration.ofSeconds(5)); assertThat(records).isEmpty();
    }
    @Test void stopWordsAreNotAcceptedAsAPracticeResponse() {
        h.activate(); h.childSays("stop", "무중아 그만할래");
        assertThat(extension.utterances).isEmpty();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.CLOSING);
    }
    @Test void processingFailureIsNotReportedAsAGuardianStopAndStillWaitsForAcceptedEvaluation() {
        h.activate(); extension.deadline = h.clock.now().plusSeconds(7);
        extension.out.post("test-processing-failure", null); h.drain();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.FAILED);
        assertThat(extension.stopped).isTrue();
        h.advanceAlive(Duration.ofSeconds(6)); assertThat(records).isEmpty();
        h.advanceAlive(Duration.ofSeconds(1)); assertThat(records).hasSize(1);
    }
}
