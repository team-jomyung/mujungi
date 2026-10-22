package kr.mujoong.realtime.practiceflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.judgment.JudgmentPoint;
import kr.mujoong.realtime.practicelegacy.PracticePorts.Playback;
import kr.mujoong.realtime.practiceflow.PracticeFlowTestKit.Harness;
import kr.mujoong.realtime.practiceflow.PracticeFlowTestKit.Judgment;
import kr.mujoong.realtime.practiceflow.PracticeFlowTestKit.Slot;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.ConsentAnswer;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.FactKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.HelpKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.HelpRecord;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.LiveTiming;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneRunRecord;
import kr.mujoong.realtime.practiceflow.PracticeScript.Scene;
import kr.mujoong.realtime.practiceflow.StandardPracticeDialogue.ChildTurn;
import kr.mujoong.realtime.session.PracticeSessionExtension.Signal;
import kr.mujoong.realtime.testing.InternalApi;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 팀장 기준 연습 대화를 대역 시험 하나로 재현한다(docs/practice-dialogue-standard.md). 고정 대본(크레용 · 우유)과 기준 대화의 아이 대사 · 기대 판정을
 * 그대로 주고, 상태 기계가 내는 차례가 기준 대화와 같은 순서 · 같은 말하는 사람인지 본다.
 *
 * <ol>
 *   <li>장면 1: 무중의 설명 → 친구 첫 대사 → 친구 기회 문장 → (일부 전달) 친구 되묻기 → (전달됨) 친구 반응 — 칭찬 · 채점 없음.
 *   <li>장면 2: 무중의 변형 제안 → (수락) 친구 기회 문장 → (도움 요청) 전환 알림 → 무중(코치)의 요점 + 말틀 → (다시 말함) 복귀 알림 → 친구 반응.
 * </ol>
 *
 * <p>가짜 출력 · 가짜 판정 위의 합성 대화다(테스트 대역 증거). 친구 · 코치 대사는 실제 모델이 만들지 않고 지시문만 확인한다 — 실제 말은
 * 실호출 하네스({@code PracticeDialogueLiveTest})의 대화 기록으로 사람이 본다(R-M11).
 */
@Tag("practice-flow")
class StandardPracticeDialogueTest {

    /** 운영 시드와 같은 승인 발화(infra/seed/004-u8-reference.sql). */
    private static final Map<String, String> APPROVED = Map.of("HELP_OFFER", "무중이가 조금 도와줄까?", "COACH_SWITCH", "잠깐, 무중이 도와줄게",
            "PEER_RETURN", "다시 친구 역할로 돌아갈게", "D_REST_ACK", "잠깐 쉬고 싶구나. 천천히 해도 괜찮아.");
    private static final String FRIEND = "같은 모둠 친구";
    private static final List<String> PRAISE = List.of("잘 마쳤", "잘했", "훌륭", "멋지", "최고", "정답이야", "맞았어", "맞아요");
    private static final JsonMapper TREE = JsonMapper.builder().build();

    private final StandardPracticeDialogue standard = StandardPracticeDialogue.load();
    private final Scene base = standard.script().baseScene();
    private final Scene variant = standard.script().variantScene().orElseThrow();

    @Test
    void theFixedStandardScriptIsAV6ContractScriptWhoseHeardLinesNeverCarryTheTargetExpression() {
        assertThat(InternalApi.schemaErrors("practice-scenario.schema.json", standard.firstScenarioJson())).isEmpty();
        assertThat(standard.childTurns()).extracting(ChildTurn::point).containsExactly("J2", "J3", "J4", "J2", "J5");
        assertThat(base.hasPartnerOpener()).isTrue();
        assertThat(variant.hasPartnerOpener()).as("변형은 첫 대사 없이 친구 기회 문장으로 시작한다").isFalse();
        assertThat(SceneIntro.carriesOpportunity(base.sceneDescription(), base.opportunitySentence())).isFalse();
        for (Scene scene : List.of(base, variant)) {
            String target = SceneIntro.normalize(scene.expressionExamples().getFirst());
            for (String heard : List.of(scene.sceneDescription(), scene.partnerOpener(), scene.opportunitySentence(), scene.reexplanation())) {
                assertThat(SceneIntro.normalize(heard)).as("아이가 말하기 전에 듣는 말에 표현 예시가 그대로 없다").doesNotContain(target);
            }
        }
    }

