package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.realtime.practicelegacy.PracticePorts.Playback;
import kr.mujoong.realtime.testing.ControllingPracticeExtension;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.PracticeSpeech;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import kr.mujoong.realtime.voice.ProviderRequestFactory.InstructionsAppend;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 4회차 R2 — 연습 발화 지시의 말 간격(실측 B3: GPT-Live가 스스로 지어낸 말을 하는 중에 보낸 지시 8번 중 3번이 첫머리 잘림 · 새 말 없음으로 틀어졌다).
 * 말 간격을 맞추는 출력권 연습의 GPT-Live 원문 읽기 · 생성형 역할 지시는 공급자 출력이 조용할 때(A4-b 기준) 보내고, 말하는 중이면
 * {@code instruction-defer-max}(3초)까지 미뤘다가 처음 조용해질 때 보낸다. 상한이 지나면 지금처럼 말하는 중에 보낸다. 새 발화 · 취소 · 끼어들기 ·
 * 출력 보류는 미룬 지시를 버린다. 새 말이 없었거나 원문 대조로 버려진 발화는 실패를 알리기 전에 한 번 다시 보낸다. 공급자는 인메모리 가짜 대역이고
 * 시각은 수동 시계다(R-F1, 테스트 대역 증거 — 실제 공급자 · 실제 기기 성공이 아니다, R-M11).
 */
@Tag("practice-flow")
class PracticeSpeechPacingTest {

    private static final Duration QUIET = Duration.ofMillis(800);
    private static final String LINE = "자, 여기 초록색!";
    private static final String OTHER_LINE = "이건 어때?";
    private static final String INSTRUCTION = "친구로서 방금 말을 한 번 더 물어본다";
    private static final String SELF_TALK = "나는 친구 민수 역할이야";

    private final SessionHarness h = new SessionHarness();
    private final ControllingPracticeExtension extension = new ControllingPracticeExtension();

    private void start(boolean paces) {
        extension.paces = paces;
        h.activityType = ActivityType.PRACTICE;
        h.practice = extension.factory;
        h.activate();
        h.link.touch(h.clock.now());
    }

    private static PracticeSpeech line(String text) {
        return PracticeSpeech.scenario(UUID.randomUUID(), UUID.randomUUID(), 0, "친구", text);
    }

    /** 보낸 지시 중 이 글을 담은 것. */
    private List<InstructionsAppend> sentWith(String text) {
        return h.provider.last().requests(InstructionsAppend.class).stream().filter(append -> append.content().contains(text))
                .toList();
    }

    private List<String> events(String type) {
        h.recorder.tick();
        return h.store.sessionEvents.stream().filter(event -> type.equals(event.getEventType())).map(SessionEvent::getPayloadJson)
                .toList();
    }

