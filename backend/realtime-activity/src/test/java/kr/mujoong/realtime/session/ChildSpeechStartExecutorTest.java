package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.realtime.session.PracticeSessionExtension.Signal;
import kr.mujoong.realtime.testing.RecordingPracticeExtension;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * '아이 말 시작' 알림 시험 (4회차 R1, docs/practice-direct-ask.md 7.2 무응답 시계). 실행부는 새 입력 전사 묶음의 첫 조각마다 한 번, ACTIVE 중
 * 출력권을 가진 연습에만 알리고, 참여 확인 · 기술 일시정지 · 종료 뒤에는 알리지 않는다. 확정 전사는 지금처럼 조용 시간 뒤 따로 간다. 가짜 공급자 ·
 * 연습 확장점 대역 위의 합성 입력이다(테스트 대역 증거, R-F1).
 */
@Tag("session-runtime")
@Tag("practice-flow")
class ChildSpeechStartExecutorTest {

    private final SessionHarness h = new SessionHarness();
    private final RecordingPracticeExtension extension = new RecordingPracticeExtension();

    private void practice(boolean controls) {
        extension.controls = controls;
        h.activityType = ActivityType.PRACTICE;
        h.practiceJson = "{\"goals\":[{\"order\":1}]}";
        h.practice = extension.factory;
    }

    private void delta(String itemId, String text) {
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta(itemId, text));
        h.drain();
    }

    /** 조용 시간이 지나 묶음이 확정 전사가 된다. 기기는 살아 있다. */
    private void quiet() {
        h.advanceAlive(h.settings.inputTranscriptQuiet());
    }

    @Test
    void theFirstPieceOfEachNewInputGroupNotifiesTheControllingPracticeOnce() {
        practice(true);
        h.activate();
        assertThat(extension.speechStarts).as("참여 대답은 알리지 않는다").isEmpty();
        int before = extension.utterances.size();

        Instant first = h.clock.now();
        delta("item-1", "손 ");
        delta("item-1", "괜찮아?");
        assertThat(extension.speechStarts).as("묶음마다 한 번").containsExactly(first);
        assertThat(extension.utterances).as("확정 전사는 조용 시간 뒤").hasSize(before);

        quiet();
        assertThat(extension.utterances).hasSize(before + 1);
        assertThat(extension.utterances.getLast().text()).isEqualTo("손 괜찮아?");
        assertThat(extension.calls).containsSubsequence("onChildSpeechStarted", "onChildUtterance");

        delta("item-2", "그럼 다음에 같이 그리자");
        assertThat(extension.speechStarts).as("다음 말은 새 묶음").hasSize(2);
    }

    @Test
    void aPracticeWithoutOutputControlIsNeverNotifiedButStillGetsTheTranscript() {
        practice(false);
        h.activate();

        delta("item-1", "손 괜찮아?");
        quiet();

        assertThat(extension.speechStarts).isEmpty();
        assertThat(extension.calls).doesNotContain("onChildSpeechStarted");
        assertThat(extension.utterances.getLast().text()).isEqualTo("손 괜찮아?");
    }

    @Test
    void speechBeforeTheChildJoinsIsNotNotified() {
        practice(true);
        h.boot();
        h.ackAndReady();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.WAITING_CHILD);

        delta("item-0", "어");

        assertThat(extension.speechStarts).isEmpty();
    }

    @Test
    void speechDuringATechnicalPauseIsLeftToTheExecutor() {
        practice(true);
        h.activate();
        h.link.touch(h.clock.now());
        h.advance(Duration.ofSeconds(12));
        assertThat(h.executor.liveState().technicalPause()).as("기기 소식이 끊겨 기술 일시정지").isNotNull();

        delta("item-1", "어디 갔어");

        assertThat(extension.speechStarts).isEmpty();
    }

    @Test
    void speechAfterThePracticeEndedIsNotNotified() {
        practice(true);
        extension.onUtterance = (utterance, out) -> utterance.text().contains("다음에") ? Signal.LAST_GOAL : Signal.NONE;
        h.activate();
        delta("item-1", "그럼 다음에 같이 그리자");
        quiet();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.CLOSING);
        int notified = extension.speechStarts.size();

        delta("item-2", "또 해");

        assertThat(extension.speechStarts).hasSize(notified);
    }

    @Test
    void theSignalTheNotificationReturnsIsAppliedLikeAnyOtherPracticeMoment() {
        practice(true);
        // FT-1023-g로 출력 실패 신호(OUTPUT_FAILED)가 없어져, 남은 끝 신호(LAST_GOAL)로 같은 적용 경로를 본다
        extension.onSpeechStarted = (at, out) -> Signal.LAST_GOAL;
        h.activate();

        delta("item-1", "어");

        assertThat(h.executor.status()).isEqualTo(SessionStatus.CLOSING);
        assertThat(h.transitions.find(h.sessionId).orElseThrow().endDetail()).isEqualTo("PRACTICE_COMPLETED");
    }
}
