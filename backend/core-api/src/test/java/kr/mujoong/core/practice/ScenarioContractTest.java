package kr.mujoong.core.practice;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.textai.ScenarioReview;
import kr.mujoong.core.textai.ScenarioScript;
import kr.mujoong.core.web.support.WebFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("scenario-generation")
class ScenarioContractTest {
    final JsonCodec json = JsonCodec.shared();
    static ScenarioScript.Scene scene() {
        return new ScenarioScript.Scene("base-1", "BASE", "놀이 중 친구가 멈추려고 해. 이제 그만할래.",
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
    @Test void openingLineFollowsTheOutputSchemaAndReflectionFieldsAreGone() {
        var s=scene();
        var withStart=new ScenarioScript.Scene(s.sceneKey(),s.sceneKind(),s.sceneDescription(),s.opportunitySentence(),s.reexplanation(),
                s.focusPoints(),s.expressionExamples(),s.relation(),s.place(),s.topic(),null,null,null,
                "우와, 너도 블록 놀이 하러 왔어?");
        assertThat(WebFixtures.schemaErrors("ai-scenario-output.schema.json",json.write(new ScenarioScript(List.of(withStart))))).isEmpty();
        var oversized=new ScenarioScript.Scene(s.sceneKey(),s.sceneKind(),s.sceneDescription(),s.opportunitySentence(),s.reexplanation(),
                s.focusPoints(),s.expressionExamples(),s.relation(),s.place(),s.topic(),null,null,null,"가".repeat(201));
        assertThat(WebFixtures.schemaErrors("ai-scenario-output.schema.json",json.write(new ScenarioScript(List.of(oversized))))).isNotEmpty();
        String old="{\"scenes\":[{\"sceneKey\":\"base-1\",\"sceneKind\":\"BASE\",\"sceneDescription\":\"상황\",\"opportunitySentence\":\"말\","
                +"\"reexplanation\":\"다시\",\"relation\":\"PEER\",\"place\":\"교실\",\"topic\":\"놀이\",\"reflectionPrompt\":\"떠올려 볼까?\"}]}";
        assertThat(WebFixtures.schemaErrors("ai-scenario-output.schema.json",old)).as("옛 질문 필드는 생성 출력이 아니다").isNotEmpty();
        String summary=old.replace("\"reflectionPrompt\":\"떠올려 볼까?\"","\"reflectionSummary\":\"지난번에 너는 놀이 이야기를 해 줬어.\"");
        assertThat(WebFixtures.schemaErrors("ai-scenario-output.schema.json",summary)).as("원래 대화 요약도 생성 출력이 아니다").isNotEmpty();
        assertThat(json.read(summary,ScenarioScript.class).scenes().getFirst().openingLine()).as("저장된 옛 대본은 그대로 읽힌다").isNull();
    }
    @Test void listSnapshotsCannotBeModifiedAndRoleIsServerOverwritten() {
        var scene=scene().withPartnerRole(ScenarioContentPolicy.PARTNER_ROLE);
        assertThat(scene.partnerRole()).isEqualTo(ScenarioContentPolicy.PARTNER_ROLE);
        var list=new java.util.ArrayList<>(List.of(scene)); var script=new ScenarioScript(list); list.clear();
        assertThat(script.scenes()).hasSize(1);
        assertThatThrownBy(()->script.scenes().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
