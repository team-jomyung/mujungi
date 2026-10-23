package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.realtime.testing.ControllingPracticeExtension;
import kr.mujoong.realtime.testing.RecordingPracticeExtension;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 4회차 R1 — 실행부가 '아이 말 시작'을 출력권 연습에 알리는 규칙. 새 입력 전사 묶음의 첫 조각(끼어들기 봉인과 같은 신호)마다 한 번, ACTIVE에서만
 * 알리고, 참여 확인 · 기술 일시정지 · 출력권 없는 확장 · 자유대화에는 알리지 않는다. 확정 전사는 지금처럼 묶음이 조용해진 뒤 따로 간다. 공급자는 가짜
 * 대역이고 시각은 수동 시계다(R-F1, 테스트 대역 증거 — 실제 공급자 · 실제 기기 성공이 아니다, R-M11).
 */
@Tag("practice-flow")
class ChildSpeechStartExecutorTest {

    private static final Duration INPUT_QUIET = SessionHarness.SETTINGS.inputTranscriptQuiet();

    private final SessionHarness h = new SessionHarness();
    private final ControllingPracticeExtension extension = new ControllingPracticeExtension();

    private void startPractice() {
        h.activityType = ActivityType.PRACTICE;
        h.practice = extension.factory;
        h.activate();
    }

    private void delta(String itemId, String text) {
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta(itemId, text));
        h.drain();
    }

    @Test
    void theControllingPracticeIsToldOncePerInputGroupBeforeTheConfirmedTranscript() {
        startPractice();
        Instant startedAt = h.clock.now();

        delta("item-1", "그");
        h.advance(Duration.ofMillis(300));
        delta("item-1", "거");
        delta("item-1", " 줘");

        assertThat(extension.speechStarts).as("한 묶음은 한 번만 알린다").containsExactly(startedAt);
        assertThat(extension.utterances).as("확정 전사는 묶음이 조용해진 뒤다").isEmpty();
        h.advance(INPUT_QUIET);
        assertThat(extension.utterances).singleElement().satisfies(utterance -> assertThat(utterance.text()).isEqualTo("그거 줘"));

        delta("item-2", "파란 거");
        assertThat(extension.speechStarts).as("조용해진 뒤의 새 묶음은 새 말이다").hasSize(2);
    }

    @Test
    void theJoinAnswerWhileWaitingForTheChildIsNotASpeechStart() {
        h.activityType = ActivityType.PRACTICE;
        h.practice = extension.factory;
        h.boot();
        h.ackAndReady();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.WAITING_CHILD);

        delta(SessionHarness.JOIN_ITEM, "응");
        h.advance(INPUT_QUIET);
        h.settleSend();

        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(extension.speechStarts).as("참여 확인 대답은 연습의 말이 아니다").isEmpty();
        delta("item-2", "그거");
        assertThat(extension.speechStarts).hasSize(1);
    }

    @Test
    void anExtensionWithoutTheOutputIsNeverTold() {
        RecordingPracticeExtension recording = new RecordingPracticeExtension();
        h.activityType = ActivityType.PRACTICE;
        h.practice = recording.factory;
        h.activate();

        delta("item-1", "공룡");
        h.advance(INPUT_QUIET);

        assertThat(recording.calls).contains("onChildUtterance").doesNotContain("onChildSpeechStarted");
    }

    @Test
    void freeTalkHasNoPracticeToTell() {
        h.practice = extension.factory;
        h.activate();

        delta("item-1", "공룡");
        h.advance(INPUT_QUIET);

        assertThat(extension.out).as("자유대화는 연습 확장을 만들지 않는다").isNull();
        assertThat(extension.speechStarts).isEmpty();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
    }

    @Test
    void noSpeechStartIsDeliveredWhileThePracticeIsPausedByATechnicalPause() {
        startPractice();
        h.advance(SessionHarness.LIFECYCLE.gapDisplay().plusSeconds(1));
        assertThat(h.executor.liveState().technicalPause()).isNotNull();

        delta("item-1", "어");

        assertThat(extension.speechStarts).as("멈춘 동안의 말은 실행부가 맡는다").isEmpty();
    }
}
