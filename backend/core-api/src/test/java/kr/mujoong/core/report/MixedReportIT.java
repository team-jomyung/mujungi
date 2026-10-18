package kr.mujoong.core.report;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.record.ActivityRecordRepository.StoredRecord;
import kr.mujoong.core.result.*;
import org.junit.jupiter.api.*;
@Tag("goal-result")
class MixedReportIT {
    UUID goal=UUID.randomUUID();ReportAssembler assembler=new ReportAssembler();
    ReportInput modern(int no,String timing){
        var old=ReportTestData.practice(no,"DONE","FULL",goal,List.of());var r=old.record();
        var scene=new SceneJudgmentRepository.Scene(goal,1,1,"ORIGINAL",null,"functional",timing,null,null,false,null,SceneChipMapper.chip("functional",timing),UUID.randomUUID(),PracticeRecordV2.VERSION,List.of(),null);
        String practice=JsonCodec.shared().write(Map.of("flowVersion",PracticeRecordV2.VERSION,"scenes",List.of(Map.of("sceneRunId",scene.sceneRunId().toString(),"goalRef",goal.toString(),"sceneKind","BASE","startedAt",r.listedAt().toString()))));
        var record=new StoredRecord(r.sessionId(),r.guardianId(),r.childId(),r.activityType(),r.finalStatus(),r.endReason(),r.confirmed(),r.startedAt(),r.endedAt(),r.listedAt(),r.recordFrom(),r.recordTo(),r.transcriptJson(),r.gapsJson(),practice,r.snapshotHash(),r.recordedAt());
        return new ReportInput(no,record,"DONE",old.result(),List.of(scene),Map.of(),List.of());
    }
    @Test void legacyAndNewReaskAreBothPreserved(){var sections=assembler.assemble(ReportTestData.report(),List.of(ReportTestData.practice(1,"DONE","FULL",goal,List.of()),modern(2,"AFTER_REASK")));assertThat(sections.get(3).text()).contains("부탁하기","되묻기 뒤 전달");}
    @Test void sameDisplayChipDoesNotMergeReportTiming(){var sections=assembler.assemble(ReportTestData.report(),List.of(modern(1,"IMMEDIATE"),modern(2,"AFTER_REASK")));assertThat(sections.get(3).text()).contains("바로 전달","되묻기 뒤 전달");}
    @Test void helpTimingStaysDistinct(){assertThat(assembler.assemble(ReportTestData.report(),List.of(modern(1,"AFTER_HELP"))).get(3).text()).contains("도움 뒤 전달");}
    @Test void singleSceneReportDoesNotInventAnother(){assertThat(assembler.assemble(ReportTestData.report(),List.of(modern(1,"AFTER_REASK"))).get(3).text()).contains("장면 1개").doesNotContain("장면 2");}
    @Test void emptyHelpIsNotFailure(){assertThat(assembler.assemble(ReportTestData.report(),List.of(modern(1,"AFTER_REASK"))).get(8).text()).contains("도움 0회");}
    @Test void reportsNeverPublishRawJudgmentFields(){assertThat(assembler.assemble(ReportTestData.report(),List.of(modern(1,"AFTER_REASK"))).get(3).text()).doesNotContain("functional","differs","rationale","AFTER_REASK");}
}
