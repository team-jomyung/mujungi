package kr.mujoong.core.result;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
@Tag("goal-result")
class SceneProjectionTest {
    SceneJudgmentRepository.Scene scene(String timing,String flow){return new SceneJudgmentRepository.Scene(UUID.randomUUID(),1,3,"NEW_SCENARIO",null,"functional",timing,null,null,false,null,SceneChipMapper.chip("functional",timing),UUID.randomUUID(),flow,List.of("REASK_RESOLVED"),null);}
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"IMMEDIATE,바로 전달","AFTER_REASK,되묻기 뒤 전달","AFTER_HELP,도움 뒤 전달"})
    void reportsStoredTiming(String timing,String text){assertThat(SceneReportMapper.text(scene(timing,PracticeRecordV2.VERSION))).isEqualTo(text);}
    @Test void legacyProjectionRetainsOriginalChipMeaning(){assertThat(SceneReportMapper.text(scene("AFTER_HELP",null))).isEqualTo("도움 뒤 전달");}
    @Test void reaskChipIsOnlyDisplayFold(){var row=scene("AFTER_REASK",PracticeRecordV2.VERSION);assertThat(row.sceneChip()).isEqualTo("DONE_IMMEDIATE");assertThat(row.timing()).isEqualTo("AFTER_REASK");assertThat(SceneReportMapper.text(row)).isEqualTo("되묻기 뒤 전달");}
    @Test void unknownLiveLabelIsNeverCreated(){assertThat(scene("AFTER_REASK",PracticeRecordV2.VERSION).liveLabel()).isNull();}
    @Test void goalExpressionIsBoundedToEightCodePoints(){var checker=new kr.mujoong.core.result.check.OutputChecker(new kr.mujoong.core.result.check.ForbiddenDictionary(),org.mockito.Mockito.mock(ResultTextGateway.class));assertThat(SessionResultHandler.safeGoalExpression("함께놀고싶다는뜻말하기",checker).codePointCount(0,SessionResultHandler.safeGoalExpression("함께놀고싶다는뜻말하기",checker).length())).isEqualTo(8);}
    @Test void unsafeOrBlankGoalExpressionUsesDeliveryFallback(){var checker=new kr.mujoong.core.result.check.OutputChecker(new kr.mujoong.core.result.check.ForbiddenDictionary(),org.mockito.Mockito.mock(ResultTextGateway.class));assertThat(SessionResultHandler.safeGoalExpression("자폐 진단",checker)).isEqualTo("전달");assertThat(SessionResultHandler.safeGoalExpression(" ",checker)).isEqualTo("전달");}
}