    @Test
    void theStandardDialogueRunsTurnByTurnInTheStandardOrderWithTheStandardSpeakers() {
        Harness h = new Harness(standard.practiceJson(), APPROVED);
        List<ChildTurn> child = standard.childTurns();

        // [장면 1] 무중: 상황 설명(역할 밖) → 친구: 알았어, 잠깐만! → 친구: 자, 여기 초록색!
        h.activate();
        expectApproved(h, RolePolicy.NARRATOR_LABEL, base.sceneDescription());
        assertThat(h.ext.progress().goalName()).isEqualTo("다시 말해 바로잡기");
        h.played();
        expectApproved(h, FRIEND, "알았어, 잠깐만!");
        h.played();
        expectApproved(h, FRIEND, "자, 여기 초록색!");
        h.played();

        // 아이: 어… 이거 아닌데. → J2 일부 전달 → 친구: 되묻기(D_REASK)
        answer(h, child.get(0));
        expectGenerated(h, "PEER", PracticeInstructions.peerReask(base, false));
        h.played();
        // 아이: 파란색! 나 파란색 달라고 했어. → J3 전달됨 → 친구: 역할 안의 반응(D_CONTINUE), 칭찬 · 채점 없음
        answer(h, child.get(1));
        expectGenerated(h, "PEER", PracticeInstructions.peerReaction(base));
        h.played();

        // [장면 2] 무중: 그림 다 그렸네. … 딸기 우유를 달라고 했어. 이어서 해 볼까?(D_VARIANT_OFFER)
        expectApproved(h, RolePolicy.NARRATOR_LABEL, variant.sceneDescription() + " 이어서 해 볼까?");
        h.played();
        // 아이: 응. → J4 수락 → 친구: 자, 초코 우유 여기!(D_VARIANT_START, 설명을 다시 읽지 않는다)
        answer(h, child.get(2));
        expectApproved(h, FRIEND, "자, 초코 우유 여기!");
        assertThat(h.ext.progress().stage()).isEqualTo("NEW_SCENARIO_RESPONSE");
        h.played();
        // 아이: 음… 몰라. 뭐라고 해? → J2 도움 요청 → 무중: 잠깐, 무중이 도와줄게.(승인 알림) → 요점 + 말틀(D_FOCUS)
        answer(h, child.get(3));
        assertThat(h.out.timeline.getLast()).isEqualTo("ANNOUNCE:COACH");
        h.announcedNotice(Playback.COMPLETED);
        String coach = PracticeInstructions.coachHelp(variant, RolePolicy.Example.FRAME);
        expectGenerated(h, "COACH", coach);
        assertThat(coach).contains("이 요점 하나만 쉬운 말로 짧게 알려 준다: 달라고 한 것과 받은 것이 같은지 생각해 보기",
                "'뭐뭐'로 비운 말틀로 바꿔 하나만 들어 준다: 아니, 나 딸기 우유 달라고 했어.", "마지막에 아이 말로 해 보자고 한다");
        h.played();
        // 아이: 아니, 나 딸기 우유 달라고 했어. → J5 → 무중: 다시 친구 역할로 돌아갈게.(승인 알림) → 친구: 반응(D_RESUME)
        answer(h, child.get(4));
        assertThat(h.out.timeline.getLast()).isEqualTo("ANNOUNCE:PEER");
        h.announcedNotice(Playback.COMPLETED);
        expectGenerated(h, "PEER", PracticeInstructions.peerReaction(variant));
        Signal end = h.played();

        assertThat(end).as("목표가 하나라 기준 대화가 끝나면 연습을 마친다").isEqualTo(Signal.LAST_GOAL);
        assertThat(turns(h)).containsExactly(
                "무중: " + base.sceneDescription(),
                FRIEND + ": 알았어, 잠깐만!",
                FRIEND + ": 자, 여기 초록색!",
                "판정 J2",
                "친구(생성): 되묻기",
                "판정 J3",
                "친구(생성): 반응",
                "무중: " + variant.sceneDescription() + " 이어서 해 볼까?",
                "판정 J4",
                FRIEND + ": 자, 초코 우유 여기!",
                "판정 J2",
                "무중(승인 알림): COACH",
                "코치(생성): 요점 + 말틀",
                "판정 J5",
                "무중(승인 알림): PEER",
                "친구(생성): 반응");
        assertThat(h.failures).isEmpty();
        assertTheRecordShowsAfterReaskThenAfterHelp(h, ConsentAnswer.REQUESTED);
    }

