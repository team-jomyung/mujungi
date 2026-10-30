package kr.mujoong.core.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PracticeEvidenceTranscriptTest {
    private static final Instant START = Instant.parse("2026-10-28T01:00:00Z");

    static String at(int seconds) { return START.plusSeconds(seconds).toString(); }

    static Map<String, Object> scene(int start, Integer end, boolean prepared) {
        Map<String, Object> scene = new LinkedHashMap<>();
        scene.put("sceneRunId", UUID.randomUUID().toString());
        scene.put("goalRef", UUID.randomUUID().toString());
        scene.put("sceneKind", "BASE");
        scene.put("startedAt", at(start));
        scene.put("facts", prepared ? List.of("PREPRACTICE_PROVIDED") : List.of());
        if (end != null) scene.put("endedAt", at(end));
        return scene;
    }

    @SafeVarargs
    static Map<String, Object> practice(Map<String, Object>... scenes) {
        return Map.of("flowVersion", PracticeRecordV2.VERSION, "scenes", List.of(scenes));
    }

    static Map<String, Object> turn(int seq, int start, int end) {
        return Map.of("seq", seq, "speaker", "CHILD", "text", "합성 발화", "uncertain", false,
                "startedAt", at(start), "endedAt", at(end));
    }

    @Test
    void eachGoalsPreparationAndTheFinalReviewAreExcludedWithoutChangingTheOriginalTranscript() {
        var before = turn(1, 1, 2);
        var firstRole = turn(2, 11, 12);
        var nextPreparation = turn(3, 30, 31);
        var nextRole = turn(4, 41, 42);
        var review = turn(5, 51, 52);
        var transcript = new ArrayList<>(List.of(before, firstRole, nextPreparation, nextRole, review));
        var original = List.copyOf(transcript);

        var filtered = PracticeEvidenceTranscript.forAnalysis(practice(scene(10, 20, true), scene(40, 50, true)), transcript);

        assertThat(filtered).containsExactly(firstRole, nextRole);
        assertThat(transcript).containsExactlyElementsOf(original);
        assertThat(filtered.getFirst()).isSameAs(firstRole);
    }

    @Test
    void uncertainTimesAndUtterancesCrossingEitherSceneBoundaryCannotBecomePerformanceEvidence() {
        var exactInterval = turn(1, 10, 20);
        var crossesStart = turn(2, 9, 11);
        var crossesEnd = turn(3, 19, 21);
        var missingEnd = Map.<String, Object>of("seq", 4, "speaker", "CHILD", "text", "시각 미확인", "startedAt", at(11));
        var malformed = Map.<String, Object>of("seq", 5, "speaker", "CHILD", "text", "시각 오류", "startedAt", "unknown", "endedAt", at(12));
        var zeroLength = turn(6, 12, 12);
        var reversed = turn(7, 14, 13);

        assertThat(PracticeEvidenceTranscript.forAnalysis(practice(scene(10, 20, true)),
                List.of(exactInterval, crossesStart, crossesEnd, missingEnd, malformed, zeroLength, reversed)))
                .containsExactly(exactInterval);
    }

    @Test
    void overlappingScenesDoNotAssignAmbiguousSpeechToEitherScene() {
        var distinct = turn(1, 11, 12);
        var overlapsBoth = turn(2, 16, 17);
        var crossesIntoSecond = turn(3, 14, 16);
        assertThat(PracticeEvidenceTranscript.forAnalysis(practice(scene(10, 20, true), scene(15, 25, true)),
                List.of(distinct, overlapsBoth, crossesIntoSecond))).containsExactly(distinct);
    }

    @Test
    void aNewPreparedSceneWithoutAnEndProvidesNoConfirmedPerformanceWindow() {
        var transcript = List.of(turn(1, 11, 12));
        assertThat(PracticeEvidenceTranscript.forAnalysis(practice(scene(10, null, true)), transcript)).isEmpty();
    }

    @Test
    void oldRecordsWithoutPreparationFactsOrEndTimesKeepTheirPreviousEvidence() {
        var transcript = List.of(Map.<String, Object>of("seq", 1, "speaker", "CHILD", "text", "이전 형식 발화"));
        assertThat(PracticeEvidenceTranscript.forAnalysis(practice(scene(10, null, false)), transcript)).isSameAs(transcript);
        assertThat(PracticeEvidenceTranscript.forAnalysis(Map.of("goals", List.of()), transcript)).isSameAs(transcript);
    }

    @Test
    void parserPreservesOptionalEndAndRejectsAnEndBeforeTheSceneStarted() {
        assertThat(PracticeRecordV2.read(practice(scene(10, 20, true))).getFirst().endedAt()).isEqualTo(START.plusSeconds(20));
        assertThat(PracticeRecordV2.read(practice(scene(10, null, false))).getFirst().endedAt()).isNull();
        assertThatThrownBy(() -> PracticeRecordV2.read(practice(scene(10, 9, true)))).isInstanceOf(ResultAiException.class);
    }
}
