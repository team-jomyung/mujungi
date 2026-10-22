package kr.mujoong.core.practice;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.knowledge.KnowledgeSeed;
import kr.mujoong.core.knowledge.VariantPlanner;
import kr.mujoong.core.textai.ScenarioReview;
import kr.mujoong.core.textai.ScenarioScript;
import kr.mujoong.core.web.support.WebFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("scenario-generation")
class ScenarioContractTest {
    final JsonCodec json = JsonCodec.shared();
    static ScenarioScript.Scene scene() {
        return new ScenarioScript.Scene("base-1", "BASE", "무중이랑 놀이를 한다고 생각해 보자. 무중이가 친구야.", "휴, 오래 했다.",
                "이제 그만할래.", "친구가 쉬려고 해.", null, null, "PEER", "교실", "놀이", null, null);
    }
    @Test void oldEightFieldConstructorAndJsonRemainValid() {
        var old = new Scenario(UUID.randomUUID(), "ORIGINAL", "PEER", "기회", "상황", "역할", "말",
                new Scenario.Validation(true, true, false));
        assertThat(WebFixtures.schemaErrors("practice-scenario.schema.json", json.write(old))).isEmpty();
        assertThat(json.read(json.write(old), Scenario.class)).isEqualTo(old);
        assertThat(json.write(old)).doesNotContain("scriptVersion", "scenes");
    }
    @Test void newOptionalFieldsRoundTripAndMatchContract() {
        var value = new Scenario(UUID.randomUUID(), "ORIGINAL", "PEER", "기회", "상황", "역할", "말",
                new Scenario.Validation(true,true,false), "scenario-script.v1", List.of(scene().withPartnerRole("역할")),
                new Scenario.Review("APPROVE", "fake", "scenario-review.v1"),
                new Scenario.Generation("fake", "scenario-generation.v2", false, 2));
        assertThat(WebFixtures.schemaErrors("practice-scenario.schema.json",json.write(value))).isEmpty();
        assertThat(json.read(json.write(value), Scenario.class)).isEqualTo(value);
    }
    @Test void missingCoachListsBecomeEmptyAndContentIsRedacted() {
        var value=scene();
        assertThat(value.focusPoints()).isEmpty(); assertThat(value.expressionExamples()).isEmpty();
        assertThat(value.toString()).doesNotContain("친구", "놀이");
        assertThat(new ScenarioScript(List.of(value)).toString()).doesNotContain("친구");
    }
    @Test void approvalWithReasonIsRejectedByModelAndSchema() {
        assertThatThrownBy(()->new ScenarioReview("APPROVE", List.of("PRESSURE"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(WebFixtures.schemaErrors("ai-scenario-review-output.schema.json", "{\"verdict\":\"APPROVE\",\"reasons\":[\"PRESSURE\"]}")).isNotEmpty();
    }
    @Test void closedReasonsExcludeUnknownFreeReasonAndDuplicates() {
        for(var reasons:List.of(List.of("OTHER_APPROVED_REASON"), List.of("PRESSURE","PRESSURE")))
            assertThatThrownBy(()->new ScenarioReview("REJECT", reasons)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ScenarioReview.REASONS).hasSize(12).contains("AGE_MISMATCH","REPETITION","FORBIDDEN_CONTENT");
    }
    @Test void rejectUncertainAndMissingAreNeverApproval() {
        for(String verdict:List.of("REJECT","UNCERTAIN")) assertThat(new ScenarioReview(verdict,List.of()).approved()).isFalse();
        assertThatThrownBy(()->new ScenarioReview("", List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ScenarioReview("APPROVE", null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void providerCannotSupplyRoleOrOversizedSpokenText() {
        String body=json.write(new ScenarioScript(List.of(scene().withPartnerRole("임의 역할"))));
        assertThat(WebFixtures.schemaErrors("ai-scenario-output.schema.json",body)).isNotEmpty();
        assertThat(WebFixtures.schemaErrors("ai-scenario-output.schema.json",json.write(new ScenarioScript(List.of(scene()))))).isEmpty();
    }
    /** 생성 출력은 친구 첫 대사 칸을 늘 담고(VARIANT는 빈 문자열), 공개 대본은 그 칸이 없는 옛 행도 받는다(대본 형식 v6). */
    @Test void theGenerationOutputAlwaysCarriesAPartnerOpenerAndThePublicScriptAcceptsRowsWithoutIt() {
        var withoutOpener=new ScenarioScript.Scene("base-1","BASE","놀이 중 친구가 멈추려고 해. 이제 그만할래.","이제 그만할래.","친구가 쉬려고 해.",
                null,null,"PEER","교실","놀이",null,null);
        assertThat(withoutOpener.partnerOpener()).isNull();
        assertThat(WebFixtures.schemaErrors("ai-scenario-output.schema.json",json.write(new ScenarioScript(List.of(withoutOpener)))))
                .as("생성 출력에서는 필수").isNotEmpty();
        var emptyVariantOpener=new ScenarioScript.Scene("variant-1","VARIANT","이번엔 운동장이야.","","나 이제 그만할래.","친구가 쉬려고 해.",
                null,null,"PEER","운동장","놀이",null,null);
        assertThat(WebFixtures.schemaErrors("ai-scenario-output.schema.json",json.write(new ScenarioScript(List.of(scene(),emptyVariantOpener))))).isEmpty();
        var tooLong=new ScenarioScript.Scene("base-1","BASE","놀이 중이야.","가".repeat(301),"이제 그만할래.","친구가 쉬려고 해.",
                null,null,"PEER","교실","놀이",null,null);
        assertThat(WebFixtures.schemaErrors("ai-scenario-output.schema.json",json.write(new ScenarioScript(List.of(tooLong))))).isNotEmpty();
        for(var scene:List.of(scene(),withoutOpener)){
            var stored=new Scenario(UUID.randomUUID(),"ORIGINAL","PEER","기회","상황","역할","말",new Scenario.Validation(true,true,false),
                    "scenario-script.v1",List.of(scene.withPartnerRole("역할")),new Scenario.Review("APPROVE","fake","scenario-review.v4"),
                    new Scenario.Generation("fake","scenario-generation.v6",false,2));
            assertThat(WebFixtures.schemaErrors("practice-scenario.schema.json",json.write(stored))).isEmpty();
            assertThat(json.read(json.write(stored),Scenario.class)).isEqualTo(stored);
        }
        assertThat(scene().withPartnerRole("역할").partnerOpener()).as("역할 이름을 덮어써도 첫 대사는 남는다").isEqualTo("휴, 오래 했다.");
    }
    @Test void listSnapshotsCannotBeModifiedAndRoleIsServerOverwritten() {
        var scene=scene().withPartnerRole(ScenarioContentPolicy.PARTNER_ROLE);
        assertThat(scene.partnerRole()).isEqualTo(ScenarioContentPolicy.PARTNER_ROLE);
        var list=new java.util.ArrayList<>(List.of(scene)); var script=new ScenarioScript(list); list.clear();
        assertThat(script.scenes()).hasSize(1);
        assertThatThrownBy(()->script.scenes().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    static ScenarioScript.Scene graphBase(String participants,String relationship){
        return new ScenarioScript.Scene("base-1","BASE","교실에서 친구와 놀이 중이야.","휴, 오래 했다.","이제 그만할래.","친구가 쉬려고 해.",null,null,
                "같은 반 친구","교실","놀이",new ScenarioScript.Preservation("seed:S1","친구가 멈추려 한다","의도를 유지한다","SAME_CONTEXT","topic",
                participants,"SCHOOL_GROUP",relationship),null);
    }
    @Test void graphCoordinatesInTheOutputSchemaAreExactlyTheServerVocabulary() throws java.io.IOException {
        Map<?,?> schema;
        try(var in=getClass().getClassLoader().getResourceAsStream("luna/schemas/ai-scenario-output.schema.json")){
            schema=json.read(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8),Map.class);
        }
        Map<?,?> preservation=(Map<?,?>)((Map<?,?>)((Map<?,?>)((Map<?,?>)((Map<?,?>)schema.get("properties")).get("scenes")).get("items")).get("properties")).get("preservation");
        Map<?,?> props=(Map<?,?>)preservation.get("properties");
        assertThat(Set.copyOf((List<?>)((Map<?,?>)props.get("participants")).get("enum"))).isEqualTo(VariantPlanner.PARTICIPANTS);
        assertThat(Set.copyOf((List<?>)((Map<?,?>)props.get("relationship")).get("enum"))).isEqualTo(KnowledgeSeed.RELATIONSHIPS);
        assertThat(Set.copyOf((List<?>)((Map<?,?>)props.get("situation")).get("enum"))).isEqualTo(KnowledgeSeed.SITUATIONS);
        @SuppressWarnings("unchecked") var required=(List<Object>)preservation.get("required");
        assertThat(required).contains("relationship","participants","situation");
    }
    @Test void graphBaseNeedsAnEnumeratedRelationshipCoordinateBesideTheFreePartner() {
        assertThat(WebFixtures.schemaErrors("ai-scenario-output.schema.json",json.write(new ScenarioScript(List.of(graphBase("아이와 친구","CLASSMATE")))))).isEmpty();
        assertThat(WebFixtures.schemaErrors("ai-scenario-output.schema.json",json.write(new ScenarioScript(List.of(graphBase("아이와 친구",null)))))).isNotEmpty();
        assertThat(WebFixtures.schemaErrors("ai-scenario-output.schema.json",json.write(new ScenarioScript(List.of(graphBase("아이와 친구","같은 반 친구")))))).isNotEmpty();
        assertThat(WebFixtures.schemaErrors("ai-scenario-output.schema.json",json.write(new ScenarioScript(List.of(graphBase("아이와 형","CLASSMATE")))))).isNotEmpty();
    }
    @Test void storedV1ToV3AndNewV4ReviewsAllMatchThePracticeScenarioContract() {
        assertThat(kr.mujoong.core.textai.ScenarioScriptProvider.REVIEW_PROMPT_VERSION).isEqualTo("scenario-review.v4");
        for(String version:List.of("scenario-review.v1","scenario-review.v2","scenario-review.v3",kr.mujoong.core.textai.ScenarioScriptProvider.REVIEW_PROMPT_VERSION)){
            var value=new Scenario(UUID.randomUUID(),"ORIGINAL","PEER","기회","상황","역할","말",new Scenario.Validation(true,true,false),
                    "scenario-script.v1",List.of(scene().withPartnerRole("역할")),new Scenario.Review("APPROVE","fake",version),
                    new Scenario.Generation("fake",kr.mujoong.core.textai.ScenarioScriptProvider.GENERATION_PROMPT_VERSION,false,2));
            assertThat(WebFixtures.schemaErrors("practice-scenario.schema.json",json.write(value))).as(version).isEmpty();
        }
        var unknown=new Scenario(UUID.randomUUID(),"ORIGINAL","PEER","기회","상황","역할","말",new Scenario.Validation(true,true,false),
                "scenario-script.v1",List.of(scene().withPartnerRole("역할")),new Scenario.Review("APPROVE","fake","scenario-review.v9"),
                new Scenario.Generation("fake",kr.mujoong.core.textai.ScenarioScriptProvider.GENERATION_PROMPT_VERSION,false,2));
        assertThat(WebFixtures.schemaErrors("practice-scenario.schema.json",json.write(unknown))).isNotEmpty();
    }
    @Test void storedV2ToV5AndNewV6GenerationsAllMatchThePracticeScenarioContract() {
        assertThat(kr.mujoong.core.textai.ScenarioScriptProvider.GENERATION_PROMPT_VERSION).isEqualTo("scenario-generation.v6");
        for(String version:List.of("scenario-generation.v2","scenario-generation.v3","scenario-generation.v4","scenario-generation.v5",kr.mujoong.core.textai.ScenarioScriptProvider.GENERATION_PROMPT_VERSION)){
            var value=new Scenario(UUID.randomUUID(),"ORIGINAL","PEER","기회","상황","역할","말",new Scenario.Validation(true,true,false),
                    "scenario-script.v1",List.of(scene().withPartnerRole("역할")),new Scenario.Review("APPROVE","fake","scenario-review.v1"),
                    new Scenario.Generation("fake",version,false,2));
            assertThat(WebFixtures.schemaErrors("practice-scenario.schema.json",json.write(value))).as(version).isEmpty();
        }
        var unknown=new Scenario(UUID.randomUUID(),"ORIGINAL","PEER","기회","상황","역할","말",new Scenario.Validation(true,true,false),
                "scenario-script.v1",List.of(scene().withPartnerRole("역할")),new Scenario.Review("APPROVE","fake","scenario-review.v1"),
                new Scenario.Generation("fake","scenario-generation.v1",false,2));
        assertThat(WebFixtures.schemaErrors("practice-scenario.schema.json",json.write(unknown))).isNotEmpty();
    }
}
