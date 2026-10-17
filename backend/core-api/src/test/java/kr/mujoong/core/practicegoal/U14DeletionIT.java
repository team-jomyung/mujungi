package kr.mujoong.core.practicegoal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import kr.mujoong.core.common.db.DbRetry;
import kr.mujoong.core.identity.GuardianWriteLock;
import kr.mujoong.core.knowledge.SeedGenerationService;
import kr.mujoong.core.practice.PracticeAccountPort;
import kr.mujoong.core.practice.PracticeDeletionParticipant;
import kr.mujoong.core.result.ResultDeletionParticipant;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Real PostgreSQL deletion, rollback and delayed post-commit callback boundaries. */
@Tag("u14") @Tag("db")
class U14DeletionIT {
    CandidateSeedReplayIT f;
    @BeforeEach void setup(){f=new CandidateSeedReplayIT();f.setup();}
    @AfterEach void close(){f.close();}
    PracticeDeletionParticipant participant(kr.mujoong.core.practice.PracticeGoalRepository repository){
        var accounts=mock(PracticeAccountPort.class);
        doAnswer(i->{new GuardianWriteLock(f.db.jdbc()).statusForUpdate(f.guardian);return null;}).when(accounts).lockForDeletion(f.guardian);
        when(accounts.childrenForDeletion(f.guardian)).thenReturn(List.of(f.child));
        return new PracticeDeletionParticipant(repository,accounts,new DbRetry(0,new Random(1),d->{}),new DataSourceTransactionManager(f.db.dataSource()));
    }
    void remove(){new ResultDeletionParticipant(f.db.jdbc(),new GuardianWriteLock(f.db.jdbc()),new DataSourceTransactionManager(f.db.dataSource())).deleteByGuardian(f.guardian,UUID.randomUUID());participant(new kr.mujoong.core.practice.PracticeGoalRepository(f.db.jdbc())).deleteByGuardian(f.guardian,UUID.randomUUID());}
    UUID selected(){f.deliver(f.result);var v=f.goals.candidates(f.child,f.session).getFirst();return f.db.inTx(t->f.goals.select(f.guardian,v,f.clock.now()));}
    long count(String table){return f.db.jdbc().queryForObject("select count(*) from core.core_"+table+" where child_id=:c",Map.of("c",f.child),Long.class);}
    @Test void removesSelectionsVersionsAndCandidatesInForeignKeyOrder(){selected();remove();assertThat(count("saved_goal")).isZero();assertThat(count("goal_candidate_version")).isZero();assertThat(count("goal_candidate")).isZero();}
    @Test void removesEveryImmutableVersionAndArchivedSnapshot(){UUID selection=selected();var v=f.goals.candidates(f.child,f.session).getFirst();f.db.inTx(t->f.goals.linkSeed(v,"seed:S1",f.clock.now()));f.goals.delete(selection,f.clock.now(),f.clock.now().plusSeconds(60));remove();assertThat(count("goal_candidate_version")).isZero();assertThat(count("saved_goal")).isZero();}
    @Test void repeatedAccountDeletionIsIdempotent(){selected();remove();remove();assertThat(count("goal_candidate")).isZero();}
    @Test void removesPendingResultBeforePracticeAndRejectsReplay(){selected();remove();assertThatThrownBy(()->f.deliver(f.result)).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);assertThat(count("goal_candidate")).isZero();}
    @Test void removedSourceRecordCannotBeReplayed(){selected();f.db.jdbc().update("delete from core.core_activity_record where session_id=:s",Map.of("s",f.session));assertThatThrownBy(()->f.deliver(f.result)).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);}
    @Test void seedResponseAfterDeletionCannotPublishOrRevive(){when(f.seeds.generateSeed(any())).thenAnswer(i->{remove();return new SeedGenerationService.Result("seed:S1","REUSED");});f.deliver(f.result);assertThat(count("goal_candidate")).isZero();assertThat(count("goal_candidate_version")).isZero();}
    @Test void withdrawalBeforeDelayedSeedResponseKeepsNone(){when(f.seeds.generateSeed(any())).thenAnswer(i->{when(f.consent.check(f.guardian)).thenReturn(new kr.mujoong.core.consent.ConsentPolicyService.GateResult(false,null,new kr.mujoong.core.consent.ConsentPolicyService.Snapshot("v1",false,true,true,true)));return new SeedGenerationService.Result("seed:S1","REUSED");});f.deliver(f.result);assertThat(f.goals.candidates(f.child,f.session).getFirst().version()).isEqualTo(1);assertThat(f.goals.candidates(f.child,f.session).getFirst().content().skillRef()).isEqualTo("NONE");}
    @Test void failureRollsBackTheWholePracticeParticipant(){selected();var failing=new kr.mujoong.core.practice.PracticeGoalRepository(f.db.jdbc()){@Override public Map<String,Integer> deleteByGuardian(UUID guardian,List<UUID> children){super.deleteByGuardian(guardian,children);throw new IllegalStateException("synthetic failure");}};assertThatThrownBy(()->participant(failing).deleteByGuardian(f.guardian,UUID.randomUUID())).isInstanceOf(IllegalStateException.class);assertThat(count("saved_goal")).isEqualTo(1);assertThat(count("goal_candidate_version")).isEqualTo(1);}
}
