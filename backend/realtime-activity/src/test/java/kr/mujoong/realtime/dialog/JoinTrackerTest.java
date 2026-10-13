package kr.mujoong.realtime.dialog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import kr.mujoong.realtime.dialog.JoinTracker.Action;
import kr.mujoong.realtime.dialog.JoinTracker.Phase;
import kr.mujoong.realtime.dialog.JoinTracker.Reply;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 참여 판단 추적기 시험(U3 L4 · U3-R13 · B17 · AC6.3.3 ~ 6.3.8 · AC3.5.1).
 *
 * <p>무응답 창 경계(19초 응답 → 시작 / 21초 → 재안내, 2차 19초 → 시작 / 21초 → DECLINED), 거부 → 대안 1회 → 재거부 → DECLINED, 주제 거부,
 * 불명확 발화 = 되묻기(재안내 1회에 포함), 안내 최대 3번을 시각을 인자로 넣어 확인한다.
 */
@Tag("u3")
@Tag("u4b")
class JoinTrackerTest {

    private static final Duration WINDOW = Duration.ofSeconds(20);
    private static final Instant T0 = Instant.parse("2026-10-15T09:00:00Z");

    private final JoinTracker tracker = new JoinTracker(WINDOW);

    @Test
    void aReplyAt19SecondsStartsAndSilenceUntil21SecondsReprompts() {
        tracker.promptPlayed(T0);
        assertThat(tracker.tick(T0.plusSeconds(19))).isEqualTo(Action.NONE);
        assertThat(tracker.reply(Reply.ACCEPT, T0.plusSeconds(19))).isEqualTo(Action.START);
        assertThat(tracker.phase()).isEqualTo(Phase.JOINED);

        JoinTracker silent = new JoinTracker(WINDOW);
        silent.promptPlayed(T0);
        assertThat(silent.tick(T0.plusSeconds(21))).isEqualTo(Action.REPROMPT);
        assertThat(silent.prompts()).isEqualTo(2);
        assertThat(silent.phase()).as("재안내를 재생하는 동안은 창이 없다").isEqualTo(Phase.PROMPTING);
        assertThat(silent.tick(T0.plusSeconds(60))).isEqualTo(Action.NONE);
    }

    @Test
    void afterTheRepromptA19SecondReplyStartsAndSilenceUntil21SecondsDeclines() {
        JoinTracker second = repromptedAt(T0);
        Instant replayed = T0.plusSeconds(25);
        second.promptPlayed(replayed);
        assertThat(second.reply(Reply.ACCEPT, replayed.plusSeconds(19))).isEqualTo(Action.START);

        JoinTracker declined = repromptedAt(T0);
        declined.promptPlayed(replayed);
        assertThat(declined.tick(replayed.plusSeconds(19))).isEqualTo(Action.NONE);
        assertThat(declined.tick(replayed.plusSeconds(21))).isEqualTo(Action.DECLINE);
        assertThat(declined.phase()).isEqualTo(Phase.DECLINED);
        assertThat(declined.isFinished()).isTrue();
        assertThat(declined.reply(Reply.ACCEPT, replayed.plusSeconds(22))).as("끝난 뒤 응답은 무시").isEqualTo(Action.NONE);
    }

    @Test
    void theWindowEdgeItselfCountsAsSilenceAndALateReplyIsHandledAsTheExpiryFirst() {
        tracker.promptPlayed(T0);
        assertThat(tracker.reply(Reply.ACCEPT, T0.plus(WINDOW))).as("창이 찬 시각의 응답은 재안내가 먼저").isEqualTo(Action.REPROMPT);
        assertThat(tracker.phase()).isEqualTo(Phase.PROMPTING);
        // 재안내 재생 중에 온 응답도 판단한다
        assertThat(tracker.reply(Reply.ACCEPT, T0.plusSeconds(22))).isEqualTo(Action.START);
    }

    @Test
    void aRefusalGetsOneAlternativeAndASecondRefusalDeclines() {
        tracker.promptPlayed(T0);
        assertThat(tracker.reply(Reply.REFUSE, T0.plusSeconds(5))).isEqualTo(Action.OFFER_ALTERNATIVE);
        assertThat(tracker.prompts()).isEqualTo(2);
        tracker.promptPlayed(T0.plusSeconds(10));
        assertThat(tracker.reply(Reply.REFUSE, T0.plusSeconds(12))).isEqualTo(Action.DECLINE);
        assertThat(tracker.phase()).isEqualTo(Phase.DECLINED);
    }

