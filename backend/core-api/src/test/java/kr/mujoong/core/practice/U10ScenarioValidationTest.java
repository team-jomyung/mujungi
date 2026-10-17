package kr.mujoong.core.practice;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.Set;
import kr.mujoong.core.textai.*;
import org.junit.jupiter.api.*;

@Tag("u10")
class U10ScenarioValidationTest {
    final ScenarioScriptValidation validator=new ScenarioScriptValidation(new ForbiddenExpressionPolicy());
    ScenarioScriptValidation.Result inspect(ScenarioScript.Scene...scenes){return validator.inspect(new ScenarioScript(List.of(scenes)),U10ScenarioGenerationTest.rules());}
    ScenarioScript.Scene scene(String text,String kind,String relation){var s=U10ScenarioGenerationTest.base();return new ScenarioScript.Scene(s.sceneKey(),kind,text,s.opportunitySentence(),s.reexplanation(),s.focusPoints(),s.expressionExamples(),relation,s.place(),s.topic(),null,null);}
    @Test void correctBaseAndSeparateCoachExamplePass(){assertThat(inspect(U10ScenarioGenerationTest.base())).isEqualTo(ScenarioScriptValidation.Result.VALID);}
    @Test void missingBaseAndMultipleBaseFail(){assertThat(inspect()).isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);assertThat(inspect(U10ScenarioGenerationTest.base(),U10ScenarioGenerationTest.base())).isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);}
    @Test void firstVariantCannotReplaceBase(){assertThat(inspect(scene("상황","VARIANT","PEER"))).isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);}
    @Test void fixedRelationAndOpportunityAreIndependent(){var s=U10ScenarioGenerationTest.base();assertThat(inspect(scene(s.sceneDescription(),"BASE","ADULT"))).isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);assertThat(inspect(scene("기회 없는 장면","BASE","PEER"))).isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);}
    @Test void targetAnswerAndPressureInSpokenAreaAreRejected(){var s=U10ScenarioGenerationTest.base();for(String unsafe:List.of("잠깐 쉬자","빨리 말해","합성아이","010-1234-5678","person@example.invalid"))assertThat(inspect(scene(s.sceneDescription()+unsafe,"BASE","PEER"))).isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);}
    @Test void oversizedAndMissingSpokenFieldsFail(){assertThat(inspect(scene("가".repeat(8001),"BASE","PEER"))).isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);assertThat(inspect(scene(null,"BASE","PEER"))).isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);}
    @Test void duplicateFingerprintIsRejected(){var r=U10ScenarioGenerationTest.rules();var seen=new ScenarioScriptValidation.Rules(r.relation(),r.opportunity(),r.targetExpression(),Set.of(),null,x->true,r.variants());assertThat(validator.inspect(new ScenarioScript(List.of(U10ScenarioGenerationTest.base())),seen)).isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);}
    @Test void punctuationOnlyChangeFromOriginalIsRejected(){var r=U10ScenarioGenerationTest.rules();var seen=new ScenarioScriptValidation.Rules(r.relation(),r.opportunity(),r.targetExpression(),Set.of(),U10ScenarioGenerationTest.base().sceneDescription()+"!!!",x->false,r.variants());assertThat(validator.inspect(new ScenarioScript(List.of(U10ScenarioGenerationTest.base())),seen)).isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);}
}
