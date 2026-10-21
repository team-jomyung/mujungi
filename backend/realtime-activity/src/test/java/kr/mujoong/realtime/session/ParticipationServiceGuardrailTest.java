package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import java.util.List;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.realtime.testing.SessionHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("service-guardrail")
class ParticipationServiceGuardrailTest {
    @Test void actualCompletionThenCurrentAcceptStartsExactlyOnceAndRecordsFacts() {
        SessionHarness h = ready();
        h.rawAudioConsent = true;
        complete(h);
        h.join();
        h.join();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(h.transitions.applied().stream().filter(value -> value.to() == SessionStatus.ACTIVE)).hasSize(1);
        assertThat(h.transitions.guardrailFacts.get(h.sessionId).participation()).isEqualTo(SessionGuardrailFacts.Participation.ACCEPTED);
        assertThat(h.recordings.opened()).hasSize(1);
    }

    @Test void earlyAcceptDoesNotBecomeParticipationWhenNoticeLaterCompletes() {
        SessionHarness h = ready();
        h.join();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.WAITING_CHILD);
        complete(h);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.WAITING_CHILD);
        h.join();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        SessionHarness partial = ready();
        partial.provider.last().emit(new kr.mujoong.realtime.voice.ProviderPort.ProviderEvent.InputTranscriptDelta("old", "응"));
        partial.drain();
        complete(partial);
        partial.provider.last().emit(new kr.mujoong.realtime.voice.ProviderPort.ProviderEvent.InputTranscriptDelta("old", "응"));
        partial.drain();
        partial.advanceAlive(partial.settings.inputTranscriptQuiet());
        assertThat(partial.executor.status()).isEqualTo(SessionStatus.WAITING_CHILD);
        partial.join();
        assertThat(partial.executor.status()).isEqualTo(SessionStatus.ACTIVE);
    }

    @Test void startedIsNotCompletionAndCannotOpenParticipation() {
        SessionHarness h = ready();
        mark(h, PlaybackMark.Status.STARTED);
        h.join();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.WAITING_CHILD);
        assertThat(h.recordings.opened()).isEmpty();
    }

    @Test void clearRefusalDeclinesWithoutOfferingAlternative() {
        SessionHarness h = ready();
        complete(h);
        h.childSays("refusal", "싫어");
        assertThat(h.executor.status()).isEqualTo(SessionStatus.DECLINED);
        assertThat(h.transitions.guardrailFacts.get(h.sessionId).participation()).isEqualTo(SessionGuardrailFacts.Participation.REFUSED);
        assertThat(h.transitions.guardrailFacts.get(h.sessionId).goodbye()).isEqualTo(SessionGuardrailFacts.Goodbye.PENDING);
        assertThat(h.link.sent(PlayApprovedContent.class)).noneMatch(play -> play.contentCode().equals(SessionExecutor.DECLINE_ALTERNATIVE));
        assertThat(h.recordings.opened()).isEmpty();
        mark(h, PlaybackMark.Status.STARTED);
        assertThat(h.transitions.guardrailFacts.get(h.sessionId).goodbye()).isEqualTo(SessionGuardrailFacts.Goodbye.PLAYING);
        complete(h);
        assertThat(h.transitions.guardrailFacts.get(h.sessionId).goodbye()).isEqualTo(SessionGuardrailFacts.Goodbye.COMPLETED);

        SessionHarness unverified = ServiceGuardrailFixtures.harness();
        unverified.noticeAssets = ServiceGuardrailFixtures.catalog();
        unverified.boot(); unverified.ackAndReady(); complete(unverified);
        unverified.childSays("refusal", "싫어");
        assertThat(unverified.executor.status()).isEqualTo(SessionStatus.DECLINED);
        assertThat(unverified.transitions.guardrailFacts.get(unverified.sessionId).goodbye()).isEqualTo(SessionGuardrailFacts.Goodbye.NONE);
        assertThat(unverified.link.sent(PlayApprovedContent.class)).noneMatch(play -> play.contentCode().equals("CLOSING"));
    }

    @Test void missingSpokenAssetsFailClosedAsTechnicalFailure() {
        SessionHarness h = ServiceGuardrailFixtures.harness();
        h.noticeAssets = new NoticeAssetCatalog();
        h.boot(); h.ackAndReady();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.FAILED);
        assertThat(h.transitions.guardrailFacts.get(h.sessionId).notice()).isEqualTo(SessionGuardrailFacts.Notice.FAILED);
        assertThat(h.recordings.opened()).isEmpty();
    }

    @Test void failedAndExpiredPlaybackDoNotBecomeChildSilence() {
        SessionHarness failed = ready();
        mark(failed, PlaybackMark.Status.FAILED);
        assertThat(failed.executor.status()).isEqualTo(SessionStatus.FAILED);
        SessionHarness expired = ready();
        expired.advanceAlive(Duration.ofSeconds(20));
        assertThat(expired.executor.status()).isEqualTo(SessionStatus.FAILED);
        assertThat(expired.transitions.guardrailFacts.get(expired.sessionId).participation())
                .isEqualTo(SessionGuardrailFacts.Participation.UNKNOWN);
    }

    @Test void failedPersistenceDoesNotStartRecordingOrFirstResponse() {
        SessionHarness h = ready();
        complete(h);
        h.transitions.failNext(1);
        h.join();
        assertThat(h.transitions.applied()).noneMatch(value -> value.to() == SessionStatus.ACTIVE);
        assertThat(h.recordings.opened()).isEmpty();
    }

    @Test void twoActualCompletedPromptWindowsWithNoReplyRecordNoResponse() {
        SessionHarness h = ready();
        complete(h);
        h.advanceAlive(Duration.ofSeconds(20));
        complete(h);
        h.advanceAlive(Duration.ofSeconds(20));
        assertThat(h.executor.status()).isEqualTo(SessionStatus.DECLINED);
        assertThat(h.transitions.guardrailFacts.get(h.sessionId).participation()).isEqualTo(SessionGuardrailFacts.Participation.NO_RESPONSE);
    }

    private static SessionHarness ready() {
        SessionHarness h = ServiceGuardrailFixtures.harness();
        h.rawAudioConsent = true;
        h.boot(); h.ackAndReady();
        return h;
    }

    private static void complete(SessionHarness h) { mark(h, PlaybackMark.Status.COMPLETED); }
    private static void mark(SessionHarness h, PlaybackMark.Status status) {
        List<PlayApprovedContent> plays = h.link.sent(PlayApprovedContent.class);
        PlayApprovedContent play = plays.getLast();
        h.mailbox.putSafety(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                new PlaybackMark(play.playbackId(), status, null, h.clock.now(), null, null), h.clock.now()));
        h.drain();
    }
}
