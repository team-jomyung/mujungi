package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("service-guardrail")
class ParticipationLateItemServiceGuardrailTest {
    @Test
    void preNoticeItemCannotJoinWhenItsTailArrivesAfterDrainQuiet() {
        SessionHarness h = ready();
        delta(h, "old-before-notice");
        completeNotice(h);
        h.advanceAlive(h.settings.inputTranscriptQuiet());
        delta(h, "old-before-notice");
        h.advanceAlive(h.settings.inputTranscriptQuiet());
        assertOnlyFreshAcceptanceCanStart(h);
    }

    @Test
    void preNoticeItemThatAlreadyWentQuietCannotBecomeNewAcceptance() {
        SessionHarness h = ready();
        delta(h, "old-quiet-before-notice");
        h.advanceAlive(h.settings.inputTranscriptQuiet());
        completeNotice(h);
        delta(h, "old-quiet-before-notice");
        h.advanceAlive(h.settings.inputTranscriptQuiet());
        assertOnlyFreshAcceptanceCanStart(h);
    }

    @Test void nullIdTimelineTailCannotJoinAfterQuiet() {
        SessionHarness h = ready();
        timed(h, 10, 30);
        completeNotice(h);
        h.advanceAlive(h.settings.inputTranscriptQuiet());
        timed(h, 10, 50);
        h.advanceAlive(h.settings.inputTranscriptQuiet());
        assertOnlyFreshAcceptanceCanStart(h);
    }

    @Test void nullIdAlreadyQuietBeforeCompletionKeepsItsTimeline() {
        SessionHarness h = ready();
        timed(h, 10, 30);
        h.advanceAlive(h.settings.inputTranscriptQuiet());
        completeNotice(h);
        timed(h, 10, 50);
        h.advanceAlive(h.settings.inputTranscriptQuiet());
        assertOnlyFreshAcceptanceCanStart(h);
    }

    @Test void unseenDelayedInputBeforeCurrentSessionTimelineIsRejected() {
        SessionHarness h = ready();
        h.provider.last().emit(new ProviderEvent.OutputSilenceDelta(48000));
        h.drain();
        completeNotice(h);
        timed(h, 100, 200);
        h.advanceAlive(h.settings.inputTranscriptQuiet());
        assertOnlyFreshAcceptanceCanStart(h);
    }

    @Test void missingIdentityAndTimelineCannotBeAssumedCurrent() {
        SessionHarness h = ready();
        completeNotice(h);
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta(null, "응"));
        h.drain();
        h.advanceAlive(h.settings.inputTranscriptQuiet());
        assertOnlyFreshAcceptanceCanStart(h);

        SessionHarness replaced = ready();
        completeNotice(replaced);
        replaced.mailbox.putSafety(new SessionMailbox.LinkReplaced(
                new kr.mujoong.realtime.testing.FakeDeviceLink(replaced.deviceId, replaced.clock.now()),
                true, replaced.clock.now()));
        replaced.drain();
        timed(replaced, 50, 70);
        replaced.advanceAlive(replaced.settings.inputTranscriptQuiet());
        assertThat(replaced.executor.status()).isEqualTo(SessionStatus.WAITING_CHILD);
        assertThat(replaced.recordings.opened()).isEmpty();

        SessionHarness queued = ready();
        queued.provider.last().emit(new ProviderEvent.InputTranscriptDelta(null, "응", 50, 70));
        // 안전 편지함의 완료 표식이 먼저 처리돼도, 이미 수신한 전사를 새 참여로 승격하지 않는다.
        completeNotice(queued);
        queued.advanceAlive(queued.settings.inputTranscriptQuiet());
        assertOnlyFreshAcceptanceCanStart(queued);
    }

    @Test void newNullIdTimelineAfterWindowStartsExactlyOnce() {
        SessionHarness h = ready();
        timed(h, 10, 30);
        completeNotice(h);
        timed(h, 50, 70);
        h.advanceAlive(h.settings.inputTranscriptQuiet());
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(h.transitions.applied().stream().filter(value -> value.to() == SessionStatus.ACTIVE)).hasSize(1);
        assertThat(h.recordings.opened()).hasSize(1);
    }

    @Test void repromptInvalidatesEarlierTimelineAndLegacyItems() {
        SessionHarness h = ready();
        timed(h, 10, 30);
        delta(h, "old-reprompt");
        completeNotice(h);
        h.advanceAlive(java.time.Duration.ofSeconds(20));
        completeNotice(h);
        timed(h, 10, 50);
        delta(h, "old-reprompt");
        h.advanceAlive(h.settings.inputTranscriptQuiet());
        assertOnlyFreshAcceptanceCanStart(h);
    }

    private static void timed(SessionHarness h, long start, long end) {
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta(null, "응", start, end));
        h.drain();
    }

    private static SessionHarness ready() {
        SessionHarness h = ServiceGuardrailFixtures.harness();
        h.rawAudioConsent = true;
        h.boot();
        h.ackAndReady();
        return h;
    }

    private static void delta(SessionHarness h, String itemId) {
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta(itemId, "응"));
        h.drain();
    }

    private static void completeNotice(SessionHarness h) {
        PlayApprovedContent play = h.link.sent(PlayApprovedContent.class).getLast();
        h.mailbox.putSafety(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                new PlaybackMark(play.playbackId(), PlaybackMark.Status.COMPLETED,
                        null, h.clock.now(), null, null), h.clock.now()));
        h.drain();
    }

    private static void assertOnlyFreshAcceptanceCanStart(SessionHarness h) {
        assertThat(h.executor.status()).isEqualTo(SessionStatus.WAITING_CHILD);
        assertThat(h.transitions.applied()).noneMatch(value -> value.to() == SessionStatus.ACTIVE);
        assertThat(h.recordings.opened()).isEmpty();
        h.join();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(h.transitions.applied().stream().filter(value -> value.to() == SessionStatus.ACTIVE)).hasSize(1);
        assertThat(h.recordings.opened()).hasSize(1);
    }
}
