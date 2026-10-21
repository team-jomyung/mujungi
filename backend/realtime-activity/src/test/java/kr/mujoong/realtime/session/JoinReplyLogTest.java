package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.realtime.dialog.JoinTracker;
import kr.mujoong.realtime.judgment.AbstainReason;
import kr.mujoong.realtime.judgment.JudgmentOutcomeKind;
import kr.mujoong.realtime.judgment.JudgmentPoint;
import kr.mujoong.realtime.judgment.JudgmentTurn;
import kr.mujoong.realtime.judgment.NextAction;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import kr.mujoong.testsupport.log.LogCapture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 참여 관문 진단 로그 시험(contracts/ai-io.md 10.15). 참여 확인 중 확정 전사마다 JOIN_REPLY 한 줄이 분류 · 할 일 코드와 전사의 모양(글자 수 ·
 * 낱말 수 · 조각 수 · 최대 간격 · 재생 중 · 창 늦음)만 담는지, 판정을 열지 못한 J1이 JUDGMENT_TURN 한 줄을 남기는지 본다. 공급자 · 판정기는
 * 가짜이고 값은 합성이다(R-F1).
 */
@Tag("join-gate")
@Tag("session-runtime")
class JoinReplyLogTest {

    private static final String INTRO = "안녕, 무중이야. 오늘 나랑 이야기할래?";

    private final SessionHarness h = new SessionHarness();

    @Test
    void aReplyFromGroupedDeltasLogsItsShapeAndActionButNeverItsWords() {
        h.boot();
        h.ackAndReady();

        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta(null, "스테고 ", 0, 300));
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta(null, "좋아", 500, 800));
        h.drain();
        h.advance(h.settings.inputTranscriptQuiet());

        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        Map<String, Object> line = onlyJoinReply();
        assertThat(line).containsEntry("joinReply", "ACCEPT").containsEntry("joinAction", "START")
                .containsEntry("devicePlaying", false).containsEntry("lateForWindow", false);
        assertThat(number(line, "prompts")).isEqualTo(1);
        assertThat(number(line, "textLen")).as("정규화 뒤 '스테고좋아'").isEqualTo(5);
        assertThat(number(line, "wordCount")).isEqualTo(2);
        assertThat(number(line, "deltaCount")).isEqualTo(2);
        assertThat(number(line, "maxGapMs")).isEqualTo(200);
        assertThat(h.logs.lines()).noneMatch(text -> text.contains("스테고"));
    }

    @Test
    void anUnclearReplyWithoutAJudgeLogsTheSkippedJ1AndTheClarification() {
        h.boot();
        h.ackAndReady();

        h.childSays("i-1", "음 브라키오");

        Map<String, Object> reply = onlyJoinReply();
        assertThat(reply).containsEntry("joinReply", "UNCLEAR").containsEntry("joinAction", "CLARIFY");
        assertThat(number(reply, "prompts")).isEqualTo(2);
        assertThat(number(reply, "deltaCount")).as("완료 사건 하나로 온 대역 전사").isZero();
        assertThat(number(reply, "maxGapMs")).isEqualTo(-1);
        List<Map<String, Object>> turns = h.logs.events(EventCode.JUDGMENT_TURN);
        assertThat(turns).singleElement().satisfies(event -> {
            Map<String, Object> attrs = LogCapture.attrs(event);
            assertThat(attrs).containsEntry("judgmentPoint", "J1").containsEntry("judgmentOutcome", "ABSTAINED")
                    .containsEntry("abstainReason", "NO_PROVIDER").containsEntry("nextAction", "REPROMPT_JOIN")
                    .doesNotContainKeys("closeReason", "firstOption");
            assertThat(event.get("component")).isEqualTo("realtime/SessionLifecycle");
        });
        assertThat(h.logs.lines()).noneMatch(text -> text.contains("브라키오"));
    }

    @Test
    void aReplyThatArrivesAfterTheWindowEdgeIsMarkedLateAndTheExpiryGoesFirst() {
        h.boot();
        h.ackAndReady();

        // 창 시계가 아직 돌지 않은 채 시각만 지나간다: 늦은 대답이 만기보다 먼저 처리되는 경계(JoinTracker 만기 우선)
        h.clock.advance(h.settings.lifecycle().joinWait().plusSeconds(1));
        h.childSays("i-1", "응");

        Map<String, Object> line = onlyJoinReply();
        assertThat(line).containsEntry("joinReply", "ACCEPT").containsEntry("joinAction", "REPROMPT")
                .containsEntry("lateForWindow", true);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.WAITING_CHILD);
    }

    @Test
    void devicePlayingIsTakenWhenTheChildStartedSpeaking() {
        h.extraApproved.put(SessionExecutor.INTRO_FREE_TALK, INTRO);
        h.boot();
        h.ackAndReady();
        PlayApprovedContent intro = h.link.sent(PlayApprovedContent.class).getFirst();
        mark(intro, PlaybackMark.Status.STARTED);

        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta(null, "응"));
        h.drain();
        mark(intro, PlaybackMark.Status.COMPLETED);
        h.advance(h.settings.inputTranscriptQuiet());

        assertThat(onlyJoinReply()).containsEntry("devicePlaying", true).containsEntry("joinAction", "START");
    }

    @Test
    void theRegisteredLogValuesFollowTheRealtimeEnums() {
        assertThat(AttrKey.JUDGMENT_POINT.enumValues()).isEqualTo(names(JudgmentPoint.values()));
        assertThat(AttrKey.JUDGMENT_OUTCOME.enumValues()).isEqualTo(names(JudgmentOutcomeKind.values()));
        assertThat(AttrKey.ABSTAIN_REASON.enumValues()).isEqualTo(names(AbstainReason.values()));
        assertThat(AttrKey.CLOSE_REASON.enumValues()).isEqualTo(names(JudgmentTurn.CloseReason.values()));
        assertThat(AttrKey.NEXT_ACTION.enumValues()).isEqualTo(names(NextAction.values()));
        assertThat(AttrKey.JOIN_REPLY_KIND.enumValues()).isEqualTo(Stream.concat(
                Arrays.stream(JoinTracker.Reply.values()).map(Enum::name), Stream.of("ECHO_SUSPECT")).collect(Collectors.toSet()));
        assertThat(AttrKey.JOIN_ACTION.enumValues()).isEqualTo(Stream.concat(
                Arrays.stream(JoinTracker.Action.values()).map(Enum::name), Stream.of("J1_OPENED", "J1_HELD"))
                .collect(Collectors.toSet()));
    }

    private Map<String, Object> onlyJoinReply() {
        List<Map<String, Object>> lines = h.logs.events(EventCode.JOIN_REPLY);
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().get("component")).isEqualTo("realtime/SessionLifecycle");
        assertThat(lines.getFirst().get("level")).isEqualTo("INFO");
        return LogCapture.attrs(lines.getFirst());
    }

    private static long number(Map<String, Object> attrs, String key) {
        return ((Number) attrs.get(key)).longValue();
    }

    private static java.util.Set<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toSet());
    }

    private void mark(PlayApprovedContent play, PlaybackMark.Status status) {
        h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                new PlaybackMark(play.playbackId(), status, null, h.clock.now(), null, null), h.clock.now()));
        h.drain();
    }
}
