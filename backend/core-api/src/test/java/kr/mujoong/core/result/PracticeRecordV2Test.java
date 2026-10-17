package kr.mujoong.core.result;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
@Tag("u14")
class PracticeRecordV2Test {
    UUID goal=UUID.randomUUID();
    Map<String,Object> scene(){return new LinkedHashMap<>(Map.of("sceneRunId",UUID.randomUUID().toString(),"goalRef",goal.toString(),"sceneKind","BASE","startedAt","2026-10-16T00:00:00Z"));}
    Map<String,Object> record(List<Map<String,Object>> scenes){return Map.of("flowVersion",PracticeRecordV2.VERSION,"scenes",scenes);}
    @Test void absentFlowVersionStaysLegacy(){assertThat(PracticeRecordV2.present(Map.of("snapshotVersion",99))).isFalse();assertThat(ResultData.goals(Map.of("goals",List.of(Map.of("displayName","옛 목표"))))).hasSize(1);}
    @Test void threeStartedScenesRemainThree(){assertThat(PracticeRecordV2.read(record(List.of(scene(),scene(),scene())))).extracting(PracticeRecordV2.Scene::sceneNo).containsExactly(1,2,3);}
    @Test void unknownFactsAreIgnoredAndKnownFactsDeduplicated(){var s=scene();s.put("facts",List.of("UNKNOWN","FOCUS_HELP","FOCUS_HELP"));assertThat(PracticeRecordV2.read(record(List.of(s))).getFirst().facts()).containsExactly("FOCUS_HELP");}
    @Test void duplicateSceneRunIsInvalid(){var s=scene();assertThatThrownBy(()->PracticeRecordV2.read(record(List.of(s,s)))).isInstanceOf(ResultAiException.class);}
    @Test void unsupportedVersionCannotFallBackToLegacy(){assertThatThrownBy(()->ResultData.goals(Map.of("flowVersion","future","scenes",List.of()))).isInstanceOf(ResultAiException.class);}
    @Test void notStartedSceneIsNotMaterialized(){var s=scene();s.remove("startedAt");assertThat(PracticeRecordV2.read(record(List.of(s)))).isEmpty();}
    @Test void factsDoNotInventAfterReaskTiming(){var s=scene();s.put("facts",List.of("REASK_RESOLVED"));assertThat(PracticeRecordV2.read(record(List.of(s))).getFirst().liveTiming()).isNull();}
    @Test void preservesLiveTimingAndVariantSkip(){var s=scene();s.put("liveOutcome",Map.of("timing","AFTER_REASK"));s.put("variantSkipReason","DECLINED");var row=PracticeRecordV2.read(record(List.of(s))).getFirst();assertThat(row.liveTiming()).isEqualTo("AFTER_REASK");assertThat(row.variantSkipReason()).isEqualTo("DECLINED");}
    @Test void factHelpCountsDoNotDoubleCountRepeatedFacts(){var s=scene();s.put("facts",List.of("FOCUS_HELP","FOCUS_HELP","HELP_ACCEPTED"));assertThat(HelpSummarizer.summarize(ResultData.goals(record(List.of(s))).getFirst())).containsEntry("count",1).containsEntry("consent","ACCEPTED");}
    @Test void invalidSceneAndTimestampAreRejected(){var s=scene();s.put("goalRef","bad");assertThatThrownBy(()->PracticeRecordV2.read(record(List.of(s)))).isInstanceOf(ResultAiException.class);var invalid=scene();invalid.put("startedAt","bad");assertThatThrownBy(()->PracticeRecordV2.read(record(List.of(invalid)))).isInstanceOf(ResultAiException.class);}
    @Test void scalarSceneCannotSilentlyDisappear(){assertThatThrownBy(()->PracticeRecordV2.read(Map.of("flowVersion",PracticeRecordV2.VERSION,"scenes",List.of(42)))).isInstanceOf(ResultAiException.class);}
    @Test void mixedSceneListIsRejected(){assertThatThrownBy(()->PracticeRecordV2.read(Map.of("flowVersion",PracticeRecordV2.VERSION,"scenes",List.of(scene(),42)))).isInstanceOf(ResultAiException.class);}
    @Test void nullSceneIsRejected(){assertThatThrownBy(()->PracticeRecordV2.read(Map.of("flowVersion",PracticeRecordV2.VERSION,"scenes",Arrays.asList((Object)null)))).isInstanceOf(ResultAiException.class);}
}
