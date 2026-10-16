package kr.mujoong.core.result;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.activity.ActivityContextRepository;
import kr.mujoong.core.identity.*;
import kr.mujoong.core.knowledge.*;
import kr.mujoong.core.luna.*;
import kr.mujoong.core.practice.*;
import kr.mujoong.core.practicegoal.*;
import kr.mujoong.core.report.*;
import kr.mujoong.core.result.check.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

@Tag("u14") @Tag("db")
class U14ResultPipelineIT {
    SessionResultHandlerIT fixture;CoreLunaClient luna;
    @BeforeEach void setup(){fixture=new SessionResultHandlerIT();fixture.setup();luna=mock(CoreLunaClient.class);}
    @AfterEach void close(){fixture.close();}
    ResultJob prepare(int count,String live){
        List<Map<String,Object>> scenes=new ArrayList<>();for(int i=0;i<count;i++){var scene=new LinkedHashMap<String,Object>();scene.put("sceneRunId",UUID.randomUUID().toString());scene.put("goalRef",fixture.goal.toString());scene.put("sceneKind",i==0?"BASE":"VARIANT");scene.put("startedAt",fixture.clock.now().toString());scene.put("facts",List.of("REASK_RESOLVED"));if(live!=null)scene.put("liveOutcome",Map.of("timing",live));scenes.add(scene);}
        var job=fixture.prepare("PRACTICE",3,Map.of("flowVersion",PracticeRecordV2.VERSION,"scenes",scenes));
        when(luna.call(any(LunaRequest.class))).thenAnswer(i->{LunaRequest request=i.getArgument(0);return new LunaResult(JsonCodec.shared().write(Map.of("scenes",ResultData.objects(request.input().get("scenes")).stream().map(s->Map.of("sceneRunId",s.get("sceneRunId"),"value","functional","timing","AFTER_REASK")).toList())),"fake","rejudgment.v1",Duration.ZERO,1,false);});
        var ai=new ResultTextGateway(fixture.provider,ResultTextGatewayTest.properties(Duration.ofSeconds(2)),fixture.clock,fixture.executor,JsonCodec.shared());var dictionary=new ForbiddenDictionary();var tm=new DataSourceTransactionManager(fixture.db.dataSource());
        var handler=new SessionResultHandler(fixture.reader,mock(ActivityContextRepository.class),fixture.consent,mock(ChildRepository.class),fixture.definitions,new ScenarioLookup(fixture.scenarioRepository),ai,new OutputChecker(dictionary,ai),new CandidateScreen(dictionary,fixture.events),new TipMatcher(mock(SupportLibrary.class),dictionary),new NotDoneReasonMapper(fixture.events),fixture.results,fixture.experiences,fixture.scenes,fixture.registry,JsonCodec.shared(),tm);
        var snapshots=mock(PinnedSavedGoalReader.class);when(snapshots.historical(any(),any())).thenReturn(Optional.empty());handler.rejudgment(new RejudgmentGateway(Optional.of(luna),snapshots));
        fixture.runner=new JobRunner(fixture.jobs,new ReportJobStateAdapter(new ReportRepository(fixture.db.jdbc())),new GuardianWriteLock(fixture.db.jdbc()),fixture.clock,JobRunnerTest.properties(),tm,List.of(handler),fixture.events);return job;
    }
    List<SceneJudgmentRepository.Scene> rows(){return fixture.scenes.find(fixture.results.current(fixture.session).orElseThrow().id());}
    @Test void threeScenesPersistAfterReaskAndFacts(){var job=prepare(3,"IMMEDIATE");fixture.runner.execute(job);assertThat(rows()).hasSize(3).allSatisfy(s->{assertThat(s.timing()).isEqualTo("AFTER_REASK");assertThat(s.sceneChip()).isEqualTo("DONE_IMMEDIATE");assertThat(s.facts()).containsExactly("REASK_RESOLVED");assertThat(s.differsFromLive()).isTrue();});assertThat(rows()).extracting(SceneJudgmentRepository.Scene::sceneNo).containsExactly(1,2,3);verify(luna,times(1)).call(any(LunaRequest.class));}
    @Test void oneSceneNeverSynthesizesSecond(){var job=prepare(1,null);fixture.runner.execute(job);assertThat(rows()).hasSize(1);assertThat(rows().getFirst().differsFromLive()).isFalse();assertThat(fixture.results.current(fixture.session).orElseThrow().sentencesJson()).contains("장면 1개");}
    @Test void abstainedLiveOutcomeDoesNotCreateDifference(){var job=prepare(1,"ABSTAINED");fixture.runner.execute(job);assertThat(rows().getFirst().differsFromLive()).isFalse();}
    @Test void liveNoneCanDifferFromObservedFunctionalTiming(){var job=prepare(1,"NONE");fixture.runner.execute(job);assertThat(rows().getFirst().differsFromLive()).isTrue();}
    @Test void absentAiSceneFailsWholeJobAttempt(){var job=prepare(1,null);when(luna.call(any(LunaRequest.class))).thenReturn(new LunaResult("{\"scenes\":[]}","fake","rejudgment.v1",Duration.ZERO,1,false));fixture.runner.execute(job);assertThat(fixture.results.current(fixture.session)).isEmpty();assertThat(fixture.jobs.find(job.id()).orElseThrow().lastErrorCode()).isEqualTo("SCHEMA_INVALID");}
    @Test void withdrawnConsentStoresOnlyActuallyStartedNotDoneScenes(){var job=prepare(3,null);when(fixture.consent.check(fixture.guardian)).thenReturn(new kr.mujoong.core.consent.ConsentPolicyService.GateResult(false,null,new kr.mujoong.core.consent.ConsentPolicyService.Snapshot("v1",false,true,true,true)));fixture.runner.execute(job);assertThat(rows()).hasSize(3).allSatisfy(s->assertThat(s.sceneChip()).isEqualTo("NOT_DONE"));verifyNoInteractions(luna);}
}
