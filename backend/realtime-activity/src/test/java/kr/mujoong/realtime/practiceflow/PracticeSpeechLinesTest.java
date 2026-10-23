package kr.mujoong.realtime.practiceflow;

import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.fullScript;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.minimalScript;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.packageJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.realtime.internalhttp.InternalHttpClient;
import kr.mujoong.realtime.practicelegacy.PracticePorts.Playback;
import kr.mujoong.realtime.practiceflow.PracticeFlowTestKit.Harness;
import kr.mujoong.realtime.practiceflow.PracticeFlowTestKit.Slot;
import kr.mujoong.realtime.practiceflow.PracticeScript.Scene;
import kr.mujoong.realtime.practiceflow.StandardPracticeDialogue.ChildTurn;
import kr.mujoong.realtime.session.NoOpPracticeSessionExtension;
import kr.mujoong.realtime.session.PracticeSessionExtension;
import kr.mujoong.realtime.session.PracticeSessionExtension.SpeechLine;
import kr.mujoong.realtime.session.PracticeSessionExtension.SpeechVoice;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 3회차 V1: 새 연습 흐름이 실행부에 알려 주는 승인 원문 대본 줄(실행부가 참여 확인 동안 음성을 미리 만든다, contracts/ai-io.md 10.16). 줄의 말하는 사람
 * 표시 · 원문이 낼 때의 {@code speak}와 글자 그대로 같은지, 기준 대화가 승인 원문으로 내는 모든 줄이 미리 알려 준 줄 안에 있는지, 낼 차례가 이른 줄부터
 * 오는지, 다음 목표 대본은 도착할 때 맡기는지, 레거시 · 대본 없는 패키지는 줄이 없는지 본다. 가짜 출력 위의 합성 대화다(테스트 대역 증거, R-M11).
 */
@Tag("practice-flow")
class PracticeSpeechLinesTest {

    private static final Map<String, String> APPROVED = Map.of("HELP_OFFER", "무중이가 조금 도와줄까?", "COACH_SWITCH", "잠깐, 무중이 도와줄게",
            "PEER_RETURN", "다시 친구 역할로 돌아갈게");
    private static final UUID GOAL = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID GOAL_TWO = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID SCENARIO = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID SCENARIO_TWO = UUID.fromString("00000000-0000-4000-8000-0000000000a2");
    private static final String FRIEND = "같은 모둠 친구";

    private final StandardPracticeDialogue standard = StandardPracticeDialogue.load();

    @Test
    void theStandardScriptListsItsApprovedLinesInTheOrderTheyComeUp() {
        Scene base = standard.script().baseScene();
        Scene variant = standard.script().variantScene().orElseThrow();
        Harness h = new Harness(standard.practiceJson(), APPROVED);

        List<SpeechLine> lines = h.ext.speechLines(standard.practiceJson(), APPROVED);

        assertThat(lines).containsExactly(
                new SpeechLine("무중", base.sceneDescription(), SpeechVoice.NARRATOR),
                new SpeechLine(FRIEND, "알았어, 잠깐만!", SpeechVoice.PEER),
                new SpeechLine(FRIEND, "자, 여기 초록색!", SpeechVoice.PEER),
                new SpeechLine("무중", base.reexplanation(), SpeechVoice.NARRATOR),
                new SpeechLine("무중", "무중이가 조금 도와줄까?", SpeechVoice.NARRATOR),
                new SpeechLine("무중", variant.sceneDescription() + " " + PracticeInstructions.VARIANT_OFFER_QUESTION, SpeechVoice.NARRATOR),
                new SpeechLine(FRIEND, variant.opportunitySentence(), SpeechVoice.PEER),
                new SpeechLine("무중", variant.reexplanation(), SpeechVoice.NARRATOR));
        assertThat(h.ext.progress()).as("묻기만 해서는 상태가 바뀌지 않는다(onActive 전)").isNull();
        assertThat(h.out.calls).isZero();
        assertThat(h.ext.speechLines(standard.practiceJson(), APPROVED)).isEqualTo(lines);
        assertThat(lines.getFirst().toString()).doesNotContain(base.sceneDescription());
    }

