package kr.mujoong.core.result;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.config.CoreTimingProperties;
import kr.mujoong.core.identity.GuardianWriteLock;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
@Tag("result-report")
class JobRunnerTest {
    ResultJobRepository jobs;ReportJobState reports;GuardianWriteLock guardians;MutableClock clock;
    JobRunner runner;PlatformTransactionManager transactions;CoreProperties props;
    @BeforeEach void setup(){jobs=mock(ResultJobRepository.class);reports=mock(ReportJobState.class);guardians=mock(GuardianWriteLock.class);when(guardians.statusForShare(any())).thenReturn(Optional.of("ACTIVE"));clock=new MutableClock();props=properties();transactions=mock(PlatformTransactionManager.class);when(transactions.getTransaction(any())).thenAnswer(i->new SimpleTransactionStatus());runner=new JobRunner(jobs,reports,guardians,clock,props,transactions,List.of(),mock(kr.mujoong.core.observability.EventLogger.class));}
    static CoreProperties properties(){var p=mock(CoreProperties.class,RETURNS_DEEP_STUBS);when(p.timing().result()).thenReturn(new CoreTimingProperties.ResultTiming(3,List.of(Duration.ofSeconds(5),Duration.ofSeconds(15)),Duration.ofMillis(10),Duration.ofSeconds(30),Duration.ofMinutes(5),Duration.ofHours(1)));return p;}
    ResultJob job(int attempt){return new ResultJob(UUID.randomUUID(),"SESSION_RESULT",UUID.randomUUID(),UUID.randomUUID(),"key","RUNNING",attempt,clock.now(),clock.now().plusSeconds(30),null,null);}
    void handler(Outcome outcome){runner.register(new JobHandler(){public String jobType(){return "SESSION_RESULT";}public Outcome handle(JobContext c){return outcome;}});}
    @Test void firstFailureWaitsFiveSeconds(){var j=job(1);handler(Outcome.failed("PROVIDER_ERROR",true));runner.execute(j);verify(jobs).transition(j,"REQUESTED","PROVIDER_ERROR",clock.now().plusSeconds(5),clock.now(),"FAILED");}
    @Test void secondFailureWaitsFifteenSeconds(){var j=job(2);handler(Outcome.failed("SCHEMA_INVALID",true));runner.execute(j);verify(jobs).transition(j,"REQUESTED","SCHEMA_INVALID",clock.now().plusSeconds(15),clock.now(),"FAILED");}
    @Test void thirdFailureIsFinal(){var j=job(3);handler(Outcome.failed("TIMEOUT",true));runner.execute(j);verify(jobs).transition(j,"FAILED","TIMEOUT",null,clock.now(),"FAILED");}
    @Test void secondAttemptCanCommit(){var j=job(2);when(guardians.statusForShare(j.guardianId())).thenReturn(Optional.of("ACTIVE"));when(jobs.lock(j.id())).thenReturn(Optional.of(j));when(jobs.transition(j,"DONE",null,null,clock.now(),"SUCCEEDED")).thenReturn(true);runner.register(new JobHandler(){public String jobType(){return "SESSION_RESULT";}public Outcome handle(JobContext c){assertThat(c.commit(()->{})).isTrue();return Outcome.done();}});runner.execute(j);verify(jobs).transition(j,"DONE",null,null,clock.now(),"SUCCEEDED");verify(jobs,never()).transition(eq(j),eq("FAILED"),any(),any(),any(),any());}
    @Test void nonRetryableFailureStops(){var j=job(1);handler(Outcome.failed("ACCOUNT_DELETING",false));runner.execute(j);verify(jobs).transition(j,"FAILED","ACCOUNT_DELETING",null,clock.now(),"FAILED");}
    @Test void leaseExpirationRequestsTimeoutRetry(){var j=job(1);when(jobs.expired(clock.now())).thenReturn(List.of(j));assertThat(runner.expire()).containsExactly(j.id());verify(jobs).transition(j,"REQUESTED","TIMEOUT",clock.now().plusSeconds(5),clock.now(),"FAILED");}
    @Test void duplicateTypeFailsAtRegistration(){handler(Outcome.done());assertThatThrownBy(()->handler(Outcome.done())).isInstanceOf(IllegalStateException.class).hasMessage("DUPLICATE_JOB_HANDLER");}
    @Test void doneWithoutCommitFailsAndContextHasNoRepository(){handler(Outcome.done());var j=job(1);runner.execute(j);verify(jobs).transition(j,"FAILED","INTERNAL",null,clock.now(),"FAILED");assertThat(Arrays.stream(JobContext.class.getDeclaredFields()).map(java.lang.reflect.Field::getType)).doesNotContain(ResultJobRepository.class);}
}
