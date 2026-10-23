package kr.mujoong.realtime.practiceflow;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.realtime.session.SessionMailbox;
import kr.mujoong.realtime.testing.FakeGuardrailClient;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderRequestFactory;
import kr.mujoong.testsupport.log.LogCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

@Tag("practice-flow")
class PracticeStageDeadlineTest {
    final SessionHarness h = new SessionHarness();
    FakeGuardrailClient guard;

    void prepare() {
        h.activityType=ActivityType.PRACTICE;guard=h.useGuardrail();
        h.practiceJson=PracticeScriptFixtures.packageJson(UUID.randomUUID(),PracticeScriptFixtures.minimalScript(UUID.randomUUID()));
        var kit=new PracticeFlowTestKit.Harness(h.practiceJson,Map.of());
        var settings=new PracticeFlowSettings(Duration.ofSeconds(10),Duration.ofSeconds(90),Duration.ofMinutes(10),Duration.ofSeconds(20),2,1,true);
        h.practice=x->new PracticeConversation(h.sessionId,h.guardianId,h.clock,settings,
                new PracticeScriptRequester(kit.http,Runnable::run,Duration.ofSeconds(1)),kit.recorder,e->{});
        h.boot();h.ackAndReady();
    }

    @Test void oldSpeechWaitAndGenerationDoNotConsumeCollectionOrPlaybackTime() {
        prepare();h.speak("previous speech",4800);h.join();
        for(int i=0;i<80;i++){h.speak("old tail",4800);h.advanceAlive(Duration.ofMillis(100));}
        h.advanceAlive(Duration.ofMillis(801));h.settleSend();
        h.advanceAlive(Duration.ofSeconds(7));
        h.speak("오늘은 함께 연습해 보자.",0);
        for(int i=0;i<142;i++){h.speak(null,4800);h.advanceAlive(Duration.ofMillis(100));}
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        h.advanceAlive(Duration.ofMillis(801));
        assertThat(guard.count()).isEqualTo(1);assertThat(h.link.outputs()).isEmpty();
        guard.allow();h.drain();assertThat(h.link.outputs()).hasSize(1);
        UUID playback=h.link.outputs().getFirst().playbackId();
        h.advanceAlive(Duration.ofSeconds(5));mark(playback,PlaybackMark.Status.STARTED);
        h.advanceAlive(Duration.ofSeconds(16));
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        mark(playback,PlaybackMark.Status.COMPLETED);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(h.logs.last(EventCode.OUTPUT_DISCARDED)).isEmpty();
        assertThat(h.logs.events().stream().filter(e -> "PRACTICE_AUDIO_STAGE".equals(e.get("event")))
                .map(LogCapture::attrs).map(a -> a.get("audioStage")))
                .containsSubsequence("DRAINING","REQUEST_QUEUED","REQUEST_SENT","COLLECTING","PLAYBACK_QUEUED","PLAYING","PLAYBACK_ENDED");
    }

    @Test void firstResponseDeadlineStartsAfterTheActualSendAndStopCancelsQueuedWork() {
        prepare();h.join();h.advanceAlive(Duration.ofSeconds(9));h.settleSend();
        h.advanceAlive(Duration.ofSeconds(9));
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        h.speak("오늘은 함께 연습해 보자.",4800);h.advanceAlive(Duration.ofMillis(801));
        assertThat(guard.count()).isEqualTo(1);
        h.childSays("stop","무중아 그만할래");h.settleSend();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.CLOSING);
        assertThat(h.link.outputs()).isEmpty();
    }

    @Test void aTricklingResponseStillHasAnAbsoluteCollectionBoundAndNoPartialAudioIsPlayed() {
        prepare();h.join();h.settleSend();
        h.speak("안내",960);
        for(int i=0;i<61;i++){h.advanceAlive(Duration.ofMillis(500));h.speak(null,960);}
        assertThat(LogCapture.attrs(h.logs.last(EventCode.OUTPUT_DISCARDED).orElseThrow()))
                .containsEntry("discardDetail","PROVIDER_COLLECTION_TIMEOUT");
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(h.link.outputs()).isEmpty();assertThat(guard.count()).isZero();
    }

    @Test void queuedSendTimeoutCannotLaterResurrectAnOldInstruction() {
        prepare();h.join();h.advanceAlive(Duration.ofSeconds(11));
        h.childSays("stop","무중아 그만할래");h.settleSend();
        assertThat(h.provider.last().requests(ProviderRequestFactory.InstructionsAppend.class)).isEmpty();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.CLOSING);
    }

    @Test void duplicatePlaybackStartedCannotKeepExtendingThePlaybackDeadline() {
        prepare();h.join();h.settleSend();h.speak("오늘은 함께 연습해 보자.",4800);
        h.advanceAlive(Duration.ofMillis(801));guard.allow();h.drain();
        UUID playback=h.link.outputs().getFirst().playbackId();mark(playback,PlaybackMark.Status.STARTED);
        h.advanceAlive(Duration.ofSeconds(19));mark(playback,PlaybackMark.Status.STARTED);
        h.advanceAlive(Duration.ofSeconds(2));
        assertThat(h.executor.status()).isEqualTo(SessionStatus.CLOSING);
        assertThat(LogCapture.attrs(h.logs.last(EventCode.PRACTICE_AUDIO_STAGE).orElseThrow()))
                .containsEntry("audioStage","PLAYBACK_TIMEOUT");
    }

    void mark(UUID id,PlaybackMark.Status status) {
        h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                new PlaybackMark(id,status,null,h.clock.now(),null,null),h.clock.now()));h.drain();
    }
}
