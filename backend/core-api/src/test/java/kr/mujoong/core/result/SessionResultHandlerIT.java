package kr.mujoong.core.result;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.activity.ActivityContextRepository;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.consent.ConsentPolicyService;
import kr.mujoong.core.identity.*;
import kr.mujoong.core.knowledge.*;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.practice.*;
import kr.mujoong.core.record.*;
import kr.mujoong.core.report.*;
import kr.mujoong.core.result.check.*;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
@Tag("result-report") @Tag("db")
class SessionResultHandlerIT {
    DbTestSupport db;MutableClock clock;ExecutorService executor;FakeResultTextProvider provider;ResultJobRepository jobs;SessionResultStateRepository states;
    ActivityResultRepository results;SceneJudgmentRepository scenes;StructuredExperienceRepository experiences;JobRunner runner;CandidateRegistry registry;
    ConsentPolicyService consent;ScenarioRepository scenarioRepository;ActivityContextRepository contexts;ChildRepository childProfiles;
    UUID session,guardian,child,goal;ResultRecordReader reader;BehaviorDefinitionLookup definitions;EventLogger events;
    @BeforeEach void setup(){db=DbTestSupport.coreApp();clock=new MutableClock();executor=Executors.newVirtualThreadPerTaskExecutor();provider=new FakeResultTextProvider();jobs=new ResultJobRepository(db.jdbc());states=new SessionResultStateRepository(db.jdbc());results=new ActivityResultRepository(db.jdbc());scenes=new SceneJudgmentRepository(db.jdbc());experiences=new StructuredExperienceRepository(db.jdbc());registry=mock(CandidateRegistry.class);reader=mock(ResultRecordReader.class);scenarioRepository=mock(ScenarioRepository.class);definitions=mock(BehaviorDefinitionLookup.class);events=mock(EventLogger.class);session=UUID.randomUUID();goal=UUID.randomUUID();provider.response=this::normalOutput;}
    @AfterEach void close(){executor.shutdownNow();db.close();}
    List<Map<String,Object>> transcript(int count){var rows=new ArrayList<Map<String,Object>>();for(int i=1;i<=count;i++)rows.add(Map.of("seq",i,"speaker","CHILD","text","같이 블록 쌓자","uncertain",false,"startedAt",clock.now().toString()));return rows;}
    Map<String,Object> practice(){return Map.of("goals",List.of(Map.of("goalSelectionId",goal.toString(),"behaviorKey","request","behaviorVersion",1,"displayName","부탁하기","order",1,"status","DONE","responses",List.of(Map.of("responseType","FIRST","utteranceSeq",1,"label","none"),Map.of("responseType","NEW_SCENARIO","utteranceSeq",2,"label","functional")),"cues",List.of())));}
    String normalOutput(ResultTextProvider.Request request){return switch(request.task()){
        case "structure"->"{\"characters\":[],\"situation\":{\"text\":\"같이 블록 쌓자\",\"source\":\"CHILD_RECALL\",\"utteranceSeq\":1},\"partnerUtterance\":null,\"childResponse\":null,\"explanation\":null}";
        case "sentences"->JsonCodec.shared().write(Map.of("sentences",List.of(Map.of("text","함께 쌓자는 말을 했어요.","citations",List.of(Map.of("utteranceSeq",1))))));
        case "candidates"->"{\"candidates\":[]}";
        case "classify"->JsonCodec.shared().write(Map.of("results",ResultData.objects(request.input().get("sentences")).stream().map(s->Map.of("id",s.get("id"),"violation",false)).toList()));
        case "judgment"->JsonCodec.shared().write(Map.of("goals",List.of(Map.of("goalSelectionId",ResultData.objects(request.input().get("goals")).getFirst().get("goalSelectionId"),"scenes",List.of(Map.of("sceneNo",1,"value","functional","timing","IMMEDIATE","rationale","기능을 표현","firstResponse",Map.of("utteranceSeq",1,"quote","같이 블록 쌓자")),Map.of("sceneNo",2,"value","functional","timing","IMMEDIATE","rationale","기능을 표현","firstResponse",Map.of("utteranceSeq",2,"quote","같이 블록 쌓자")))))));
        default->throw new AssertionError(request.task());};}
    ResultJob prepare(String type,int utterances,Map<String,Object> practice){
        guardian=db.insertGuardian("ACTIVE");child=db.insertChild(guardian);
        var r=new ActivityRecordRepository.StoredRecord(session,guardian,child,type,"ENDED","CHILD_STOP",true,clock.now().minusSeconds(180),clock.now(),clock.now(),clock.now().minusSeconds(180),clock.now(),JsonCodec.shared().write(transcript(utterances)),"[]",practice==null?null:JsonCodec.shared().write(practice),"hash",clock.now());
        when(reader.find(session,guardian)).thenReturn(Optional.of(new ResultRecordReader.Input(r,"ENDED",null,utterances>0)));
        consent=mock(ConsentPolicyService.class);when(consent.check(guardian)).thenReturn(new ConsentPolicyService.GateResult(true,null,new ConsentPolicyService.Snapshot("v1",true,true,true,true)));
        var definition=new BehaviorDefinition(UUID.randomUUID(),"request",1,"부탁하기","필요한 것 부탁","기회","상황","기준","ACTIVE",clock.now(),null);when(definitions.get("request",1)).thenReturn(definition);when(definitions.active()).thenReturn(List.of(definition));
        var ai=new ResultTextGateway(provider,ResultTextGatewayTest.properties(Duration.ofSeconds(2)),clock,executor,JsonCodec.shared());var dictionary=new ForbiddenDictionary();var tm=new DataSourceTransactionManager(db.dataSource());
        contexts=mock(ActivityContextRepository.class);childProfiles=mock(ChildRepository.class);
        var handler=new SessionResultHandler(reader,contexts,consent,childProfiles,definitions,new ScenarioLookup(scenarioRepository),ai,new OutputChecker(dictionary,ai),new CandidateScreen(dictionary,events),new TipMatcher(mock(SupportLibrary.class),dictionary),new NotDoneReasonMapper(events),results,experiences,scenes,registry,JsonCodec.shared(),tm);
        runner=new JobRunner(jobs,new ReportJobStateAdapter(new ReportRepository(db.jdbc())),new GuardianWriteLock(db.jdbc()),clock,JobRunnerTest.properties(),tm,List.of(handler),events);
        states.insert(session,guardian,child,type,true,clock.now());var j=jobs.submit("SESSION_RESULT",session,guardian,session.toString(),"RECONCILE",null,clock.now());states.attach(session,j.id(),1,clock.now());return jobs.claim("SESSION_RESULT",1,clock.now(),Duration.ofSeconds(30)).getFirst();
    }
    @Test void guardianContextIsUsedForStructureOnlyAndBothSourcesRemainStored() {
        db.inRolledBackTx(t->{
            var job=prepare("FREE_TALK",3,null);
            when(contexts.findBySession(session)).thenReturn(Optional.of(new ActivityContextRepository.StoredContext(session,"친구와 블록 놀이를 했다. 연락처 010-1234-5678","FRIEND","HEARD_FROM_CHILD","다퉜다고 단정해")));
            provider.response=r->r.task().equals("structure")?"{\"characters\":[],\"situation\":{\"text\":\"친구와 블록 놀이를 했다\",\"source\":\"GUARDIAN_INPUT\"},\"childResponse\":{\"text\":\"같이 블록 쌓자\",\"source\":\"CHILD_RECALL\",\"utteranceSeq\":1}}":normalOutput(r);
            runner.execute(job);
            var saved=experiences.find(results.current(session).orElseThrow().id()).orElseThrow();
            assertThat(saved).contains("GUARDIAN_INPUT","CHILD_RECALL","guardianSourceType","HEARD_FROM_CHILD").doesNotContain("010-1234-5678","다퉜다고");
            var structure=provider.requests.stream().filter(r->r.task().equals("structure")).findFirst().orElseThrow();
            assertThat(structure.input()).containsEntry("sourceType","HEARD_FROM_CHILD").containsKey("knownContent");
            assertThat(JsonCodec.shared().write(structure.input())).doesNotContain("010-1234-5678");
            assertThat(provider.requests.stream().filter(r->!r.task().equals("structure"))).allSatisfy(r->assertThat(r.input()).doesNotContainKeys("knownContent","question","sourceType","situationType"));
            return null;
        });
    }

