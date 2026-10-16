package kr.mujoong.core.practicegoal;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.knowledge.ChildKnowledgeService;
import kr.mujoong.core.luna.*;
import kr.mujoong.core.result.check.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
@Tag("u14")
class GoalCandidateGatewayTest {
    CoreLunaClient luna=mock(CoreLunaClient.class);ChildKnowledgeService knowledge=mock(ChildKnowledgeService.class);OutputChecker checker=mock(OutputChecker.class);
    GoalCandidateGateway gateway;UUID guardian=UUID.randomUUID(),child=UUID.randomUUID();Instant deadline=Instant.parse("2030-01-01T00:00:00Z");
    @BeforeEach void setup(){gateway=new GoalCandidateGateway(Optional.of(luna),knowledge,new GoalCandidateValidator(new ForbiddenDictionary()),checker);when(knowledge.seedSummaries(guardian,child)).thenReturn(List.of());when(luna.call(any(LunaRequest.class))).thenReturn(new LunaResult("{\"candidates\":[]}","fake","goal-candidates.v1",Duration.ZERO,1,false));when(checker.check(anyList(),eq(deadline),any(Runnable.class))).thenAnswer(i->{Map<String,String> result=new LinkedHashMap<>();for(OutputChecker.Draft d:i.<List<OutputChecker.Draft>>getArgument(0))result.put(d.id(),d.text());return result;});}
    List<GoalCandidateRepository.Draft> run(Map<String,Object> input){return gateway.generate(guardian,child,input,Map.of("합성이름","아이"),deadline,()->{});}
    @Test void emptyCandidatesAreValid(){assertThat(run(Map.of("transcript",List.of()))).isEmpty();}
    @Test void secondClassifierChecksAllPublicCandidateTextAndCanDropWholeCandidate(){
        var draft=new GoalCandidateValidatorTest().draft();when(luna.call(any(LunaRequest.class))).thenReturn(new LunaResult(JsonCodec.shared().write(Map.of("candidates",List.of(draft))),"fake","goal-candidates.v1",Duration.ZERO,1,false));
        when(checker.check(anyList(),eq(deadline),any(Runnable.class))).thenAnswer(i->{List<OutputChecker.Draft> checks=i.getArgument(0);assertThat(checks).extracting(OutputChecker.Draft::id).containsExactly("goal-reason-0","goal-display-0","goal-name-0","goal-target-0");Map<String,String> result=new LinkedHashMap<>();for(var check:checks)result.put(check.id(),check.id().equals("goal-name-0")?null:check.text());return result;});
        assertThat(run(Map.of("transcript",List.of(Map.of("seq",1,"speaker","CHILD","text","같이 할래"))))).isEmpty();verify(checker,times(1)).check(anyList(),eq(deadline),any(Runnable.class));
    }
    @Test void usesGoalPurposeAndJobAttemptOneCall(){run(Map.of());var request=ArgumentCaptor.forClass(LunaRequest.class);verify(luna,times(1)).call(request.capture());assertThat(request.getValue().purpose()).isEqualTo(LunaPurpose.GOAL_CANDIDATES);assertThat(request.getValue().outputMode()).isEqualTo(LunaRequest.OutputMode.CONTRACT);}
    @Test void removesIdentifiersAgeAndGuardianRawInput(){run(Map.of("guardianId",guardian,"childId",child,"age",8,"guardianInput","원문","audio","원음","transcript",List.of(Map.of("speaker","CHILD","seq",1,"text","합성이름 같이 할래"))));var request=ArgumentCaptor.forClass(LunaRequest.class);verify(luna).call(request.capture());String serialized=JsonCodec.shared().write(request.getValue().input());assertThat(serialized).doesNotContain("guardianId","childId","age","guardianInput","원문","원음","합성이름").contains("아이 같이 할래");}
    @Test void rejectsInvalidRoot(){when(luna.call(any(LunaRequest.class))).thenReturn(new LunaResult("{}","fake","goal-candidates.v1",Duration.ZERO,1,false));assertThatThrownBy(()->run(Map.of())).isInstanceOf(kr.mujoong.core.result.ResultAiException.class);}
    @Test void accessDeniedNeverCallsProvider(){assertThatThrownBy(()->gateway.generate(guardian,child,Map.of(),Map.of(),deadline,()->{throw new IllegalStateException("WITHDRAWN");})).isInstanceOf(IllegalStateException.class);verifyNoInteractions(luna);}
    @Test void providerFailureHasNoInnerRetry(){when(luna.call(any(LunaRequest.class))).thenThrow(new LunaException(LunaFailureReason.TIMEOUT,1));assertThatThrownBy(()->run(Map.of())).isInstanceOf(kr.mujoong.core.result.ResultAiException.class);verify(luna,times(1)).call(any(LunaRequest.class));}
    @Test void absenceOfCoreClientIsExplicitlyUnavailable(){assertThat(new GoalCandidateGateway(Optional.empty(),knowledge,new GoalCandidateValidator(new ForbiddenDictionary()),checker).available()).isFalse();}
}