    @Test
    void aTopicRefusalIsNotADeclineButGetsTheAlternativeAndCanStillJoin() {
        tracker.promptPlayed(T0);
        assertThat(tracker.reply(Reply.TOPIC_REFUSAL, T0.plusSeconds(3))).isEqualTo(Action.OFFER_ALTERNATIVE);
        assertThat(tracker.isFinished()).isFalse();
        tracker.promptPlayed(T0.plusSeconds(8));
        assertThat(tracker.reply(Reply.ACCEPT, T0.plusSeconds(10))).isEqualTo(Action.START);
    }

    @Test
    void anUnclearReplyIsAClarificationThatUsesTheRepromptSoLaterSilenceDeclines() {
        tracker.promptPlayed(T0);
        assertThat(tracker.reply(Reply.UNCLEAR, T0.plusSeconds(4))).isEqualTo(Action.CLARIFY);
        assertThat(tracker.prompts()).isEqualTo(2);
        tracker.promptPlayed(T0.plusSeconds(8));
        assertThat(tracker.reply(Reply.UNCLEAR, T0.plusSeconds(10))).as("되묻기는 한 번").isEqualTo(Action.NONE);
        assertThat(tracker.tick(T0.plusSeconds(28))).as("되묻기가 재안내를 썼다").isEqualTo(Action.DECLINE);
    }

    @Test
    void promptsNeverExceedThreeAcrossRepromptAndAlternative() {
        tracker.promptPlayed(T0);
        assertThat(tracker.tick(T0.plusSeconds(20))).isEqualTo(Action.REPROMPT);
        tracker.promptPlayed(T0.plusSeconds(25));
        assertThat(tracker.reply(Reply.REFUSE, T0.plusSeconds(30))).isEqualTo(Action.OFFER_ALTERNATIVE);
        assertThat(tracker.prompts()).isEqualTo(JoinTracker.MAX_PROMPTS);
        tracker.promptPlayed(T0.plusSeconds(35));
        assertThat(tracker.reply(Reply.UNCLEAR, T0.plusSeconds(40))).isEqualTo(Action.NONE);
        assertThat(tracker.tick(T0.plusSeconds(55))).isEqualTo(Action.DECLINE);
        assertThat(tracker.prompts()).isEqualTo(JoinTracker.MAX_PROMPTS);
    }

    @Test
    void anAlternativeFirstStillLeavesTheRepromptForSilence() {
        tracker.promptPlayed(T0);
        assertThat(tracker.reply(Reply.REFUSE, T0.plusSeconds(2))).isEqualTo(Action.OFFER_ALTERNATIVE);
        tracker.promptPlayed(T0.plusSeconds(6));
        assertThat(tracker.tick(T0.plusSeconds(26))).isEqualTo(Action.REPROMPT);
        assertThat(tracker.prompts()).isEqualTo(3);
        tracker.promptPlayed(T0.plusSeconds(30));
        assertThat(tracker.tick(T0.plusSeconds(50))).isEqualTo(Action.DECLINE);
    }

    @Test
    void ticksBeforeAnyPromptHasPlayedDoNothingAndTheWindowMustBePositive() {
        assertThat(tracker.tick(T0.plusSeconds(600))).isEqualTo(Action.NONE);
        assertThat(tracker.phase()).isEqualTo(Phase.PROMPTING);
        assertThat(tracker.prompts()).isEqualTo(1);
        assertThatThrownBy(() -> new JoinTracker(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JoinTracker(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        tracker.reply(Reply.ACCEPT, T0);
        tracker.promptPlayed(T0.plusSeconds(1));
        assertThat(tracker.phase()).as("끝난 뒤 재생 완료는 창을 열지 않는다").isEqualTo(Phase.JOINED);
    }

    @ParameterizedTest
    @CsvSource({
        "응, ACCEPT",
        "네요, ACCEPT",
        "좋아 이야기하자, ACCEPT",
        "그래 할래, ACCEPT",
        "OK, ACCEPT",
        "싫어, REFUSE",
        "안 할래, REFUSE",
        "아니요, REFUSE",
        "안 좋아, REFUSE",
        "이거 말고 다른 거, TOPIC_REFUSAL",
        "공룡 얘기 말고, TOPIC_REFUSAL",
        "음, UNCLEAR",
        "어디야, UNCLEAR",
        "'', UNCLEAR"
    })
    void repliesAreClassifiedTopicRefusalFirstThenRefusalThenAcceptElseUnclear(String text, Reply expected) {
        assertThat(JoinTracker.classify(text)).isEqualTo(expected);
    }

    private static JoinTracker repromptedAt(Instant start) {
        JoinTracker value = new JoinTracker(WINDOW);
        value.promptPlayed(start);
        assertThat(value.tick(start.plusSeconds(21))).isEqualTo(Action.REPROMPT);
        return value;
    }
}
