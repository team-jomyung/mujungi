package kr.mujoong.core.practice;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.Set;
import kr.mujoong.core.textai.*;
import org.junit.jupiter.api.*;

@Tag("scenario-generation")
class ScenarioValidationTest {
    final ScenarioScriptValidation validator=new ScenarioScriptValidation(new ForbiddenExpressionPolicy());
    ScenarioScriptValidation.Result inspect(ScenarioScript.Scene...scenes){return validator.inspect(new ScenarioScript(List.of(scenes)),ScenarioGenerationTest.rules());}
    ScenarioScript.Scene scene(String text,String kind,String relation){var s=ScenarioGenerationTest.base();return new ScenarioScript.Scene(s.sceneKey(),kind,text,s.partnerOpener(),s.opportunitySentence(),s.reexplanation(),s.focusPoints(),s.expressionExamples(),relation,s.place(),s.topic(),null,null);}
    @Test void correctBaseAndSeparateCoachExamplePass(){assertThat(inspect(ScenarioGenerationTest.base())).isEqualTo(ScenarioScriptValidation.Result.VALID);}
    @Test void missingBaseAndMultipleBaseFail(){assertThat(inspect()).isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);assertThat(inspect(ScenarioGenerationTest.base(),ScenarioGenerationTest.base())).isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);}
    @Test void firstVariantCannotReplaceBase(){assertThat(inspect(scene("상황","VARIANT","PEER"))).isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);}
    @Test void fixedRelationIsCheckedButTheNarrationNeedNotCopyTheOpportunityCondition(){
        var s=ScenarioGenerationTest.base();
        assertThat(inspect(scene(s.sceneDescription(),"BASE","ADULT"))).isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);
        // 대본 형식 v6: 기회 조건은 생성 조건이고, 장면에서 실제로 생기는지는 별도 검토(J7)가 의미로 본다(docs/practice-dialogue-standard.md)
        assertThat(inspect(scene("기회 조건 문장을 옮기지 않은 무중의 설명","BASE","PEER"))).isEqualTo(ScenarioScriptValidation.Result.VALID);
    }
    ScenarioScript.Scene withOpener(String kind,String opener){var s=ScenarioGenerationTest.base();return new ScenarioScript.Scene(kind.equals("BASE")?"base":"variant",kind,s.sceneDescription(),opener,s.opportunitySentence(),s.reexplanation(),s.focusPoints(),s.expressionExamples(),s.relation(),s.place(),s.topic(),null,null);}
    @Test void theBaseNeedsAShortFriendOpenerAndAVariantMayLeaveItEmpty(){
        var rules=ScenarioGenerationTest.rules();var accepting=new ScenarioScriptValidation.Rules(rules.relation(),rules.opportunity(),rules.targetExpression(),Set.of("합성아이"),null,x->false,(a,b)->true);
        for(String missing:new String[]{null,"","   "})
            assertThat(inspect(withOpener("BASE",missing))).as(String.valueOf(missing)).isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);
        assertThat(inspect(withOpener("BASE","가".repeat(301)))).isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);
        assertThat(inspect(withOpener("BASE","가".repeat(300)))).isEqualTo(ScenarioScriptValidation.Result.VALID);
        for(String variantOpener:new String[]{null,"","자, 여기!"})
            assertThat(validator.inspect(new ScenarioScript(List.of(ScenarioGenerationTest.base(),withOpener("VARIANT",variantOpener))),accepting))
                    .as(String.valueOf(variantOpener)).isEqualTo(ScenarioScriptValidation.Result.VALID);
        assertThat(validator.inspect(new ScenarioScript(List.of(ScenarioGenerationTest.base(),withOpener("VARIANT","가".repeat(301)))),accepting))
                .isEqualTo(ScenarioScriptValidation.Result.INVALID_VARIANT);
    }
    @Test void theFriendOpenerIsHeardBeforeTheFirstAnswerSoTheTargetPressureAndNamesAreRejectedThereToo(){
        for(String unsafe:List.of("잠깐 쉬자","따라 말해","합성아이","010-1234-5678"))
            assertThat(inspect(withOpener("BASE","응? "+unsafe))).as(unsafe).isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);
    }
    @Test void targetAnswerAndPressureInSpokenAreaAreRejected(){var s=ScenarioGenerationTest.base();for(String unsafe:List.of("잠깐 쉬자","빨리 말해","합성아이","010-1234-5678","person@example.invalid"))assertThat(inspect(scene(s.sceneDescription()+unsafe,"BASE","PEER"))).isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);}
    @Test void oversizedAndMissingSpokenFieldsFail(){assertThat(inspect(scene("가".repeat(8001),"BASE","PEER"))).isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);assertThat(inspect(scene(null,"BASE","PEER"))).isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);}
    @Test void duplicateFingerprintIsRejected(){var r=ScenarioGenerationTest.rules();var seen=new ScenarioScriptValidation.Rules(r.relation(),r.opportunity(),r.targetExpression(),Set.of(),null,x->true,r.variants());assertThat(validator.inspect(new ScenarioScript(List.of(ScenarioGenerationTest.base())),seen)).isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);}
    @Test void punctuationOnlyChangeFromOriginalIsRejected(){var r=ScenarioGenerationTest.rules();var seen=new ScenarioScriptValidation.Rules(r.relation(),r.opportunity(),r.targetExpression(),Set.of(),ScenarioGenerationTest.base().sceneDescription()+"!!!",x->false,r.variants());assertThat(validator.inspect(new ScenarioScript(List.of(ScenarioGenerationTest.base())),seen)).isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);}
}
