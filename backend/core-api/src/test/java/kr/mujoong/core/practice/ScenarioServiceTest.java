package kr.mujoong.core.practice;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.knowledge.BehaviorDefinition;
import kr.mujoong.core.knowledge.PragmaticsKnowledge;
import kr.mujoong.core.practice.PracticeSessionContextPort.*;
import kr.mujoong.core.practice.ScenarioConfiguration.ScenarioLimits;
import kr.mujoong.core.textai.*;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

@Tag("practice-core")
class ScenarioServiceTest {
    DbTestSupport db;
    MutableClock clock;
    ScenarioRepository repository;
    ScenarioService service;
    TextAiGateway ai;
    PragmaticsKnowledge knowledge;
    UUID guardian,child,session,goal;
    SessionContext snapshot;
    AtomicReference<SessionContext> current;
    AtomicReference<ScenarioRecordPort.RecordedExposures> exposures;
    final String opportunity="친구가 놀이를 그만하고 싶다고 말한다.";

    @BeforeEach void setUp() {
        db=DbTestSupport.coreApp(); clock=new MutableClock(DbTestSupport.EPOCH);
        guardian=db.insertGuardian("ACTIVE"); child=db.insertChild(guardian); session=UUID.randomUUID();goal=UUID.randomUUID();
        snapshot=new SessionContext(session,guardian,child,true,List.of(new GoalSnapshot(goal,"ACCEPT_STOP",1,"PEER")));
        current=new AtomicReference<>(snapshot); exposures=new AtomicReference<>();
        ai=mock(TextAiGateway.class); knowledge=mock(PragmaticsKnowledge.class);
        var definition=new BehaviorDefinition(UUID.randomUUID(),"ACCEPT_STOP",1,"그만하자는 말 받아들이기","멈추겠다고 말한다.",
                opportunity,"장난감 놀이","수용 반응","ACTIVE",clock.now(),clock.now());
        when(knowledge.requireVersion("ACCEPT_STOP",1)).thenReturn(definition);
        when(knowledge.pinVersion("ACCEPT_STOP",1)).thenReturn(definition);
        repository=new ScenarioRepository(db.jdbc(),JsonCodec.shared());
        service=new ScenarioService(repository,id -> current.get(),id -> exposures.get(),
                new JdbcTestPracticeAccounts(db.jdbc()),knowledge,ai,clock,db.tx(),new ScenarioLimits(Duration.ofSeconds(15)));
        when(ai.generateScenario(any(),any())).thenReturn(output("블록"));
    }
    @AfterEach void cleanUp() {
        if (db!=null) {
            var p=new MapSqlParameterSource("guardian",guardian);
            db.jdbc().update("delete from core.core_scenario_request where guardian_id=:guardian",p);
            db.jdbc().update("delete from core.core_scenario where guardian_id=:guardian",p);
            db.close();
        }
    }
    ScenarioService.Request original() { return new ScenarioService.Request(session,goal,"ORIGINAL",null); }
    GeneratedScenario output(String material) {
        return new GeneratedScenario(material+" 놀이 중이야. "+opportunity+" 이제 그만할래.",
                "이제 그만할래.","PEER",opportunity,"model-test-1","prompt-test-1");
    }
    void error(Runnable action,ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CoreApiException.class,e -> assertThat(e.code()).isEqualTo(code));
    }
    @Test void issuesInternalScenarioWithNoticeAndReplaysSameLogicalRequest() {
        var value=service.generate(original());
        assertThat(value.scenarioText()).startsWith(ScenarioContentPolicy.IMAGINARY_NOTICE);
        assertThat(value.validation()).isEqualTo(new Scenario.Validation(true,true,false));
        assertThat(value.partnerRoleText()).isEqualTo(ScenarioContentPolicy.PARTNER_ROLE);
        assertThat(value.partnerRoleText()).doesNotContain("멈추겠다고");
        assertThat(repository.require(value.scenarioId()).status()).isEqualTo("ISSUED");
        assertThat(service.generate(original())).isEqualTo(value);
        verify(ai,times(1)).generateScenario(any(),any());
        assertThat(value.toString()).doesNotContain("블록");
    }
    @Test void invalidGoalOrTriggerIsRegeneratedOnce() {
        var bad=new GeneratedScenario("기회도 트리거도 없다","나중에","PEER",opportunity,"m1","p1");
        when(ai.generateScenario(any(),any())).thenReturn(bad,output("공놀이"));
        assertThat(service.generate(original()).scenarioText()).contains("공놀이");
        verify(ai,times(2)).generateScenario(any(),any());
    }
    @Test void twoInvalidOutputsArePersistentlyFailedAndNeverIssued() {
        when(ai.generateScenario(any(),any())).thenReturn(new GeneratedScenario("잘못된 상황","누락","PEER",opportunity,"m1","p1"));
        error(() -> service.generate(original()),ErrorCode.SCENARIO_GEN_FAILED);
        error(() -> service.generate(original()),ErrorCode.SCENARIO_GEN_FAILED);
        verify(ai,times(2)).generateScenario(any(),any());
        assertThat(db.jdbc().queryForObject("select count(*) from core.core_scenario where guardian_id=:g",new MapSqlParameterSource("g",guardian),Integer.class)).isZero();
    }
    @Test void alreadyIssuedContentTwiceIs422ButTheSameKeyCanBeRetried() {
        // U5a 리뷰 6: 이미 낸 상황과 같은 내용(또는 저장 경합)으로 두 번을 다 써도 요청 키를 FAILED로 굳히지 않는다
        service.generate(original());
        session=UUID.randomUUID();
        current.set(new SessionContext(session,guardian,child,true,snapshot.goals()));
        when(ai.generateScenario(any(),any())).thenReturn(output("블록"),output("블록"));
        error(() -> service.generate(original()),ErrorCode.SCENARIO_GEN_FAILED);
        when(ai.generateScenario(any(),any())).thenReturn(output("그림"));
        assertThat(service.generate(original()).scenarioText()).contains("그림");
        verify(ai,times(4)).generateScenario(any(),any());
    }
    @Test void anUnusableDefinitionVersionIsAContractValidationErrorBeforeAnyAiCall() {
        // U5a 리뷰 2: 고정할 수 없는 정의 버전(없음 · 초안 · 미완성)은 계약 밖 500이 아니라 400이다
        when(knowledge.pinVersion("ACCEPT_STOP",1)).thenThrow(new IllegalArgumentException("Behavior definition not found"));
        error(() -> service.generate(original()),ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(ai);
        assertThat(db.jdbc().queryForObject("select count(*) from core.core_scenario_request where guardian_id=:g",
                new MapSqlParameterSource("g",guardian),Integer.class)).as("선점도 남지 않는다").isZero();
    }
    @Test void forbiddenOutputIsNeverIssuedAndGetsOnlyOneRegeneration() {
        when(ai.generateScenario(any(),any())).thenThrow(new TextAiException(TextAiException.Reason.FORBIDDEN_OUTPUT));
        error(() -> service.generate(original()),ErrorCode.SCENARIO_GEN_FAILED);
        verify(ai,times(2)).generateScenario(any(),any());
    }
    @Test void unavailableProviderReleasesClaimForLaterRetry() {
        when(ai.generateScenario(any(),any())).thenThrow(new TextAiException(TextAiException.Reason.UNAVAILABLE)).thenReturn(output("공"));
        error(() -> service.generate(original()),ErrorCode.SERVICE_NOT_READY);
        assertThat(service.generate(original()).scenarioText()).contains("공");
    }
    @Test void timedOutProviderReleasesClaimWithoutRecordingSemanticFailure() {
        when(ai.generateScenario(any(),any())).thenThrow(new TextAiException(TextAiException.Reason.TIMEOUT));
        error(() -> service.generate(original()),ErrorCode.SERVICE_NOT_READY);
        verify(ai).generateScenario(any(),any());
    }
    @Test void duplicateIssuedSceneAcrossSessionsMustRegenerate() {
        var first=service.generate(original());
        session=UUID.randomUUID();
        current.set(new SessionContext(session,guardian,child,true,snapshot.goals()));
        when(ai.generateScenario(any(),any())).thenReturn(output("블록"),output("그림"));
        var second=service.generate(original());
        assertThat(second.scenarioId()).isNotEqualTo(first.scenarioId());
        assertThat(second.scenarioText()).contains("그림");
        verify(ai,times(3)).generateScenario(any(),any());
    }
    @Test void newScenarioPreservesContextAndChangesMaterial() {
        var first=service.generate(original());
        when(ai.generateScenario(any(),any())).thenReturn(output("블록"),output("그림"));
        var request=new ScenarioService.Request(session,goal,"NEW_SCENARIO",first.scenarioId());
        var next=service.generate(request);
        assertThat(next.kind()).isEqualTo("NEW_SCENARIO");
        assertThat(next.relationType()).isEqualTo(first.relationType());
        assertThat(next.opportunityCondition()).isEqualTo(first.opportunityCondition());
        assertThat(next.scenarioText()).contains("그림");
        assertThat(service.generate(request)).isEqualTo(next);
    }
    @Test void newScenarioCannotUseAnotherSessionOrANewScenarioAsOriginal() {
        var first=service.generate(original());
        var otherSession=UUID.randomUUID();
        current.set(new SessionContext(otherSession,guardian,child,true,snapshot.goals()));
        error(() -> service.generate(new ScenarioService.Request(otherSession,goal,"NEW_SCENARIO",first.scenarioId())),ErrorCode.NOT_FOUND);
        current.set(snapshot);
        when(ai.generateScenario(any(),any())).thenReturn(output("그림"));
        var next=service.generate(new ScenarioService.Request(session,goal,"NEW_SCENARIO",first.scenarioId()));
        error(() -> service.generate(new ScenarioService.Request(session,goal,"NEW_SCENARIO",next.scenarioId())),ErrorCode.NOT_FOUND);
    }
    @Test void inactiveMissingWrongChildAndUnselectedGoalsAreRejectedBeforeAi() {
        current.set(null); error(() -> service.generate(original()),ErrorCode.NOT_FOUND);
        current.set(new SessionContext(session,guardian,child,false,snapshot.goals()));
        error(() -> service.generate(original()),ErrorCode.SESSION_NOT_ACTIVE);
        current.set(new SessionContext(session,guardian,child,true,List.of()));
        error(() -> service.generate(original()),ErrorCode.NOT_FOUND);
        current.set(new SessionContext(session,guardian,UUID.randomUUID(),true,snapshot.goals()));
        error(() -> service.generate(original()),ErrorCode.NOT_FOUND);
        verifyNoInteractions(ai);
    }
    @Test void accountDeletionAfterProviderCallPreventsLateScenarioWrite() {
        when(ai.generateScenario(any(),any())).thenAnswer(call -> {
            db.jdbc().update("update core.core_guardian set status='DELETION_REQUESTED' where id=:id",new MapSqlParameterSource("id",guardian));
            return output("블록");
        });
        error(() -> service.generate(original()),ErrorCode.ACCOUNT_DELETION_IN_PROGRESS);
    }
    @Test void sessionEndingDuringProviderCallPreventsLateScenarioWrite() {
        when(ai.generateScenario(any(),any())).thenAnswer(call -> {
            current.set(new SessionContext(session,guardian,child,false,snapshot.goals()));return output("블록");
        });
        error(() -> service.generate(original()),ErrorCode.SESSION_NOT_ACTIVE);
    }
    @Test void claimExpiryPreventsLateProviderResultFromBeingCommitted() {
        when(ai.generateScenario(any(),any())).thenAnswer(call -> { clock.advance(Duration.ofSeconds(15)); return output("블록"); });
        error(() -> service.generate(original()),ErrorCode.SERVICE_NOT_READY);
    }
    @Test void activeClaimRejectsDuplicateButExpiredClaimCanBeReclaimed() {
        var request=original();
        var first=db.inTx(s -> repository.claim(request.key(),snapshot,request,clock.now(),clock.now().plusSeconds(15)));
        error(() -> service.generate(request),ErrorCode.SERVICE_NOT_READY);
        clock.advance(Duration.ofSeconds(15));
        var value=service.generate(request);
        assertThat(value).isNotNull();
        db.inTx(s -> {repository.release(request.key(),first.claimId(),true,clock.now());return null;});
        assertThat(service.generate(request)).isEqualTo(value);
    }
    @Test void concurrentDuplicateDoesNotCallProviderTwice() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        when(ai.generateScenario(any(),any())).thenAnswer(call -> {
            entered.countDown();
            if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("provider not released");
            return output("블록");
        });
        var first=db.start(() -> service.generate(original()));
        try {
            assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            error(() -> service.generate(original()),ErrorCode.SERVICE_NOT_READY);
        } finally { release.countDown(); }
        var issued=DbTestSupport.await(first);
        assertThat(service.generate(original())).isEqualTo(issued);
        verify(ai,times(1)).generateScenario(any(),any());
    }
    @Test void actualExposureIsAppliedOnlyFromRecordAndIsIdempotent() {
        var value=service.generate(original());
        exposures.set(new ScenarioRecordPort.RecordedExposures(session,guardian,child,List.of(new ScenarioRecordPort.Exposure(value.scenarioId(),clock.now()))));
        assertThat(service.reconcileExposures(session)).isEqualTo(1);
        assertThat(service.reconcileExposures(session)).isZero();
        assertThat(repository.require(value.scenarioId()).status()).isEqualTo("EXPOSED");
    }
    @Test void forgedAndFutureExposureRollsBackEntireBatch() {
        var value=service.generate(original());
        exposures.set(new ScenarioRecordPort.RecordedExposures(session,guardian,child,List.of(
                new ScenarioRecordPort.Exposure(value.scenarioId(),clock.now()),new ScenarioRecordPort.Exposure(UUID.randomUUID(),clock.now()))));
        error(() -> service.reconcileExposures(session),ErrorCode.NOT_FOUND);
        assertThat(repository.require(value.scenarioId()).status()).isEqualTo("ISSUED");
        exposures.set(new ScenarioRecordPort.RecordedExposures(session,guardian,child,List.of(new ScenarioRecordPort.Exposure(value.scenarioId(),clock.now().plusSeconds(1)))));
        error(() -> service.reconcileExposures(session),ErrorCode.NOT_FOUND);
    }
    @Test void requestShapeAndLimitsEnforceBoundaries() {
        error(() -> new ScenarioService.Request(null,goal,"ORIGINAL",null),ErrorCode.VALIDATION_FAILED);
        error(() -> new ScenarioService.Request(session,null,"ORIGINAL",null),ErrorCode.VALIDATION_FAILED);
        error(() -> new ScenarioService.Request(session,goal,"OTHER",null),ErrorCode.VALIDATION_FAILED);
        error(() -> new ScenarioService.Request(session,goal,"NEW_SCENARIO",null),ErrorCode.VALIDATION_FAILED);
        error(() -> new ScenarioService.Request(session,goal,"ORIGINAL",UUID.randomUUID()),ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> new ScenarioLimits(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScenarioLimits(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void contentChecksDoNotTrustRelationOrOpportunitySuppliedByModel() {
        assertThat(ScenarioContentPolicy.valid(null,"PEER",opportunity)).isFalse();
        assertThat(ScenarioContentPolicy.valid(output("블록"),"ADULT",opportunity)).isFalse();
        assertThat(ScenarioContentPolicy.valid(output("블록"),"PEER","다른 기회")).isFalse();
        assertThat(ScenarioContentPolicy.fingerprint("Ａ  B")).isEqualTo(ScenarioContentPolicy.fingerprint("a b"));
        var text=ScenarioContentPolicy.childText("상황");
        assertThat(ScenarioContentPolicy.childText(text)).isEqualTo(text);
    }
}
