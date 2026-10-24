package kr.mujoong.core.result;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.luna.*;
import kr.mujoong.core.practicegoal.PinnedSavedGoalReader;
import org.junit.jupiter.api.*;
@Tag("goal-result")
class RejudgmentGatewayTest {
    UUID goal=UUID.randomUUID(),run=UUID.randomUUID();
    PracticeRecordV2.Scene input(String live){return new PracticeRecordV2.Scene(run,goal,null,"BASE",Instant.EPOCH,live,List.of(),null,1);}
    Map<String,Object> row(String value,String timing){var row=new LinkedHashMap<String,Object>();row.put("sceneRunId","handle");row.put("value",value);row.put("timing",timing);return row;}
    List<SceneJudgmentRepository.Scene> validate(List<Map<String,Object>> rows,String live){return RejudgmentGateway.validated(Map.of("scenes",rows),Map.of("handle",input(live)));}
    @Test void afterReaskRemainsStoredWhileOnlyChipFolds(){var scene=validate(List.of(row("functional","AFTER_REASK")),"IMMEDIATE").getFirst();assertThat(scene.timing()).isEqualTo("AFTER_REASK");assertThat(scene.sceneChip()).isEqualTo("DONE_IMMEDIATE");assertThat(scene.differsFromLive()).isTrue();assertThat(scene.liveLabel()).isNull();}
    @Test void missingSceneIsInvalid(){assertThatThrownBy(()->validate(List.of(),null)).isInstanceOf(ResultAiException.class);}
    @Test void duplicateSceneIsInvalid(){var row=row("none",null);assertThatThrownBy(()->validate(List.of(row,row),null)).isInstanceOf(ResultAiException.class);}
    @Test void unknownSceneIsInvalid(){var row=row("none",null);row.put("sceneRunId","unknown");assertThatThrownBy(()->validate(List.of(row),null)).isInstanceOf(ResultAiException.class);}
    @Test void nonFunctionalMustHaveNullTiming(){assertThatThrownBy(()->validate(List.of(row("none","AFTER_REASK")),null)).isInstanceOf(ResultAiException.class);}
    @Test void functionalRequiresTiming(){assertThatThrownBy(()->validate(List.of(row("functional",null)),null)).isInstanceOf(ResultAiException.class);}
    @Test void noLiveObservationDoesNotInventDifference(){assertThat(validate(List.of(row("functional","IMMEDIATE")),null).getFirst().differsFromLive()).isFalse();}
    @Test void abstainedIsNotAnObservedTiming(){assertThat(validate(List.of(row("functional","AFTER_REASK")),"ABSTAINED").getFirst().differsFromLive()).isFalse();}
    @Test void noneDiffersOnlyWhenARealTimingIsProduced(){assertThat(validate(List.of(row("none",null)),"NONE").getFirst().differsFromLive()).isFalse();assertThat(validate(List.of(row("functional","IMMEDIATE")),"NONE").getFirst().differsFromLive()).isTrue();}
    /** 연습 대화 C안(docs/practice-direct-ask.md 6장 · FT-1024-b): 기록용 재판정도 전하는 식 대답 · 할 행동 설명을 수행으로 본다. */
    @Test void rejudgmentV2CarriesTheDirectAskAcceptanceRuleAndKeepsTheOutputRules(){
        var luna=mock(CoreLunaClient.class);var goals=mock(PinnedSavedGoalReader.class);UUID child=UUID.randomUUID();
        when(goals.historical(child,goal)).thenReturn(Optional.empty());
        var sent=new java.util.ArrayList<LunaRequest>();
        when(luna.call(any(LunaRequest.class))).thenAnswer(i->{LunaRequest request=i.getArgument(0);sent.add(request);
            String alias=ResultData.objects(request.input().get("scenes")).getFirst().get("sceneRunId").toString();
            return new LunaResult(JsonCodec.shared().write(Map.of("scenes",List.of(Map.of("sceneRunId",alias,"value","functional","timing","IMMEDIATE")))),"fake","rejudgment.v2",Duration.ZERO,1,false);});
        var practice=Map.<String,Object>of("flowVersion",PracticeRecordV2.VERSION,"scenes",List.of(Map.of("sceneRunId",run.toString(),"goalRef",goal.toString(),"sceneKind","BASE","startedAt",Instant.EPOCH.toString())));

        new RejudgmentGateway(Optional.of(luna),goals).judge(child,practice,List.of(),Map.of(),Instant.MAX,()->{});

        assertThat(sent).singleElement().satisfies(request->{
            assertThat(request.promptVersion()).isEqualTo("rejudgment.v2").isEqualTo(RejudgmentGateway.PROMPT_VERSION);
            assertThat(request.purpose()).isEqualTo(LunaPurpose.REJUDGMENT);
            assertThat(request.instructions()).isEqualTo(RejudgmentGateway.INSTRUCTIONS)
                    .contains("할 말을 전하는 말","할 행동을 설명하는 말","functional로 판정한다","방법만 묻는 말","도움 요청이며 수행이 아니다","AI 줄은 무중의 말")
                    .contains("입력 sceneRunId 집합을 정확히 한 번씩 반환한다","단순 재발화 확인만으로 AFTER_REASK를 만들지 않는다","진행 중 판정 라벨이나 확률을 추측하지 않는다");
        });
    }
    @Test void realProviderRequestUsesEphemeralSchemaUuidAndOneCall(){
        var luna=mock(CoreLunaClient.class);var goals=mock(PinnedSavedGoalReader.class);UUID child=UUID.randomUUID();
        when(goals.historical(child,goal)).thenReturn(Optional.empty());
        when(luna.call(any(LunaRequest.class))).thenAnswer(i->{LunaRequest request=i.getArgument(0);assertThat(request.purpose()).isEqualTo(LunaPurpose.REJUDGMENT);var scenes=ResultData.objects(request.input().get("scenes"));String alias=scenes.getFirst().get("sceneRunId").toString();assertThat(UUID.fromString(alias)).isNotEqualTo(run);return new LunaResult(JsonCodec.shared().write(Map.of("scenes",List.of(Map.of("sceneRunId",alias,"value","functional","timing","AFTER_REASK")))),"fake","rejudgment.v2",Duration.ZERO,1,false);});
        var gateway=new RejudgmentGateway(Optional.of(luna),goals);
        var practice=Map.<String,Object>of("flowVersion",PracticeRecordV2.VERSION,"scenes",List.of(Map.of("sceneRunId",run.toString(),"goalRef",goal.toString(),"sceneKind","BASE","startedAt",Instant.EPOCH.toString())));
        assertThat(gateway.judge(child,practice,List.of(),Map.of(),Instant.MAX,()->{})).singleElement().extracting(SceneJudgmentRepository.Scene::sceneRunId).isEqualTo(run);verify(luna,times(1)).call(any(LunaRequest.class));
    }
}
