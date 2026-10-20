package kr.mujoong.core.practice;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.*;
import kr.mujoong.core.common.db.*;
import kr.mujoong.core.identity.GuardianWriteLock;
import kr.mujoong.core.knowledge.*;
import kr.mujoong.core.practicegoal.GeneratedGoalService;
import kr.mujoong.core.textai.ForbiddenExpressionPolicy;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** U14 ownership move must keep the legacy recommendation and selection lifecycle intact. */
@Tag("goal-result") @Tag("db")
class LegacyCompatibilityIT {
    DbTestSupport db;UUID guardian,child,session;PracticeGoalRepository repository;CandidateRegistry registry;GoalSelectionService service;MutableClock clock;
    CandidateEvidencePort evidence;
    @BeforeEach void setup(){
        db=DbTestSupport.coreApp();guardian=db.insertGuardian("ACTIVE");child=db.insertChild(guardian);session=UUID.randomUUID();clock=new MutableClock();repository=new PracticeGoalRepository(db.jdbc());
        var accounts=mock(PracticeAccountPort.class);var context=new PracticeAccountPort.AccountContext(guardian,child,null,List.of());
        when(accounts.requireChild(child,true)).thenAnswer(i->{new GuardianWriteLock(db.jdbc()).lockActive(guardian);return context;});
        when(accounts.requireActive(guardian,true)).thenAnswer(i->{new GuardianWriteLock(db.jdbc()).lockActive(guardian);return context;});when(accounts.requireActive(guardian,false)).thenReturn(context);
        var knowledge=mock(PragmaticsKnowledge.class);when(knowledge.pinVersion("ACCEPT_STOP",1)).thenReturn(new BehaviorDefinition(UUID.randomUUID(),"ACCEPT_STOP",1,"멈춤 받아들이기","합성 정의","합성 기회","합성 장면","합성 기준","ACTIVE",clock.now(),null));
        var expressions=mock(ForbiddenExpressionPolicy.class);when(expressions.isAllowed(anyString())).thenReturn(true);evidence=mock(CandidateEvidencePort.class);when(evidence.verifies(any(),any(),any())).thenReturn(true);
        var retry=new DbRetry(0,new Random(1),d->{});var transactions=new DataSourceTransactionManager(db.dataSource());
        registry=new CandidateRegistry(accounts,evidence,repository,knowledge,expressions,clock,retry,transactions);
        service=new GoalSelectionService(accounts,repository,knowledge,(c,g,s)->GoalMetadataPort.Metadata.unavailable(),clock,Duration.ofSeconds(30),retry,transactions);
        service.generatedGoals(mock(GeneratedGoalService.class));
    }
    @AfterEach void close(){if(db!=null){repository.deleteByGuardian(guardian,List.of(child));db.close();}}
    CandidateRegistry.Candidate draft(){return new CandidateRegistry.Candidate("ACCEPT_STOP",1,"합성 공개 근거",List.of(new CandidateRegistry.Evidence(1,"CHILD_RECALL")));}
    UUID candidate(){return registry.register(session,child,List.of(draft())).getFirst();}
    @Test void facadeRegistersAndReadsLegacyEvidenceIdempotently(){UUID id=candidate();assertThat(candidate()).isEqualTo(id);assertThat(registry.viewForSession(session,guardian)).hasSize(1);assertThat(registry.viewForResult(session,guardian).getFirst().evidence()).containsExactly(new CandidateRegistry.Evidence(1,"CHILD_RECALL"));}
    @Test void selectingLegacyRecommendationPreservesMetadataAndDuplicateConflict(){UUID id=candidate();var selected=service.add(guardian,id);assertThat(selected.displayName()).isEqualTo("멈춤 받아들이기");assertThat(selected.sourceActivityType()).isNull();assertThat(service.list(guardian)).extracting(GoalSelectionService.View::goalSelectionId).containsExactly(selected.goalSelectionId());assertThatThrownBy(()->service.add(guardian,id)).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);}
    @Test void favoriteRemainsAnOwnedPersistentMutation(){UUID selected=service.add(guardian,candidate()).goalSelectionId();assertThat(service.favorite(guardian,selected,true).isFavorite()).isTrue();assertThat(service.list(guardian).getFirst().isFavorite()).isTrue();assertThat(service.favorite(guardian,selected,false).isFavorite()).isFalse();}
    @Test void deleteAndRestorePreserveLegacySelectionIdentity(){UUID selected=service.add(guardian,candidate()).goalSelectionId();var until=service.delete(guardian,selected);assertThat(service.list(guardian)).isEmpty();assertThat(service.delete(guardian,selected)).isEqualTo(until);assertThat(service.restore(guardian,selected).goalSelectionId()).isEqualTo(selected);assertThat(service.restore(guardian,selected).goalSelectionId()).isEqualTo(selected);}
    @Test void readdingDeletedLegacyCandidateReactivatesOriginalSelection(){UUID id=candidate(),selected=service.add(guardian,id).goalSelectionId();service.delete(guardian,selected);assertThat(service.add(guardian,id).goalSelectionId()).isEqualTo(selected);}
    @Test void expiredRestoreAndUnverifiedEvidenceStayRejected(){UUID selected=service.add(guardian,candidate()).goalSelectionId();service.delete(guardian,selected);clock.advance(Duration.ofSeconds(31));assertThatThrownBy(()->service.restore(guardian,selected)).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);when(evidence.verifies(any(),any(),any())).thenReturn(false);assertThatThrownBy(()->registry.register(session,child,List.of(draft()))).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);}
}
