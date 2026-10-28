package kr.mujoong.core.practicegoal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import kr.mujoong.core.consent.ConsentPolicyService;
import kr.mujoong.core.knowledge.*;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.practice.PracticeAccountPort;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;

@Tag("goal-result")
class CandidateSeedLinkTest {
    UUID guardian=UUID.randomUUID(),child=UUID.randomUUID(),session=UUID.randomUUID(),result=UUID.randomUUID();
    GoalCandidateRepository repository=mock(GoalCandidateRepository.class);PracticeAccountPort accounts=mock(PracticeAccountPort.class);
    ConsentPolicyService consent=mock(ConsentPolicyService.class);SeedGenerationService seeds=mock(SeedGenerationService.class);
    NamedParameterJdbcTemplate jdbc=mock(NamedParameterJdbcTemplate.class);EventLogger events=mock(EventLogger.class);CandidateSeedDelivery delivery;
    GoalCandidateRepository.Version version;
    @BeforeEach void setup(){
        var transactions=mock(PlatformTransactionManager.class);when(transactions.getTransaction(any())).thenAnswer(i->new SimpleTransactionStatus());
        when(accounts.requireActive(guardian,true)).thenReturn(new PracticeAccountPort.AccountContext(guardian,child,null,List.of()));
        when(consent.check(guardian)).thenReturn(new ConsentPolicyService.GateResult(true,null,new ConsentPolicyService.Snapshot("v1",true,true,true,true)));
        when(jdbc.queryForObject(anyString(),anyMap(),eq(Integer.class))).thenReturn(1);when(jdbc.queryForObject(anyString(),anyMap(),eq(Boolean.class))).thenReturn(true);
        var draft=new GoalCandidateRepository.Draft("합성목표","합성장면","전달",new FourElements("놀이","친구","참여","말하기"),"NONE","합성근거",List.of(new GoalCandidateRepository.Evidence(1,"같이 할래")),new StructureSignature("PRODUCE","참여 의도를 상대가 이해하도록 표현한다","상대의 의도 1층","YES","YES"));
        version=new GoalCandidateRepository.Version(UUID.randomUUID(),UUID.randomUUID(),child,session,1,draft);
        when(repository.register(eq(child),eq(session),anyString(),any(),any())).thenReturn(version);
        when(seeds.generateSeed(any())).thenReturn(new SeedGenerationService.Result("seed:S1","REUSED"));
        delivery=new CandidateSeedDelivery(repository,accounts,consent,seeds,jdbc,new MutableClock(),events,transactions);
    }
    void deliver(){delivery.deliver(guardian,child,session,result,CandidateSeedDelivery.Pending.of(List.of(version.content())));}
    @Test void successPublishesNewVersion(){deliver();verify(repository).linkSeed(eq(version),eq("seed:S1"),any());}
    @Test void freeTalkObservationsAreWrittenAfterTheNewSeedIsLinked(){
        var knowledge=mock(ChildKnowledgeService.class);delivery.connectKnowledge(knowledge);
        var observed=Map.of("seed:S1",new SeedObservation(2,java.time.Instant.parse("2030-01-01T00:00:00Z")));
        when(repository.freeTalkSkillObservations(child)).thenReturn(observed);
        deliver();
        @SuppressWarnings("unchecked") var counted=org.mockito.ArgumentCaptor.forClass(java.util.function.Supplier.class);
        var order=inOrder(repository,knowledge);
        order.verify(repository).linkSeed(eq(version),eq("seed:S1"),any());order.verify(knowledge).recordObservations(eq(guardian),eq(child),counted.capture());
        // 횟수는 그래프 서비스가 권한을 확인한 뒤 공급자를 부를 때 센다.
        verify(repository,never()).freeTalkSkillObservations(any());
        assertThat(counted.getValue().get()).isEqualTo(observed);
    }
    @Test void observationFailureDoesNotFailCompletedResult(){
        var knowledge=mock(ChildKnowledgeService.class);delivery.connectKnowledge(knowledge);
        when(knowledge.recordObservations(any(),any(),any())).thenThrow(new IllegalStateException("graph unavailable"));
        assertThatCode(this::deliver).doesNotThrowAnyException();
        verify(repository).linkSeed(eq(version),eq("seed:S1"),any());verify(events).appError(any(),eq("SkillObservationSyncFailed"));
    }
    @Test void disabledSeedDoesNotChangeCandidate(){when(seeds.generateSeed(any())).thenReturn(new SeedGenerationService.Result("NONE","DISABLED"));deliver();verify(repository,never()).linkSeed(any(),anyString(),any());}
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"TIMEOUT","RAW_SOURCE_REJECTED","PERSON_NAME_REJECTED"})
    void seedFailureDoesNotFailCompletedResult(String reason){when(seeds.generateSeed(any())).thenThrow(new IllegalStateException(reason));assertThatCode(this::deliver).doesNotThrowAnyException();verify(repository,never()).linkSeed(any(),anyString(),any());verify(events).appError(any(),eq("GoalSeedPostProcessingFailed"));}
    @Test void noAbstractionDoesNotGenerate(){var d=version.content();version=new GoalCandidateRepository.Version(version.id(),version.candidateId(),child,session,1,new GoalCandidateRepository.Draft(d.displayName(),d.displayEvidence(),d.targetExpression(),d.fourElements(),d.skillRef(),d.reasonText(),d.evidence(),null));when(repository.register(eq(child),eq(session),anyString(),any(),any())).thenReturn(version);deliver();verifyNoInteractions(seeds);}
    @Test void deletedSourceDoesNotRegisterOrRegenerate(){when(jdbc.queryForObject(anyString(),anyMap(),eq(Integer.class))).thenReturn(0);assertThatThrownBy(this::deliver).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);verifyNoInteractions(seeds);verify(repository,never()).register(any(),any(),anyString(),any(),any());}
    @Test void withdrawalDuringSeedCannotLink(){when(seeds.generateSeed(any())).thenAnswer(i->{when(consent.check(guardian)).thenReturn(new ConsentPolicyService.GateResult(false,null,new ConsentPolicyService.Snapshot("v1",false,true,true,true)));return new SeedGenerationService.Result("seed:S1","REUSED");});deliver();verify(repository,never()).linkSeed(any(),anyString(),any());}
    @Test void onlyFreeTalkGeneratesSeeds(){when(jdbc.queryForObject(anyString(),anyMap(),eq(Boolean.class))).thenReturn(false);deliver();verifyNoInteractions(seeds);}
    @Test void registrationDbFailureLeavesDurablePendingForReplay(){when(repository.register(eq(child),eq(session),anyString(),any(),any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("DB"));assertThatThrownBy(this::deliver).isInstanceOf(org.springframework.dao.DataAccessException.class);verifyNoInteractions(seeds);}
}
