package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.realtime.practicelegacy.PracticePorts;
import kr.mujoong.realtime.testing.FakeGuardrailClient;
import kr.mujoong.realtime.testing.RealtimeItEnvironment;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import kr.mujoong.realtime.voice.ProviderRequestFactory;
import kr.mujoong.testsupport.log.LogCapture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 실제 실행기와 가짜 공급자 · 제브 · 기기로 수집 시한과 재생 종료의 경계를 확인한다. 유료 호출 없음. */
@Tag("practice-flow")
@Tag("service-guardrail")
class PracticeCollectionCompletionTest {
    private final SessionHarness h = new SessionHarness();
    private final Recording extension = new Recording();
    private FakeGuardrailClient guard;
    private UUID slot;
    private long margin;

    @Test
    void aPlayedPrefixCompletesOnceAtTheCollectionDeadlineWithoutReplayingIt() {
        startPlayedPrefix();
        int requests = requests();
        trickle(155);
        assertThat(extension.ends()).containsExactly(PracticePorts.Playback.COMPLETED);
        assertThat(extension.incomplete).isEmpty();
        assertThat(requests()).isEqualTo(requests);
        assertThat(h.link.outputs()).hasSize(1);
        assertThat(h.logs.events(EventCode.APP_ERROR).stream().map(LogCapture::attrs))
                .anySatisfy(attrs -> assertThat(attrs).containsEntry("errorKind",
                        SessionExecutor.REASON_COLLECTION_TIMEOUT_AFTER_PLAYBACK));
        mark(h.link.outputs().getFirst().playbackId(), PlaybackMark.Status.COMPLETED);
        h.advanceAlive(Duration.ofSeconds(1));
        assertThat(extension.ends()).containsExactly(PracticePorts.Playback.COMPLETED);
    }

    @Test
    void collectionExpiryWaitsForTheRemainingPlaybackAndIgnoresDuplicateCompletion() {
        startPlayedPrefix();
        trickle(146);
        secondUnit();
        guard.allow(); h.drain();
        UUID playback = h.link.outputs().getLast().playbackId();
        mark(playback, PlaybackMark.Status.STARTED);
        trickle(6);
        assertThat(extension.ends()).isEmpty();
        mark(playback, PlaybackMark.Status.COMPLETED);
        mark(playback, PlaybackMark.Status.COMPLETED);
        assertThat(extension.ends()).containsExactly(PracticePorts.Playback.COMPLETED);
        assertThat(h.link.outputs()).hasSize(2);
    }

    @Test
    void collectionExpiryWaitsForASafetyCheckAndThenItsAllowedPlayback() {
        startPlayedPrefix();
        trickle(146);
        secondUnit();
        trickle(6);
        assertThat(extension.ends()).isEmpty();
        assertThat(h.link.outputs()).hasSize(1);
        guard.allow(); h.drain();
        assertThat(h.link.outputs()).hasSize(2);
        assertThat(extension.ends()).isEmpty();
        mark(h.link.outputs().getLast().playbackId(), PlaybackMark.Status.COMPLETED);
        assertThat(extension.ends()).containsExactly(PracticePorts.Playback.COMPLETED);
    }

    @Test
    void aSafetyBlockAfterCollectionExpiryCannotBecomeSuccessfulCompletion() {
        startPlayedPrefix();
        trickle(146);
        secondUnit();
        trickle(6);
        guard.block(); h.drain(); h.settleSend();
        h.advanceAlive(Duration.ofSeconds(11));
        h.advanceAlive(Duration.ofSeconds(11));
        assertThat(extension.ends()).doesNotContain(PracticePorts.Playback.COMPLETED);
        assertThat(h.link.outputs()).hasSize(1);
        assertThat(h.logs.events(EventCode.OUTPUT_DISCARDED).stream().map(LogCapture::attrs))
                .anySatisfy(attrs -> assertThat(attrs).containsEntry("discardDetail", "GUARDRAIL_BLOCK"));
    }

