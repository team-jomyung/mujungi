package kr.mujoong.core.practice.start;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.core.activity.StartActivityService;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.consent.ConsentPolicyService;
import kr.mujoong.core.knowledge.*;
import kr.mujoong.core.practice.*;
import kr.mujoong.core.practice.start.PracticePreparationRepository.Preparation;
import kr.mujoong.core.support.NoTransactions;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("u5b")
class PracticeStartServiceTest {
    final UUID guardian=UUID.randomUUID(), child=UUID.randomUUID(), goal=UUID.randomUUID(), key=UUID.randomUUID();
    final MutableClock clock=new MutableClock(java.time.Instant.parse("2026-10-14T00:00:00Z"));
    final PracticeAccountPort accounts=mock(PracticeAccountPort.class);
    final ConsentPolicyService consent=mock(ConsentPolicyService.class);
    final PracticeGoalRepository goals=mock(PracticeGoalRepository.class);
    final PragmaticsKnowledge knowledge=mock(PragmaticsKnowledge.class);
    final PracticePreparationRepository preparations=mock(PracticePreparationRepository.class);
    final ScenarioRepository scenarios=mock(ScenarioRepository.class);
    final ScenarioService generation=mock(ScenarioService.class);
    final StartActivityService activities=mock(StartActivityService.class);
    final AtomicReference<Preparation> stored=new AtomicReference<>();
    final Scenario scene=new Scenario(UUID.randomUUID(),"ORIGINAL","PEER","기회","상상 상황","상대 역할","대사",new Scenario.Validation(true,true,false));
    PracticeStartService service;
    PracticeStartRequest request() { return new PracticeStartRequest(key,List.of(goal)); }
    @BeforeEach void setup() {
        when(accounts.requireActive(eq(guardian),anyBoolean())).thenReturn(new PracticeAccountPort.AccountContext(guardian,child,8,List.of()));
        when(consent.check(guardian)).thenReturn(new ConsentPolicyService.GateResult(true,null,null));
        when(preparations.find(guardian,key)).thenAnswer(c->Optional.ofNullable(stored.get()));
        when(preparations.findSession(any())).thenAnswer(c->Optional.ofNullable(stored.get()));
        when(goals.find(guardian,goal)).thenReturn(Optional.of(new GoalSelection(goal,guardian,child,UUID.randomUUID(),UUID.randomUUID(),
                "ACCEPT_STOP",1,"그만하자는 말 받아들이기",true,1,"ACTIVE",clock.now(),null,null)));
        when(knowledge.pinVersion("ACCEPT_STOP",1)).thenReturn(new BehaviorDefinition(UUID.randomUUID(),"ACCEPT_STOP",1,"목표","정의","기회","상황","판정 기준","ACTIVE",clock.now(),clock.now()));
        when(preparations.insert(eq(guardian),eq(child),any(),anyList(),any(),any())).thenAnswer(c->{
            Preparation p=new Preparation(UUID.randomUUID(),guardian,child,key,request().requestHash(),c.getArgument(3),"PREPARING",UUID.randomUUID(),c.getArgument(5),null);
            stored.set(p);return p;
        });
        when(preparations.ready(any(),any(),any())).thenAnswer(c->{var p=stored.get();stored.set(copy(p,"READY",p.claimId(),scene.scenarioId()));return 1;});
        when(preparations.reclaim(any(),any(),any())).thenAnswer(c->{var p=stored.get();var next=new Preparation(p.sessionId(),guardian,child,key,p.requestHash(),p.goals(),"PREPARING",UUID.randomUUID(),c.getArgument(2),null);stored.set(next);return next;});
        when(generation.generatePrepared(any(),any())).thenAnswer(c->{
            @SuppressWarnings("unchecked") var supplier=(java.util.function.Supplier<PracticeSessionContextPort.SessionContext>)c.getArgument(1);
            supplier.get();return scene;
        });
        when(scenarios.require(scene.scenarioId())).thenAnswer(c->new ScenarioRepository.Stored(scene,guardian,child,stored.get().sessionId(),goal,"ACCEPT_STOP",1,"ISSUED"));
        when(activities.replayPractice(any(),any(),any())).thenReturn(Optional.empty());
        when(activities.startPractice(any(),any(),any(),any(),any())).thenAnswer(c->new StartActivityService.Started(c.getArgument(2),SessionStatus.REQUESTED));
        service=new PracticeStartService(accounts,consent,goals,knowledge,preparations,scenarios,generation,activities,
                new TransactionTemplate(new NoTransactions()),clock,new ScenarioConfiguration.ScenarioLimits(Duration.ofSeconds(15)));
    }
    Preparation copy(Preparation p,String status,UUID claim,UUID scenario) {
        return new Preparation(p.sessionId(),guardian,child,key,p.requestHash(),p.goals(),status,claim,p.leaseUntil(),scenario);
    }
    void error(Runnable action,ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CoreApiException.class,e->assertThat(e.code()).isEqualTo(expected));
    }
    @Test void firstScenarioIsPreparedBeforeRequestedStartAndNeverExposesCriteriaInParentResponse() {
        var result=service.start(guardian,request());
        assertThat(result.sessionId()).isEqualTo(stored.get().sessionId());assertThat(result.goalCount()).isEqualTo(1);
        var order=inOrder(activities,generation,preparations);
        order.verify(activities).checkPracticeReadiness(guardian);
        order.verify(generation).generatePrepared(any(),any());order.verify(preparations).ready(any(),eq(scene.scenarioId()),any());
        order.verify(activities).startPractice(eq(guardian),eq(key),eq(result.sessionId()),eq(request().requestHash()),any());
        assertThat(result.toString()).doesNotContain("판정 기준","상상 상황");
    }
    @Test void storedReadyPackageSurvivesSelectionChangesAndRestart() {
        var first=service.prepare(guardian,request());clearInvocations(goals,knowledge,generation);
        when(goals.find(guardian,goal)).thenReturn(Optional.empty());
        assertThat(service.prepare(guardian,request())).isEqualTo(first);
        verifyNoInteractions(goals,knowledge,generation);
    }
    @Test void alreadyStartedSameKeyReplaysWithoutRegeneratingOrCheckingReadiness() {
        UUID session=UUID.randomUUID();when(activities.replayPractice(any(),any(),any())).thenReturn(Optional.of(new StartActivityService.Started(session,SessionStatus.REQUESTED)));
        assertThat(service.start(guardian,request()).sessionId()).isEqualTo(session);
        verifyNoInteractions(preparations,generation);verify(activities,never()).checkPracticeReadiness(any());
    }
    @Test void sameKeyChangedGoalsIsConflictBeforeReadinessAndGeneration() {
        service.prepare(guardian,request());clearInvocations(activities,generation);
        error(()->service.prepare(guardian,new PracticeStartRequest(key,List.of(UUID.randomUUID()))),ErrorCode.IDEMPOTENCY_CONFLICT);
        verifyNoInteractions(activities,generation);
    }
    @Test void liveClaimDoesNotCallProviderTwice() {
        service.prepare(guardian,request());var p=stored.get();stored.set(copy(p,"PREPARING",p.claimId(),null));clearInvocations(generation);
        error(()->service.prepare(guardian,request()),ErrorCode.SERVICE_NOT_READY);verifyNoInteractions(generation);
    }
    @Test void expiredClaimResumesSameSessionAndOrder() {
        var first=service.prepare(guardian,request());var p=stored.get();stored.set(copy(p,"PREPARING",p.claimId(),null));
        clock.advance(Duration.ofSeconds(15));assertThat(service.prepare(guardian,request())).isEqualTo(first);
        verify(preparations).reclaim(any(),any(),any());
    }
    @Test void contentFailureIsPermanentForThatKeyAndNeverStartsRealtime() {
        doThrow(CoreApiException.of(ErrorCode.SCENARIO_GEN_FAILED)).when(generation).generatePrepared(any(),any());
        error(()->service.start(guardian,request()),ErrorCode.SCENARIO_GEN_FAILED);
        verify(preparations).release(any(),eq(true),any());verify(activities,never()).startPractice(any(),any(),any(),any(),any());
        var p=stored.get();stored.set(copy(p,"FAILED",p.claimId(),null));clearInvocations(generation);
        error(()->service.prepare(guardian,request()),ErrorCode.SCENARIO_GEN_FAILED);verifyNoInteractions(generation);
    }
    @Test void unavailableProviderReleasesClaimWithoutTurningItIntoContentFailure() {
        doThrow(CoreApiException.of(ErrorCode.SERVICE_NOT_READY)).when(generation).generatePrepared(any(),any());
        error(()->service.prepare(guardian,request()),ErrorCode.SERVICE_NOT_READY);
        verify(preparations).release(any(),eq(false),any());
    }
    @Test void deletionOrConsentWithdrawalAfterAiCannotCommitReadyPreparation() {
        doAnswer(c->{when(consent.check(guardian)).thenReturn(new ConsentPolicyService.GateResult(false,null,null));return scene;}).when(generation).generatePrepared(any(),any());
        error(()->service.prepare(guardian,request()),ErrorCode.CONSENT_MISSING);
        verify(preparations,never()).ready(any(),any(),any());
    }
    @Test void lostClaimAndDeadlineCannotCommitLateGeneration() {
        doAnswer(c->{var p=stored.get();stored.set(copy(p,"PREPARING",UUID.randomUUID(),null));return scene;}).when(generation).generatePrepared(any(),any());
        error(()->service.prepare(guardian,request()),ErrorCode.SERVICE_NOT_READY);verify(preparations,never()).ready(any(),any(),any());
    }
    @Test void foreignOrDeletedGoalDoesNotReachGeneration() {
        when(goals.find(guardian,goal)).thenReturn(Optional.empty());
        error(()->service.prepare(guardian,request()),ErrorCode.NOT_FOUND);verifyNoInteractions(generation);
    }
    @Test void consentIsRequiredBeforeFreezing() {
        when(consent.check(guardian)).thenReturn(new ConsentPolicyService.GateResult(false,null,null));
        error(()->service.prepare(guardian,request()),ErrorCode.CONSENT_MISSING);verifyNoInteractions(goals,generation);
    }
    @Test void sameKeyWinnerStartingBetweenReplayAndReadinessIsReplayed() {
        UUID session=UUID.randomUUID();
        when(activities.replayPractice(any(),any(),any())).thenReturn(Optional.empty(),Optional.of(new StartActivityService.Started(session,SessionStatus.REQUESTED)));
        doThrow(CoreApiException.of(ErrorCode.SESSION_IN_PROGRESS)).when(activities).checkPracticeReadiness(guardian);
        assertThat(service.start(guardian,request()).sessionId()).isEqualTo(session);
        verifyNoInteractions(generation);
    }
    @Test void retiredAndMissingDefinitionsAreValidationFailuresBeforeExternalCalls() {
        when(knowledge.pinVersion("ACCEPT_STOP",1)).thenReturn(new BehaviorDefinition(UUID.randomUUID(),"ACCEPT_STOP",1,"목표","정의","기회","상황","기준","RETIRED",clock.now(),clock.now()));
        error(()->service.prepare(guardian,request()),ErrorCode.VALIDATION_FAILED);
        when(knowledge.pinVersion("ACCEPT_STOP",1)).thenThrow(new IllegalArgumentException("missing"));
        error(()->service.prepare(guardian,request()),ErrorCode.VALIDATION_FAILED);verifyNoInteractions(generation);
    }
    @Test void expiredPreparationAndFailedCompareAndSetRejectLateAiSuccess() {
        doAnswer(c->{clock.advance(Duration.ofSeconds(15));return scene;}).when(generation).generatePrepared(any(),any());
        error(()->service.prepare(guardian,request()),ErrorCode.SERVICE_NOT_READY);
        verify(preparations,never()).ready(any(),any(),any());
    }
    @Test void anotherChildCannotReuseTheOldPreparation() {
        service.prepare(guardian,request());
        when(accounts.requireActive(eq(guardian),anyBoolean())).thenReturn(new PracticeAccountPort.AccountContext(guardian,UUID.randomUUID(),8,List.of()));
        error(()->service.prepare(guardian,request()),ErrorCode.NOT_FOUND);
    }
}
