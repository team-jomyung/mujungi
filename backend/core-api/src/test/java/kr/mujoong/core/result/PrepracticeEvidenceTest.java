package kr.mujoong.core.result;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 사전 연습을 장면 안 도움이나 독립 능력으로 바꾸지 않는다. AI·DB 호출 없음. */
@Tag("goal-result")
class PrepracticeEvidenceTest {

    @Test
    void prepracticeFactsSurviveParsingWithoutInventingInSceneHelp() {
        UUID goal = UUID.randomUUID();
        Map<String, Object> practice = Map.of("flowVersion", PracticeRecordV2.VERSION, "scenes", List.of(Map.of(
                "sceneRunId", UUID.randomUUID().toString(), "goalRef", goal.toString(), "sceneKind", "BASE",
                "startedAt", Instant.EPOCH.toString(), "liveOutcome", Map.of("timing", "IMMEDIATE"),
                "facts", List.of("PREPRACTICE_PROVIDED", "PREPRACTICE_ATTEMPTED"))));

        PracticeRecordV2.Scene scene = PracticeRecordV2.read(practice).getFirst();
        assertThat(scene.facts()).containsExactly("PREPRACTICE_PROVIDED", "PREPRACTICE_ATTEMPTED");
        assertThat(scene.liveTiming()).isEqualTo("IMMEDIATE");
        assertThat(PracticeRecordV2.goals(practice).getFirst().get("cues")).isEqualTo(List.of());
    }

    @Test
    void rejudgmentAndBothPublicProjectionsRetainThePrepracticeCondition() {
        UUID goal = UUID.randomUUID();
        var input = new PracticeRecordV2.Scene(UUID.randomUUID(), goal, null, "BASE", Instant.EPOCH,
                "IMMEDIATE", List.of("PREPRACTICE_PROVIDED", "PREPRACTICE_ATTEMPTED"), null, 1);
        var scene = RejudgmentGateway.validated(Map.of("scenes", List.of(Map.of(
                "sceneRunId", "handle", "value", "functional", "timing", "IMMEDIATE"))), Map.of("handle", input))
                .getFirst();

        assertThat(scene.facts()).containsExactlyElementsOf(input.facts());
        assertThat(scene.sceneChip()).isEqualTo("DONE_IMMEDIATE");
        assertThat(SceneReportMapper.publicText(scene)).isEqualTo("사전 연습 후 · 바로 전달");
        assertThat(SceneReportMapper.text(scene)).isEqualTo("사전 연습 후 · 바로 전달");
    }

    @Test
    void prepracticeAttemptDoesNotInventACompletedTeachingPlayback() {
        var scene = new SceneJudgmentRepository.Scene(UUID.randomUUID(), 1, 1, "ORIGINAL", null,
                "functional", "AFTER_REASK", null, null, false, null, "DONE_IMMEDIATE", UUID.randomUUID(),
                PracticeRecordV2.VERSION, List.of("PREPRACTICE_ATTEMPTED"), null);
        assertThat(SceneReportMapper.publicText(scene)).isEqualTo("바로 전달");
        assertThat(SceneReportMapper.text(scene)).isEqualTo("되묻기 뒤 전달");
    }
}