    private void mark(UUID playbackId, PlaybackMark.Status status) {
        h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                new PlaybackMark(playbackId, status, null, h.clock.now(), null, null), h.clock.now()));
        h.drain();
    }

    @Test
    void aLineAskedWhileGptLiveSpeaksWaitsForTheFirstQuietAndThenStartsQuiet() {
        start(true);
        h.speak(SELF_TALK, 4_800);
        h.advance(Duration.ofMillis(300));
        PracticeSpeech speech = line(LINE);

        assertThat(extension.out.speak(speech)).as("미뤄도 확장에는 받아들였다").isTrue();
        h.settleSend();
        assertThat(sentWith(LINE)).as("말하는 중에는 보내지 않는다").isEmpty();
        h.advance(Duration.ofMillis(499));
        h.settleSend();
        assertThat(sentWith(LINE)).isEmpty();

        h.advance(Duration.ofMillis(1));
        h.settleSend();
        assertThat(sentWith(LINE)).as("마지막 조각 뒤 조용 시간이 차면 곧바로 보낸다").hasSize(1);
        h.speak(LINE, 4_800);
        h.advance(QUIET);

        assertThat(h.link.outputs()).as("조용 시작이라 첫머리부터 들린다").hasSize(1);
        assertThat(events(SessionExecutor.EVENT_OUTPUT_NEW_SPEECH)).last().satisfies(payload -> assertThat(payload)
                .contains("\"start\":\"QUIET\"", "\"deferredMs\":500", "\"deferEnd\":\"QUIET\"", "\"resend\":false"));
        assertThat(events(SessionExecutor.EVENT_PRACTICE_SPEECH_SOURCE)).singleElement()
                .satisfies(payload -> assertThat(payload).contains("\"source\":\"GPT_LIVE_FALLBACK\"", "\"sent\":true"));
    }

    @Test
    void aLineStillWaitingWhenTheCapEndsIsSentWhileSpeakingAsBefore() {
        start(true);
        h.speak(SELF_TALK, 4_800);
        PracticeSpeech speech = line(LINE);
        extension.out.speak(speech);

        for (int i = 0; i < 5; i++) {
            h.advance(Duration.ofMillis(500));
            h.speak(SELF_TALK, 4_800);
        }
        h.settleSend();
        assertThat(sentWith(LINE)).as("2.5초: 아직 상한 전").isEmpty();
        h.advance(Duration.ofMillis(500));
        h.settleSend();

        assertThat(sentWith(LINE)).as("3초 상한에 말하는 중이어도 보낸다").hasSize(1);
        h.speak(LINE, 4_800);
        h.advance(QUIET);
        assertThat(events(SessionExecutor.EVENT_OUTPUT_NEW_SPEECH)).last().satisfies(payload -> assertThat(payload)
                .contains("\"start\":\"SPEAKING\"", "\"deferredMs\":3000", "\"deferEnd\":\"CAP\"", "\"resend\":false"));
    }

    @Test
    void aNewLineReplacesTheDeferredOneWhichIsNeverSent() {
        start(true);
        h.speak(SELF_TALK, 4_800);
        PracticeSpeech first = line(LINE);
        extension.out.speak(first);

        PracticeSpeech second = line(OTHER_LINE);
        extension.out.speak(second);
        h.advance(QUIET);
        h.settleSend();

        assertThat(sentWith(LINE)).as("낡은 지시는 보내지 않는다").isEmpty();
        assertThat(sentWith(OTHER_LINE)).hasSize(1);
        assertThat(events(SessionExecutor.EVENT_PRACTICE_SPEECH_SOURCE)).hasSize(2).first()
                .satisfies(payload -> assertThat(payload).contains("\"source\":\"NONE\"", "\"reason\":\"DEFER_ABANDONED\"", "\"sent\":false"));
    }

    @Test
    void cancellingTheDeferredLineOrHoldingTheOutputSendsNothing() {
        start(true);
        h.speak(SELF_TALK, 4_800);
        PracticeSpeech cancelled = line(LINE);
        extension.out.speak(cancelled);
        extension.out.cancelSpeech(cancelled.correlationId());
        h.speak(SELF_TALK, 4_800);
        PracticeSpeech held = line(OTHER_LINE);
        extension.out.speak(held);
        extension.out.holdOutput(true);

        h.advance(Duration.ofSeconds(4));
        h.settleSend();

        assertThat(sentWith(LINE)).isEmpty();
        assertThat(sentWith(OTHER_LINE)).isEmpty();
        assertThat(extension.facts).as("확장이 스스로 거둔 발화에는 사실을 보내지 않는다").isEmpty();
    }

    @Test
    void aChildWhoStartsSpeakingWhileTheLineWaitsInterruptsItAndNothingIsSent() {
        start(true);
        h.speak(SELF_TALK, 4_800);
        PracticeSpeech speech = line(LINE);
        extension.out.speak(speech);

        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta("item-1", "그런데"));
        h.drain();
        h.advance(Duration.ofSeconds(4));
        h.settleSend();

        assertThat(sentWith(LINE)).as("아이 말 위에 낡은 지시를 보내지 않는다").isEmpty();
        assertThat(extension.endFacts(speech.correlationId())).containsExactly(Playback.INTERRUPTED);
    }

    @Test
    void aGeneratedLineWhoseNewSpeechNeverStartsIsSentOnceMoreAndTheSecondMissIsAFailure() {
        start(true);
        UUID slot = UUID.randomUUID();
        assertThat(extension.out.speakGenerated(slot, "PEER", INSTRUCTION)).isTrue();
        h.settleSend();
        assertThat(sentWith(INSTRUCTION)).as("조용할 때는 곧바로 보낸다").hasSize(1);

        h.advanceAlive(Duration.ofSeconds(21));
        h.settleSend();
        assertThat(sentWith(INSTRUCTION)).as("새 말이 없으면 같은 지시를 한 번 다시 보낸다").hasSize(2);
        assertThat(sentWith(INSTRUCTION).get(1).content()).isEqualTo(sentWith(INSTRUCTION).get(0).content());
        assertThat(extension.endFacts(slot)).as("다시 보내는 동안에는 실패를 알리지 않는다").isEmpty();

        h.advanceAlive(Duration.ofSeconds(21));
        h.settleSend();

        assertThat(sentWith(INSTRUCTION)).as("슬롯마다 한 번만 다시 보낸다").hasSize(2);
        assertThat(extension.endFacts(slot)).containsExactly(Playback.FAILED);
        assertThat(events(SessionExecutor.EVENT_OUTPUT_NEW_SPEECH)).hasSize(2).satisfiesExactly(
                first -> assertThat(first).contains("\"outcome\":\"NEW_SPEECH_NOT_OBSERVED\"", "\"resend\":false"),
                second -> assertThat(second).contains("\"outcome\":\"NEW_SPEECH_NOT_OBSERVED\"", "\"resend\":true"));
        assertThat(events(SessionExecutor.EVENT_PRACTICE_SPEECH_SOURCE)).as("출처 사건은 요청마다 하나다").hasSize(1);
    }

    @Test
    void aReadingDiscardedByTheTextCheckIsSentOnceMoreAndPlaysWhenItMatches() {
        start(true);
        PracticeSpeech speech = line(LINE);
        extension.out.speak(speech);
        h.settleSend();
        h.speak("자, 여기 초록색이야! 나는 민수야", 4_800);
        h.advance(QUIET);
        h.settleSend();

        assertThat(h.link.outputs()).as("덧붙인 말은 들리지 않는다").isEmpty();
        assertThat(sentWith(LINE)).as("원문 대조로 버려지면 한 번 다시 보낸다").hasSize(2);
        assertThat(extension.endFacts(speech.correlationId())).isEmpty();
        h.speak(LINE, 4_800);
        h.advance(QUIET);

        assertThat(h.link.outputs()).hasSize(1);
        UUID playback = h.link.outputs().getFirst().playbackId();
        mark(playback, PlaybackMark.Status.STARTED);
        mark(playback, PlaybackMark.Status.COMPLETED);
        assertThat(extension.endFacts(speech.correlationId())).containsExactly(Playback.COMPLETED);
    }

    @Test
    void aSecondTextMismatchIsAFailure() {
        start(true);
        PracticeSpeech speech = line(LINE);
        extension.out.speak(speech);
        h.settleSend();
        h.speak("자, 여기 초록색이야!", 4_800);
        h.advance(QUIET);
        h.settleSend();
        h.speak("여기 초록색이야!", 4_800);
        h.advance(QUIET);
        h.settleSend();

        assertThat(sentWith(LINE)).hasSize(2);
        assertThat(h.link.outputs()).isEmpty();
        assertThat(extension.endFacts(speech.correlationId())).containsExactly(Playback.FAILED);
    }

    @Test
    void aPracticeThatDoesNotPaceItsSpeechIsSentAtOnceAndNeverResentAsBefore() {
        start(false);
        h.speak(SELF_TALK, 4_800);
        PracticeSpeech speech = line(LINE);
        extension.out.speak(speech);
        h.settleSend();

        assertThat(sentWith(LINE)).as("말 간격을 맞추지 않는 확장은 지금처럼 곧바로 보낸다").hasSize(1);
        h.speak("자, 여기 초록색이야! 나는 민수야", 4_800);
        h.advance(QUIET);
        h.settleSend();
        assertThat(sentWith(LINE)).as("다시 보내지 않는다").hasSize(1);
        assertThat(extension.endFacts(speech.correlationId())).containsExactly(Playback.FAILED);
    }

    @Test
    void instructionsOtherThanPracticeSpeechAreNeverDeferred() {
        start(true);
        h.speak(SELF_TALK, 4_800);

        assertThat(extension.out.instructProvider("목표 문장을 한 번 더 안내한다.")).isTrue();
        h.settleSend();

        assertThat(sentWith("목표 문장을 한 번 더 안내한다.")).as("연습 발화가 아닌 지시는 미루지 않는다").hasSize(1);
        assertThat(events(SessionExecutor.EVENT_OUTPUT_NEW_SPEECH)).isEmpty();
    }
}
