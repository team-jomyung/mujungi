package kr.mujoong.core.practicegoal;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.*;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.consent.ConsentPolicyService;
import kr.mujoong.core.identity.GuardianWriteLock;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.record.ResultRecordReader;
import kr.mujoong.core.result.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("u14") @Tag("db")
class U14EmptyCandidateReplayIT {
    CandidateSeedReplayIT f;
    @BeforeEach void setup(){f=new CandidateSeedReplayIT();f.setup();}
    @AfterEach void close(){f.close();}
    UUID emptyResult(){UUID id=f.results.insert(f.session,f.job,"INSUFFICIENT","[]","[]","[]",null,"{}",JsonCodec.shared().write(CandidateSeedDelivery.Pending.of(List.of())),f.clock.now()).id();return id;}
    void clear(UUID id){f.delivery.deliver(f.guardian,f.child,f.session,id,CandidateSeedDelivery.Pending.of(List.of()));}
    void withdraw(){when(f.consent.check(f.guardian)).thenReturn(new ConsentPolicyService.GateResult(false,null,new ConsentPolicyService.Snapshot("v1",false,true,true,true)));}
    @Test void emptyReplacementInvalidatesOldHandleButPreservesPinnedSnapshot(){f.deliver(f.result);var old=f.goals.candidates(f.child,f.session).getFirst();UUID selected=f.db.inTx(t->f.goals.select(f.guardian,old,f.clock.now()));clear(emptyResult());assertThat(f.goals.version(f.child,old.id())).isEmpty();assertThat(f.goals.saved(f.child,selected).orElseThrow().candidateVersion()).isEqualTo(1);}
    @Test void emptyCleanupAfterWithdrawalUsesNoSeedAndCompletesReplay(){f.deliver(f.result);UUID next=emptyResult();withdraw();clearInvocations(f.seeds);var properties=mock(CoreProperties.class,RETURNS_DEEP_STUBS);when(properties.timing().result().reconcileInterval()).thenReturn(Duration.ofSeconds(30));var reconciler=new ResultReconciler(mock(ResultRecordReader.class),mock(ResultIntake.class),f.results,new ResultJobRepository(f.db.jdbc()),new SessionResultStateRepository(f.db.jdbc()),mock(kr.mujoong.core.practice.CandidateRegistry.class),mock(ReportRetention.class),f.clock,JsonCodec.shared(),mock(EventLogger.class),properties,new DataSourceTransactionManager(f.db.dataSource()));ReflectionTestUtils.invokeMethod(reconciler,"generatedGoals",f.delivery);reconciler.registerCandidates(f.results.find(next).orElseThrow());reconciler.registerCandidates(f.results.find(next).orElseThrow());assertThat(f.results.find(next).orElseThrow().candidatesRegisteredAt()).isNotNull();assertThat(f.goals.candidates(f.child,f.session)).isEmpty();verifyNoInteractions(f.seeds);}
    @Test void staleEmptyReplayCannotInvalidateTheLatestResult(){f.deliver(f.result);UUID empty=emptyResult();UUID newest=f.publish();f.deliver(newest);UUID handle=f.goals.candidates(f.child,f.session).getFirst().id();assertThatThrownBy(()->clear(empty)).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);assertThat(f.goals.version(f.child,handle)).isPresent();}
    @Test void emptyCleanupCannotCrossChildBoundary(){f.deliver(f.result);UUID empty=emptyResult();assertThatThrownBy(()->f.delivery.deliver(f.guardian,UUID.randomUUID(),f.session,empty,CandidateSeedDelivery.Pending.of(List.of()))).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);assertThat(f.goals.candidates(f.child,f.session)).hasSize(1);}
    @Test void removedRecordRejectsEmptyCleanupWithoutRestoringAnything(){f.deliver(f.result);UUID empty=emptyResult();f.db.jdbc().update("delete from core.core_activity_record where session_id=:s",Map.of("s",f.session));assertThatThrownBy(()->clear(empty)).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);}
    @Test void deletionRequestedGuardianCannotRunEmptyCallback(){f.deliver(f.result);UUID empty=emptyResult();f.db.jdbc().update("update core.core_guardian set status='DELETION_REQUESTED' where id=:g",Map.of("g",f.guardian));assertThatThrownBy(()->clear(empty)).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);}
}