    @Test
    void everyApprovedLineTheStandardDialoguesSpeakIsAmongTheLinesListedAtTheJoin() {
        List<SpeechLine> listed = new Harness(standard.practiceJson(), APPROVED).ext.speechLines(standard.practiceJson(), APPROVED);
        List<Harness> runs = List.of(standardRun(), stuckRun());

        for (Harness run : runs) {
            List<SpeechLine> spoken = new ArrayList<>();
            for (Slot slot : run.out.slots) {
                if (!slot.generated()) {
                    spoken.add(new SpeechLine(slot.speech().partnerRole(), slot.speech().text(),
                            "무중".equals(slot.speech().partnerRole()) ? SpeechVoice.NARRATOR : SpeechVoice.PEER));
                }
            }
            assertThat(spoken).as("승인 원문으로 낸 줄이 있다").isNotEmpty();
            assertThat(listed).as("낸 줄은 모두 참여 확인 때 알려 준 줄이다(말하는 사람 표시 · 원문 글자 그대로)").containsAll(spoken);
        }
        assertThat(runs.get(1).out.slots).as("도움 제안(HELP_OFFER)도 승인 원문 줄이다")
                .anyMatch(slot -> !slot.generated() && slot.text().equals(APPROVED.get("HELP_OFFER")));
    }

    @Test
    void anOldScriptWithoutAPartnerOpenerListsTheVariantDescriptionAloneAndNoHelpOfferWithoutItsSentence() {
        String practice = packageJson(GOAL, fullScript(SCENARIO));
        Harness h = new Harness(practice, Map.of());

        List<SpeechLine> lines = h.ext.speechLines(practice, Map.of());

        assertThat(lines).extracting(SpeechLine::text).containsExactly("친구가 상자 두 개를 들고 있어.", "친구가 말했어. 그거 줘.",
                "친구는 빨간 상자와 파란 상자를 들고 있어.", "동생이 책을 들고 있어.", "동생이 말했어. 그거 줘.", "동생은 두 권을 들고 있어.");
        assertThat(lines).extracting(SpeechLine::speaker).containsExactly("무중", "같은 반 친구", "무중", "무중", "동생", "무중");
        assertThat(lines).extracting(SpeechLine::text).as("옛 대본의 변형 제안은 생성형이라 승인 줄이 아니다")
                .noneMatch(text -> text.contains(PracticeInstructions.VARIANT_OFFER_QUESTION));
    }

    @Test
    void theNextGoalScriptIsHandedOverForItsVoicesWhenItArrives() throws Exception {
        Harness h = new Harness(twoGoalPackage(), APPROVED);
        when(h.http.send(eq(InternalHttpClient.Target.PRACTICE_SCENARIO), any()))
                .thenReturn(new InternalHttpClient.Response(200, minimalScript(SCENARIO_TWO)));

        h.activate();
        assertThat(h.out.prepared).as("첫 대본 줄은 실행부가 참여 확인 때 물었다").isEmpty();
        h.deliverPosted();

        assertThat(h.out.prepared).singleElement().satisfies(batch -> assertThat(batch).extracting(SpeechLine::text)
                .containsExactly("친구가 상자 두 개를 들고 있어.", "친구가 말했어. 그거 줘.", "친구는 빨간 상자와 파란 상자를 들고 있어.",
                        APPROVED.get("HELP_OFFER")));
        assertThat(h.out.slots).as("다음 목표는 아직 시작하지 않았다(맡기기만 한다)").hasSize(1);
    }

    @Test
    void aPackageWhoseFirstScriptIsMissingOrRejectedListsNoLines() {
        String rejected = packageJson(GOAL, "{\"scenarioId\":\"" + SCENARIO
                + "\",\"kind\":\"ORIGINAL\",\"scriptVersion\":\"scenario-script.v1\",\"scenes\":[]}");
        assertThat(new Harness(rejected, APPROVED).ext.speechLines(rejected, APPROVED)).isEmpty();
        String none = packageJson(GOAL, null);
        assertThat(new Harness(none, APPROVED).ext.speechLines(none, APPROVED)).isEmpty();
    }