    @Test
    void collectionExpiryBeforeAnyAudioIsSentStillFails() {
        start();
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("합성 안내입니다", 0, margin + 100_000));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[(int) margin * 48]));
        h.drain();
        trickle(155);
        assertThat(extension.ends()).containsExactly(PracticePorts.Playback.FAILED);
        assertThat(h.link.outputs()).isEmpty();
        assertThat(guard.count()).isZero();
    }

    private void start() {
        guard = h.useGuardrail();
        h.activityType = ActivityType.PRACTICE;
        h.practice = ignored -> extension;
        h.activate();
        slot = UUID.randomUUID();
        extension.out.speakGenerated(slot, "COACH", "다음 대화에서 쓸 힌트를 알려 주세요");
        h.settleSend();
        h.provider.last().newSpeechMargin(null);
        var request = h.provider.last().requests(ProviderRequestFactory.InstructionsAppend.class).getLast();
        h.provider.last().emit(new ProviderEvent.InstructionsAppended(request.eventId(), 0, 0));
        margin = h.settings.newSpeechMargin().toMillis();
    }

    private void startPlayedPrefix() {
        start();
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("합성 안내입니다", 0, margin + 100));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[(int) margin * 48]));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(RealtimeItEnvironment.audiblePcm(4800)));
        h.drain(); guard.allow(); h.drain();
        UUID playback = h.link.outputs().getFirst().playbackId();
        mark(playback, PlaybackMark.Status.STARTED);
        mark(playback, PlaybackMark.Status.COMPLETED);
        assertThat(extension.ends()).isEmpty();
    }

    private void secondUnit() {
        long start = margin + 100 + 146 * 10;
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("다음 안내입니다", start, start + 100));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(RealtimeItEnvironment.audiblePcm(4800)));
        h.drain();
        assertThat(guard.count()).isEqualTo(2);
    }

    private void trickle(int count) {
        for (int i = 0; i < count; i++) {
            // 완전한 디지털 무음은 발화 시계를 늘리지 않는다. 실제 어댑터도 음성으로 전달할 작은 비영 조각을 쓴다.
            byte[] audio = new byte[480]; audio[0] = 1;
            h.provider.last().emit(new ProviderEvent.OutputAudioDelta(audio)); h.drain();
            h.advanceAlive(Duration.ofMillis(200));
        }
    }

    private int requests() {
        return h.provider.last().requests(ProviderRequestFactory.InstructionsAppend.class).size();
    }

    private void mark(UUID id, PlaybackMark.Status status) {
        h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                new PlaybackMark(id, status, null, h.clock.now(), null, null), h.clock.now()));
        h.drain();
    }

    private class Recording implements PracticeSessionExtension {
        PracticeOutput out;
        final List<PlaybackFact> facts = new ArrayList<>();
        final List<UUID> incomplete = new ArrayList<>();
        List<PracticePorts.Playback> ends() {
            return facts.stream().filter(fact -> !fact.started())
                    .filter(fact -> fact.speech().correlationId().equals(slot)).map(PlaybackFact::status).toList();
        }
        @Override public boolean controlsOutput() { return true; }
        @Override public boolean handlesAudioRecovery() { return true; }
        @Override public Signal onActive(PracticeContext context, PracticeOutput output) {
            out = output; return Signal.NONE;
        }
        @Override public Signal onChildUtterance(Utterance utterance, PracticeOutput output) { return Signal.NONE; }
        @Override public Signal onExtensionEvent(ExtensionEvent event, PracticeOutput output) {
            if ("practice-audio-incomplete".equals(event.name())) incomplete.add(event.correlationId());
            if (event.value() instanceof PlaybackFact fact) facts.add(fact);
            return Signal.NONE;
        }
        @Override public void onSettle(EndContext end) { }
        @Override public Object practiceRecord() { return null; }
    }
}
