package kr.mujoong.core.result;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.*;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.consent.ConsentPolicyService;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.core.identity.GuardianWriteLock;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
@Tag("result-report") @Tag("db")
class ResultRetryIT {
    DbTestSupport db;ResultJobRepository jobs;SessionResultStateRepository states;ActivityResultRepository results;ResultRetryService retry;MutableClock clock;UUID guardian,child,session;
    ConsentPolicyService consent;
    @BeforeEach void setup(){db=DbTestSupport.coreApp();guardian=db.insertGuardian("ACTIVE");child=db.insertChild(guardian);session=UUID.randomUUID();clock=new MutableClock();jobs=new ResultJobRepository(db.jdbc());states=new SessionResultStateRepository(db.jdbc());results=new ActivityResultRepository(db.jdbc());consent=mock(ConsentPolicyService.class);when(consent.check(guardian)).thenReturn(new ConsentPolicyService.GateResult(true,null,new ConsentPolicyService.Snapshot("v1",true,true,true,true)));retry=new ResultRetryService(states,jobs,new GuardianWriteLock(db.jdbc()),clock,new DataSourceTransactionManager(db.dataSource()),consent);}
    @AfterEach void close(){db.jdbc().update("delete from core.core_session_result_state where guardian_id=:g",Map.of("g",guardian));db.jdbc().update("delete from core.core_activity_result where session_id=:s",Map.of("s",session));db.jdbc().update("delete from core.core_result_job_attempt where job_id in(select id from core.core_result_job where guardian_id=:g)",Map.of("g",guardian));db.jdbc().update("delete from core.core_result_job where guardian_id=:g",Map.of("g",guardian));db.close();}
    ResultJob initial(String status){return db.inTx(t->{states.insert(session,guardian,child,"FREE_TALK",true,clock.now());var j=jobs.submit("SESSION_RESULT",session,guardian,"SR:"+session+":1","RECONCILE",null,clock.now());states.attach(session,j.id(),1,clock.now());var claimed=jobs.claim("SESSION_RESULT",1,clock.now(),Duration.ofSeconds(30)).getFirst();if(!status.equals("RUNNING"))jobs.transition(claimed,status,status.equals("FAILED")?"INTERNAL":null,null,clock.now(),status.equals("DONE")?"SUCCEEDED":"FAILED");return claimed;});}
    @Test void failedStartsNewOrdinal(){initial("FAILED");retry.retry(guardian,session);assertThat(states.find(session,guardian).orElseThrow().runOrdinal()).isEqualTo(2);assertThat(states.find(session,guardian).orElseThrow().jobStatus()).isEqualTo("REQUESTED");}
    @Test void doneRejectsRetry(){initial("DONE");assertThatThrownBy(()->retry.retry(guardian,session)).isInstanceOf(CoreApiException.class);}
    @Test void missingCurrentConsentRejectsRetryWithoutCreatingJob(){initial("FAILED");when(consent.check(guardian)).thenReturn(new ConsentPolicyService.GateResult(false,null,new ConsentPolicyService.Snapshot("v1",false,true,true,true)));assertThatThrownBy(()->retry.retry(guardian,session)).isInstanceOfSatisfying(CoreApiException.class,e->assertThat(e.code()).isEqualTo(ErrorCode.CONSENT_MISSING));assertThat(states.find(session,guardian).orElseThrow().runOrdinal()).isEqualTo(1);assertThat(states.find(session,guardian).orElseThrow().jobStatus()).isEqualTo("FAILED");assertThat(db.jdbc().queryForObject("select count(*) from core.core_result_job where guardian_id=:g",Map.of("g",guardian),Integer.class)).isEqualTo(1);}
    @Test void runningRejectsRetry(){initial("RUNNING");assertThatThrownBy(()->retry.retry(guardian,session)).isInstanceOf(CoreApiException.class);}
    @Test void threeConcurrentRetriesCreateOneJob(){initial("FAILED");var attempts=new ArrayList<java.util.concurrent.Future<Boolean>>();for(int i=0;i<3;i++)attempts.add(db.start(()->{try{retry.retry(guardian,session);return true;}catch(CoreApiException e){return false;}}));assertThat(attempts.stream().map(DbTestSupport::await).filter(Boolean::booleanValue).count()).isEqualTo(1);assertThat(states.find(session,guardian).orElseThrow().runOrdinal()).isEqualTo(2);}
    @Test void failedRetryKeepsPreviousResult(){var old=initial("FAILED");var previous=db.inTx(t->results.insert(session,old.id(),"PARTIAL","[]","[]","[]",null,"[]","[]",clock.now()));retry.retry(guardian,session);db.inTx(t->{var claimed=jobs.claim("SESSION_RESULT",1,clock.now(),Duration.ofSeconds(30)).getFirst();jobs.transition(claimed,"FAILED","INTERNAL",null,clock.now(),"FAILED");return null;});assertThat(results.current(session).orElseThrow().id()).isEqualTo(previous.id());}
    @Test void successfulRetryReplacesCurrentVersion(){var old=initial("FAILED");var previous=db.inTx(t->results.insert(session,old.id(),"PARTIAL","[]","[]","[]",null,"[]","[]",clock.now()));retry.retry(guardian,session);db.inTx(t->{var claimed=jobs.claim("SESSION_RESULT",1,clock.now(),Duration.ofSeconds(30)).getFirst();results.insert(session,claimed.id(),"FULL","[]","[]","[]",null,"[]","[]",clock.now());results.invalidate(previous.id(),clock.now());jobs.transition(claimed,"DONE",null,null,clock.now(),"SUCCEEDED");return null;});assertThat(results.current(session).orElseThrow().version()).isEqualTo(2);assertThat(results.find(previous.id()).orElseThrow().invalidatedAt()).isNotNull();}
}
