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
    @Test void fullC8EnvelopeAcceptsCandidateAndOptionalScript(){var body=new LinkedHashMap<String,Object>();body.put("sessionId",session);body.put("idempotencyKey",key);body.put("guardianId",UUID.randomUUID());body.put("childId",UUID.randomUUID());body.put("deviceId",UUID.randomUUID());body.put("activityType","PRACTICE");body.put("consentSnapshot",Map.of("policyVersion","1.0","voiceAndText",true,"guardianView",true,"reportSharing",true,"rawAudio",true));body.put("childContext",Map.of("displayName","아이","age",8,"interests",List.of()));body.put("stopExpressions",List.of(Map.of("text","무중아 그만할래","isDefault",true)));body.put("approvedContent",Map.of("CLOSING",Map.of("contentVersionId",UUID.randomUUID(),"text","안녕","audioUrl","/media/approved/closing.wav"),"WAITING_FILLER",Map.of("contentVersionId",UUID.randomUUID(),"text","음","audioUrl","/media/approved/wait.wav")));body.put("practice",mapper.c8(snapshot(true,true)));assertThat(WebFixtures.schemaErrors("internal-session-start-request.schema.json",JsonCodec.shared().write(body))).isEmpty();}
}
