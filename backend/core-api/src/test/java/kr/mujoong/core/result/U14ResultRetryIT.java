package kr.mujoong.core.result;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.config.CoreTimingProperties;
import kr.mujoong.core.identity.GuardianWriteLock;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.report.*;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
@Tag("u14") @Tag("db")
class U14ResultRetryIT {
    DbTestSupport db;UUID guardian,child,session;ResultJobRepository jobs;SessionResultStateRepository states;JobRunner runner;MutableClock clock=new MutableClock();int calls;
    @BeforeEach void setup(){db=DbTestSupport.coreApp();guardian=db.insertGuardian("ACTIVE");child=db.insertChild(guardian);session=UUID.randomUUID();jobs=new ResultJobRepository(db.jdbc());states=new SessionResultStateRepository(db.jdbc());states.insert(session,guardian,child,"FREE_TALK",true,clock.now());var properties=mock(CoreProperties.class,RETURNS_DEEP_STUBS);when(properties.timing().result()).thenReturn(timing());runner=new JobRunner(jobs,new ReportJobStateAdapter(new ReportRepository(db.jdbc())),new GuardianWriteLock(db.jdbc()),clock,properties,new DataSourceTransactionManager(db.dataSource()),List.of(),mock(EventLogger.class));}
    static CoreTimingProperties.ResultTiming timing(){return new CoreTimingProperties.ResultTiming(4,List.of(Duration.ofSeconds(5),Duration.ofSeconds(15),Duration.ofSeconds(30)),Duration.ofMillis(10),Duration.ofSeconds(30),Duration.ofMinutes(5),Duration.ofHours(1));}
    @AfterEach void close(){db.close();}
    ResultJob submit(){var j=jobs.submit("SESSION_RESULT",session,guardian,session.toString(),"RECONCILE",null,clock.now());states.attach(session,j.id(),1,clock.now());return j;}
    void failing(String code){runner.register(new JobHandler(){public String jobType(){return "SESSION_RESULT";}public Outcome handle(JobContext context){calls++;return Outcome.failed(code,true);}});}
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"TIMEOUT","SCHEMA_INVALID","PROVIDER_ERROR","PROVIDER_UNAVAILABLE"})
    void exactlyFourAttemptsAndBackoffs(String code){
        failing(code);var original=submit();long[] delays={5,15,30};
        for(int attempt=1;attempt<=4;attempt++){
            var claimed=runner.claim("SESSION_RESULT").getFirst();assertThat(claimed.attemptCount()).isEqualTo(attempt);runner.execute(claimed);var stored=jobs.find(original.id()).orElseThrow();
            if(attempt<4){assertThat(stored.status()).isEqualTo("REQUESTED");assertThat(stored.nextAttemptAt()).isEqualTo(clock.now().plusSeconds(delays[attempt-1]));assertThat(runner.claim("SESSION_RESULT")).isEmpty();clock.advance(Duration.ofSeconds(delays[attempt-1]));}
            else assertThat(stored.status()).isEqualTo("FAILED");
        }
        assertThat(calls).isEqualTo(4);clock.advance(Duration.ofDays(1));assertThat(runner.claim("SESSION_RESULT")).isEmpty();assertThat(db.jdbc().queryForObject("select count(*) from core.core_result_job_attempt where job_id=:j",Map.of("j",original.id()),Integer.class)).isEqualTo(4);
    }
    @Test void fourthAttemptCanCommitSuccessfully(){runner.register(new JobHandler(){public String jobType(){return "SESSION_RESULT";}public Outcome handle(JobContext context){calls++;if(calls<4)return Outcome.failed("TIMEOUT",true);return context.commit(()->{})?Outcome.done():Outcome.failed("INTERNAL",false);}});var job=submit();for(long delay:new long[]{5,15,30}){runner.execute(runner.claim("SESSION_RESULT").getFirst());clock.advance(Duration.ofSeconds(delay));}runner.execute(runner.claim("SESSION_RESULT").getFirst());assertThat(jobs.find(job.id()).orElseThrow().status()).isEqualTo("DONE");assertThat(calls).isEqualTo(4);}
    @Test void nonRetryableFailureDoesNotConsumeFourAttempts(){runner.register(new JobHandler(){public String jobType(){return "SESSION_RESULT";}public Outcome handle(JobContext context){calls++;return Outcome.failed("INTERNAL",false);}});var job=submit();runner.execute(runner.claim("SESSION_RESULT").getFirst());assertThat(jobs.find(job.id()).orElseThrow().status()).isEqualTo("FAILED");assertThat(calls).isEqualTo(1);}
}
