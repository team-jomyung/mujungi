package kr.mujoong.core.practice.start;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.practice.ScenarioRecordPort.Exposure;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("practice-legacy")
class RecordedScenarioExposuresTest {
    final JsonCodec json=JsonCodec.shared();
    @Test void onlyActualScenarioExposureIsReadAndCueTimesCannotSubstituteForIt() {
        UUID issued=UUID.randomUUID(),shown=UUID.randomUUID();Instant at=Instant.parse("2026-10-14T00:00:00Z");
        String record=json.write(Map.of("goals",List.of(Map.of("scenarios",List.of(
                Map.of("scenarioId",issued),Map.of("scenarioId",shown,"exposedAt",at)),
                "cues",List.of(Map.of("scenarioId",issued,"givenAt",at))))));
        assertThat(RecordedScenarioExposures.parse(record,json)).containsExactly(new Exposure(shown,at));
    }
    @Test void nullPracticeFromFreeTalkOrDeclinedHasNoExposures() {
        assertThat(RecordedScenarioExposures.parse(null,json)).isEmpty();
        assertThat(RecordedScenarioExposures.parse("null",json)).isEmpty();
        assertThat(RecordedScenarioExposures.parse("{\"goals\":[]}",json)).isEmpty();
    }
    // FT-1030-g: 새 연습 흐름 기록은 장면의 scenarioRef와 시작 시각으로 노출을 남긴다. 시작하지 않은 장면 · 장면 참조 없는 장면은 빼고, 같은 장면 행은 처음 시각 하나로 묶는다
    @Test void flowRecordExposesStartedScenesByTheirFirstStart() {
        UUID scenario=UUID.randomUUID(),goal=UUID.randomUUID();
        Instant base=Instant.parse("2026-10-30T05:13:30Z"),variant=Instant.parse("2026-10-30T05:15:00Z");
        String record=json.write(Map.of("flowVersion","practice-conversation-v1","scenes",List.of(
                Map.of("sceneRunId",UUID.randomUUID(),"goalRef",goal,"scenarioRef",scenario,"sceneKind","BASE","startedAt",base),
                Map.of("sceneRunId",UUID.randomUUID(),"goalRef",goal,"scenarioRef",scenario,"sceneKind","VARIANT","startedAt",variant),
                Map.of("sceneRunId",UUID.randomUUID(),"goalRef",goal,"sceneKind","BASE","startedAt",variant))));
        assertThat(RecordedScenarioExposures.parse(record,json)).containsExactly(new Exposure(scenario,base));
    }
    @Test void flowRecordWithoutStartedScenesHasNoExposures() {
        assertThat(RecordedScenarioExposures.parse("{\"flowVersion\":\"practice-conversation-v1\",\"scenes\":[]}",json)).isEmpty();
    }
    @Test void invalidFlowRecordCannotBeMarkedReconciled() {
        for(String raw:List.of("{\"flowVersion\":\"practice-conversation-v1\"}","{\"flowVersion\":\"other\",\"scenes\":[]}",
                "{\"flowVersion\":\"practice-conversation-v1\",\"scenes\":[{\"sceneRunId\":\"x\"}]}"))
            assertThatThrownBy(()->RecordedScenarioExposures.parse(raw,json)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void invalidRecordCannotBeMarkedReconciled() {
        for(String raw:List.of("[]","{}","{\"goals\":[{}]}","{\"goals\":[{\"scenarios\":[null]}]}",
                "{\"goals\":[{\"scenarios\":[{\"exposedAt\":123}]}]}"))
            assertThatThrownBy(()->RecordedScenarioExposures.parse(raw,json)).isInstanceOf(IllegalArgumentException.class);
    }
}
