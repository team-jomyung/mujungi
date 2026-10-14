package kr.mujoong.core.result;
import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import kr.mujoong.core.record.ActivityRecordRepository.StoredRecord;
import org.junit.jupiter.api.*;
@Tag("u6")
class ResultLevelCalculatorTest {
    static StoredRecord record(String type,String status,String end,boolean confirmed){return new StoredRecord(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),type,status,end,confirmed,Instant.EPOCH,Instant.EPOCH.plusSeconds(180),Instant.EPOCH,Instant.EPOCH,Instant.EPOCH.plusSeconds(180),"[]","[]",null,"hash",Instant.EPOCH);}
    static Map<String,Object> utterance(String text,boolean uncertain){return Map.of("speaker","CHILD","text",text,"uncertain",uncertain);}
    static Map<String,Object> goal(boolean response){return Map.of("responses",response?List.of(Map.of("responseType","FIRST","utteranceSeq",1)):List.of());}
    @Test void freeTalkNeedsThreeMeaningfulUtterances(){var r=record("FREE_TALK","ENDED","CHILD_STOP",true);var u=utterance("같이 블록 쌓자",false);assertThat(ResultLevelCalculator.before(r,List.of(u,u),Map.of(),true)).isEqualTo("INSUFFICIENT");assertThat(ResultLevelCalculator.before(r,List.of(u,u,u),Map.of(),true)).isEqualTo("PARTIAL");}
    @Test void twoWordsDoNotCount(){var u=utterance("같이 놀자",false);assertThat(ResultData.meaningful(u)).isFalse();}
    @Test void uncertainDoesNotCount(){assertThat(ResultData.meaningful(utterance("같이 블록 쌓자",true))).isFalse();}
    @Test void practiceNeedsAFirstResponse(){var r=record("PRACTICE","ENDED","CHILD_STOP",true);assertThat(ResultLevelCalculator.before(r,List.of(),Map.of("goals",List.of(goal(false))),true)).isEqualTo("INSUFFICIENT");assertThat(ResultLevelCalculator.before(r,List.of(),Map.of("goals",List.of(goal(true))),true)).isEqualTo("PARTIAL");}
    @Test void everyGoalMustHaveFirstResponseForFull(){var r=record("PRACTICE","ENDED","CHILD_STOP",true);assertThat(ResultLevelCalculator.after(r,Map.of("goals",List.of(goal(true),goal(false))),Map.of())).isEqualTo("PARTIAL");assertThat(ResultLevelCalculator.after(r,Map.of("goals",List.of(goal(true),goal(true))),Map.of())).isEqualTo("FULL");}
    @Test void withdrawnConsentAlwaysInsufficient(){var u=utterance("같이 블록 쌓자",false);assertThat(ResultLevelCalculator.before(record("FREE_TALK","ENDED","CONSENT_WITHDRAWN",true),List.of(u,u,u),Map.of(),true)).isEqualTo("INSUFFICIENT");assertThat(ResultLevelCalculator.before(record("FREE_TALK","ENDED","CHILD_STOP",true),List.of(u,u,u),Map.of(),false)).isEqualTo("INSUFFICIENT");}
    @Test void abnormalEndNeverFull(){for(String state:List.of("ENDED_UNCONFIRMED","ABORTED","FAILED"))assertThat(ResultLevelCalculator.after(record("FREE_TALK",state,"CHILD_STOP",true),Map.of(),Map.of("situation",Map.of("source","CHILD_RECALL")))).isEqualTo("PARTIAL");}
    @Test void freeTalkFullRequiresRecall(){var r=record("FREE_TALK","ENDED","CHILD_STOP",true);assertThat(ResultLevelCalculator.after(r,Map.of(),Map.of())).isEqualTo("PARTIAL");assertThat(ResultLevelCalculator.after(r,Map.of(),Map.of("situation",Map.of("source","CHILD_RECALL")))).isEqualTo("FULL");}
    @Test void characterRecallAlsoCountsAsStructuredEvidence(){assertThat(ResultLevelCalculator.after(record("FREE_TALK","ENDED","CHILD_STOP",true),Map.of(),Map.of("characters",List.of(Map.of("alias","친구 A","source","CHILD_RECALL","utteranceSeq",1))))).isEqualTo("FULL");}
}
