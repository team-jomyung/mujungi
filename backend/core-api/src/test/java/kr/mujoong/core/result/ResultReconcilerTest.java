package kr.mujoong.core.result;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.practice.CandidateRegistry;
import kr.mujoong.core.record.ResultRecordReader;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
@Tag("u6")
class ResultReconcilerTest {
    @Test void failedCandidateRegistrationIsRetriedWithoutChangingDone(){
        var reader=mock(ResultRecordReader.class);var intake=mock(ResultIntake.class);var results=mock(ActivityResultRepository.class);var jobs=mock(ResultJobRepository.class);var states=mock(SessionResultStateRepository.class);var candidates=mock(CandidateRegistry.class);var retention=mock(ReportRetention.class);var events=mock(EventLogger.class);var tm=mock(PlatformTransactionManager.class);when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());var clock=new MutableClock();UUID id=UUID.randomUUID(),session=UUID.randomUUID(),job=UUID.randomUUID(),guardian=UUID.randomUUID(),child=UUID.randomUUID();
        var result=new ActivityResultRepository.Result(id,session,1,job,"PARTIAL","[]","[]","[]",null,"[]",JsonCodec.shared().write(List.of(new CandidateRegistry.Candidate("key",1,"함께 말했어요",List.of(new CandidateRegistry.Evidence(1,"CHILD_RECALL"))))),null,clock.now(),null);
        when(results.pendingCandidates()).thenReturn(List.of(result));when(jobs.find(job)).thenReturn(Optional.of(new ResultJob(job,"SESSION_RESULT",session,guardian,"key","DONE",1,clock.now(),null,null,null)));when(states.find(session,guardian)).thenReturn(Optional.of(new SessionResultStateRepository.State(session,guardian,child,"FREE_TALK","DONE",job,id,1)));
        when(candidates.register(eq(session),eq(child),anyList())).thenThrow(new IllegalStateException("일시 실패")).thenReturn(List.of(UUID.randomUUID()));
        var reconciler=new ResultReconciler(reader,intake,results,jobs,states,candidates,retention,clock,JsonCodec.shared(),events,JobRunnerTest.properties(),tm);
        reconciler.runOnce();verify(results,never()).candidatesRegistered(any(),any());reconciler.runOnce();verify(results).candidatesRegistered(id,clock.now());verify(candidates,times(2)).register(eq(session),eq(child),anyList());verify(jobs,never()).transition(any(),any(),any(),any(),any(),any());verify(retention,times(2)).discardExpired();
    }
}
