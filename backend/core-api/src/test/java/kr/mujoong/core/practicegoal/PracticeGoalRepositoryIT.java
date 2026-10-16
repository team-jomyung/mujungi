package kr.mujoong.core.practicegoal;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import kr.mujoong.core.common.db.DbTestSupport;
import org.junit.jupiter.api.*;

@Tag("u14") @Tag("db")
class PracticeGoalRepositoryIT {
    DbTestSupport db;GoalCandidateRepository goals;UUID guardian,child,session;Instant now=DbTestSupport.EPOCH;
    @BeforeEach void setup(){db=DbTestSupport.coreApp();goals=new GoalCandidateRepository(db.jdbc());guardian=db.insertGuardian("ACTIVE");child=db.insertChild(guardian);session=UUID.randomUUID();}
    @AfterEach void close(){if(db!=null){goals.deleteGuardian(guardian);db.close();}}
    GoalCandidateRepository.Draft draft(){return new GoalCandidateRepository.Draft("물어보기","합성 놀이 장면","함께 물어보기",new FourElements("친구와 놀 때","친구","참여하고 싶다","함께 해도 돼?"),"NONE","합성 근거",List.of(new GoalCandidateRepository.Evidence(1,"함께 해도 돼?")),null);}
    GoalCandidateRepository.Version register(){return db.inTx(t->goals.register(child,session,"source:0",draft(),now));}
    @Test void storesFullCandidateContext(){var v=register();assertThat(v.content()).isEqualTo(draft());assertThat(v.version()).isEqualTo(1);}
    @Test void legacySavedGoalReaderPreservesBehaviorVersion(){
        var legacy=new kr.mujoong.core.practice.PracticeGoalRepository(db.jdbc());
        var candidate=new kr.mujoong.core.practice.CandidateRegistry.Candidate("ACCEPT_STOP",1,"합성 근거",List.of(new kr.mujoong.core.practice.CandidateRegistry.Evidence(1,"CHILD_RECALL")));
        UUID recommendation=db.inTx(t->legacy.register(child,session,candidate,"합성목표",now));
        UUID selection=db.inTx(t->legacy.insertSelection(guardian,child,new kr.mujoong.core.practice.PracticeGoalRepository.Recommendation(recommendation,"ACCEPT_STOP",1,"ACTIVE"),"합성목표",now));
        var accounts=org.mockito.Mockito.mock(kr.mujoong.core.practice.PracticeAccountPort.class);
        org.mockito.Mockito.when(accounts.requireChild(child,false)).thenReturn(new kr.mujoong.core.practice.PracticeAccountPort.AccountContext(guardian,child,null,List.of()));
        var consent=org.mockito.Mockito.mock(kr.mujoong.core.consent.ConsentPolicyService.class);
        org.mockito.Mockito.when(consent.check(guardian)).thenReturn(new kr.mujoong.core.consent.ConsentPolicyService.GateResult(true,null,new kr.mujoong.core.consent.ConsentPolicyService.Snapshot("v1",true,true,true,true)));
        var view=new PinnedSavedGoalReader(goals,accounts,consent,db.jdbc()).find(child,selection).orElseThrow();
        assertThat(view.kind()).isEqualTo(SavedGoalReader.SavedGoalView.Kind.LEGACY_BEHAVIOR);assertThat(view.legacyBehaviorKey()).isEqualTo("ACCEPT_STOP");assertThat(view.legacyBehaviorVersion()).isEqualTo(1);
        legacy.deleteByGuardian(guardian,List.of(child));
    }
    @Test void registeringReplayKeepsImmutableHandle(){var first=register();assertThat(register().id()).isEqualTo(first.id());}
    @Test void versionHandleIsChildScoped(){var v=register();assertThat(goals.version(UUID.randomUUID(),v.id())).isEmpty();}
    @Test void oldVersionHandleStillPinsNoneAfterSeedPublished(){var v1=register();var v2=db.inTx(t->goals.linkSeed(v1,"seed:1",now)).orElseThrow();UUID selected=db.inTx(t->goals.select(guardian,v1,now));assertThat(goals.saved(child,selected).orElseThrow().skillRef()).isEqualTo("NONE");assertThat(v2.id()).isNotEqualTo(v1.id());assertThat(goals.version(child,v1.id()).orElseThrow().content().skillRef()).isEqualTo("NONE");}
    @Test void selectingNewVersionPinsSeed(){var v1=register();var v2=db.inTx(t->goals.linkSeed(v1,"seed:1",now)).orElseThrow();UUID selected=db.inTx(t->goals.select(guardian,v2,now));assertThat(goals.saved(child,selected).orElseThrow().candidateVersion()).isEqualTo(2);assertThat(goals.saved(child,selected).orElseThrow().skillRef()).isEqualTo("seed:1");}
    @Test void activeCandidateDeduplicatesDifferentHandles(){var v1=register();UUID first=db.inTx(t->goals.select(guardian,v1,now));var v2=db.inTx(t->goals.linkSeed(v1,"seed:1",now)).orElseThrow();UUID replay=db.inTx(t->goals.select(guardian,v2,now));assertThat(replay).isEqualTo(first);assertThat(goals.saved(child,first).orElseThrow().candidateVersion()).isEqualTo(1);}
    @Test void staleSeedReplayDoesNotPublishAnotherVersion(){var v=register();db.inTx(t->goals.linkSeed(v,"seed:1",now));var replay=db.inTx(t->goals.linkSeed(v,"seed:1",now));assertThat(replay).isEmpty();}
    @Test void deleteThenSelectNewVersionPreventsOldRestore(){var v1=register();UUID first=db.inTx(t->goals.select(guardian,v1,now));db.inTx(t->{goals.delete(first,now,now.plusSeconds(30));return null;});var v2=db.inTx(t->goals.linkSeed(v1,"seed:1",now)).orElseThrow();UUID second=db.inTx(t->goals.select(guardian,v2,now));assertThat(second).isNotEqualTo(first);assertThat(goals.saved(child,first)).isEmpty();assertThatThrownBy(()->db.inTx(t->{goals.restore(first);return null;})).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);}
}
