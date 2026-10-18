package kr.mujoong.core.practicegoal;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import kr.mujoong.core.common.db.DbTestSupport;
import org.junit.jupiter.api.*;

@Tag("goal-result") @Tag("db")
class PracticeGoalRepositoryIT {
    DbTestSupport db;GoalCandidateRepository goals;UUID guardian,child,session;Instant now=DbTestSupport.EPOCH;
    @BeforeEach void setup(){db=DbTestSupport.coreApp();goals=new GoalCandidateRepository(db.jdbc());guardian=db.insertGuardian("ACTIVE");child=db.insertChild(guardian);session=UUID.randomUUID();}
    @AfterEach void close(){if(db!=null){goals.deleteGuardian(guardian);db.close();}}
    GoalCandidateRepository.Draft draft(){return new GoalCandidateRepository.Draft("물어보기","합성 놀이 장면","함께 물어보기",new FourElements("친구와 놀 때","친구","참여하고 싶다","함께 해도 돼?"),"NONE","합성 근거",List.of(new GoalCandidateRepository.Evidence(1,"함께 해도 돼?")),null);}
    GoalCandidateRepository.Version register(){return db.inTx(t->goals.register(child,session,"source:0",draft(),now));}
    @Test void storesFullCandidateContext(){var v=register();assertThat(v.content()).isEqualTo(draft());assertThat(v.version()).isEqualTo(1);}
    @Test void rejudgmentRejectsAnotherChildsGeneratedGoalBeforeAi(){
        UUID otherGuardian=db.insertGuardian("ACTIVE"),otherChild=db.insertChild(otherGuardian);
        var version=db.inTx(t->goals.register(otherChild,UUID.randomUUID(),"other",draft(),now));UUID selection=db.inTx(t->goals.select(otherGuardian,version,now));
        rejectsForeignSelection(selection);goals.deleteGuardian(otherGuardian);
    }
    @Test void rejudgmentRejectsAnotherChildsLegacyGoalBeforeAi(){
        UUID otherGuardian=db.insertGuardian("ACTIVE"),otherChild=db.insertChild(otherGuardian);var legacy=new kr.mujoong.core.practice.PracticeGoalRepository(db.jdbc());
        var candidate=new kr.mujoong.core.practice.CandidateRegistry.Candidate("ACCEPT_STOP",1,"합성 근거",List.of(new kr.mujoong.core.practice.CandidateRegistry.Evidence(1,"CHILD_RECALL")));
        UUID recommendation=db.inTx(t->legacy.register(otherChild,UUID.randomUUID(),candidate,"합성목표",now));
        UUID selection=db.inTx(t->legacy.insertSelection(otherGuardian,otherChild,new kr.mujoong.core.practice.PracticeGoalRepository.Recommendation(recommendation,"ACCEPT_STOP",1,"ACTIVE"),"합성목표",now));
        rejectsForeignSelection(selection);legacy.deleteByGuardian(otherGuardian,List.of(otherChild));
    }
    void rejectsForeignSelection(UUID selection){
        var reader=new PinnedSavedGoalReader(goals,org.mockito.Mockito.mock(kr.mujoong.core.practice.PracticeAccountPort.class),org.mockito.Mockito.mock(kr.mujoong.core.consent.ConsentPolicyService.class),db.jdbc());
        var luna=org.mockito.Mockito.mock(kr.mujoong.core.luna.CoreLunaClient.class);var gateway=new kr.mujoong.core.result.RejudgmentGateway(Optional.of(luna),reader);
        var practice=Map.<String,Object>of("flowVersion",kr.mujoong.core.result.PracticeRecordV2.VERSION,"scenes",List.of(Map.of("sceneRunId",UUID.randomUUID().toString(),"goalRef",selection.toString(),"sceneKind","BASE","startedAt",now.toString())));
        assertThatThrownBy(()->gateway.judge(child,practice,List.of(),Map.of(),now.plusSeconds(30),()->{})).isInstanceOf(kr.mujoong.core.result.ResultAiException.class);org.mockito.Mockito.verifyNoInteractions(luna);
    }
    @Test void historicalSnapshotSurvivesSoftDeleteWhilePublicSavedGoalIsEmpty(){var version=register();UUID selected=db.inTx(t->goals.select(guardian,version,now));goals.delete(selected,now,now.plusSeconds(30));assertThat(goals.saved(child,selected)).isEmpty();assertThat(goals.historical(child,selected).orElseThrow().targetExpression()).isEqualTo(version.content().targetExpression());assertThat(goals.selection(guardian,selected).orElseThrow().displayName()).isEqualTo(version.content().displayName());assertThat(goals.historical(UUID.randomUUID(),selected)).isEmpty();}
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