    @Test
    void theRouterChoosesItsDelegateEarlyAndTheLegacyFlowListsNothing() {
        PracticeScriptRequester requester = new PracticeScriptRequester(mock(InternalHttpClient.class), Runnable::run,
                Duration.ofSeconds(1));
        PracticeSessionExtension legacy = NoOpPracticeSessionExtension.FACTORY.create(UUID.randomUUID());
        UUID session = UUID.randomUUID();
        PracticeFlowRouter router = new PracticeFlowRouter(legacy, () -> new PracticeConversation(session, UUID.randomUUID(),
                new kr.mujoong.testsupport.time.MutableClock(), PracticeFlowTestKit.defaults(), requester,
                new PracticeFlowTestKit.MemoryRecorder(), failure -> { }));

        assertThat(router.speechLines(standard.practiceJson(), APPROVED)).hasSize(8);
        assertThat(router.toString()).contains("chosen=flow");
        PracticeFlowRouter legacyRouter = new PracticeFlowRouter(legacy, () -> {
            throw new AssertionError("새 흐름을 만들지 않는다");
        });
        String legacyPackage = "{\"goals\":[{\"goalSelectionId\":\"" + GOAL + "\",\"order\":1,\"displayName\":\"부탁하기\","
                + "\"judgmentCriteria\":\"원하는 것을 말로 요청한다\"}]}";
        assertThat(legacyRouter.speechLines(legacyPackage, APPROVED)).as("레거시는 기본값(없음)").isEmpty();
        assertThat(legacyRouter.toString()).contains("chosen=legacy");
        assertThatThrownBy(() -> new PracticeFlowRouter(legacy, () -> legacy).speechLines(" ", APPROVED))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- 보조 ----

    private Harness standardRun() {
        Harness h = new Harness(standard.practiceJson(), APPROVED);
        List<ChildTurn> child = standard.childTurns();
        h.toOpportunityThroughOpener();
        answer(h, child.get(0));
        h.played();
        answer(h, child.get(1));
        h.played();
        h.played();
        answer(h, child.get(2));
        h.played();
        answer(h, child.get(3));
        h.announcedNotice(Playback.COMPLETED);
        h.played();
        answer(h, child.get(4));
        h.announcedNotice(Playback.COMPLETED);
        h.played();
        return h;
    }

    /** 장면 2에서 막힌 아이에게 무중이 역할 밖에서 도움을 제안하는 길(HELP_OFFER 승인 발화). */
    private Harness stuckRun() {
        Harness h = new Harness(standard.practiceJson(), APPROVED);
        List<ChildTurn> child = standard.childTurns();
        h.toOpportunityThroughOpener();
        answer(h, child.get(0));
        h.played();
        answer(h, child.get(1));
        h.played();
        h.played();
        answer(h, child.get(2));
        h.played();
        h.child(child.get(3).text());
        h.unsure("J2", "LOW_CONFIDENCE", "HELP");
        h.played();
        return h;
    }

    private static void answer(Harness h, ChildTurn turn) {
        h.child(turn.text());
        h.result(turn.point(), turn.option());
    }

    private static String twoGoalPackage() {
        return "{\"goals\":[{\"goalSelectionId\":\"" + GOAL + "\",\"order\":1,\"displayName\":\"부탁하기\","
                + "\"judgmentCriteria\":\"원하는 것을 말로 요청한다\",\"firstScenario\":" + minimalScript(SCENARIO) + "},"
                + "{\"goalSelectionId\":\"" + GOAL_TWO + "\",\"order\":2,\"displayName\":\"거절하기\","
                + "\"judgmentCriteria\":\"싫다는 뜻을 말로 전한다\"}]}";
    }
}