    @Test void freeTalkCreatesStructuredFullResult(){db.inRolledBackTx(t->{var j=prepare("FREE_TALK",3,null);runner.execute(j);var result=results.current(session).orElseThrow();assertThat(result.level()).isEqualTo("FULL");assertThat(experiences.find(result.id()).orElseThrow()).contains("CHILD_RECALL").doesNotContain("emotion","intent");assertThat(provider.requests).hasSize(4);return null;});}
    @Test void practiceStoresTwoSceneChipsAndDifference(){db.inRolledBackTx(t->{var j=prepare("PRACTICE",3,practice());runner.execute(j);var result=results.current(session).orElseThrow();assertThat(scenes.find(result.id())).hasSize(2).allSatisfy(s->assertThat(s.sceneChip()).isEqualTo("DONE_IMMEDIATE"));assertThat(scenes.find(result.id()).getFirst().differsFromLive()).isTrue();return null;});}
    @Test void nullPracticeFinishesWithoutAi(){db.inRolledBackTx(t->{var j=prepare("PRACTICE",3,null);runner.execute(j);assertThat(results.current(session).orElseThrow().level()).isEqualTo("INSUFFICIENT");assertThat(provider.requests).isEmpty();assertThat(jobs.find(j.id()).orElseThrow().status()).isEqualTo("DONE");return null;});}
    @Test void insufficientFreeTalkSkipsEveryAiIncludingClassifier(){db.inRolledBackTx(t->{provider.response=r->{throw new ResultAiException("PROVIDER_UNAVAILABLE");};var j=prepare("FREE_TALK",2,null);runner.execute(j);assertThat(provider.requests).isEmpty();assertThat(results.current(session).orElseThrow().sentencesJson()).isEqualTo("[]");assertThat(jobs.find(j.id()).orElseThrow().status()).isEqualTo("DONE");return null;});}
    @Test void forbiddenAndMismatchedQuoteUseServerTemplate(){db.inRolledBackTx(t->{provider.response=r->r.task().equals("sentences")?"{\"sentences\":[{\"text\":\"성격이 좋아요\",\"citations\":[]},{\"text\":\"\\\"없는 말\\\"이라고 했어요\",\"citations\":[{\"utteranceSeq\":1}]}]}":normalOutput(r);var j=prepare("FREE_TALK",3,null);runner.execute(j);assertThat(results.current(session).orElseThrow().sentencesJson()).contains("3분 동안 이야기를 나눴어요.").doesNotContain("성격","없는 말");return null;});}
    @Test void unavailableProviderFailsAfterThreeAttempts(){db.inRolledBackTx(t->{provider.response=r->{throw new ResultAiException("PROVIDER_UNAVAILABLE");};var j=prepare("FREE_TALK",3,null);runner.execute(j);clock.advance(Duration.ofSeconds(5));runner.execute(jobs.claim("SESSION_RESULT",1,clock.now(),Duration.ofSeconds(30)).getFirst());clock.advance(Duration.ofSeconds(15));runner.execute(jobs.claim("SESSION_RESULT",1,clock.now(),Duration.ofSeconds(30)).getFirst());assertThat(jobs.find(j.id()).orElseThrow().status()).isEqualTo("FAILED");assertThat(provider.requests).hasSize(3);return null;});}
    @Test void guardianOnlyEvidenceDoesNotRegisterCandidate(){db.inRolledBackTx(t->{provider.response=r->r.task().equals("candidates")?"{\"candidates\":[{\"behaviorKey\":\"request\",\"behaviorVersion\":1,\"reasonText\":\"함께 말했어요\",\"evidence\":[{\"utteranceSeq\":1,\"quote\":\"같이 블록 쌓자\",\"kind\":\"GUARDIAN_INPUT\"}]}]}":normalOutput(r);var j=prepare("FREE_TALK",3,null);runner.execute(j);verify(registry,never()).register(any(),any(),any());assertThat(results.current(session).orElseThrow().pendingCandidatesJson()).isEqualTo("[]");return null;});}
    @Test void failedCandidateHookLeavesDoneAndDurablePending(){db.inRolledBackTx(t->{provider.response=r->r.task().equals("candidates")?"{\"candidates\":[{\"behaviorKey\":\"request\",\"behaviorVersion\":1,\"reasonText\":\"함께 말했어요\",\"evidence\":[{\"utteranceSeq\":1,\"quote\":\"같이 블록 쌓자\",\"kind\":\"CHILD_RECALL\"}]}]}":normalOutput(r);when(registry.register(any(),any(),any())).thenThrow(new IllegalStateException("일시 실패"));var j=prepare("FREE_TALK",3,null);runner.execute(j);assertThat(jobs.find(j.id()).orElseThrow().status()).isEqualTo("DONE");var result=results.current(session).orElseThrow();assertThat(result.pendingCandidatesJson()).contains("request");assertThat(result.candidatesRegisteredAt()).isNull();return null;});}

