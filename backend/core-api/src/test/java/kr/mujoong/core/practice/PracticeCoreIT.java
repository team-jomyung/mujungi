package kr.mujoong.core.practice;
import static org.assertj.core.api.Assertions.*;
import static kr.mujoong.core.common.db.DbTestSupport.await;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.knowledge.*;
import kr.mujoong.core.support.NoTransactions;
import kr.mujoong.core.textai.ForbiddenExpressionPolicy;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
@Tag("u5a") @Tag("db")
class PracticeCoreIT {
    private DbTestSupport db;
    private MutableClock clock;
    private JdbcTestPracticeAccounts accounts;
    private PracticeGoalRepository repository;
    private PragmaticsKnowledge knowledge;
    private GoalSelectionService service;
    private CandidateRegistry registry;
    private PracticeDeletionParticipant deletion;
    private final java.util.ArrayList<UUID> owners=new java.util.ArrayList<>();
    @BeforeEach void setup() {
        db=DbTestSupport.coreApp();clock=new MutableClock(DbTestSupport.EPOCH);
        accounts=new JdbcTestPracticeAccounts(db.jdbc());
        repository=new PracticeGoalRepository(db.jdbc());
        knowledge=new PragmaticsKnowledge(new KnowledgeJdbcRepository(db.jdbc()),clock,new KnowledgeProperties(false),Optional.empty());
        var tx=new DataSourceTransactionManager(db.dataSource());
        service=new GoalSelectionService(accounts,repository,knowledge,(c,g,s)->GoalMetadataPort.Metadata.unavailable(),
                clock,Duration.ofSeconds(5),NoTransactions.noRetry(),tx);
        registry=new CandidateRegistry(accounts,(c,s,e)->true,repository,knowledge,new ForbiddenExpressionPolicy(),clock,NoTransactions.noRetry(),tx);
        deletion=new PracticeDeletionParticipant(repository,accounts,NoTransactions.noRetry(),tx);
    }
    @AfterEach void cleanup(){
        if(db!=null){ for(UUID owner:owners)deletion.deleteByGuardian(owner,UUID.randomUUID());db.close(); }
    }
    private PracticeAccountPort.AccountContext family(){
        UUID guardian=db.insertGuardian("ACTIVE"); owners.add(guardian);
        UUID child=db.insertChild(guardian);
        return new PracticeAccountPort.AccountContext(guardian,child,8,List.of("공룡"));
    }
    private UUID candidate(PracticeAccountPort.AccountContext family,UUID session,String behavior){
        return registry.register(session,family.childId(),List.of(new CandidateRegistry.Candidate(behavior,1,
            "친구의 말을 듣고 아이가 다음 말을 했어요.",List.of(new CandidateRegistry.Evidence(1,"CHILD_RECALL"))))).getFirst();
    }
    private GoalSelectionService.View add(PracticeAccountPort.AccountContext family,String behavior){
        return service.add(family.guardianId(),candidate(family,UUID.randomUUID(),behavior));
    }
    private static void error(Runnable action,ErrorCode code){
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CoreApiException.class,e->assertThat(e.code()).isEqualTo(code));
    }
    @Test void candidateRegistrationIsIdempotentAndDoesNotAutomaticallySelect(){
        var f=family();UUID session=UUID.randomUUID();
        UUID first=candidate(f,session,"ACCEPT_STOP");
        assertThat(candidate(f,session,"ACCEPT_STOP")).isEqualTo(first);
        assertThat(service.list(f.guardianId())).isEmpty();
        var candidate=registry.viewForSession(session,f.guardianId()).getFirst();
        assertThat(candidate.recommendationId()).isEqualTo(first);assertThat(candidate.selected()).isFalse();
        assertThat(db.jdbc().queryForObject("select count(*) from core.core_recommendation_evidence where recommendation_id=:id",
            Map.of("id",first),Integer.class)).isEqualTo(1);
        service.add(f.guardianId(),first);
        assertThat(registry.viewForSession(session,f.guardianId()).getFirst().selected()).isTrue();
        assertThat(accounts.requireChild(f.childId(),false)).isEqualTo(f);
    }
    @Test void favoritesSortBeforeStableSavedOrderAndNeverInventMetadata(){
        var f=family();var first=add(f,"ACCEPT_STOP");var second=add(f,"ASK_TO_JOIN");var third=add(f,"ASK_FOR_CLARIFICATION");
        service.favorite(f.guardianId(),third.goalSelectionId(),true);
        var list=service.list(f.guardianId());
        assertThat(list).extracting(GoalSelectionService.View::goalSelectionId)
            .containsExactly(third.goalSelectionId(),first.goalSelectionId(),second.goalSelectionId());
        assertThat(list.getFirst().sortOrder()).isEqualTo(3);
        assertThat(list.getFirst().sourceActivityType()).isNull();
        assertThat(list.getFirst().sourceActivityAt()).isNull();assertThat(list.getFirst().practiceCount()).isNull();
        service.favorite(f.guardianId(),third.goalSelectionId(),true);
        service.favorite(f.guardianId(),third.goalSelectionId(),false);
        assertThat(service.list(f.guardianId())).extracting(GoalSelectionService.View::sortOrder).containsExactly(1L,2L,3L);
    }
    @Test void deletionRestoreAndReaddingPreserveOneIdentityAndFavoriteAndOriginalOrder(){
        var f=family();UUID session=UUID.randomUUID();UUID candidate=candidate(f,session,"ACCEPT_STOP");
        var saved=service.add(f.guardianId(),candidate);service.favorite(f.guardianId(),saved.goalSelectionId(),true);
        error(()->service.add(f.guardianId(),candidate),ErrorCode.ALREADY_SELECTED);
        Instant until=service.delete(f.guardianId(),saved.goalSelectionId());
        assertThat(service.list(f.guardianId())).isEmpty();
        assertThat(registry.viewForSession(session,f.guardianId()).getFirst().selected()).isFalse();
        clock.advanceMillis(1000);
        assertThat(service.delete(f.guardianId(),saved.goalSelectionId())).isEqualTo(until);
        clock.setInstant(until.minusMillis(1));
        var restored=service.restore(f.guardianId(),saved.goalSelectionId());
        assertThat(restored.goalSelectionId()).isEqualTo(saved.goalSelectionId());assertThat(restored.isFavorite()).isTrue();
        assertThat(service.restore(f.guardianId(),saved.goalSelectionId())).isEqualTo(restored);
        service.delete(f.guardianId(),saved.goalSelectionId());clock.advance(Duration.ofSeconds(10));
        var readded=service.add(f.guardianId(),candidate);
        assertThat(readded).isEqualTo(restored);
        assertThat(db.jdbc().queryForObject("select count(*) from core.core_practice_goal_selection where guardian_id=:id",
            Map.of("id",f.guardianId()),Integer.class)).isEqualTo(1);
    }
    @Test void restoreAtAndAfterDeadlineFailsAndDeletedFavoriteCannotChange(){
        var f=family();var saved=add(f,"ACCEPT_STOP");
        Instant until=service.delete(f.guardianId(),saved.goalSelectionId());
        error(()->service.favorite(f.guardianId(),saved.goalSelectionId(),true),ErrorCode.NOT_FOUND);
        clock.setInstant(until);error(()->service.restore(f.guardianId(),saved.goalSelectionId()),ErrorCode.RESTORE_EXPIRED);
        clock.advanceMillis(1);error(()->service.restore(f.guardianId(),saved.goalSelectionId()),ErrorCode.RESTORE_EXPIRED);
    }
    @Test void ownershipAndDeletionBlockBothReadAndWrite(){
        var f=family();var other=family();UUID candidate=candidate(f,UUID.randomUUID(),"ACCEPT_STOP");
        var saved=service.add(f.guardianId(),candidate);
        assertThat(service.list(other.guardianId())).isEmpty();
        error(()->service.add(other.guardianId(),candidate),ErrorCode.NOT_FOUND);
        error(()->service.delete(other.guardianId(),saved.goalSelectionId()),ErrorCode.NOT_FOUND);
        error(()->service.restore(other.guardianId(),saved.goalSelectionId()),ErrorCode.NOT_FOUND);
        error(()->service.favorite(other.guardianId(),saved.goalSelectionId(),true),ErrorCode.NOT_FOUND);
        db.jdbc().update("update core.core_guardian set status='DELETION_REQUESTED' where id=:id",Map.of("id",f.guardianId()));
        error(()->service.list(f.guardianId()),ErrorCode.ACCOUNT_DELETION_IN_PROGRESS);
        error(()->service.delete(f.guardianId(),saved.goalSelectionId()),ErrorCode.ACCOUNT_DELETION_IN_PROGRESS);
        error(()->candidate(f,UUID.randomUUID(),"ASK_TO_JOIN"),ErrorCode.ACCOUNT_DELETION_IN_PROGRESS);
    }
    @Test void invalidOrInactiveCandidatesNeverEnterSelection(){
        var f=family();UUID candidate=candidate(f,UUID.randomUUID(),"ACCEPT_STOP");
        db.jdbc().update("update core.core_goal_recommendation set status='INVALIDATED',invalidated_at=created_at where id=:id",Map.of("id",candidate));
        error(()->service.add(f.guardianId(),candidate),ErrorCode.NOT_FOUND);
        error(()->service.add(f.guardianId(),UUID.randomUUID()),ErrorCode.NOT_FOUND);
        error(()->service.add(f.guardianId(),null),ErrorCode.VALIDATION_FAILED);
        error(()->service.favorite(f.guardianId(),null,true),ErrorCode.VALIDATION_FAILED);
        error(()->service.favorite(f.guardianId(),UUID.randomUUID(),null),ErrorCode.VALIDATION_FAILED);
        error(()->registry.register(UUID.randomUUID(),f.childId(),List.of(new CandidateRegistry.Candidate(
            "MISSING",1,"실제 발화 근거",List.of(new CandidateRegistry.Evidence(1,"CHILD_RECALL"))))),ErrorCode.VALIDATION_FAILED);
    }
    @Test void candidateValidationRejectsUnverifiedAndUnsafeFactsAndWholeBatchRollsBack(){
        var f=family();var tx=new DataSourceTransactionManager(db.dataSource());
        var blocked=new CandidateRegistry(accounts,(c,s,e)->false,repository,knowledge,new ForbiddenExpressionPolicy(),clock,NoTransactions.noRetry(),tx);
        var valid=new CandidateRegistry.Candidate("ACCEPT_STOP",1,"친구의 말을 듣고 대답했어요.",List.of(new CandidateRegistry.Evidence(1,"CHILD_RECALL")));
        UUID session=UUID.randomUUID();
        error(()->blocked.register(session,f.childId(),List.of(valid)),ErrorCode.VALIDATION_FAILED);
        var bad=new CandidateRegistry.Candidate("ASK_TO_JOIN",1,"사회성 점수가 향상됐어요.",valid.evidence());
        error(()->registry.register(session,f.childId(),List.of(valid,bad)),ErrorCode.VALIDATION_FAILED);
        assertThat(registry.viewForSession(session,f.guardianId())).isEmpty();
        for(var invalid:List.of(
            new CandidateRegistry.Candidate("",1,"근거",valid.evidence()),
            new CandidateRegistry.Candidate("ACCEPT_STOP",0,"근거",valid.evidence()),
            new CandidateRegistry.Candidate("ACCEPT_STOP",1,"",valid.evidence()),
            new CandidateRegistry.Candidate("ACCEPT_STOP",1,"근거",List.of()),
            new CandidateRegistry.Candidate("ACCEPT_STOP",1,"근거",List.of(new CandidateRegistry.Evidence(0,"CHILD_RECALL"))),
            new CandidateRegistry.Candidate("ACCEPT_STOP",1,"근거",List.of(new CandidateRegistry.Evidence(1,"UNKNOWN")))) )
            error(()->registry.register(session,f.childId(),List.of(invalid)),ErrorCode.VALIDATION_FAILED);
        error(()->registry.register(null,f.childId(),List.of(valid)),ErrorCode.VALIDATION_FAILED);
        error(()->registry.viewForSession(null,f.guardianId()),ErrorCode.VALIDATION_FAILED);
    }
    @Test void concurrentDoubleSaveHasOneWinnerAndOneSelection(){
        var f=family();UUID candidate=candidate(f,UUID.randomUUID(),"ACCEPT_STOP");var start=new CountDownLatch(1);
        java.util.concurrent.Callable<String> work=()->{
            assertThat(start.await(10,TimeUnit.SECONDS)).isTrue();
            try{service.add(f.guardianId(),candidate);return "SAVED";}
            catch(CoreApiException e){return e.code().name();}
        };
        var a=db.start(work);var b=db.start(work);start.countDown();
        assertThat(List.of(await(a),await(b))).containsExactlyInAnyOrder("SAVED","ALREADY_SELECTED");
        assertThat(service.list(f.guardianId())).hasSize(1);
    }
    @Test void concurrentDifferentGoalsReceiveDistinctStableOrder(){
        var f=family();UUID a=candidate(f,UUID.randomUUID(),"ACCEPT_STOP");UUID b=candidate(f,UUID.randomUUID(),"ASK_TO_JOIN");
        var one=db.start(()->service.add(f.guardianId(),a));var two=db.start(()->service.add(f.guardianId(),b));
        await(one);await(two);
        assertThat(service.list(f.guardianId())).extracting(GoalSelectionService.View::sortOrder).containsExactly(1L,2L);
    }
    @Test void deletionIsIdempotentAndKeepsOtherAccountsAndReferenceCatalog(){
        var f=family();var other=family();add(f,"ACCEPT_STOP");add(f,"ASK_TO_JOIN");add(other,"ACCEPT_STOP");
        var deleted=deletion.deleteByGuardian(f.guardianId(),UUID.randomUUID());
        assertThat(deleted.get("goalSelections")).isEqualTo(2);assertThat(deleted.get("recommendationEvidence")).isEqualTo(2);
        assertThat(deleted.get("goalRecommendations")).isEqualTo(2);
        assertThat(deletion.deleteByGuardian(f.guardianId(),UUID.randomUUID()).values()).allMatch(count->count==0);
        assertThat(service.list(f.guardianId())).isEmpty();assertThat(service.list(other.guardianId())).hasSize(1);
        assertThat(knowledge.requireVersion("ACCEPT_STOP",1).status()).isEqualTo("ACTIVE");
        assertThatThrownBy(()->deletion.deleteByGuardian(null,UUID.randomUUID())).isInstanceOf(NullPointerException.class);
    }
    @Test void missingIntegrationPortsFailClosedAndMetadataIsUnknown(){
        var config=new PracticePortConfiguration();
        assertThat(config.candidateEvidencePort().verifies(UUID.randomUUID(),UUID.randomUUID(),List.of())).isFalse();
        assertThat(config.goalMetadataPort().lookup(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()))
            .isEqualTo(GoalMetadataPort.Metadata.unavailable());
    }
    @Test void aGuardianWithoutAChildGetsAnEmptyListButStillCannotWrite(){
        // U5a 리뷰 4: 아이를 아직 등록하지 않은 보호자의 18은 빈 상태다(404가 아니다)
        UUID withoutChild=db.insertGuardian("ACTIVE");owners.add(withoutChild);
        assertThat(service.list(withoutChild)).isEmpty();
        error(()->service.add(withoutChild,UUID.randomUUID()),ErrorCode.NOT_FOUND);
        error(()->service.list(UUID.randomUUID()),ErrorCode.UNAUTHENTICATED);
        error(()->service.list(null),ErrorCode.UNAUTHENTICATED);
    }
    @Test void candidateEvidenceIsVerifiedBeforeTheTransactionOpensAndPinsFollowDefinitionOrder(){
        // U5a 리뷰 8: 근거 확인(외부 포트)은 보호자 잠금 · 정의 고정을 쥔 트랜잭션 밖에서 끝낸다
        var f=family();var tx=new DataSourceTransactionManager(db.dataSource());
        List<Boolean> insideTransaction=new java.util.ArrayList<>();
        var watching=new CandidateRegistry(accounts,(c,s,e)->{
            insideTransaction.add(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return true;},repository,knowledge,new ForbiddenExpressionPolicy(),clock,NoTransactions.noRetry(),tx);
        var evidence=List.of(new CandidateRegistry.Evidence(1,"CHILD_RECALL"));
        UUID session=UUID.randomUUID();
        List<UUID> ids=watching.register(session,f.childId(),List.of(
            new CandidateRegistry.Candidate("ASK_TO_JOIN",1,"친구에게 같이 하자고 말했어요.",evidence),
            new CandidateRegistry.Candidate("ACCEPT_STOP",1,"친구의 말을 듣고 멈췄어요.",evidence)));
        assertThat(insideTransaction).containsExactly(false,false);
        // 돌려주는 id는 들어온 순서 그대로다(고정 순서와 무관)
        var views=registry.viewForSession(session,f.guardianId());
        assertThat(ids).hasSize(2).doesNotHaveDuplicates();
        assertThat(views).extracting(CandidateRegistry.CandidateView::recommendationId).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(db.jdbc().queryForObject("select behavior_key from core.core_goal_recommendation where id=:id",
            Map.of("id",ids.getFirst()),String.class)).isEqualTo("ASK_TO_JOIN");
    }
}
