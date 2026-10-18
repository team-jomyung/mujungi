package kr.mujoong.core.result;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import kr.mujoong.core.practice.CandidateEvidencePort;
import kr.mujoong.core.record.*;
import org.junit.jupiter.api.*;
@Tag("result-report")
class ResultEvidenceAdapterIT {
    ResultRecordReader reader=mock(ResultRecordReader.class);ResultEvidenceAdapter adapter=new ResultEvidenceAdapter(reader);UUID child=UUID.randomUUID(),session=UUID.randomUUID();
    @BeforeEach void setup(){when(reader.findForChild(session,child)).thenReturn(Optional.of(new ActivityRecordRepository.StoredRecord(session,UUID.randomUUID(),child,"FREE_TALK","ENDED","CHILD_STOP",true,Instant.EPOCH,Instant.EPOCH,Instant.EPOCH,Instant.EPOCH,Instant.EPOCH,"[{\"seq\":1,\"speaker\":\"CHILD\",\"text\":\"같이 놀자\"},{\"seq\":2,\"speaker\":\"AI\",\"text\":\"좋아\"}]","[]",null,"hash",Instant.EPOCH)));}
    boolean verify(UUID childId,UUID sessionId,long seq){return adapter.verifies(childId,sessionId,List.of(new CandidateEvidencePort.Evidence(sessionId,seq,"CHILD_RECALL")));}
    @Test void acceptsActualChildUtterance(){assertThat(verify(child,session,1)).isTrue();}
    @Test void rejectsMissingSequence(){assertThat(verify(child,session,3)).isFalse();}
    @Test void rejectsOtherChildOrSession(){assertThat(verify(UUID.randomUUID(),session,1)).isFalse();assertThat(verify(child,UUID.randomUUID(),1)).isFalse();}
    @Test void rejectsAiUtterance(){assertThat(verify(child,session,2)).isFalse();}
}
