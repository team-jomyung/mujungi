package kr.mujoong.core.practicegoal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.consent.ConsentPolicyService;
import kr.mujoong.core.identity.GuardianWriteLock;
import kr.mujoong.core.practice.PracticeAccountPort;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

@Tag("goal-result") @Tag("db")
class CandidateVersionSelectionIT {
    DbTestSupport db;GoalCandidateRepository repository;GeneratedGoalService service;ConsentPolicyService consent;
    CandidateSeedReplayIT fixture;
    UUID guardian,child,session;MutableClock clock=new MutableClock();
    @BeforeEach void setup(){
        fixture=new CandidateSeedReplayIT();fixture.setup();db=fixture.db;guardian=fixture.guardian;child=fixture.child;session=fixture.session;
        repository=new GoalCandidateRepository(db.jdbc());var accounts=mock(PracticeAccountPort.class);
        var context=new PracticeAccountPort.AccountContext(guardian,child,null,List.of());
        when(accounts.requireActive(guardian,true)).thenAnswer(i->{new GuardianWriteLock(db.jdbc()).lockActive(guardian);return context;});
        when(accounts.requireActive(guardian,false)).thenReturn(context);when(accounts.requireChild(child,false)).thenReturn(context);
        consent=mock(ConsentPolicyService.class);when(consent.check(guardian)).thenReturn(new ConsentPolicyService.GateResult(true,null,new ConsentPolicyService.Snapshot("v1",true,true,true,true)));
        service=new GeneratedGoalService(repository,accounts,consent,clock,new DataSourceTransactionManager(db.dataSource()));
    }
    @AfterEach void close(){if(fixture!=null)fixture.close();}
    GoalCandidateRepository.Version version(){return db.inTx(t->repository.register(child,session,fixture.result+":0",new GoalCandidateRepository.Draft("합성목표","합성장면","전달",new FourElements("놀이","친구","참여","말하기"),"NONE","합성 근거",List.of(new GoalCandidateRepository.Evidence(1,"같이 할래")),null),clock.now()));}
    @Test void observedOldHandlePinsOldVersion(){var old=version();db.inTx(t->repository.linkSeed(old,"seed:1",clock.now()));var saved=service.add(guardian,old.id()).orElseThrow();assertThat(repository.saved(child,saved.id()).orElseThrow().skillRef()).isEqualTo("NONE");}
    @Test void latestHandlePinsSeed(){var old=version();var latest=db.inTx(t->repository.linkSeed(old,"seed:1",clock.now())).orElseThrow();var saved=service.add(guardian,latest.id()).orElseThrow();assertThat(repository.saved(child,saved.id()).orElseThrow().skillRef()).isEqualTo("seed:1");}
    @Test void newVersionDoesNotReplaceActiveSelection(){var old=version();var saved=service.add(guardian,old.id()).orElseThrow();var latest=db.inTx(t->repository.linkSeed(old,"seed:1",clock.now())).orElseThrow();assertThat(service.add(guardian,latest.id()).orElseThrow().id()).isEqualTo(saved.id());}
    @Test void concurrentDifferentHandlesCreateOneSelection(){var old=version();var latest=db.inTx(t->repository.linkSeed(old,"seed:1",clock.now())).orElseThrow();var first=db.start(()->service.add(guardian,old.id()).orElseThrow());var second=db.start(()->service.add(guardian,latest.id()).orElseThrow());assertThat(DbTestSupport.await(first).id()).isEqualTo(DbTestSupport.await(second).id());assertThat(repository.list(guardian)).hasSize(1);}
    @Test void deleteThenNewVersionSelectionConflictsWithOldRestore(){var old=version();var first=service.add(guardian,old.id()).orElseThrow();service.delete(guardian,first.id(),java.time.Duration.ofSeconds(30));var latest=db.inTx(t->repository.linkSeed(old,"seed:1",clock.now())).orElseThrow();var next=service.add(guardian,latest.id()).orElseThrow();assertThat(next.id()).isNotEqualTo(first.id());assertThatThrownBy(()->service.restore(guardian,first.id())).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);}
    @Test void sameHandleRetryIsIdempotent(){var v=version();assertThat(service.add(guardian,v.id()).orElseThrow().id()).isEqualTo(service.add(guardian,v.id()).orElseThrow().id());}
    @Test void withdrawnConsentCannotSelect(){var v=version();when(consent.check(guardian)).thenReturn(new ConsentPolicyService.GateResult(false,null,new ConsentPolicyService.Snapshot("v1",false,true,true,true)));assertThatThrownBy(()->service.add(guardian,v.id())).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);assertThat(repository.list(guardian)).isEmpty();}
    @Test void favoriteAndRestoreKeepPinnedContext(){var v=version();var selected=service.add(guardian,v.id()).orElseThrow();assertThat(service.favorite(guardian,selected.id(),true).isFavorite()).isTrue();service.delete(guardian,selected.id(),java.time.Duration.ofSeconds(30));assertThat(service.restore(guardian,selected.id()).recommendationId()).isEqualTo(v.id());}
    @Test void replacedResultRejectsOldHandleBeforePendingDelivery(){var v=version();fixture.publish();assertThat(repository.version(child,v.id())).isPresent();assertThatThrownBy(()->service.add(guardian,v.id())).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);assertThat(repository.list(guardian)).isEmpty();}
    @Test void replacedResultKeepsAlreadySelectedSnapshotReadable(){var v=version();var selected=service.add(guardian,v.id()).orElseThrow();fixture.publish();assertThat(repository.saved(child,selected.id()).orElseThrow().candidateVersion()).isEqualTo(1);}
    @Test void removedSourceCannotBeSelectedEvenBeforeCleanup(){var v=version();db.jdbc().update("delete from core.core_activity_record where session_id=:s",Map.of("s",session));assertThatThrownBy(()->service.add(guardian,v.id())).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);}
    UUID savedGoal(int index){
        var v=repository.register(child,session,fixture.result+":"+index,fixture.pending().candidates().getFirst(),clock.now());
        return service.add(guardian,v.id()).orElseThrow().id();
    }
    void limitError(Runnable request){
        assertThatThrownBy(request::run).isInstanceOfSatisfying(kr.mujoong.core.common.error.CoreApiException.class,
            error->assertThat(error.code()).isEqualTo(kr.mujoong.contract.error.ErrorCode.FAVORITE_LIMIT_REACHED));
    }
    @Test void generatedFavoritesAllowFiveAndIdempotentUpdatesButRejectTheSixth(){
        List<UUID> ids=new ArrayList<>();for(int i=0;i<6;i++)ids.add(savedGoal(i));
        for(int i=0;i<5;i++)service.favorite(guardian,ids.get(i),true);
        limitError(()->service.favorite(guardian,ids.get(5),true));
        assertThat(service.favorite(guardian,ids.getFirst(),true).isFavorite()).isTrue();
        service.favorite(guardian,ids.getFirst(),false);
        assertThat(service.favorite(guardian,ids.get(5),true).isFavorite()).isTrue();
        assertThat(repository.list(guardian).stream().filter(kr.mujoong.core.practice.GoalSelection::isFavorite)).hasSize(5);
    }
    @Test void concurrentFavoritesCannotBothTakeTheFifthSlot(){
        for(int i=0;i<4;i++)service.favorite(guardian,savedGoal(i),true);
        UUID a=savedGoal(4),b=savedGoal(5);
        java.util.function.Function<UUID,Boolean> attempt=id->{try{service.favorite(guardian,id,true);return true;}
            catch(kr.mujoong.core.common.error.CoreApiException e){assertThat(e.code()).isEqualTo(kr.mujoong.contract.error.ErrorCode.FAVORITE_LIMIT_REACHED);return false;}};
        var first=db.start(()->attempt.apply(a));var second=db.start(()->attempt.apply(b));
        assertThat(List.of(DbTestSupport.await(first),DbTestSupport.await(second))).containsExactlyInAnyOrder(true,false);
        assertThat(repository.list(guardian).stream().filter(kr.mujoong.core.practice.GoalSelection::isFavorite)).hasSize(5);
    }
    @Test void restoreCannotExceedFiveAndCanRetryAfterFreeingASlot(){
        List<UUID> ids=new ArrayList<>();for(int i=0;i<6;i++)ids.add(savedGoal(i));
        for(int i=0;i<5;i++)service.favorite(guardian,ids.get(i),true);
        service.delete(guardian,ids.getFirst(),java.time.Duration.ofSeconds(30));
        service.favorite(guardian,ids.get(5),true);
        limitError(()->service.restore(guardian,ids.getFirst()));
        assertThat(repository.saved(child,ids.getFirst())).isEmpty();
        service.favorite(guardian,ids.get(5),false);
        assertThat(service.restore(guardian,ids.getFirst()).isFavorite()).isTrue();
    }
}