    @SuppressWarnings("unchecked")
    String editFirstScene(ResultTextProvider.Request request,java.util.function.Consumer<Map<String,Object>> change){
        if(!request.task().equals("judgment"))return normalOutput(request);
        var output=JsonCodec.shared().read(normalOutput(request),Map.class);
        var first=ResultData.objects(ResultData.objects(output.get("goals")).getFirst().get("scenes")).getFirst();
        change.accept(first);return JsonCodec.shared().write(output);
    }
    Map<String,Object> withHelp(UUID scenario,UUID helpScenario){
        var g=new LinkedHashMap<>(ResultData.goals(practice()).getFirst());
        g.put("responses",List.of(Map.of("responseType","FIRST","utteranceSeq",1,"scenarioId",scenario.toString(),"label","none"),
            Map.of("responseType","NEW_SCENARIO","utteranceSeq",2,"scenarioId",UUID.randomUUID().toString(),"label","functional"),
            Map.of("responseType","AFTER_CUE","utteranceSeq",3,"scenarioId",helpScenario.toString(),"label","functional")));
        return Map.of("goals",List.of(g));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void missingOrNullTimingCompletesWithImmediate(boolean explicitNull){
        var j=prepare("PRACTICE",3,practice());
        provider.response=r->editFirstScene(r,s->{if(explicitNull)s.put("timing",null);else s.remove("timing");});
        runner.execute(j);
        assertThat(jobs.find(j.id()).orElseThrow().status()).isEqualTo("DONE");
        var scene=scenes.find(results.current(session).orElseThrow().id()).getFirst();
        assertThat(scene.sceneChip()).isEqualTo("DONE_IMMEDIATE");assertThat(scene.timing()).isEqualTo("IMMEDIATE");
    }
    @Test void invalidTimingStillFailsSchema(){
        var j=prepare("PRACTICE",3,practice());provider.response=r->editFirstScene(r,s->s.put("timing","LATER"));
        runner.execute(j);assertThat(jobs.find(j.id()).orElseThrow().lastErrorCode()).isEqualTo("SCHEMA_INVALID");
        assertThat(results.current(session)).isEmpty();
    }
    @Test void anotherScenesActualQuoteCannotMakeFirstSceneImmediate(){
        var j=prepare("PRACTICE",3,practice());
        provider.response=r->editFirstScene(r,s->s.put("firstResponse",Map.of("utteranceSeq",2,"quote","같이 블록 쌓자")));
        runner.execute(j);var rows=scenes.find(results.current(session).orElseThrow().id());
        assertThat(rows.getFirst().sceneChip()).isEqualTo("NOT_DONE");
        assertThat(rows.get(1).sceneChip()).isEqualTo("DONE_IMMEDIATE");
        assertThat(jobs.find(j.id()).orElseThrow().status()).isEqualTo("DONE");
    }
    @Test void helpResponseCannotReplaceImmediateEvidence(){
        UUID scenario=UUID.randomUUID();var j=prepare("PRACTICE",3,withHelp(scenario,scenario));
        provider.response=r->editFirstScene(r,s->s.put("firstResponse",Map.of("utteranceSeq",3,"quote","같이 블록 쌓자")));
        runner.execute(j);assertThat(scenes.find(results.current(session).orElseThrow().id()).getFirst().sceneChip()).isEqualTo("NOT_DONE");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void afterHelpMustBelongToTheSameScenario(boolean sameScenario){
        UUID scenario=UUID.randomUUID();var j=prepare("PRACTICE",3,withHelp(scenario,sameScenario?scenario:UUID.randomUUID()));
        provider.response=r->editFirstScene(r,s->{s.put("timing","AFTER_HELP");s.put("afterHelpResponse",Map.of("utteranceSeq",3,"quote","같이 블록 쌓자"));});
        runner.execute(j);assertThat(scenes.find(results.current(session).orElseThrow().id()).getFirst().sceneChip())
            .isEqualTo(sameScenario?"DONE_AFTER_HELP":"NOT_DONE");
    }
    @Test void anotherGoalsActualQuoteCannotSupportThisGoal(){
        var first=ResultData.goals(practice()).getFirst();var other=new LinkedHashMap<>(first);
        other.put("goalSelectionId",UUID.randomUUID().toString());other.put("order",2);
        other.put("responses",List.of(Map.of("responseType","FIRST","utteranceSeq",4,"label","functional")));
        var j=prepare("PRACTICE",4,Map.of("goals",List.of(first,other)));
        provider.response=r->editFirstScene(r,s->s.put("firstResponse",Map.of("utteranceSeq",4,"quote","같이 블록 쌓자")));
        runner.execute(j);assertThat(scenes.find(results.current(session).orElseThrow().id()).stream().filter(s->s.goalSelectionId().equals(goal)&&s.sceneNo()==1).findFirst().orElseThrow().sceneChip()).isEqualTo("NOT_DONE");
    }
    @Test void validMissingAndForeignScenariosStillCompleteTheJob(){
        UUID valid=UUID.randomUUID(),missing=UUID.randomUUID(),foreign=UUID.randomUUID();
        var g=new LinkedHashMap<>(ResultData.goals(practice()).getFirst());
        g.put("responses",List.of(Map.of("responseType","FIRST","utteranceSeq",1,"scenarioId",valid.toString(),"label","none"),
            Map.of("responseType","NEW_SCENARIO","utteranceSeq",2,"scenarioId",missing.toString(),"label","functional"),
            Map.of("responseType","AFTER_CUE","utteranceSeq",3,"scenarioId",foreign.toString(),"label","functional")));
        var j=prepare("PRACTICE",3,Map.of("goals",List.of(g)));
        var content=new Scenario(valid,"ORIGINAL","FRIEND","기회","유효한 상황","비공개 상대역","시작",null);
        when(scenarioRepository.find(valid)).thenReturn(Optional.of(new ScenarioRepository.Stored(content,guardian,child,session,goal,"request",1,"ISSUED")));
        when(scenarioRepository.find(foreign)).thenReturn(Optional.of(new ScenarioRepository.Stored(content,UUID.randomUUID(),child,session,goal,"request",1,"ISSUED")));
        var lookup=new ScenarioLookup(scenarioRepository);
        assertThat(lookup.get(guardian,List.of(valid,missing,foreign))).extracting(ScenarioLookup.Snapshot::scenarioId).containsExactly(valid);
        assertThat(lookup.get(valid,guardian,UUID.randomUUID())).isEmpty();
        runner.execute(j);assertThat(jobs.find(j.id()).orElseThrow().status()).isEqualTo("DONE");
        var judgment=provider.requests.stream().filter(r->r.task().equals("judgment")).findFirst().orElseThrow();
        assertThat(ResultData.objects(ResultData.objects(judgment.input().get("goals")).getFirst().get("scenarios"))).hasSize(1);
        assertThat(JsonCodec.shared().write(judgment.input())).contains("유효한 상황").doesNotContain("비공개 상대역");
    }
    @Test void withdrawalAfterFirstCallStopsFollowingSessionAiCalls(){
        var j=prepare("FREE_TALK",3,null);
        provider.response=r->{when(consent.check(guardian)).thenReturn(new ConsentPolicyService.GateResult(false,null,new ConsentPolicyService.Snapshot("v1",false,true,true,true)));return normalOutput(r);};
        runner.execute(j);assertThat(provider.requests).hasSize(1);
        assertThat(jobs.find(j.id()).orElseThrow().lastErrorCode()).isEqualTo("INTERNAL");
        assertThat(jobs.find(j.id()).orElseThrow().status()).isEqualTo("FAILED");
        assertThat(results.current(session)).isEmpty();
    }
    @Test void withdrawnEligibleActivityFinishesInsufficientWithoutCurrentConsentOrAi(){
        var j=prepare("FREE_TALK",3,null);var r=reader.find(session,guardian).orElseThrow().record();
        var withdrawn=new ActivityRecordRepository.StoredRecord(r.sessionId(),guardian,child,r.activityType(),r.finalStatus(),"CONSENT_WITHDRAWN",r.confirmed(),r.startedAt(),r.endedAt(),r.listedAt(),r.recordFrom(),r.recordTo(),r.transcriptJson(),r.gapsJson(),r.practiceJson(),r.snapshotHash(),r.recordedAt());
        when(reader.find(session,guardian)).thenReturn(Optional.of(new ResultRecordReader.Input(withdrawn,"ENDED",null,true)));
        when(consent.check(guardian)).thenReturn(new ConsentPolicyService.GateResult(false,null,new ConsentPolicyService.Snapshot("v1",false,true,true,true)));
        runner.execute(j);assertThat(provider.requests).isEmpty();assertThat(jobs.find(j.id()).orElseThrow().status()).isEqualTo("DONE");
        var result=results.current(session).orElseThrow();assertThat(result.level()).isEqualTo("INSUFFICIENT");assertThat(result.sentencesJson()).isEqualTo("[]");verify(registry,never()).register(any(),any(),any());
    }
    @Test void withdrawalDuringClassifierPreventsPublishingResultAndCandidates(){
        var j=prepare("FREE_TALK",3,null);
        provider.response=r->{if(r.task().equals("classify"))when(consent.check(guardian)).thenReturn(new ConsentPolicyService.GateResult(false,null,new ConsentPolicyService.Snapshot("v1",false,true,true,true)));return normalOutput(r);};
        runner.execute(j);assertThat(provider.requests).hasSize(4);assertThat(jobs.find(j.id()).orElseThrow().status()).isEqualTo("FAILED");
        assertThat(jobs.find(j.id()).orElseThrow().lastErrorCode()).isEqualTo("INTERNAL");assertThat(results.current(session)).isEmpty();verify(registry,never()).register(any(),any(),any());
    }
}
