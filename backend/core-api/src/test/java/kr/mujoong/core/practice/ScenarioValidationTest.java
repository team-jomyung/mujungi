package kr.mujoong.core.practice;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.Set;
import kr.mujoong.core.textai.*;
import org.junit.jupiter.api.*;

@Tag("scenario-generation")
class ScenarioValidationTest {
    @Test void detailedChecksDistinguishMissingConditionRelationAndAnswerWithoutCopyingText(){
        var rules=ScenarioGenerationTest.rules();var base=ScenarioGenerationTest.base();
        assertThat(validator.inspectDetailed(new ScenarioScript(List.of(scene(base.sceneDescription(),"BASE","OTHER"))),rules).reason())
                .isEqualTo(ScenarioScriptValidation.Reason.RELATION_MISMATCH);
        assertThat(validator.inspectDetailed(new ScenarioScript(List.of(scene("이제 그만할래.","BASE","PEER"))),rules).reason())
                .isEqualTo(ScenarioScriptValidation.Reason.CONDITION_MISSING);
        // 상황 안내는 칸별 판정이다: 장면은 통과하고 그 칸만 사유가 붙는다(발급 때 그 칸을 뺀다)
        assertThat(validator.inspectDetailed(new ScenarioScript(List.of(introduction("잠깐 쉬자"))),rules).reason())
                .isEqualTo(ScenarioScriptValidation.Reason.NONE);
        assertThat(validator.optionalSpokenIssues(introduction("잠깐 쉬자"),rules)).containsExactly(java.util.Map.entry(
                ScenarioScriptValidation.SPOKEN_INTRODUCTION,ScenarioScriptValidation.Reason.TARGET_EXPRESSION_DISCLOSED));
    }
    @Test void detailedDuplicateCheckRunsOnceAndKeepsTheExistingRejection(){
        var r=ScenarioGenerationTest.rules();var calls=new java.util.concurrent.atomic.AtomicInteger();
        var rules=new ScenarioScriptValidation.Rules(r.relation(),r.opportunity(),r.targetExpression(),r.originalNames(),null,
                fingerprint->{calls.incrementAndGet();return true;},r.variants());
        var result=validator.inspectDetailed(new ScenarioScript(List.of(ScenarioGenerationTest.base())),rules);
        assertThat(result.result()).isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);
        assertThat(result.reason()).isEqualTo(ScenarioScriptValidation.Reason.ALREADY_SEEN);
        assertThat(calls.get()).isEqualTo(1);
    }
    final ScenarioScriptValidation validator=new ScenarioScriptValidation(new ForbiddenExpressionPolicy());
    ScenarioScriptValidation.Result inspect(ScenarioScript.Scene...scenes){return validator.inspect(new ScenarioScript(List.of(scenes)),ScenarioGenerationTest.rules());}
    ScenarioScript.Scene scene(String text,String kind,String relation){var s=ScenarioGenerationTest.base();return new ScenarioScript.Scene(s.sceneKey(),kind,text,s.opportunitySentence(),s.reexplanation(),s.focusPoints(),s.expressionExamples(),relation,s.place(),s.topic(),null,null);}
    @Test void correctBaseAndSeparateCoachExamplePass(){assertThat(inspect(ScenarioGenerationTest.base())).isEqualTo(ScenarioScriptValidation.Result.VALID);}
    @Test void reflectionNeedsVerifiedEvidenceAndKeepsAllSpokenSafetyChecks() {
        var s=ScenarioGenerationTest.base();var r=ScenarioGenerationTest.rules();
        var allowed=new ScenarioScriptValidation.Rules(r.relation(),r.opportunity(),r.targetExpression(),r.originalNames(),r.sourceText(),r.seen(),r.variants(),true);
        for(String question:List.of("지난번 블록 이야기를 떠올려 볼까?","빨리 말해","잠깐 쉬자")) {
            var scene=new ScenarioScript.Scene(s.sceneKey(),s.sceneKind(),s.sceneDescription(),s.opportunitySentence(),s.reexplanation(),
                    s.focusPoints(),s.expressionExamples(),s.relation(),s.place(),s.topic(),s.preservation(),s.partnerRole(),null,question);
            assertThat(inspect(scene)).isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);
            assertThat(validator.inspect(new ScenarioScript(List.of(scene)),allowed)).isEqualTo(
                    question.startsWith("지난번")?ScenarioScriptValidation.Result.VALID:ScenarioScriptValidation.Result.INVALID_BASE);
            assertThat(scene.withPartnerRole("상대 역할").reflectionPrompt()).isEqualTo(question);
        }
    }
    ScenarioScript.Scene introduction(String text) {
        var s=ScenarioGenerationTest.base();
        return new ScenarioScript.Scene(s.sceneKey(),s.sceneKind(),s.sceneDescription(),s.opportunitySentence(),s.reexplanation(),
                s.focusPoints(),s.expressionExamples(),s.relation(),s.place(),s.topic(),s.preservation(),s.partnerRole(),text);
    }
    @Test void naturalSpokenIntroductionIsPreservedWhenTheServerAssignsThePartnerRole() {
        var s=introduction("친구랑 블록 놀이를 하려고 해. 무중이가 친구 역할을 해 볼게.");
        assertThat(inspect(s)).isEqualTo(ScenarioScriptValidation.Result.VALID);
        assertThat(s.withPartnerRole("상대 역할").spokenIntroduction()).isEqualTo(s.spokenIntroduction());
    }
    @Test void spokenIntroductionCannotLeakTheAnswerPressureIdentityOrInternalConditionTail() {
        // 걸린 상황 안내는 그 칸만 빠지고 장면은 발급된다(realtime이 대체 안내를 읽는다). 칸 규칙은 main 그대로다
        var expected=new java.util.LinkedHashMap<String,ScenarioScriptValidation.Reason>();
        expected.put("잠깐 쉬자",ScenarioScriptValidation.Reason.TARGET_EXPRESSION_DISCLOSED);
        expected.put("빨리 말해",ScenarioScriptValidation.Reason.PRESSURE_OR_ANSWER);
        expected.put("합성아이와 함께야",ScenarioScriptValidation.Reason.PRIVATE_OR_FORBIDDEN_CONTENT);
        expected.put("친구와 놀이가 다를 때.",ScenarioScriptValidation.Reason.SPOKEN_FORMAT);
        expected.put("가".repeat(501),ScenarioScriptValidation.Reason.SPOKEN_FORMAT);
        expected.put("친구랑"+(char)0+" 놀려고 해.",ScenarioScriptValidation.Reason.SPOKEN_FORMAT);
        expected.put(" ",ScenarioScriptValidation.Reason.SPOKEN_FORMAT);
        expected.forEach((text,reason)->{
            assertThat(inspect(introduction(text))).isEqualTo(ScenarioScriptValidation.Result.VALID);
            assertThat(validator.optionalSpokenIssues(introduction(text),ScenarioGenerationTest.rules()))
                    .containsExactly(java.util.Map.entry(ScenarioScriptValidation.SPOKEN_INTRODUCTION,reason));
        });
    }
    @Test void missingBaseAndMultipleBaseFail(){assertThat(inspect()).isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);assertThat(inspect(ScenarioGenerationTest.base(),ScenarioGenerationTest.base())).isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);}
    @Test void firstVariantCannotReplaceBase(){assertThat(inspect(scene("상황","VARIANT","PEER"))).isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);}
    @Test void fixedRelationAndOpportunityAreIndependent(){var s=ScenarioGenerationTest.base();assertThat(inspect(scene(s.sceneDescription(),"BASE","ADULT"))).isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);assertThat(inspect(scene("기회 없는 장면","BASE","PEER"))).isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);}
    @Test void targetAnswerAndPressureInSpokenAreaAreRejected(){var s=ScenarioGenerationTest.base();for(String unsafe:List.of("잠깐 쉬자","빨리 말해","합성아이","010-1234-5678","person@example.invalid"))assertThat(inspect(scene(s.sceneDescription()+unsafe,"BASE","PEER"))).isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);}
    @Test void oversizedAndMissingSpokenFieldsFail(){assertThat(inspect(scene("가".repeat(8001),"BASE","PEER"))).isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);assertThat(inspect(scene(null,"BASE","PEER"))).isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);}
    @Test void duplicateFingerprintIsRejected(){var r=ScenarioGenerationTest.rules();var seen=new ScenarioScriptValidation.Rules(r.relation(),r.opportunity(),r.targetExpression(),Set.of(),null,x->true,r.variants());assertThat(validator.inspect(new ScenarioScript(List.of(ScenarioGenerationTest.base())),seen)).isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);}
    @Test void punctuationOnlyChangeFromOriginalIsRejected(){var r=ScenarioGenerationTest.rules();var seen=new ScenarioScriptValidation.Rules(r.relation(),r.opportunity(),r.targetExpression(),Set.of(),ScenarioGenerationTest.base().sceneDescription()+"!!!",x->false,r.variants());assertThat(validator.inspect(new ScenarioScript(List.of(ScenarioGenerationTest.base())),seen)).isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);}
}