    @Test
    void theStuckChildOfSceneTwoMayInsteadBeOfferedHelpByMujoongOutOfRoleBeforeTheCoachHelps() {
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

        // 아이: 음… 몰라. 뭐라고 해? → 확신이 모자란 보류(1순위 도움 요청) → 무중: 무중이가 조금 도와줄까?(D_OFFER_HELP, 승인 발화)
        h.child(child.get(3).text());
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J2);
        h.unsure("J2", "LOW_CONFIDENCE", "HELP");
        expectApproved(h, RolePolicy.NARRATOR_LABEL, "무중이가 조금 도와줄까?");
        assertThat(h.ext.progress().stage()).isEqualTo("NEW_SCENARIO_RESPONSE");
        h.played();
        h.child("응, 도와줘.");
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J4);
        h.result("J4", "ACCEPT");
        assertThat(h.out.timeline.getLast()).isEqualTo("ANNOUNCE:COACH");
        h.announcedNotice(Playback.COMPLETED);
        expectGenerated(h, "COACH", PracticeInstructions.coachHelp(variant, RolePolicy.Example.FRAME));
        h.played();
        answer(h, child.get(4));
        h.announcedNotice(Playback.COMPLETED);
        expectGenerated(h, "PEER", PracticeInstructions.peerReaction(variant));

        assertThat(h.played()).isEqualTo(Signal.LAST_GOAL);
        assertThat(h.out.generatedSlots()).as("도움 제안에 친구 생성형 발화를 쓰지 않는다")
                .noneMatch(slot -> slot.text().contains("도와줘도 되는지"));
        assertTheRecordShowsAfterReaskThenAfterHelp(h, ConsentAnswer.ACCEPT);
    }

    @Test
    void nothingTheStandardDialogueSpeaksOrInstructsPraisesOrScoresTheChild() {
        Harness h = new Harness(standard.practiceJson(), APPROVED);
        h.activate();
        for (ChildTurn turn : standard.childTurns()) {
            // 아이 말을 기다릴 때까지 지금 슬롯(재생 · 생성형 · 승인 알림)을 끝까지 들려준다
            for (int step = 0; !waitingForTheChild(h); step++) {
                assertThat(step).as("기다리는 차례에 닿지 못했다").isLessThan(10);
                if (h.out.timeline.getLast() instanceof String) {
                    h.announcedNotice(Playback.COMPLETED);
                } else {
                    h.played();
                }
            }
            answer(h, turn);
        }
        h.announcedNotice(Playback.COMPLETED);
        h.played();

        List<String> spoken = new ArrayList<>();
        h.out.slots.forEach(slot -> spoken.add(slot.text()));
        APPROVED.values().forEach(spoken::add);
        assertThat(spoken).isNotEmpty().allSatisfy(text -> assertThat(text).doesNotContain(PRAISE.toArray(new String[0])));
        assertThat(h.out.generatedSlots()).filteredOn(slot -> slot.role().equals("PEER"))
                .allSatisfy(slot -> assertThat(slot.text()).contains("고쳐 주"));
    }

    // ---- 보조 ----

    private static boolean waitingForTheChild(Harness h) {
        FlowState state = h.ext.state();
        return state.phase().awaitsInput() && state.sub() == FlowState.Sub.NONE && h.out.timers.containsKey("no-response");
    }

    private static void answer(Harness h, ChildTurn turn) {
        int before = h.out.judgments.size();
        h.child(turn.text());
        assertThat(h.out.judgments).as("아이 차례 '%s'는 판정한다", turn.text()).hasSize(before + 1);
        assertThat(h.out.lastJudgment().point().name()).isEqualTo(turn.point());
        h.result(turn.point(), turn.option());
    }

    private static void expectApproved(Harness h, String speaker, String text) {
        Slot slot = h.out.lastSlot();
        assertThat(slot.generated()).as("승인 원문 재생: %s", text).isFalse();
        assertThat(slot.text()).isEqualTo(text);
        assertThat(slot.speech().partnerRole()).as("말하는 사람 표시").isEqualTo(speaker);
        assertThat(slot.speech().matches(text.replace(" ", ""))).as("원문 일치로 제브를 건너뛴다").isTrue();
    }

    private static void expectGenerated(Harness h, String role, String instruction) {
        Slot slot = h.out.lastSlot();
        assertThat(slot.generated()).isTrue();
        assertThat(slot.role()).isEqualTo(role);
        assertThat(slot.text()).isEqualTo(instruction);
    }

    /** 출력 능력 호출 순서를 사람이 읽는 차례로 바꾼다(생성형은 지시문 종류만). */
    private List<String> turns(Harness h) {
        List<String> turns = new ArrayList<>();
        for (Object event : h.out.timeline) {
            switch (event) {
                case Slot slot when !slot.generated() -> turns.add(slot.speech().partnerRole() + ": " + slot.text());
                case Slot slot -> turns.add(kind(slot));
                case Judgment judgment -> turns.add("판정 " + judgment.point().name());
                case String announce -> turns.add("무중(승인 알림): " + announce.substring("ANNOUNCE:".length()));
                default -> turns.add("?");
            }
        }
        return turns;
    }

    private String kind(Slot slot) {
        for (Scene scene : List.of(base, variant)) {
            if (slot.text().equals(PracticeInstructions.peerReask(scene, false))) {
                return "친구(생성): 되묻기";
            }
            if (slot.text().equals(PracticeInstructions.peerReaction(scene))) {
                return "친구(생성): 반응";
            }
            if (slot.text().equals(PracticeInstructions.coachHelp(scene, RolePolicy.Example.FRAME))) {
                return "코치(생성): 요점 + 말틀";
            }
        }
        return slot.role() + "(생성): 기타";
    }

    private static List<FactKind> facts(SceneRunRecord row) {
        return row.facts().stream().map(PracticeFlowTypes.Fact::kind).toList();
    }

    /** 장면 1 = 되묻기 뒤 전달(AFTER_REASK), 장면 2 = 도움 뒤 전달(AFTER_HELP). core 보고서가 이 값을 그 문구로 보여 준다(MixedReportIT). */
    private static void assertTheRecordShowsAfterReaskThenAfterHelp(Harness h, ConsentAnswer consent) {
        List<SceneRunRecord> rows = h.recorder.list();
        assertThat(rows).extracting(SceneRunRecord::sceneKind).containsExactly(SceneKind.BASE, SceneKind.VARIANT);
        assertThat(rows.get(0).liveTiming()).as("장면 1: 되묻기 뒤 전달").isEqualTo(LiveTiming.AFTER_REASK);
        assertThat(rows.get(0).variantSkipReason()).as("변형을 했다").isNull();
        assertThat(facts(rows.get(0))).containsExactly(FactKind.REASK_RESOLVED);
        assertThat(rows.get(1).liveTiming()).as("장면 2: 도움 뒤 전달").isEqualTo(LiveTiming.AFTER_HELP);
        List<FactKind> variantFacts = facts(rows.get(1));
        assertThat(variantFacts).containsSubsequence(FactKind.COACH_SWITCH, FactKind.FOCUS_HELP, FactKind.EXPRESSION_EXAMPLE,
                FactKind.COACH_RETURN, FactKind.ORIGINAL_SCENE_RETURN, FactKind.VARIANT_PLAYED);
        assertThat(variantFacts).doesNotContain(FactKind.PLAYBACK_FAILED, FactKind.TIMEOUT, FactKind.TRANSCRIPT_FAILED);
        assertThat(h.recorder.helps).extracting(HelpRecord::kind).containsExactly(HelpKind.ROLE_SWITCH, HelpKind.FOCUS_HELP,
                HelpKind.ROLE_RETURN);
        assertThat(h.recorder.helps.getFirst().consentAnswer()).isEqualTo(consent);
        assertThat(h.recorder.helps.get(1).exampleGiven()).as("말틀도 표현 예시를 준 도움으로 남긴다").isTrue();

        String json = JsonCodec.shared().write(h.ext.practiceRecord());
        assertThat(InternalApi.schemaErrors("practice-record-v2.schema.json", json)).isEmpty();
        JsonNode scenes = TREE.readTree(json).path("scenes");
        assertThat(scenes.get(0).path("liveOutcome").path("timing").asString()).isEqualTo("AFTER_REASK");
        assertThat(scenes.get(1).path("liveOutcome").path("timing").asString()).isEqualTo("AFTER_HELP");
    }
}
