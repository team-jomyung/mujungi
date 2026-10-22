package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderRequestFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("service-guardrail")
class GreetingHandoffServiceGuardrailTest {
    @Test void currentCompletedRecordingProducesOneActualHandoffRequest() {
        var h = active("응, 안녕");
        var requests = h.provider.last().requests(ProviderRequestFactory.InstructionsAppend.class);
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().content()).contains("녹음의 실제 재생이 완료", "시작 인사·자기소개", "반복하지 않는다")
                .doesNotContain("응, 안녕").hasSizeLessThan(500);
        h.join(); h.settleSend();
        assertThat(h.provider.last().requests(ProviderRequestFactory.InstructionsAppend.class)).hasSize(1);
    }

    @Test void incompleteFailedAndForeignRecordingNeverRequestFirstResponse() {
        for (var status : java.util.List.of(PlaybackMark.Status.STARTED, PlaybackMark.Status.FAILED)) {
            var h = ready();
            markNotice(h, status, h.link.connectionId()); h.join(); h.settleSend();
            assertThat(h.provider.last().requests(ProviderRequestFactory.InstructionsAppend.class)).isEmpty();
            assertThat(h.link.outputs()).isEmpty();
        }
        var h = ready();
        markNotice(h, PlaybackMark.Status.COMPLETED, UUID.randomUUID()); h.join(); h.settleSend();
        assertThat(h.provider.last().requests(ProviderRequestFactory.InstructionsAppend.class)).isEmpty();
    }

    @Test void aGreetingReplyToTheChildsGreetingIsAllowedButSelfIntroductionAndScriptRepeatsAreStillBlocked() {
        var h = active("응, 안녕");
        speak(h, "안녕! 반가워.");
        assertThat(h.link.outputs()).as("아이가 인사했으니 인사말 답은 막지 않는다").hasSize(1);
    }

    @Test void unnecessaryGreetingAndSelfIntroductionProduceZeroAudioWithoutNewRequests() {
        for (String text : java.util.List.of("안녕, 나는 무중이야. 반가워.", "나는 AI야. 만나서 반가워.", "지금 이야기해 볼까?")) {
            var h = active("응, 안녕");
            speak(h, text);
            assertThat(h.link.outputs()).isEmpty();
            assertThat(h.link.deliveredFrames()).isEmpty();
            h.settleSend();
            assertThat(h.provider.last().requests(ProviderRequestFactory.InstructionsAppend.class)).hasSize(1);
        }
        // 아이가 인사하지 않았으면 인사말로 시작하는 응답도 시작 인사 반복으로 막는다
        for (String text : java.util.List.of("안녕", "반가워. 이야기해 볼까?")) {
            var h = active("응");
            speak(h, text);
            assertThat(h.link.outputs()).isEmpty();
            assertThat(h.link.deliveredFrames()).isEmpty();
            h.settleSend();
            assertThat(h.provider.last().requests(ProviderRequestFactory.InstructionsAppend.class)).hasSize(1);
        }
    }

    @Test void approvedIntroCannotBypassEvenWhenJevAllowsOrVerbatimMatches() {
        var h = ServiceGuardrailFixtures.harness(); h.useJudge(); var guard = h.useGuardrail();
        h.guardrail = h.guardrail.withApprovedVerbatim((session, text) -> text.equals(ServiceGuardrailFixtures.TEXT)
                ? java.util.Optional.of("APPROVED_SPEECH") : java.util.Optional.empty());
        h.boot(); h.ackAndReady();
        completeAndAccept(h, "응");
        speak(h, "공룡을 좋아하는구나.");
        guard.allow(); h.drain();
        assertThat(h.link.outputs()).hasSize(1);
        speak(h, ServiceGuardrailFixtures.TEXT);
        assertThat(guard.count()).isEqualTo(1);
        assertThat(h.link.outputs()).hasSize(1);
        assertThat(h.link.deliveredFrames()).isEmpty();

        for (boolean reconnect : java.util.List.of(false, true)) {
            var late = ServiceGuardrailFixtures.harness(); var lateGuard = late.useGuardrail();
            late.boot(); late.ackAndReady(); completeAndAccept(late, "응");
            speak(late, "공룡을 좋아하는구나.");
            assertThat(lateGuard.count()).isEqualTo(1);
            var destination = late.link;
            if (reconnect) {
                var old = late.link;
                var replacement = new kr.mujoong.realtime.testing.FakeDeviceLink(late.deviceId, late.clock.now());
                destination = replacement;
                late.mailbox.putSafety(new SessionMailbox.LinkReplaced(replacement, true, late.clock.now()));
                late.drain();
                old.setOpen(false);
            } else late.childSays("stop", "무중아 그만할래");
            lateGuard.allow(); late.drain();
            assertThat(late.link.outputs()).isEmpty();
            assertThat(late.link.deliveredFrames()).isEmpty();
            assertThat(destination.outputs()).isEmpty();
            assertThat(destination.deliveredFrames()).isEmpty();
        }
        assertThat(h.provider.last().requests(ProviderRequestFactory.InstructionsAppend.class)).hasSize(1);
    }

    @Test void substantiveAnswerAndQuotedGreetingRemainAllowed() {
        for (String text : java.util.List.of("공룡은 아주 오래전에 살았어.", "‘안녕’이라고 친구에게 말했구나.")) {
            var h = active("응, 공룡은 언제 살았어?");
            speak(h, text);
            assertThat(h.link.outputs()).hasSize(1);
            assertThat(h.link.deliver(h.link.outputs().getFirst(), h.clock.now())).isPositive();
        }
    }

    @Test void explicitIdentityQuestionAllowsRelevantAnswer() {
        var h = active("응, 너 누구야?");
        speak(h, "나는 무중이야. 함께 이야기하는 AI야.");
        assertThat(h.link.outputs()).hasSize(1);
        assertThat(h.link.deliver(h.link.outputs().getFirst(), h.clock.now())).isPositive();
        var greeting = active("응, 안녕이라고 말해줘");
        speak(greeting, "안녕!");
        assertThat(greeting.link.outputs()).hasSize(1);
        assertThat(greeting.link.deliver(greeting.link.outputs().getFirst(), greeting.clock.now())).isPositive();
    }

    @Test void pendingProposalOrStaleCompletionCannotReleaseHandoff() {
        var h = active("응");
        speak(h, "공룡을 좋아하는구나.");
        var queued = h.link.outputs().getFirst();
        mark(h, queued.playbackId(), PlaybackMark.Status.COMPLETED, h.link.connectionId());
        speak(h, "안녕, 나는 무중이야.");
        assertThat(h.link.outputs()).hasSize(1);
        h.childSays("rest", "잠깐 쉴게");
        assertThat(h.link.deliver(queued, h.clock.now())).isZero();
        mark(h, queued.playbackId(), PlaybackMark.Status.COMPLETED, h.link.connectionId());
        assertThat(h.link.deliveredFrames()).isEmpty();
        for (String result : java.util.List.of("FAILED", "STOPPED", "EXPIRED")) {
            var cancelled = active("응");
            speak(cancelled, "공룡을 좋아하는구나.");
            var first = cancelled.link.outputs().getFirst();
            assertThat(cancelled.link.deliver(first, cancelled.clock.now())).isPositive();
            if (result.equals("EXPIRED")) cancelled.advanceAlive(cancelled.settings.playbackExpiry());
            else mark(cancelled, first.playbackId(), PlaybackMark.Status.valueOf(result), cancelled.link.connectionId());
            mark(cancelled, first.playbackId(), PlaybackMark.Status.COMPLETED, cancelled.link.connectionId());
            speak(cancelled, "안녕, 나는 무중이야.");
            assertThat(cancelled.link.outputs()).hasSize(1);
        }
    }

    @Test void actualCurrentPlaybackCompletionReleasesLaterConversationAndPracticeIsSeparate() {
        var h = active("응");
        speak(h, "공룡을 좋아하는구나.");
        var first = h.link.outputs().getFirst();
        assertThat(h.link.deliver(first, h.clock.now())).isPositive();
        mark(h, first.playbackId(), PlaybackMark.Status.COMPLETED, h.link.connectionId());
        h.childSays("later", "친구에게 인사했어");
        speak(h, "안녕, 친구야.");
        assertThat(h.link.outputs()).hasSize(2);
        var practice = new SessionHarness(); practice.activityType = ActivityType.PRACTICE;
        practice.boot(); practice.ackAndReady(); practice.join(); practice.settleSend();
        speak(practice, "안녕, 나는 무중이야.");
        assertThat(practice.link.outputs()).hasSize(1);
    }

    @Test void aDiscardedRestartGreetingUnitNeverLetsTheSoundOfItsOwnResponseOutThroughALaterUnit() {
        var h = ServiceGuardrailFixtures.harness(); h.useJudge(); var guard = h.useGuardrail();
        // 아이가 인사하지 않아야 "안녕!"이 시작 인사 반복으로 폐기된다(아이가 인사했으면 인사말 답은 막지 않는다, GreetingHandoff)
        h.boot(); h.ackAndReady(); completeAndAccept(h, "응");
        long margin = h.settings.newSpeechMargin().toMillis();
        h.provider.last().newSpeechMargin(null);
        String id = h.provider.last().requests(ProviderRequestFactory.InstructionsAppend.class).getLast().eventId();
        h.provider.last().emit(new kr.mujoong.realtime.voice.ProviderPort.ProviderEvent.InstructionsAppended(id, 0, 0));
        h.provider.last().emit(new kr.mujoong.realtime.voice.ProviderPort.ProviderEvent.OutputTranscriptDelta("안녕!", 0, 100 + margin));
        h.provider.last().emit(new kr.mujoong.realtime.voice.ProviderPort.ProviderEvent.OutputTranscriptDelta("오늘 뭐 할까?",
                100 + margin, 300 + margin));
        h.provider.last().emit(new kr.mujoong.realtime.voice.ProviderPort.ProviderEvent.OutputAudioDelta(new byte[(int) margin * 48]));
        h.provider.last().emit(new kr.mujoong.realtime.voice.ProviderPort.ProviderEvent.OutputAudioDelta(
                kr.mujoong.realtime.testing.RealtimeItEnvironment.audiblePcm(14_400)));
        h.drain();
        assertThat(guard.count()).as("폐기한 응답의 뒷 단위는 검사도 받지 않는다").isZero();
        assertThat(h.link.outputs()).as("폐기된 인사의 음성이 뒷 단위와 함께 나가지 않는다").isEmpty();
        assertThat(h.link.deliveredFrames()).isEmpty();
    }

    @Test void firstResponseAfterALunaAcceptedUnclearGreetingStillReachesTheDevice() {
        var h = ServiceGuardrailFixtures.harness(); h.useJudge(); var guard = h.useGuardrail();
        h.boot(); h.ackAndReady(); completeAndAccept(h, "안녕, 무중아");
        assertThat(h.executor.status()).isEqualTo(kr.mujoong.contract.status.SessionStatus.ACTIVE);
        long margin = h.settings.newSpeechMargin().toMillis();
        h.provider.last().newSpeechMargin(null);
        var instructions = h.provider.last().requests(ProviderRequestFactory.InstructionsAppend.class);
        assertThat(instructions).as("참여 수락 뒤 첫 응답 지시가 나갔다").isNotEmpty();
        String id = instructions.getLast().eventId();
        h.provider.last().emit(new kr.mujoong.realtime.voice.ProviderPort.ProviderEvent.InstructionsAppended(id, 0, 0));
        h.provider.last().emit(new kr.mujoong.realtime.voice.ProviderPort.ProviderEvent.OutputTranscriptDelta("그래, 이야기해 보자.",
                0, 200 + margin));
        h.provider.last().emit(new kr.mujoong.realtime.voice.ProviderPort.ProviderEvent.OutputAudioDelta(new byte[(int) margin * 48]));
        h.provider.last().emit(new kr.mujoong.realtime.voice.ProviderPort.ProviderEvent.OutputAudioDelta(
                kr.mujoong.realtime.testing.RealtimeItEnvironment.audiblePcm(9_600)));
        h.drain();
        assertThat(guard.count()).as("첫 응답이 제브 검사까지 간다").isEqualTo(1);
        guard.allow(); h.drain();
        assertThat(h.link.outputs()).as("허용되면 소리가 나간다").isNotEmpty();
    }

    private static SessionHarness ready() {
        var h = ServiceGuardrailFixtures.harness(); h.useJudge(); h.boot(); h.ackAndReady(); return h;
    }

    private static SessionHarness active(String child) {
        var h = ready(); completeAndAccept(h, child); return h;
    }

    private static void completeAndAccept(SessionHarness h, String child) {
        markNotice(h, PlaybackMark.Status.COMPLETED, h.link.connectionId());
        h.childSays("fresh-accept", child);
        if (h.executor.status() == kr.mujoong.contract.status.SessionStatus.WAITING_CHILD
                && h.judge != null && !h.judge.calls().isEmpty()) {
            h.judge.confirm("PARTICIPATE", 0.9, "UNCLEAR", 0.05); h.drain();
        }
        h.settleSend();
    }

    private static void speak(SessionHarness h, String text) {
        h.speak(text, 4800); h.advanceAlive(Duration.ofSeconds(1));
    }

    private static void markNotice(SessionHarness h, PlaybackMark.Status status, UUID connection) {
        var play = h.link.sent(kr.mujoong.contract.ws.message.PlayApprovedContent.class).getLast();
        mark(h, play.playbackId(), status, connection);
    }

    private static void mark(SessionHarness h, UUID playback, PlaybackMark.Status status, UUID connection) {
        h.mailbox.putSafety(new SessionMailbox.PlaybackMarkReceived(connection,
                new PlaybackMark(playback, status, null, h.clock.now(), null, null), h.clock.now())); h.drain();
    }
}
