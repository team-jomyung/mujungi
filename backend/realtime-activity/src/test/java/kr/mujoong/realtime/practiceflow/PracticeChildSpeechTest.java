package kr.mujoong.realtime.practiceflow;

import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.minimalScript;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.packageJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.realtime.judgment.JudgmentPoint;
import kr.mujoong.realtime.practicelegacy.PracticePorts.Playback;
import kr.mujoong.realtime.practiceflow.PracticeFlowTestKit.Harness;
import kr.mujoong.realtime.session.PracticeSessionExtension.ExtensionEvent;
import kr.mujoong.realtime.session.PracticeSessionExtension.Signal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 4회차 R1 — 아이가 말하기 시작하면 무응답 시계를 멈춘다(실측 B1: 기회 문장 뒤 10초 시계가 아이 대답 도중에 울렸다). 실행부가 알리는 '아이 말 시작'
 * ({@code onChildSpeechStarted})부터 그 말의 확정 전사까지 무응답 시계를 걸지 않고, 확정 전사는 지금처럼 판정한다. 전사 없이 끝난 말은 안전 시계
 * (무응답 대기와 같은 길이)가 끝날 때 무응답 시계를 처음부터 다시 건다. 기록하는 가짜 출력 · 수동 시계 위의 상태 기계 시험이다(테스트 대역 · 합성 대화
 * 증거이고 실제 AI · 실제 기기가 아니다, R-M11).
 */
@Tag("practice-flow")
class PracticeChildSpeechTest {

    private static final UUID GOAL = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID SCENARIO = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final String OPPORTUNITY = "친구가 말했어. 그거 줘.";
    private static final Map<String, String> APPROVED = Map.of("COACH_SWITCH", "이제 코치가 도와줄게.", "PEER_RETURN", "다시 친구로 돌아왔어.");

    private static Harness minimal() {
        return new Harness(packageJson(GOAL, minimalScript(SCENARIO)), APPROVED);
    }

