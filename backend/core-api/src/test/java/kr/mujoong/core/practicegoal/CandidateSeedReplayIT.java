package kr.mujoong.core.practicegoal;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.consent.ConsentPolicyService;
import kr.mujoong.core.identity.GuardianWriteLock;
import kr.mujoong.core.knowledge.*;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.practice.PracticeAccountPort;
import kr.mujoong.core.record.ActivityRecordRepository;
import kr.mujoong.core.result.*;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
@Tag("u14") @Tag("db")
class CandidateSeedReplayIT {
    DbTestSupport db;GoalCandidateRepository goals;CandidateSeedDelivery delivery;ConsentPolicyService consent;SeedGenerationService seeds;
    UUID guardian,child,session,job,result;ActivityResultRepository results;MutableClock clock=new MutableClock();
    @BeforeEach void setup(){
        db=DbTestSupport.coreApp();goals=new GoalCandidateRepository(db.jdbc());guardian=db.insertGuardian("ACTIVE");child=db.insertChild(guardian);session=UUID.randomUUID();UUID device=db.trackDevice(UUID.randomUUID());
        var p=Map.of("g",guardian,"c",child,"s",session,"d",device,"code",DbTestSupport.randomCode(),"hash",DbTestSupport.randomHash(),"key",session.toString());
        db.jdbc().update("insert into core.core_device(id,guardian_id,registration_code,code_issued_at,code_expires_at,bootstrap_secret_hash,registration_status,registered_at,created_at,updated_at) values(:d,:g,:code,now(),now()+interval '1 hour',:hash,'REGISTERED',now(),now(),now())",p);
        db.jdbc().update("insert into core.core_activity(session_id,guardian_id,child_id,device_id,activity_type,status,start_idempotency_key,request_hash,requested_at,created_at,updated_at) values(:s,:g,:c,:d,'FREE_TALK','ENDED',:key,:hash,now(),now(),now())",p);
        new ActivityRecordRepository(db.jdbc()).insertIfAbsent(new ActivityRecordRepository.NewRecord(session,guardian,child,"FREE_TALK","ENDED","CHILD_STOP",true,clock.now(),clock.now(),clock.now(),clock.now(),clock.now(),"[]","[]",null,"[]",null,UUID.randomUUID(),1,"sha256:"+DbTestSupport.randomHash()),clock.now());
        var states=new SessionResultStateRepository(db.jdbc());states.insert(session,guardian,child,"FREE_TALK",true,clock.now());
        var jobs=new ResultJobRepository(db.jdbc());job=jobs.submit("SESSION_RESULT",session,guardian,session.toString(),"RECONCILE",null,clock.now()).id();states.attach(session,job,1,clock.now());results=new ActivityResultRepository(db.jdbc());result=publish();
        var accounts=mock(PracticeAccountPort.class);when(accounts.requireActive(guardian,true)).thenAnswer(i->{new GuardianWriteLock(db.jdbc()).lockActive(guardian);return new PracticeAccountPort.AccountContext(guardian,child,null,List.of());});
        consent=mock(ConsentPolicyService.class);when(consent.check(guardian)).thenReturn(new ConsentPolicyService.GateResult(true,null,new ConsentPolicyService.Snapshot("v1",true,true,true,true)));
        seeds=mock(SeedGenerationService.class);when(seeds.generateSeed(any())).thenReturn(new SeedGenerationService.Result("NONE","DISABLED"));goals=new GoalCandidateRepository(db.jdbc());delivery=new CandidateSeedDelivery(goals,accounts,consent,seeds,db.jdbc(),clock,mock(EventLogger.class),new DataSourceTransactionManager(db.dataSource()));
    }
    UUID publish(){UUID id=results.insert(session,job,"PARTIAL","[]","[]","[]",null,"{}","[]",clock.now()).id();db.jdbc().update("update core.core_session_result_state set job_status='DONE' where session_id=:s",Map.of("s",session));return id;}
    @AfterEach void close(){if(db!=null){goals.deleteGuardian(guardian);db.close();}}
    CandidateSeedDelivery.Pending pending(){return CandidateSeedDelivery.Pending.of(List.of(new GoalCandidateRepository.Draft("물어보기","합성놀이","전달",new FourElements("놀이","친구","참여","말하기"),"NONE","합성근거",List.of(new GoalCandidateRepository.Evidence(1,"같이 할래")),new StructureSignature("PRODUCE","참여 의도를 상대가 이해하도록 표현한다","상대의 의도 1층","YES","YES"))));}
    void deliver(UUID id){delivery.deliver(guardian,child,session,id,pending());}
    @Test void durableReplayDoesNotDuplicateCandidate(){deliver(result);UUID handle=goals.candidates(child,session).getFirst().id();deliver(result);assertThat(goals.candidates(child,session)).hasSize(1);assertThat(goals.candidates(child,session).getFirst().id()).isEqualTo(handle);}
    @Test void staleResultCannotRegister(){UUID old=result;publish();assertThatThrownBy(()->deliver(old)).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);assertThat(goals.candidates(child,session)).isEmpty();}
    @Test void replacementInvalidatesOldCandidatesButKeepsPinnedSelection(){deliver(result);var old=goals.candidates(child,session).getFirst();UUID saved=db.inTx(t->goals.select(guardian,old,clock.now()));UUID latest=publish();deliver(latest);assertThat(goals.candidates(child,session)).hasSize(1);assertThat(goals.version(child,old.id())).isEmpty();assertThat(goals.saved(child,saved).orElseThrow().candidateVersion()).isEqualTo(1);}
    @Test void lateOldPendingCannotReviveOldCandidates(){UUID old=result;deliver(old);UUID latest=publish();deliver(latest);UUID handle=goals.candidates(child,session).getFirst().id();assertThatThrownBy(()->deliver(old)).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);assertThat(goals.candidates(child,session)).hasSize(1);assertThat(goals.candidates(child,session).getFirst().id()).isEqualTo(handle);}
    @Test void removedSourceCannotBeReplayed(){db.jdbc().update("delete from core.core_activity_record where session_id=:s",Map.of("s",session));assertThatThrownBy(()->deliver(result)).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);assertThat(goals.candidates(child,session)).isEmpty();}
    @Test void withdrawnConsentCannotBeReplayed(){when(consent.check(guardian)).thenReturn(new ConsentPolicyService.GateResult(false,null,new ConsentPolicyService.Snapshot("v1",false,true,true,true)));assertThatThrownBy(()->deliver(result)).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);assertThat(goals.candidates(child,session)).isEmpty();}
    @Test void seedFailurePreservesDoneResult(){when(seeds.generateSeed(any())).thenThrow(new IllegalStateException("TIMEOUT"));deliver(result);assertThat(db.jdbc().queryForObject("select job_status from core.core_session_result_state where session_id=:s",Map.of("s",session),String.class)).isEqualTo("DONE");assertThat(goals.candidates(child,session).getFirst().content().skillRef()).isEqualTo("NONE");}
    @Test void successfulSeedPublishesOnceWithoutOverwritingPinnedVersion(){when(seeds.generateSeed(any())).thenReturn(new SeedGenerationService.Result("seed:S1","REUSED"));deliver(result);var version=goals.candidates(child,session).getFirst();assertThat(version.version()).isEqualTo(2);deliver(result);assertThat(goals.candidates(child,session).getFirst().id()).isEqualTo(version.id());verify(seeds,times(1)).generateSeed(any());}
}
