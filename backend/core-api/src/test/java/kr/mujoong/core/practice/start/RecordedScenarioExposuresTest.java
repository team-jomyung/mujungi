package kr.mujoong.core.practice.start;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.practice.ScenarioRecordPort.Exposure;
import kr.mujoong.core.result.PracticeRecordV2;
import kr.mujoong.core.web.support.WebFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 기록의 실제 노출 읽기: U5b 형식과 새 연습 흐름 형식(practice-record-v2, 공유 고정 자료). 합성 값만 쓴다. */
@Tag("practice-legacy")
class RecordedScenarioExposuresTest {

    private static final UUID C01 = UUID.fromString("00000000-0000-4000-8000-000000000c01");
    private static final UUID C02 = UUID.fromString("00000000-0000-4000-8000-000000000c02");

    final JsonCodec json = JsonCodec.shared();

    static String sharedRecordFixture() throws IOException {
        return Files.readString(WebFixtures.repoRoot().resolve("contracts/fixtures/practice-record-v2.json"), StandardCharsets.UTF_8);
    }

    private String flow(List<Map<String, Object>> scenes) {
        return json.write(Map.of("flowVersion", "practice-conversation-v1", "scenes", scenes));
    }

    private static Map<String, Object> scene(Object scenarioRef, Object startedAt) {
        var row = new java.util.LinkedHashMap<String, Object>();
        row.put("sceneRunId", UUID.randomUUID().toString());
        row.put("goalRef", UUID.randomUUID().toString());
        if (scenarioRef != null) {
            row.put("scenarioRef", scenarioRef);
        }
        row.put("sceneKind", "BASE");
        if (startedAt != null) {
            row.put("startedAt", startedAt);
        }
        return row;
    }

    // ---- U5b 형식(기존) ----

    @Test
    void onlyActualScenarioExposureIsReadAndCueTimesCannotSubstituteForIt() {
        UUID issued = UUID.randomUUID();
        UUID shown = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-14T00:00:00Z");
        String record = json.write(Map.of("goals", List.of(Map.of(
                "scenarios", List.of(Map.of("scenarioId", issued), Map.of("scenarioId", shown, "exposedAt", at)),
                "cues", List.of(Map.of("scenarioId", issued, "givenAt", at))))));

        assertThat(RecordedScenarioExposures.parse(record, json)).containsExactly(new Exposure(shown, at));
    }

    @Test
    void nullPracticeFromFreeTalkOrDeclinedHasNoExposures() {
        assertThat(RecordedScenarioExposures.parse(null, json)).isEmpty();
        assertThat(RecordedScenarioExposures.parse("null", json)).isEmpty();
        assertThat(RecordedScenarioExposures.parse("{\"goals\":[]}", json)).isEmpty();
    }

    @Test
    void invalidRecordCannotBeMarkedReconciled() {
        for (String raw : List.of("[]", "{}", "{\"goals\":[{}]}", "{\"goals\":[{\"scenarios\":[null]}]}",
                "{\"goals\":[{\"scenarios\":[{\"exposedAt\":123}]}]}")) {
            assertThatThrownBy(() -> RecordedScenarioExposures.parse(raw, json)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ---- 새 연습 흐름 형식(practice-record-v2) ----

    @Test
    void theSharedRecordFixtureYieldsEachScenarioOnceAtItsEarliestSceneStart() throws IOException {
        assertThat(RecordedScenarioExposures.parse(sharedRecordFixture(), json)).containsExactly(
                new Exposure(C01, Instant.parse("2026-10-20T01:00:05Z")),
                new Exposure(C02, Instant.parse("2026-10-20T01:05:00Z")));
    }

    @Test
    void theSameScenarioInLaterAndEarlierScenesKeepsTheEarliestStartAndResultIsInTimeOrder() {
        String record = flow(List.of(
                scene(C02.toString(), "2026-10-20T01:09:00Z"),
                scene(C01.toString(), "2026-10-20T01:04:00Z"),
                scene(C02.toString(), "2026-10-20T01:01:00Z"),
                scene(C01.toString(), "2026-10-20T01:07:00Z")));

        assertThat(RecordedScenarioExposures.parse(record, json)).containsExactly(
                new Exposure(C02, Instant.parse("2026-10-20T01:01:00Z")),
                new Exposure(C01, Instant.parse("2026-10-20T01:04:00Z")));
    }

    @Test
    void scenesWithoutScenarioRefOrStartTimeAreSkippedNotFailed() {
        String record = flow(List.of(
                scene(null, "2026-10-20T01:00:00Z"),
                scene(C01.toString(), null),
                scene(C01.toString(), ""),
                scene(C02.toString(), "2026-10-20T01:03:00Z")));

        assertThat(RecordedScenarioExposures.parse(record, json))
                .containsExactly(new Exposure(C02, Instant.parse("2026-10-20T01:03:00Z")));
        assertThat(RecordedScenarioExposures.parse(flow(List.of()), json)).as("장면 없는 기록은 노출 없음").isEmpty();
    }

    @Test
    void anUnknownFlowVersionIsNotReconciled() {
        String record = json.write(Map.of("flowVersion", "practice-conversation-v2", "scenes", List.of()));

        assertThatThrownBy(() -> RecordedScenarioExposures.parse(record, json)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecordedScenarioExposures.parse("{\"flowVersion\":null,\"scenes\":[]}", json))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void brokenNewFlowShapesFailWithAFixedMessageThatCarriesNoRecordValue() {
        List<String> broken = List.of(
                "{\"flowVersion\":\"practice-conversation-v1\",\"scenes\":{}}",
                "{\"flowVersion\":\"practice-conversation-v1\"}",
                "{\"flowVersion\":\"practice-conversation-v1\",\"scenes\":[\"합성장면\"]}",
                flow(List.of(scene("합성-아닌-식별자", "2026-10-20T01:00:00Z"))),
                flow(List.of(scene(42, "2026-10-20T01:00:00Z"))),
                flow(List.of(scene(C01.toString(), "합성 시각"))),
                flow(List.of(scene(C01.toString(), 1700000000))));
        for (String raw : broken) {
            assertThatThrownBy(() -> RecordedScenarioExposures.parse(raw, json))
                    .isInstanceOf(IllegalArgumentException.class)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("합성", "1700000000"));
        }
    }

    @Test
    void theFlowVersionConstantMatchesTheResultProjectionReader() {
        assertThat(RecordedScenarioExposures.FLOW_VERSION).isEqualTo(PracticeRecordV2.VERSION);
    }
}
