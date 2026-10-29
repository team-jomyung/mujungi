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
    kr.mujoong.core.support.TestEvents events=new kr.mujoong.core.support.TestEvents(new kr.mujoong.testsupport.time.MutableClock(Instant.parse("2030-01-01T00:00:00Z")));
    GoalCandidateGateway gateway;UUID guardian=UUID.randomUUID(),child=UUID.randomUUID();Instant deadline=Instant.parse("2030-01-01T00:00:00Z");
    @BeforeEach void setup(){gateway=new GoalCandidateGateway(Optional.of(luna),knowledge,new GoalCandidateValidator(new ForbiddenDictionary()),checker,events.logger());when(knowledge.seedSummaries(guardian,child)).thenReturn(List.of());when(luna.call(any(LunaRequest.class))).thenReturn(new LunaResult("{\"candidates\":[]}","fake","goal-candidates.v1",Duration.ZERO,1,false));when(checker.check(anyList(),eq(deadline),any(Runnable.class))).thenAnswer(i->{Map<String,String> result=new LinkedHashMap<>();for(OutputChecker.Draft d:i.<List<OutputChecker.Draft>>getArgument(0))result.put(d.id(),d.text());return result;});}
    List<GoalCandidateRepository.Draft> run(Map<String,Object> input){return gateway.generate(guardian,child,input,Map.of("합성이름","아이"),deadline,()->{});}
    @Test void emptyCandidatesAreValid(){assertThat(run(Map.of("transcript",List.of()))).isEmpty();}
    @Test void secondClassifierChecksAllPublicCandidateTextAndCanDropWholeCandidate(){
        var draft=new GoalCandidateValidatorTest().draft();when(luna.call(any(LunaRequest.class))).thenReturn(new LunaResult(JsonCodec.shared().write(Map.of("candidates",List.of(draft))),"fake","goal-candidates.v1",Duration.ZERO,1,false));
        when(checker.check(anyList(),eq(deadline),any(Runnable.class))).thenAnswer(i->{List<OutputChecker.Draft> checks=i.getArgument(0);assertThat(checks).extracting(OutputChecker.Draft::id).containsExactly("goal-reason-0","goal-display-0","goal-name-0","goal-target-0");Map<String,String> result=new LinkedHashMap<>();for(var check:checks)result.put(check.id(),check.id().equals("goal-name-0")?null:check.text());return result;});
        assertThat(run(Map.of("transcript",List.of(Map.of("seq",1,"speaker","CHILD","text","같이 할래"))))).isEmpty();verify(checker,times(1)).check(anyList(),eq(deadline),any(Runnable.class));
    }
    /**
     * FT-1027-g · FT-1027-i · FT-1029-b: 목표 후보는 관찰된 지원 필요에서 만든다(goal-candidates.v4). v4는 평가 기준표 지표 여섯 개를 모두 평가하고
     * 지시어만으로 가리키기 · 낱말 대신 '그거'를 P2 지원 필요로 보며, 상대가 되물어 해결된 경우도 빼지 않는다.
     */
    @Test void sendsTheAllIndicatorsPromptVersionFour(){run(Map.of());var request=ArgumentCaptor.forClass(LunaRequest.class);verify(luna).call(request.capture());
        assertThat(request.getValue().promptVersion()).isEqualTo("goal-candidates.v4");
        assertThat(request.getValue().instructions()).contains("이미 적절하게 사용한 표현만 있는 경우에도 빈 배열이다","다음 여섯 지표를 하나도 빠뜨리지 않고 평가한다",
                "'엄마랑 그거 먹으러 갔어'","'그거 있잖아 그거'","상대가 되물어 결국 뜻이 통했더라도","최대 8개","assessment에는 여섯 지표를 한 번씩")
                .doesNotContain("완성 문장을 다시 말하는 목표를 만들지 않는다","최대 3개");
        // 서버 NumberGuard가 숫자 섞인 사유를 버리므로 지시문이 seq 번호를 evidence에만 쓰게 한다
        assertThat(request.getValue().instructions()).contains("reasonText·displayEvidence에는 seq 번호나 다른 숫자를 쓰지 않는다").doesNotContain("입력에 seq가 있으면 해당 seq로 근거를 연결한다");
        // PERFORM_RUBRIC P1(응답) · P3(화제)와 씨앗 정의로 다시 확인하는 문장. 모욕 · 무시하는 말 자체는 근거가 아니다(3-2)
        assertThat(request.getValue().instructions()).contains("아이가 그에 답하지 않고 관련 없는 말을 했다","연결이나 알림 없이 자기 화제로 바꿨다",
                "상대를 탓하거나 무시하는 말 자체는 응답·화제 기준의 근거가 아니다","그 seedId를 skillRef로 쓰고, 그 정의에 비추어 근거가 맞는지 다시 확인한다");}
    /** FT-1027-i: AI가 같은 말을 되풀이한 구간은 보내지 않고, 그 구간의 아이 발화를 인용한 후보는 인용 검사에서 빠진다. */
    @Test void repeatedAiSpanIsNotSentAndCannotGroundACandidate(){
        var draft=new GoalCandidateValidatorTest().draft();draft.put("evidence",List.of(Map.of("utteranceSeq",4,"quote","너 왜 그 말만 반복해")));
        when(luna.call(any(LunaRequest.class))).thenReturn(new LunaResult(JsonCodec.shared().write(Map.of("candidates",List.of(draft))),"fake","goal-candidates.v3",Duration.ZERO,1,false));
        var result=run(Map.of("transcript",List.of(Map.of("seq",1,"speaker","AI","text","헷갈릴 수 있지."),Map.of("seq",2,"speaker","CHILD","text","사후세계에 갔어"),
                Map.of("seq",3,"speaker","AI","text","음, 헷갈릴 수 있지."),Map.of("seq",4,"speaker","CHILD","text","너 왜 그 말만 반복해"),Map.of("seq",5,"speaker","AI","text","걱정되는구나."),Map.of("seq",6,"speaker","CHILD","text","응"))));
        assertThat(result).isEmpty();
        var capture=ArgumentCaptor.forClass(LunaRequest.class);verify(luna).call(capture.capture());
        assertThat(kr.mujoong.core.result.ResultData.objects(capture.getValue().input().get("transcript"))).extracting(row->((Number)row.get("seq")).intValue()).containsExactly(5,6);
        assertThat(events.capture().events(kr.mujoong.contract.log.EventCode.GOAL_CANDIDATE_DROPPED)).singleElement().extracting(line->kr.mujoong.testsupport.log.LogCapture.attrs(line).get("dropReason")).isEqualTo("QUOTE_MISMATCH");
    }
    @Test void usesGoalPurposeAndJobAttemptOneCall(){run(Map.of());var request=ArgumentCaptor.forClass(LunaRequest.class);verify(luna,times(1)).call(request.capture());assertThat(request.getValue().purpose()).isEqualTo(LunaPurpose.GOAL_CANDIDATES);assertThat(request.getValue().outputMode()).isEqualTo(LunaRequest.OutputMode.CONTRACT);}
    @Test void removesIdentifiersAgeAndGuardianRawInput(){run(Map.of("guardianId",guardian,"childId",child,"age",8,"guardianInput","원문","audio","원음","transcript",List.of(Map.of("speaker","CHILD","seq",1,"text","합성이름 같이 할래"))));var request=ArgumentCaptor.forClass(LunaRequest.class);verify(luna).call(request.capture());String serialized=JsonCodec.shared().write(request.getValue().input());assertThat(serialized).doesNotContain("guardianId","childId","age","guardianInput","원문","원음","합성이름").contains("아이 같이 할래");}
    @Test void rejectsInvalidRoot(){when(luna.call(any(LunaRequest.class))).thenReturn(new LunaResult("{}","fake","goal-candidates.v1",Duration.ZERO,1,false));assertThatThrownBy(()->run(Map.of())).isInstanceOf(kr.mujoong.core.result.ResultAiException.class);}
    @Test void accessDeniedNeverCallsProvider(){assertThatThrownBy(()->gateway.generate(guardian,child,Map.of(),Map.of(),deadline,()->{throw new IllegalStateException("WITHDRAWN");})).isInstanceOf(IllegalStateException.class);verifyNoInteractions(luna);}
    @Test void providerFailureHasNoInnerRetry(){when(luna.call(any(LunaRequest.class))).thenThrow(new LunaException(LunaFailureReason.TIMEOUT,1));assertThatThrownBy(()->run(Map.of())).isInstanceOf(kr.mujoong.core.result.ResultAiException.class);verify(luna,times(1)).call(any(LunaRequest.class));}
    @Test void absenceOfCoreClientIsExplicitlyUnavailable(){assertThat(new GoalCandidateGateway(Optional.empty(),knowledge,new GoalCandidateValidator(new ForbiddenDictionary()),checker,events.logger()).available()).isFalse();}
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
        var subject=new GoalCandidateGateway(Optional.of(luna),knowledge,new GoalCandidateValidator(new ForbiddenDictionary()),realChecker,events.logger());
        when(luna.call(any(LunaRequest.class))).thenReturn(new LunaResult(JsonCodec.shared().write(Map.of("candidates",List.of(draft))),"fake","goal-candidates.v1",Duration.ZERO,1,false));
        var result=subject.generate(guardian,child,Map.of("transcript",List.of(Map.of("seq",1,"speaker","CHILD","text","같이 할래"))),Map.of(),deadline,()->{});
        assertThat(result).singleElement().satisfies(c->{assertThat(c.targetExpression()).contains("함께 해도 돼?");assertThat(c.evidence()).singleElement().extracting(GoalCandidateRepository.Evidence::quote).isEqualTo("같이 할래");});
        draft.put("evidence",List.of(Map.of("utteranceSeq",1,"quote","함께 해도 돼?")));
        when(luna.call(any(LunaRequest.class))).thenReturn(new LunaResult(JsonCodec.shared().write(Map.of("candidates",List.of(draft))),"fake","goal-candidates.v1",Duration.ZERO,1,false));
        assertThat(subject.generate(guardian,child,Map.of("transcript",List.of(Map.of("seq",1,"speaker","CHILD","text","같이 할래"))),Map.of(),deadline,()->{})).isEmpty();
        verify(classifier,times(1)).call(eq("classify"),anyMap(),anyMap(),eq(deadline),any(Runnable.class));
    }
    /** FT-1029-b: 호출마다 화용 지표 평가 요약 한 줄. 지표 코드와 모델이 낸 후보 수만 싣고, 모르는 지표 · 판정은 버린다. */
    @Test void assessmentIsLoggedAsIndicatorCodesOnly(){
        var assessment=List.of(Map.of("indicator","P1","rating","OK"),Map.of("indicator","P2","rating","NEED"),Map.of("indicator","P3","rating","OK"),
                Map.of("indicator","P4","rating","NOT_OBSERVED"),Map.of("indicator","P5","rating","NOT_OBSERVED"),Map.of("indicator","U5","rating","NEED"),
                Map.of("indicator","X9","rating","NEED"),Map.of("indicator","P1","rating","GOOD"));
        when(luna.call(any(LunaRequest.class))).thenReturn(new LunaResult(JsonCodec.shared().write(Map.of("candidates",List.of(),"assessment",assessment)),"fake","goal-candidates.v4",Duration.ZERO,1,false));
        assertThat(run(Map.of("transcript",List.of(Map.of("seq",1,"speaker","CHILD","text","엄마랑 그거 먹으러 갔어"))))).isEmpty();
        assertThat(events.capture().events(kr.mujoong.contract.log.EventCode.GOAL_CANDIDATES_ASSESSED)).singleElement().extracting(kr.mujoong.testsupport.log.LogCapture::attrs)
                .isEqualTo(Map.of("promptVersion","goal-candidates.v4","candidatesProposed",0,"needIndicators","P2,U5","okIndicators","P1,P3","notObservedIndicators","P4,P5"));
        assertThat(events.capture().lines()).noneMatch(line->line.contains("그거 먹으러"));
    }
    /** 평가가 없던 판의 출력(빈 assessment)도 줄은 남기고 세 목록은 비어 있다. 후보 0개가 모델 판단인지 가르는 줄이 늘 있다. */
    @Test void missingAssessmentStillLogsEmptyLists(){
        when(luna.call(any(LunaRequest.class))).thenReturn(new LunaResult(JsonCodec.shared().write(Map.of("candidates",List.of())),"fake","goal-candidates.v4",Duration.ZERO,1,false));
        run(Map.of());
        assertThat(events.capture().events(kr.mujoong.contract.log.EventCode.GOAL_CANDIDATES_ASSESSED)).singleElement().extracting(line->kr.mujoong.testsupport.log.LogCapture.attrs(line).get("candidatesProposed")).isEqualTo(0);
    }
    /** 서버가 버린 후보는 GOAL_CANDIDATE_DROPPED에 사유 코드만 남는다(후보 글 · 인용은 로그에 없다, R-F14). */
    @Test void numberInReasonIsDroppedWithCodeOnlyDiagnostic(){
        var numbered=new GoalCandidateValidatorTest().draft();numbered.put("reasonText","합성 장면 41번 발화에서 대답했어요.");
        var clean=new GoalCandidateValidatorTest().draft();clean.put("displayName","다른 이름");
        when(luna.call(any(LunaRequest.class))).thenReturn(new LunaResult(JsonCodec.shared().write(Map.of("candidates",List.of(numbered,clean))),"fake","goal-candidates.v2",Duration.ZERO,1,false));
        when(checker.check(anyList(),eq(deadline),any(Runnable.class))).thenAnswer(i->{Map<String,String> all=new LinkedHashMap<>();for(OutputChecker.Draft d:i.<List<OutputChecker.Draft>>getArgument(0))all.put(d.id(),d.text());return all;});
        assertThat(run(Map.of("transcript",List.of(Map.of("seq",1,"speaker","CHILD","text","같이 할래"))))).singleElement().extracting(GoalCandidateRepository.Draft::displayName).isEqualTo("다른 이름");
        var lines=events.capture().events(kr.mujoong.contract.log.EventCode.GOAL_CANDIDATE_DROPPED);
        assertThat(lines).singleElement().extracting(kr.mujoong.testsupport.log.LogCapture::attrs).isEqualTo(Map.of("promptVersion","goal-candidates.v4","candidateIndex",0,"candidatesProposed",2,"dropReason","NUMBER_GUARD","dropField","REASON_TEXT"));
        assertThat(events.capture().lines()).noneMatch(line->line.contains("41번")||line.contains("같이 할래"));
    }
    @Test void secondClassifierDropKeepsOriginalIndexAndFirstFailingField(){
        var first=new GoalCandidateValidatorTest().draft();first.put("fourElements",Map.of("situation","놀이"));
        var second=new GoalCandidateValidatorTest().draft();
        when(luna.call(any(LunaRequest.class))).thenReturn(new LunaResult(JsonCodec.shared().write(Map.of("candidates",List.of(first,second))),"fake","goal-candidates.v2",Duration.ZERO,1,false));
        when(checker.firstPassFailure(any())).thenReturn(Optional.empty());
        when(checker.check(anyList(),eq(deadline),any(Runnable.class))).thenAnswer(i->{Map<String,String> all=new HashMap<>();for(OutputChecker.Draft d:i.<List<OutputChecker.Draft>>getArgument(0))all.put(d.id(),d.id().startsWith("goal-display")?null:d.text());return all;});
        assertThat(run(Map.of("transcript",List.of(Map.of("seq",1,"speaker","CHILD","text","같이 할래"))))).isEmpty();
        assertThat(events.capture().events(kr.mujoong.contract.log.EventCode.GOAL_CANDIDATE_DROPPED)).extracting(line->kr.mujoong.testsupport.log.LogCapture.attrs(line).get("dropReason")+"/"+kr.mujoong.testsupport.log.LogCapture.attrs(line).get("dropField")+"/"+kr.mujoong.testsupport.log.LogCapture.attrs(line).get("candidateIndex"))
                .containsExactly("LENGTH/PARTNER/0","CHECK_CLASSIFIER/DISPLAY_EVIDENCE/1");
    }
    @Test void modelReturningNoCandidatesLeavesNoDropLine(){assertThat(run(Map.of("transcript",List.of()))).isEmpty();assertThat(events.capture().events(kr.mujoong.contract.log.EventCode.GOAL_CANDIDATE_DROPPED)).isEmpty();}
}
