package kr.mujoong.core.result;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.*;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.identity.GuardianWriteLock;
import kr.mujoong.core.record.*;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
@Tag("u6") @Tag("db")
class ResultIntakeIT {
    DbTestSupport db;UUID guardian,child,session;MutableClock clock;ResultRecordReader reader;ResultIntake intake;SessionResultStateRepository states;ResultJobRepository jobs;
    kr.mujoong.core.observability.EventLogger events;
    @BeforeEach void open(){db=DbTestSupport.coreApp();guardian=db.insertGuardian("ACTIVE");child=db.insertChild(guardian);session=UUID.randomUUID();clock=new MutableClock();reader=mock(ResultRecordReader.class);states=new SessionResultStateRepository(db.jdbc());jobs=new ResultJobRepository(db.jdbc());events=mock(kr.mujoong.core.observability.EventLogger.class);intake=new ResultIntake(reader,states,jobs,new GuardianWriteLock(db.jdbc()),clock,JobRunnerTest.properties(),new DataSourceTransactionManager(db.dataSource()),events);}
    @AfterEach void close(){db.jdbc().update("delete from core.core_session_result_state where guardian_id=:g",Map.of("g",guardian));db.jdbc().update("delete from core.core_result_job where guardian_id=:g",Map.of("g",guardian));db.close();}
    ResultRecordReader.Input input(String status,String copy,String failure,boolean hasUtterance){return new ResultRecordReader.Input(new ActivityRecordRepository.StoredRecord(session,guardian,child,"FREE_TALK",status,"CHILD_STOP",true,clock.now(),clock.now(),clock.now(),clock.now(),clock.now(),hasUtterance?"[{}]":"[]","[]",null,"hash",clock.now()),copy,failure,hasUtterance);}
    void given(ResultRecordReader.Input input){when(reader.find(session,guardian)).thenReturn(Optional.of(input));}
    @Test void eventCreatesOneHeadAndJob(){given(input("ENDED","ENDED",null,true));var event=new ActivityRecorded(session,guardian,child,"FREE_TALK","ENDED",clock.now());intake.recorded(event);intake.recorded(event);assertThat(states.find(session,guardian).orElseThrow().runOrdinal()).isEqualTo(1);assertThat(jobs.unfinished()).filteredOn(j->j.guardianId().equals(guardian)).hasSize(1);}
    @Test void declinedAndEmptyRecordsHaveNoJob(){given(input("DECLINED","DECLINED",null,true));intake.accept(session,guardian,"RECONCILE");assertThat(states.find(session,guardian).orElseThrow().jobStatus()).isEqualTo("NONE");assertThat(jobs.unfinished()).filteredOn(j->j.guardianId().equals(guardian)).isEmpty();}
    @Test void zeroUtterancesHaveNoneHead(){given(input("ENDED","ENDED",null,false));intake.accept(session,guardian,"RECONCILE");assertThat(states.find(session,guardian).orElseThrow().jobStatus()).isEqualTo("NONE");}
    @Test void recordBeforeCopyDefersUntilRestartCopyArrives(){given(input("FAILED","ACTIVE",null,true));intake.accept(session,guardian,"ACTIVITY_RECORDED");assertThat(states.find(session,guardian)).isEmpty();given(input("FAILED","FAILED","SERVER_RESTART",true));intake.accept(session,guardian,"RECONCILE");assertThat(states.find(session,guardian).orElseThrow().jobStatus()).isEqualTo("REQUESTED");}
    @Test void missingEventIsRecovered(){given(input("ABORTED","ABORTED",null,true));intake.accept(session,guardian,"RECONCILE");assertThat(states.find(session,guardian).orElseThrow().currentJobId()).isNotNull();}
    @Test void concurrentEventAndReconcileCreateOneJob(){given(input("ENDED","ENDED",null,true));var a=db.start(()->{intake.accept(session,guardian,"RECONCILE");return null;});var b=db.start(()->{intake.accept(session,guardian,"ACTIVITY_RECORDED");return null;});DbTestSupport.await(a);DbTestSupport.await(b);assertThat(jobs.unfinished()).filteredOn(j->j.guardianId().equals(guardian)).hasSize(1);}
    @Test void oldUndecidedRecordBecomesNoneAtOneHour(){given(input("FAILED","ACTIVE",null,true));clock.advance(Duration.ofHours(1));intake.accept(session,guardian,"RECONCILE");assertThat(states.find(session,guardian).orElseThrow().jobStatus()).isEqualTo("NONE");verify(events).appError(kr.mujoong.contract.log.EventLog.Component.CORE_MAINTENANCE,"ResultEligibilityExpired");}
    @Test void springListenerRunsOnlyAfterCommit(){
        given(input("ENDED","ENDED",null,true));
        try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()){
            context.registerBean(org.springframework.transaction.event.TransactionalEventListenerFactory.class);
            context.registerBean(ResultIntake.class,()->intake);context.refresh();
            var event=new ActivityRecorded(session,guardian,child,"FREE_TALK","ENDED",clock.now());
            db.inRolledBackTx(t->{context.publishEvent(event);assertThat(states.find(session,guardian)).isEmpty();return null;});
            assertThat(states.find(session,guardian)).isEmpty();
            db.inTx(t->{context.publishEvent(event);assertThat(states.find(session,guardian)).isEmpty();return null;});
            assertThat(states.find(session,guardian)).isPresent();
        }
    }
    @Test void oneHundredOldUndecidedHeadsNoLongerStarveTheNextRecord(){
        var inputs=new ArrayList<ResultRecordReader.Input>();
        for(int i=0;i<101;i++){session=UUID.randomUUID();var in=input(i<100?"FAILED":"ENDED",i<100?"ACTIVE":"ENDED",null,true);inputs.add(in);when(reader.find(session,guardian)).thenReturn(Optional.of(in));}
        clock.advance(Duration.ofHours(1));
        for(int pass=0;pass<2;pass++){
            var missing=inputs.stream().filter(in->states.find(in.record().sessionId(),guardian).isEmpty()).limit(100).toList();
            for(var in:missing)intake.accept(in.record().sessionId(),guardian,"RECONCILE");
        }
        assertThat(states.find(inputs.getLast().record().sessionId(),guardian).orElseThrow().jobStatus()).isEqualTo("REQUESTED");
        verify(events,times(100)).appError(kr.mujoong.contract.log.EventLog.Component.CORE_MAINTENANCE,"ResultEligibilityExpired");
    }
}
