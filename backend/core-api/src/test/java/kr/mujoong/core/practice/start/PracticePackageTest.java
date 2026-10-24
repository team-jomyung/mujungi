package kr.mujoong.core.practice.start;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.practice.*;
import kr.mujoong.core.textai.ScenarioScript;
import kr.mujoong.core.web.support.WebFixtures;
import org.junit.jupiter.api.*;

@Tag("scenario-generation") class PracticePackageTest {
    final PreparedPracticePackageMapper mapper=new PreparedPracticePackageMapper();
    final UUID goal=UUID.randomUUID(),session=UUID.randomUUID(),key=UUID.randomUUID();
    FrozenGoal frozen(boolean candidate){var c=candidate?FrozenGoalTest.candidate():GoalContext.legacy("ACCEPT_STOP",1);return new FrozenGoal(goal,"목표",c.carrierKey(),c.carrierVersion(),candidate?c.criteria():"기준",1,1,1,false,1,1,1,c);}
    Scenario scenario(boolean extended){return new Scenario(UUID.randomUUID(),"ORIGINAL","PEER","기회","상황","역할","말",new Scenario.Validation(true,true,false),extended?"scenario-script.v1":null,extended?List.of(new ScenarioScript.Scene("base","BASE","상황","말","설명",List.of(),List.of(),"PEER","교실","놀이",null,"역할")):null,extended?new Scenario.Review("APPROVE","fake","scenario-review.v1"):null,extended?new Scenario.Generation("fake","scenario-generation.v2",false,2):null);}
    PreparedPracticePackageMapper.PreparedSnapshot snapshot(boolean candidate,boolean extended){return new PreparedPracticePackageMapper.PreparedSnapshot(session,key,List.of(frozen(candidate)),scenario(extended));}
    @Test void firstBaseKeepsAllEightLegacyFields(){assertThat(mapper.map(snapshot(false,true)).firstScenario()).containsKeys("scenarioId","kind","relationType","opportunityCondition","scenarioText","partnerRoleText","triggerScript","validation");}
    @Test void extendedFieldsReachInitialPackage(){assertThat(mapper.map(snapshot(false,true)).firstScenario()).containsKeys("scriptVersion","scenes","selfReview","generation");}
    @Test void oldRowsDoNotInventOptionalFields(){assertThat(mapper.map(snapshot(false,false)).firstScenario()).doesNotContainKeys("scriptVersion","scenes","selfReview","generation");}
    @Test void candidateCarrierAndTypedContextReachC8(){var c8=mapper.c8(snapshot(true,true));var first=(Map<?,?>)((List<?>)c8.get("goals")).getFirst();assertThat(first.get("behaviorKey")).isEqualTo("candidate:synthetic-candidate");assertThat(first.get("candidateVersion")).isEqualTo(2);assertThat(first.get("goalContext")).isEqualTo(FrozenGoalTest.candidate());assertThat(first.get("firstScenario")).isNotNull();}
    @Test void scenarioPayloadMatchesCurrentWireSchema(){String body=JsonCodec.shared().write(mapper.map(snapshot(true,true)).firstScenario());assertThat(WebFixtures.schemaErrors("practice-scenario.schema.json",body)).isEmpty();}
    @Test void oldBehaviorWireValuesStayUnchanged(){var first=(Map<?,?>)((List<?>)mapper.c8(snapshot(false,false)).get("goals")).getFirst();assertThat(first.get("behaviorKey")).isEqualTo("ACCEPT_STOP");assertThat(first.get("behaviorVersion")).isEqualTo(1);assertThat(first.containsKey("candidateRef")).isFalse();}
    @Test void reuseCannotChangeSessionOrSelection(){var source=snapshot(true,true);assertThat(mapper.reuse(source,session,key,Set.of(goal))).isSameAs(source);assertThatThrownBy(()->mapper.reuse(source,UUID.randomUUID(),key,Set.of(goal))).isInstanceOf(IllegalArgumentException.class);}
    /** 연습 대화 C안(docs/practice-direct-ask.md 4.2): 대본 v2의 새 칸 다섯 개가 저장 → 시작 패키지 firstScenario.scenes로 그대로 간다. */
    @Test void directAskFieldsOfAScriptV2ReachTheStartPackageScenes(){
        var scene=new ScenarioScript.Scene("base","BASE","상황","말","설명",List.of("요점"),List.of("예시"),"PEER","교실","놀이",null,"역할")
                .withDirectAsk("친구가 쉬고 싶대.","너라면 친구한테 뭐라고 말할래?","친구가 쉬고 싶다고 했지? 어떤 말을 해 주면 좋을까?",
                        "친구 마음을 먼저 받아 주면 돼. 예를 들면 그럼 쉬자처럼 말할 수 있어. 네 말로 해 볼래?","그렇게 말해 주면 친구도 고마워할 거야.");
        var scenario=new Scenario(UUID.randomUUID(),"ORIGINAL","PEER","기회","상황","역할","말",new Scenario.Validation(true,true,false),
                "scenario-script.v2",List.of(scene),new Scenario.Review("APPROVE","fake","scenario-review.v2"),
                new Scenario.Generation("fake","scenario-generation.v3",false,2));
        var prepared=new PreparedPracticePackageMapper.PreparedSnapshot(session,key,List.of(frozen(false)),scenario);
        var first=(Map<?,?>)((List<?>)mapper.c8(prepared).get("goals")).getFirst();
        String body=JsonCodec.shared().write(first.get("firstScenario"));
        assertThat(WebFixtures.schemaErrors("practice-scenario.schema.json",body)).isEmpty();
        @SuppressWarnings("unchecked") var sent=(Map<String,Object>)((List<?>)JsonCodec.shared().read(body,Map.class).get("scenes")).getFirst();
        assertThat(sent).containsEntry("opportunityRelay","친구가 쉬고 싶대.").containsEntry("askPrompt","너라면 친구한테 뭐라고 말할래?")
                .containsEntry("reaskPrompt","친구가 쉬고 싶다고 했지? 어떤 말을 해 주면 좋을까?")
                .containsEntry("helpSpeech","친구 마음을 먼저 받아 주면 돼. 예를 들면 그럼 쉬자처럼 말할 수 있어. 네 말로 해 볼래?")
                .containsEntry("friendReaction","그렇게 말해 주면 친구도 고마워할 거야.").containsEntry("partnerRole","역할");
        assertThat(JsonCodec.shared().read(body,Map.class).get("scriptVersion")).isEqualTo("scenario-script.v2");
        String old=JsonCodec.shared().write(mapper.map(snapshot(false,true)).firstScenario());
        assertThat(old).as("옛 대본 v1 장면에는 새 칸이 생기지 않는다(null은 쓰지 않음)")
                .doesNotContain("opportunityRelay","askPrompt","reaskPrompt","helpSpeech","friendReaction");
    }
    @Test void fullC8EnvelopeAcceptsCandidateAndOptionalScript(){var body=new LinkedHashMap<String,Object>();body.put("sessionId",session);body.put("idempotencyKey",key);body.put("guardianId",UUID.randomUUID());body.put("childId",UUID.randomUUID());body.put("deviceId",UUID.randomUUID());body.put("activityType","PRACTICE");body.put("consentSnapshot",Map.of("policyVersion","1.0","voiceAndText",true,"guardianView",true,"reportSharing",true,"rawAudio",true));body.put("childContext",Map.of("displayName","아이","age",8,"interests",List.of()));body.put("stopExpressions",List.of(Map.of("text","무중아 그만할래","isDefault",true)));body.put("approvedContent",Map.of("CLOSING",Map.of("contentVersionId",UUID.randomUUID(),"text","안녕","audioUrl","/media/approved/closing.wav"),"WAITING_FILLER",Map.of("contentVersionId",UUID.randomUUID(),"text","음","audioUrl","/media/approved/wait.wav")));body.put("practice",mapper.c8(snapshot(true,true)));assertThat(WebFixtures.schemaErrors("internal-session-start-request.schema.json",JsonCodec.shared().write(body))).isEmpty();}
}
