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
    @Test void invalidRecordCannotBeMarkedReconciled() {
        for(String raw:List.of("[]","{}","{\"goals\":[{}]}","{\"goals\":[{\"scenarios\":[null]}]}",
                "{\"goals\":[{\"scenarios\":[{\"exposedAt\":123}]}]}"))
            assertThatThrownBy(()->RecordedScenarioExposures.parse(raw,json)).isInstanceOf(IllegalArgumentException.class);
    }
}
