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
    @Test void requestKeepsPreparationFactsAndSceneStartWithoutTurningThemIntoAnOutcome(){
        var luna=mock(CoreLunaClient.class);var goals=mock(PinnedSavedGoalReader.class);UUID child=UUID.randomUUID();
        when(luna.call(any(LunaRequest.class))).thenAnswer(i->{
            LunaRequest request=i.getArgument(0);
            assertThat(request.promptVersion()).isEqualTo("rejudgment.v2");
            assertThat(request.instructions()).contains("사전 연습에서 한 말은 상황극 수행 근거에서 제외", "평소 독립 능력이나 장기 향상이 아니다");
            var scene=ResultData.objects(request.input().get("scenes")).getFirst();
            assertThat(scene).containsEntry("startedAt",Instant.EPOCH.toString())
                    .containsEntry("facts",List.of("PREPRACTICE_PROVIDED","PREPRACTICE_ATTEMPTED"))
                    .doesNotContainKeys("liveTiming","liveOutcome");
            return new LunaResult(JsonCodec.shared().write(Map.of("scenes",List.of(Map.of(
                    "sceneRunId",scene.get("sceneRunId"),"value","functional","timing","IMMEDIATE")))),
                    "fake","rejudgment.v2",Duration.ZERO,1,false);
        });
        var practice=Map.<String,Object>of("flowVersion",PracticeRecordV2.VERSION,"scenes",List.of(Map.of(
                "sceneRunId",run.toString(),"goalRef",goal.toString(),"sceneKind","BASE","startedAt",Instant.EPOCH.toString(),
                "facts",List.of("PREPRACTICE_PROVIDED","PREPRACTICE_ATTEMPTED"))));
        assertThat(new RejudgmentGateway(Optional.of(luna),goals).judge(child,practice,List.of(),Map.of(),Instant.MAX,()->{}))
                .singleElement().satisfies(scene->assertThat(scene.facts()).contains("PREPRACTICE_PROVIDED"));
        verify(luna,times(1)).call(any(LunaRequest.class));
    }
    @Test void realProviderRequestUsesEphemeralSchemaUuidAndOneCall(){
        var luna=mock(CoreLunaClient.class);var goals=mock(PinnedSavedGoalReader.class);UUID child=UUID.randomUUID();
        when(goals.historical(child,goal)).thenReturn(Optional.empty());
        when(luna.call(any(LunaRequest.class))).thenAnswer(i->{LunaRequest request=i.getArgument(0);assertThat(request.purpose()).isEqualTo(LunaPurpose.REJUDGMENT);var scenes=ResultData.objects(request.input().get("scenes"));String alias=scenes.getFirst().get("sceneRunId").toString();assertThat(UUID.fromString(alias)).isNotEqualTo(run);return new LunaResult(JsonCodec.shared().write(Map.of("scenes",List.of(Map.of("sceneRunId",alias,"value","functional","timing","AFTER_REASK")))),"fake","rejudgment.v1",Duration.ZERO,1,false);});
        var gateway=new RejudgmentGateway(Optional.of(luna),goals);
        var practice=Map.<String,Object>of("flowVersion",PracticeRecordV2.VERSION,"scenes",List.of(Map.of("sceneRunId",run.toString(),"goalRef",goal.toString(),"sceneKind","BASE","startedAt",Instant.EPOCH.toString())));
        assertThat(gateway.judge(child,practice,List.of(),Map.of(),Instant.MAX,()->{})).singleElement().extracting(SceneJudgmentRepository.Scene::sceneRunId).isEqualTo(run);verify(luna,times(1)).call(any(LunaRequest.class));
    }
    @Test void providerReceivesOnlyBoundedRoleplayAcrossTwoGoalsAndLegacyKeepsItsTranscript(){
        var luna=mock(CoreLunaClient.class);var goals=mock(PinnedSavedGoalReader.class);UUID child=UUID.randomUUID();
        var before=PracticeEvidenceTranscriptTest.turn(1,1,2);
        var roleOne=PracticeEvidenceTranscriptTest.turn(2,11,12);
        var between=PracticeEvidenceTranscriptTest.turn(3,30,31);
        var roleTwo=PracticeEvidenceTranscriptTest.turn(4,41,42);
        var after=PracticeEvidenceTranscriptTest.turn(5,60,61);
        var transcript=List.of(before,roleOne,between,roleTwo,after);
        when(luna.call(any(LunaRequest.class))).thenAnswer(i->{
            LunaRequest request=i.getArgument(0);var requested=ResultData.objects(request.input().get("scenes"));
            if(requested.getFirst().containsKey("endedAt"))assertThat(request.input().get("transcript")).isEqualTo(List.of(roleOne,roleTwo));
            else assertThat(request.input().get("transcript")).isEqualTo(transcript);
            return new LunaResult(JsonCodec.shared().write(Map.of("scenes",requested.stream().map(scene->Map.of(
                    "sceneRunId",scene.get("sceneRunId"),"value","functional","timing","IMMEDIATE")).toList())),
                    "fake","rejudgment.v2",Duration.ZERO,1,false);
        });
        var gateway=new RejudgmentGateway(Optional.of(luna),goals);
        gateway.judge(child,PracticeEvidenceTranscriptTest.practice(PracticeEvidenceTranscriptTest.scene(10,20,true),
                PracticeEvidenceTranscriptTest.scene(40,50,true)),transcript,Map.of(),Instant.MAX,()->{});
        gateway.judge(child,PracticeEvidenceTranscriptTest.practice(PracticeEvidenceTranscriptTest.scene(10,null,false)),
                transcript,Map.of(),Instant.MAX,()->{});
        verify(luna,times(2)).call(any(LunaRequest.class));
    }
}
