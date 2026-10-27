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
@Tag("goal-result")
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
    /** FT-1027-g: 목표 후보는 관찰된 지원 필요에서 만든다(goal-candidates.v2). 강점 강화로 후보를 채우던 v1은 이력으로만 남는다. */
    @Test void sendsTheSupportNeedPromptVersionTwo(){run(Map.of());var request=ArgumentCaptor.forClass(LunaRequest.class);verify(luna).call(request.capture());
        assertThat(request.getValue().promptVersion()).isEqualTo("goal-candidates.v2");
        assertThat(request.getValue().instructions()).contains("실제로 어려움을 겪었거나 목표 관련 도움이 필요했던 의사소통 과제","이미 적절하게 사용한 표현만 있는 경우에도 빈 배열이다");}
    @Test void usesGoalPurposeAndJobAttemptOneCall(){run(Map.of());var request=ArgumentCaptor.forClass(LunaRequest.class);verify(luna,times(1)).call(request.capture());assertThat(request.getValue().purpose()).isEqualTo(LunaPurpose.GOAL_CANDIDATES);assertThat(request.getValue().outputMode()).isEqualTo(LunaRequest.OutputMode.CONTRACT);}
    @Test void removesIdentifiersAgeAndGuardianRawInput(){run(Map.of("guardianId",guardian,"childId",child,"age",8,"guardianInput","원문","audio","원음","transcript",List.of(Map.of("speaker","CHILD","seq",1,"text","합성이름 같이 할래"))));var request=ArgumentCaptor.forClass(LunaRequest.class);verify(luna).call(request.capture());String serialized=JsonCodec.shared().write(request.getValue().input());assertThat(serialized).doesNotContain("guardianId","childId","age","guardianInput","원문","원음","합성이름").contains("아이 같이 할래");}
    @Test void rejectsInvalidRoot(){when(luna.call(any(LunaRequest.class))).thenReturn(new LunaResult("{}","fake","goal-candidates.v1",Duration.ZERO,1,false));assertThatThrownBy(()->run(Map.of())).isInstanceOf(kr.mujoong.core.result.ResultAiException.class);}
    @Test void accessDeniedNeverCallsProvider(){assertThatThrownBy(()->gateway.generate(guardian,child,Map.of(),Map.of(),deadline,()->{throw new IllegalStateException("WITHDRAWN");})).isInstanceOf(IllegalStateException.class);verifyNoInteractions(luna);}
    @Test void providerFailureHasNoInnerRetry(){when(luna.call(any(LunaRequest.class))).thenThrow(new LunaException(LunaFailureReason.TIMEOUT,1));assertThatThrownBy(()->run(Map.of())).isInstanceOf(kr.mujoong.core.result.ResultAiException.class);verify(luna,times(1)).call(any(LunaRequest.class));}
    @Test void absenceOfCoreClientIsExplicitlyUnavailable(){assertThat(new GoalCandidateGateway(Optional.empty(),knowledge,new GoalCandidateValidator(new ForbiddenDictionary()),checker).available()).isFalse();}
    @Test void practiceStoredIdentifiersBecomeConsistentPerCallReferencesAtEveryDepth(){
        UUID goal=UUID.randomUUID(),scene=UUID.randomUUID(),utterance=UUID.randomUUID();var input=Map.<String,Object>of("scenes",List.of(Map.of("goalSelectionId",goal,"sceneNo",3,"sceneChip","DONE_IMMEDIATE","chipText","바로 전달","nested",Map.of("goalRef",goal.toString(),"sceneRunId",scene.toString(),"utterances",List.of(Map.of("id",utterance))))));
        run(input);run(input);var capture=ArgumentCaptor.forClass(LunaRequest.class);verify(luna,times(2)).call(capture.capture());
        var first=capture.getAllValues().getFirst().input();String serialized=JsonCodec.shared().write(first);assertThat(serialized).doesNotContain(goal.toString(),scene.toString(),utterance.toString()).contains("sceneNo","DONE_IMMEDIATE","바로 전달");
        var row=kr.mujoong.core.result.ResultData.objects(first.get("scenes")).getFirst();assertThat(kr.mujoong.core.result.ResultData.object(row.get("nested")).get("goalRef")).isEqualTo(row.get("goalSelectionId"));assertThat(capture.getAllValues().getLast().input()).isNotEqualTo(first);
    }
    @Test void seedCatalogIdentifierRemainsValidWhileStoredSceneIdentifiersAreReplaced(){
        var signature=new kr.mujoong.core.knowledge.StructureSignature("PRODUCE","참여 의도를 상대가 이해하도록 표현한다","상대의 의도 1층","YES","YES");var seed=new kr.mujoong.core.knowledge.KnowledgeSeed("seed:S1",kr.mujoong.core.knowledge.KnowledgeSeed.Origin.POC,"참여 표현",signature,"참여 의도를 표현한다","표현","참여 의미","FRIEND_CLOSE","FRIEND_PRIVATE",true);when(knowledge.seedSummaries(guardian,child)).thenReturn(List.of(seed));
        var draft=new GoalCandidateValidatorTest().draft();draft.put("skillRef","seed:S1");when(luna.call(any(LunaRequest.class))).thenReturn(new LunaResult(JsonCodec.shared().write(Map.of("candidates",List.of(draft))),"fake","goal-candidates.v1",Duration.ZERO,1,false));UUID goal=UUID.randomUUID();assertThat(run(Map.of("transcript",List.of(Map.of("seq",1,"speaker","CHILD","text","같이 할래")),"scenes",List.of(Map.of("goalSelectionId",goal))))).singleElement().extracting(GoalCandidateRepository.Draft::skillRef).isEqualTo("seed:S1");var capture=ArgumentCaptor.forClass(LunaRequest.class);verify(luna).call(capture.capture());assertThat(JsonCodec.shared().write(capture.getValue().input())).contains("seed:S1").doesNotContain(goal.toString());
    }
    @Test void mixedTranscriptDropsUncertainEvidenceCandidateAndKeepsCertainCandidate(){
        var uncertain=new GoalCandidateValidatorTest().draft();uncertain.put("displayName","다시 묻기");uncertain.put("evidence",List.of(Map.of("utteranceSeq",2,"quote","아마 함께 할래")));var certain=new GoalCandidateValidatorTest().draft();
        when(luna.call(any(LunaRequest.class))).thenReturn(new LunaResult(JsonCodec.shared().write(Map.of("candidates",List.of(uncertain,certain))),"fake","goal-candidates.v1",Duration.ZERO,1,false));
        var result=run(Map.of("transcript",List.of(Map.of("seq",1,"speaker","CHILD","text","같이 할래","uncertain",false),Map.of("seq",2,"speaker","CHILD","text","아마 함께 할래","uncertain",true))));
        assertThat(result).singleElement().extracting(GoalCandidateRepository.Draft::displayName).isEqualTo("물어보기");var capture=ArgumentCaptor.forClass(LunaRequest.class);verify(luna).call(capture.capture());assertThat(JsonCodec.shared().write(capture.getValue().input())).contains("같이 할래").doesNotContain("아마 함께 할래");
    }
    @Test void uncertainOnlyTranscriptCannotGroundACandidate(){
        var draft=new GoalCandidateValidatorTest().draft();when(luna.call(any(LunaRequest.class))).thenReturn(new LunaResult(JsonCodec.shared().write(Map.of("candidates",List.of(draft))),"fake","goal-candidates.v1",Duration.ZERO,1,false));
        assertThat(run(Map.of("transcript",List.of(Map.of("seq",1,"speaker","CHILD","text","같이 할래","uncertain",true))))).isEmpty();
    }

    @Test void quotedFutureExpressionKeepsGroundedCandidateWithoutInventingChildEvidence(){
        var draft=new GoalCandidateValidatorTest().draft();draft.put("targetExpression","다음 연습에서 “함께 해도 돼?” 하고 물어보기");
        var classifier=mock(kr.mujoong.core.result.ResultTextGateway.class);
        when(classifier.call(eq("classify"),anyMap(),anyMap(),eq(deadline),any(Runnable.class))).thenAnswer(i->{
            Map<String,Object> input=i.getArgument(1);var rows=(List<Map<String,Object>>)input.get("sentences");
            assertThat(rows).hasSize(4);assertThat(rows.stream().filter(r->"PRACTICE_PROPOSAL".equals(r.get("textUse"))).map(r->r.get("id")).toList()).containsExactly("goal-target-0");
            return Map.of("results",rows.stream().map(r->Map.of("id",r.get("id"),"violation",false)).toList());
        });
        var realChecker=new OutputChecker(new ForbiddenDictionary(),classifier);
        var subject=new GoalCandidateGateway(Optional.of(luna),knowledge,new GoalCandidateValidator(new ForbiddenDictionary()),realChecker);
        when(luna.call(any(LunaRequest.class))).thenReturn(new LunaResult(JsonCodec.shared().write(Map.of("candidates",List.of(draft))),"fake","goal-candidates.v1",Duration.ZERO,1,false));
        var result=subject.generate(guardian,child,Map.of("transcript",List.of(Map.of("seq",1,"speaker","CHILD","text","같이 할래"))),Map.of(),deadline,()->{});
        assertThat(result).singleElement().satisfies(c->{assertThat(c.targetExpression()).contains("함께 해도 돼?");assertThat(c.evidence()).singleElement().extracting(GoalCandidateRepository.Evidence::quote).isEqualTo("같이 할래");});
        draft.put("evidence",List.of(Map.of("utteranceSeq",1,"quote","함께 해도 돼?")));
        when(luna.call(any(LunaRequest.class))).thenReturn(new LunaResult(JsonCodec.shared().write(Map.of("candidates",List.of(draft))),"fake","goal-candidates.v1",Duration.ZERO,1,false));
        assertThat(subject.generate(guardian,child,Map.of("transcript",List.of(Map.of("seq",1,"speaker","CHILD","text","같이 할래"))),Map.of(),deadline,()->{})).isEmpty();
        verify(classifier,times(1)).call(eq("classify"),anyMap(),anyMap(),eq(deadline),any(Runnable.class));
    }
}
