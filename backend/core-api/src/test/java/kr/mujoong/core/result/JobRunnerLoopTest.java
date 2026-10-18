package kr.mujoong.core.result;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
@Tag("result-report")
class JobRunnerLoopTest {
    @Test void slowSessionDoesNotStarveReport()throws Exception{
        var runner=mock(JobRunner.class);var session=job("SESSION_RESULT");var report=job("REPORT");
        var sessionStarted=new CountDownLatch(1);var releaseSession=new CountDownLatch(1);var reportDone=new CountDownLatch(1);
        when(runner.claim("REPORT")).thenAnswer(call->sessionStarted.getCount()==0?List.of(report):List.of());
        when(runner.claim("SESSION_RESULT")).thenReturn(List.of(session));
        doAnswer(call->{if(((ResultJob)call.getArgument(0)).jobType().equals("SESSION_RESULT")){sessionStarted.countDown();releaseSession.await(5,TimeUnit.SECONDS);}else reportDone.countDown();return null;}).when(runner).execute(any());
        var loop=new JobRunnerLoop(runner,JobRunnerTest.properties(),mock(kr.mujoong.core.observability.EventLogger.class));
        try{loop.start();assertThat(sessionStarted.await(5,TimeUnit.SECONDS)).isTrue();assertThat(reportDone.await(5,TimeUnit.SECONDS)).isTrue();assertThat(releaseSession.getCount()).isEqualTo(1);}
        finally{releaseSession.countDown();loop.stop();}
        assertThat(loop.isRunning()).isFalse();
    }
    private ResultJob job(String type){return new ResultJob(UUID.randomUUID(),type,UUID.randomUUID(),UUID.randomUUID(),"key","RUNNING",1,Instant.EPOCH,Instant.EPOCH.plusSeconds(30),null,null);}
}