    @Test
    void aChildWhoStartsSpeakingLateInTheWaitIsNotReguidedMidSentenceAndTheConfirmedTranscriptIsJudged() {
        Harness h = minimal();
        h.toOpportunity();
        int slots = h.out.slots.size();
        h.advance(Duration.ofMillis(8_500));

        h.speechStarted();

        assertThat(h.out.timers).as("말이 시작되면 무응답 시계를 멈춘다").doesNotContainKey("no-response").containsKey("child-speech");
        assertThat(h.out.scheduleLog).contains("child-speech:PT10S");
        h.advance(Duration.ofSeconds(3));
        assertThat(h.out.slots).as("본래 10초 시점이 지나도 말하는 아이에게 기회 문장을 다시 말하지 않는다").hasSize(slots);
        assertThat(h.out.judgments).as("말 시작만으로는 판정하지 않는다").isEmpty();

        h.child("그거 줘");

        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J2);
        assertThat(h.out.timers).doesNotContainKeys("child-speech", "no-response");
        assertThat(h.out.cancelLog).contains("child-speech");
        assertThat(h.failures).isEmpty();
    }

    @Test
    void aSpeechThatEndsWithoutATranscriptReArmsTheNoResponseClockFromScratchWhenTheSafetyWaitEnds() {
        Harness h = minimal();
        h.toOpportunity();
        int slots = h.out.slots.size();
        h.advance(Duration.ofSeconds(3));
        h.speechStarted();

        h.advance(Duration.ofSeconds(10).minusMillis(1));
        assertThat(h.out.timers).as("안전 시계가 끝나기 전에는 무응답 시계가 없다").doesNotContainKey("no-response");
        h.advance(Duration.ofMillis(1));
        assertThat(h.out.timers).as("빈 말이었다: 무응답 시계를 처음부터 다시 건다").containsKey("no-response").doesNotContainKey("child-speech");
        assertThat(h.out.timers.get("no-response")).isEqualTo(h.clock.now().plusSeconds(10));
        assertThat(h.out.slots).hasSize(slots);

        h.advance(Duration.ofSeconds(9));
        assertThat(h.out.slots).as("다시 건 시계의 9초에는 재안내하지 않는다").hasSize(slots);
        h.advance(Duration.ofSeconds(1));
        assertThat(h.out.slots).as("다시 건 시계가 끝나면 지금처럼 같은 질문을 한 번 다시 안내한다").hasSize(slots + 1);
        assertThat(h.out.lastSlot().text()).isEqualTo(OPPORTUNITY);
        assertThat(h.out.judgments).isEmpty();
    }

    @Test
    void eachNewSpeechStartRestartsTheSafetyWait() {
        Harness h = minimal();
        h.toOpportunity();
        h.speechStarted();
        h.advance(Duration.ofSeconds(6));

        h.speechStarted();

        assertThat(h.out.timers.get("child-speech")).as("말이 다시 시작된 때부터 다시 잰다").isEqualTo(h.clock.now().plusSeconds(10));
        h.advance(Duration.ofSeconds(9));
        assertThat(h.out.timers).doesNotContainKey("no-response");
        h.child("그거 줘");
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J2);
    }

    @Test
    void aSpeechStartedWhileTheOpportunityLineStillPlaysKeepsTheClockOffAfterTheLineUntilItsTranscriptArrives() {
        Harness h = minimal();
        h.activate();
        h.played();
        assertThat(h.out.lastSlot().text()).isEqualTo(OPPORTUNITY);

        h.speechStarted();
        h.played();

        assertThat(h.out.timers).as("우리 말이 끝나도 아이가 말하는 중이면 무응답 시계를 걸지 않는다").doesNotContainKey("no-response");
        h.advance(Duration.ofSeconds(4));
        h.child("그거 줘");
        assertThat(h.out.lastJudgment().point()).as("재생 뒤에 확정된 전사를 곧바로 판정한다").isEqualTo(JudgmentPoint.J2);
        assertThat(h.out.timers).doesNotContainKey("child-speech");
    }

    @Test
    void aTranscriptConfirmedWhileTheLineStillPlaysIsJudgedWhenTheLineEndsAsBefore() {
        Harness h = minimal();
        h.activate();
        h.played();
        h.speechStarted();
        h.child("그거 줘");
        assertThat(h.out.judgments).as("재생 중에는 판정하지 않는다").isEmpty();
        assertThat(h.out.timers).doesNotContainKey("child-speech");

        h.played();

        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J2);
        assertThat(h.out.judgments).hasSize(1);
        assertThat(h.out.timers).doesNotContainKey("no-response");
    }

    @Test
    void aSpeechThatStartsDuringJudgmentOpensNoSecondJudgmentAndItsTranscriptIsJudgedAfterTheReask() {
        Harness h = minimal();
        h.toOpportunity();
        h.child("그거 줘");
        assertThat(h.out.judgments).hasSize(1);

        h.speechStarted();
        assertThat(h.out.judgments).as("판정 중의 말 시작은 판정을 더 열지 않는다").hasSize(1);
        h.result("J2", "PARTIAL");
        assertThat(h.out.lastSlot().generated()).as("친구 되묻기").isTrue();
        h.played();

        assertThat(h.out.timers).as("판정 중에 시작된 말이 아직 확정되지 않았다").doesNotContainKey("no-response");
        h.child("파란 거");
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J3);
        assertThat(h.failures).isEmpty();
    }

    @Test
    void aSpeechStartedDuringJudgmentThatEndsWithoutATranscriptStillGetsTheNoResponseClockAfterTheSafetyWait() {
        Harness h = minimal();
        h.toOpportunity();
        h.child("그거 줘");
        h.speechStarted();
        h.result("J2", "PARTIAL");
        h.played();
        int slots = h.out.slots.size();

        h.advance(Duration.ofSeconds(10));

        assertThat(h.out.timers).containsKey("no-response");
        h.advance(Duration.ofSeconds(10));
        assertThat(h.out.slots).as("되묻기를 한 번 다시 안내한다").hasSize(slots + 1);
    }

    @Test
    void aTranscriptTheFlowDoesNotUseStillEndsTheSpeechSoTheNextWaitArmsTheClockAsBefore() {
        Harness h = minimal();
        h.activate();
        h.speechStarted();
        h.child("뭐라고?");
        assertThat(h.out.judgments).as("도입 중 전사는 대답이 아니다").isEmpty();
        assertThat(h.out.timers).doesNotContainKey("child-speech");

        h.played();
        h.played();

        assertThat(h.out.timers).as("기회 문장이 끝나면 지금처럼 무응답 시계를 건다").containsKey("no-response");
        assertThat(h.out.scheduleLog).contains("no-response:PT10S");
    }

    @Test
    void closingDropsTheSafetyWaitAndALateExpiryDoesNothing() {
        Harness h = minimal();
        h.toOpportunity();
        h.speechStarted();

        h.event(ExtensionEvent.Kind.REST_REQUESTED, "rest", null, null);

        assertThat(h.out.timers).doesNotContainKeys("child-speech", "no-response");
        int slots = h.out.slots.size();
        assertThat(h.event(ExtensionEvent.Kind.TIMER_FIRED, "child-speech", null, null)).isEqualTo(Signal.NONE);
        assertThat(h.out.timers).doesNotContainKey("no-response");
        assertThat(h.out.slots).hasSize(slots);
        assertThat(h.speechStarted()).isEqualTo(Signal.NONE);
        assertThat(h.out.timers).as("닫힌 뒤의 말 시작은 무시한다").doesNotContainKey("child-speech");
    }

    @Test
    void beforeTheFlowIsActiveASpeechStartIsHarmless() {
        Harness h = minimal();

        assertThat(h.speechStarted()).isEqualTo(Signal.NONE);

        assertThat(h.out.calls).isZero();
    }

    @Test
    void anInterruptedOpportunityLineWithSpeechKeepsWaitingForThatSpeech() {
        Harness h = minimal();
        h.activate();
        h.played();
        h.speechStarted();

        h.end(Playback.INTERRUPTED);

        assertThat(h.out.timers).as("끼어든 말이 아직 확정되지 않았다").doesNotContainKey("no-response");
        h.child("그거 줘");
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J2);
    }
}
