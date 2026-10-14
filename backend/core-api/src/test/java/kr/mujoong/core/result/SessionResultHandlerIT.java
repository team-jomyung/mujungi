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
@Tag("u6") @Tag("db")
class SessionResultHandlerIT {
    DbTestSupport db;MutableClock clock;ExecutorService executor;FakeResultTextProvider provider;ResultJobRepository jobs;SessionResultStateRepository states;
    ActivityResultRepository results;SceneJudgmentRepository scenes;StructuredExperienceRepository experiences;JobRunner runner;CandidateRegistry registry;
    UUID session,guardian,child,goal;ResultRecordReader reader;BehaviorDefinitionLookup definitions;EventLogger events;
    @BeforeEach void setup(){db=DbTestSupport.coreApp();clock=new MutableClock();executor=Executors.newVirtualThreadPerTaskExecutor();provider=new FakeResultTextProvider();jobs=new ResultJobRepository(db.jdbc());states=new SessionResultStateRepository(db.jdbc());results=new ActivityResultRepository(db.jdbc());scenes=new SceneJudgmentRepository(db.jdbc());experiences=new StructuredExperienceRepository(db.jdbc());registry=mock(CandidateRegistry.class);reader=mock(ResultRecordReader.class);definitions=mock(BehaviorDefinitionLookup.class);events=mock(EventLogger.class);session=UUID.randomUUID();goal=UUID.randomUUID();provider.response=this::normalOutput;}
    @AfterEach void close(){executor.shutdownNow();db.close();}
    List<Map<String,Object>> transcript(int count){var rows=new ArrayList<Map<String,Object>>();for(int i=1;i<=count;i++)rows.add(Map.of("seq",i,"speaker","CHILD","text","같이 블록 쌓자","uncertain",false,"startedAt",clock.now().toString()));return rows;}
    Map<String,Object> practice(){return Map.of("goals",List.of(Map.of("goalSelectionId",goal.toString(),"behaviorKey","request","behaviorVersion",1,"displayName","부탁하기","order",1,"status","DONE","responses",List.of(Map.of("responseType","FIRST","utteranceSeq",1,"label","none"),Map.of("responseType","NEW_SCENARIO","utteranceSeq",2,"label","functional")),"cues",List.of())));}
    String normalOutput(ResultTextProvider.Request request){return switch(request.task()){
        case "structure"->"{\"characters\":[],\"situation\":{\"text\":\"함께 쌓은 이야기\",\"source\":\"CHILD_RECALL\",\"utteranceSeq\":1},\"partnerUtterance\":null,\"childResponse\":null,\"explanation\":null}";
        case "sentences"->JsonCodec.shared().write(Map.of("sentences",List.of(Map.of("text","함께 쌓자는 말을 했어요.","citations",List.of(Map.of("utteranceSeq",1))))));
        case "candidates"->"{\"candidates\":[]}";
        case "classify"->JsonCodec.shared().write(Map.of("results",ResultData.objects(request.input().get("sentences")).stream().map(s->Map.of("id",s.get("id"),"violation",false)).toList()));
        case "judgment"->JsonCodec.shared().write(Map.of("goals",List.of(Map.of("goalSelectionId",ResultData.objects(request.input().get("goals")).getFirst().get("goalSelectionId"),"scenes",List.of(Map.of("sceneNo",1,"value","functional","timing","IMMEDIATE","rationale","기능을 표현","firstResponse",Map.of("utteranceSeq",1,"quote","같이 블록 쌓자")),Map.of("sceneNo",2,"value","functional","timing","IMMEDIATE","rationale","기능을 표현","firstResponse",Map.of("utteranceSeq",2,"quote","같이 블록 쌓자")))))));
        default->throw new AssertionError(request.task());};}
    ResultJob prepare(String type,int utterances,Map<String,Object> practice){
        guardian=db.insertGuardian("ACTIVE");child=db.insertChild(guardian);
        var r=new ActivityRecordRepository.StoredRecord(session,guardian,child,type,"ENDED","CHILD_STOP",true,clock.now().minusSeconds(180),clock.now(),clock.now(),clock.now().minusSeconds(180),clock.now(),JsonCodec.shared().write(transcript(utterances)),"[]",practice==null?null:JsonCodec.shared().write(practice),"hash",clock.now());
        when(reader.find(session,guardian)).thenReturn(Optional.of(new ResultRecordReader.Input(r,"ENDED",null,utterances>0)));
        var consent=mock(ConsentPolicyService.class);when(consent.check(guardian)).thenReturn(new ConsentPolicyService.GateResult(true,null,new ConsentPolicyService.Snapshot("v1",true,true,true,true)));
        var definition=new BehaviorDefinition(UUID.randomUUID(),"request",1,"부탁하기","필요한 것 부탁","기회","상황","기준","ACTIVE",clock.now(),null);when(definitions.get("request",1)).thenReturn(definition);when(definitions.active()).thenReturn(List.of(definition));
        var ai=new ResultTextGateway(provider,ResultTextGatewayTest.properties(Duration.ofSeconds(2)),clock,executor,JsonCodec.shared());var dictionary=new ForbiddenDictionary();var tm=new DataSourceTransactionManager(db.dataSource());
        var handler=new SessionResultHandler(reader,mock(ActivityContextRepository.class),consent,mock(ChildRepository.class),definitions,mock(ScenarioLookup.class),ai,new OutputChecker(dictionary,ai),new CandidateScreen(dictionary,events),new TipMatcher(mock(SupportLibrary.class),dictionary),new NotDoneReasonMapper(events),results,experiences,scenes,registry,JsonCodec.shared(),tm);
        runner=new JobRunner(jobs,new ReportJobStateAdapter(new ReportRepository(db.jdbc())),new GuardianWriteLock(db.jdbc()),clock,JobRunnerTest.properties(),tm,List.of(handler),events);
        states.insert(session,guardian,child,type,true,clock.now());var j=jobs.submit("SESSION_RESULT",session,guardian,session.toString(),"RECONCILE",null,clock.now());states.attach(session,j.id(),1,clock.now());return jobs.claim("SESSION_RESULT",1,clock.now(),Duration.ofSeconds(30)).getFirst();
    }
    @Test void freeTalkCreatesStructuredFullResult(){db.inRolledBackTx(t->{var j=prepare("FREE_TALK",3,null);runner.execute(j);var result=results.current(session).orElseThrow();assertThat(result.level()).isEqualTo("FULL");assertThat(experiences.find(result.id()).orElseThrow()).contains("CHILD_RECALL").doesNotContain("emotion","intent");assertThat(provider.requests).hasSize(4);return null;});}
    @Test void practiceStoresTwoSceneChipsAndDifference(){db.inRolledBackTx(t->{var j=prepare("PRACTICE",3,practice());runner.execute(j);var result=results.current(session).orElseThrow();assertThat(scenes.find(result.id())).hasSize(2).allSatisfy(s->assertThat(s.sceneChip()).isEqualTo("DONE_IMMEDIATE"));assertThat(scenes.find(result.id()).getFirst().differsFromLive()).isTrue();return null;});}
    @Test void nullPracticeFinishesWithoutAi(){db.inRolledBackTx(t->{var j=prepare("PRACTICE",3,null);runner.execute(j);assertThat(results.current(session).orElseThrow().level()).isEqualTo("INSUFFICIENT");assertThat(provider.requests).isEmpty();assertThat(jobs.find(j.id()).orElseThrow().status()).isEqualTo("DONE");return null;});}
    @Test void insufficientFreeTalkSkipsEveryAiIncludingClassifier(){db.inRolledBackTx(t->{provider.response=r->{throw new ResultAiException("PROVIDER_UNAVAILABLE");};var j=prepare("FREE_TALK",2,null);runner.execute(j);assertThat(provider.requests).isEmpty();assertThat(results.current(session).orElseThrow().sentencesJson()).isEqualTo("[]");assertThat(jobs.find(j.id()).orElseThrow().status()).isEqualTo("DONE");return null;});}
    @Test void forbiddenAndMismatchedQuoteUseServerTemplate(){db.inRolledBackTx(t->{provider.response=r->r.task().equals("sentences")?"{\"sentences\":[{\"text\":\"성격이 좋아요\",\"citations\":[]},{\"text\":\"\\\"없는 말\\\"이라고 했어요\",\"citations\":[{\"utteranceSeq\":1}]}]}":normalOutput(r);var j=prepare("FREE_TALK",3,null);runner.execute(j);assertThat(results.current(session).orElseThrow().sentencesJson()).contains("3분 동안 이야기를 나눴어요.").doesNotContain("성격","없는 말");return null;});}
    @Test void unavailableProviderFailsAfterThreeAttempts(){db.inRolledBackTx(t->{provider.response=r->{throw new ResultAiException("PROVIDER_UNAVAILABLE");};var j=prepare("FREE_TALK",3,null);runner.execute(j);clock.advance(Duration.ofSeconds(5));runner.execute(jobs.claim("SESSION_RESULT",1,clock.now(),Duration.ofSeconds(30)).getFirst());clock.advance(Duration.ofSeconds(15));runner.execute(jobs.claim("SESSION_RESULT",1,clock.now(),Duration.ofSeconds(30)).getFirst());assertThat(jobs.find(j.id()).orElseThrow().status()).isEqualTo("FAILED");assertThat(provider.requests).hasSize(3);return null;});}
    @Test void guardianOnlyEvidenceDoesNotRegisterCandidate(){db.inRolledBackTx(t->{provider.response=r->r.task().equals("candidates")?"{\"candidates\":[{\"behaviorKey\":\"request\",\"behaviorVersion\":1,\"reasonText\":\"함께 말했어요\",\"evidence\":[{\"utteranceSeq\":1,\"quote\":\"같이 블록 쌓자\",\"kind\":\"GUARDIAN_INPUT\"}]}]}":normalOutput(r);var j=prepare("FREE_TALK",3,null);runner.execute(j);verify(registry,never()).register(any(),any(),any());assertThat(results.current(session).orElseThrow().pendingCandidatesJson()).isEqualTo("[]");return null;});}
    @Test void failedCandidateHookLeavesDoneAndDurablePending(){db.inRolledBackTx(t->{provider.response=r->r.task().equals("candidates")?"{\"candidates\":[{\"behaviorKey\":\"request\",\"behaviorVersion\":1,\"reasonText\":\"함께 말했어요\",\"evidence\":[{\"utteranceSeq\":1,\"quote\":\"같이 블록 쌓자\",\"kind\":\"CHILD_RECALL\"}]}]}":normalOutput(r);when(registry.register(any(),any(),any())).thenThrow(new IllegalStateException("일시 실패"));var j=prepare("FREE_TALK",3,null);runner.execute(j);assertThat(jobs.find(j.id()).orElseThrow().status()).isEqualTo("DONE");var result=results.current(session).orElseThrow();assertThat(result.pendingCandidatesJson()).contains("request");assertThat(result.candidatesRegisteredAt()).isNull();return null;});}
}
