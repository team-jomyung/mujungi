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
    /** 변형은 BASE와 다른 기회 문장을 쓴다(대본 형식 v7: 같은 기회 문장의 변형은 빠진다). */
    ScenarioScript.Scene withOpener(String kind,String opener){var s=ScenarioGenerationTest.base();return new ScenarioScript.Scene(kind.equals("BASE")?"base":"variant",kind,s.sceneDescription(),opener,kind.equals("BASE")?s.opportunitySentence():"나 이제 그만 놀래.",s.reexplanation(),s.focusPoints(),s.expressionExamples(),s.relation(),s.place(),s.topic(),null,null);}
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
    ScenarioScriptValidation.Rules accepting(){var r=ScenarioGenerationTest.rules();return new ScenarioScriptValidation.Rules(r.relation(),r.opportunity(),r.targetExpression(),Set.of("합성아이"),null,x->false,(a,b)->true);}
    /** 대본 형식 v7(실측 F2): VARIANT 기회 문장이 BASE 기회 문장과 같으면(띄어쓰기 · 문장부호만 달라도) 소재를 바꾼 새 상황이 아니라 변형을 뺀다. */
    @Test void aVariantWhoseOpportunityLineEqualsTheBaseLineIsDropped(){
        var base=ScenarioGenerationTest.base();
        var same=new ScenarioScript.Scene("variant","VARIANT","이번엔 운동장이야.","",base.opportunitySentence().replace(".","!")+" ",base.reexplanation(),base.focusPoints(),base.expressionExamples(),base.relation(),"운동장",base.topic(),null,null);
        assertThat(validator.inspect(new ScenarioScript(List.of(base,same)),accepting())).isEqualTo(ScenarioScriptValidation.Result.INVALID_VARIANT);
        assertThat(validator.inspect(new ScenarioScript(List.of(base,withOpener("VARIANT",""))),accepting())).isEqualTo(ScenarioScriptValidation.Result.VALID);
    }
    ScenarioScript.Scene withFrames(List<String> examples,List<String> frames){var s=ScenarioGenerationTest.base();return new ScenarioScript.Scene(s.sceneKey(),s.sceneKind(),s.sceneDescription(),s.partnerOpener(),s.opportunitySentence(),s.reexplanation(),s.focusPoints(),examples,frames,s.relation(),s.place(),s.topic(),null,null);}
    /** 빈칸 예문(실측 F5): 표현 예시와 1:1이고, 예문마다 '뭐뭐'가 있고, 예시와 같지 않고, 꾸밈말 하나만 비우지 않는다. 없으면(옛 모양) 보지 않는다. */
    @Test void expressionFramesMustBlankTheKeyContentOfTheirExampleOneToOne(){
        assertThat(inspect(withFrames(List.of("그래, 잠깐 쉬어"),List.of("그래, 잠깐 뭐뭐")))).isEqualTo(ScenarioScriptValidation.Result.VALID);
        assertThat(inspect(withFrames(List.of("그래, 잠깐 쉬어"),null))).as("판 v6까지의 모양").isEqualTo(ScenarioScriptValidation.Result.VALID);
        assertThat(inspect(withFrames(List.of(),List.of()))).isEqualTo(ScenarioScriptValidation.Result.VALID);
        for(List<String> frames:List.<List<String>>of(List.of("그래, 뭐뭐 쉬어"),List.of("그래, 잠깐 쉬어"),List.of("그래, 잠깐 쉬어."),List.of(),List.of("그래, 잠깐 뭐뭐","하나 더")))
            assertThat(inspect(withFrames(List.of("그래, 잠깐 쉬어"),frames))).as(frames.toString()).isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);
        assertThat(inspect(withFrames(List.of("그래, 잠깐 쉬어"),List.of("합성아이야, 잠깐 뭐뭐")))).as("이름 검사도 받는다").isEqualTo(ScenarioScriptValidation.Result.INVALID_BASE);
    }
    @Test void frameAlignmentAcceptsTheParticleAfterTheBlankAndJudgesOnlyWhatItCanAlign(){
        assertThat(ScenarioScriptValidation.frameValid("아니, 나 파란색 달라고 했어.","아니, 나 뭐뭐 달라고 했어.")).isTrue();
        assertThat(ScenarioScriptValidation.frameValid("형, 다 쓰고 나서 잠깐 빌려줄 수 있어?","형, 다 쓰고 나서 뭐뭐 빌려줄 수 있어?")).as("실측 s10: '잠깐'만 비움").isFalse();
        assertThat(ScenarioScriptValidation.removedSpans("파란색을줘","뭐뭐를줘")).contains(List.of("파란색"));
        assertThat(ScenarioScriptValidation.frameValid("파란색을 줘.","뭐뭐를 줘.")).isTrue();
        assertThat(ScenarioScriptValidation.frameValid("나도 블록 하나 써도 돼?","나도 뭐뭐 하나 뭐뭐 돼?")).isTrue();
        assertThat(ScenarioScriptValidation.frameValid("나도 블록 줘","나도 블록 뭐뭐 줘")).as("비운 말이 없다").isFalse();
        assertThat(ScenarioScriptValidation.frameValid("파란색 줘","뭐뭐")).as("빈칸뿐").isFalse();
        assertThat(ScenarioScriptValidation.frameValid("나도 블록 하나 써도 돼?","나도 뭐뭐 빌려줄래?")).as("맞춰 볼 수 없으면 검토가 본다").isTrue();
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
