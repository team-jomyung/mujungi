package kr.mujoong.realtime.practice;

import static org.assertj.core.api.Assertions.*;
import static kr.mujoong.realtime.practice.PracticeTypes.*;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u5b")
class StageOutputPolicyTest {
    final UUID scene = UUID.randomUUID();
    final CueBrief brief = new CueBrief("블록 놀이", "나도 쌓아 볼래", "상대에게 차례 표현");
    final StageOutputPolicy policy = new StageOutputPolicy();
    StageOutputPolicy.Context context(Stage stage, Consent consent, boolean active, boolean connected, boolean ready, boolean given) {
        return new StageOutputPolicy.Context(scene, stage, 3, consent, active, connected, ready, given);
    }
    @Test void permitsOnlyAcceptedCueStage() {
        assertThat(policy.cue(context(Stage.CUE, Consent.ACCEPTED, true, true, true, false), scene, 3, brief, true, false)).contains(brief);
    }
    @Test void blocksFirstAndNewAndEveryOtherStage() {
        for (Stage stage : Stage.values()) if (stage != Stage.CUE)
            assertThat(policy.cue(context(stage, Consent.ACCEPTED, true, true, true, false), scene, 3, brief, true, false)).isEmpty();
    }
    @Test void blocksDeclinedAbsentAndRepeatedCue() {
        for (Consent consent : new Consent[] {Consent.DECLINED, Consent.NO_RESPONSE, null})
            assertThat(policy.cue(context(Stage.CUE, consent, true, true, true, false), scene, 3, brief, true, false)).isEmpty();
        assertThat(policy.cue(context(Stage.CUE, Consent.ACCEPTED, true, true, true, true), scene, 3, brief, true, false)).isEmpty();
    }
    @Test void checksCurrentOwnershipAndAvailabilityAgain() {
        var good = context(Stage.CUE, Consent.ACCEPTED, true, true, true, false);
        assertThat(policy.cue(good, UUID.randomUUID(), 3, brief, true, false)).isEmpty();
        assertThat(policy.cue(good, scene, 2, brief, true, false)).isEmpty();
        for (int missing = 0; missing < 3; missing++)
            assertThat(policy.cue(context(Stage.CUE, Consent.ACCEPTED, missing != 0, missing != 1, missing != 2, false), scene, 3, brief, true, false)).isEmpty();
    }
    @Test void approvedContentStillCannotContainAnswerAndBriefHasOnlyThreeFields() {
        var good = context(Stage.CUE, Consent.ACCEPTED, true, true, true, false);
        assertThat(policy.cue(good, scene, 3, brief, false, false)).isEmpty();
        assertThat(policy.cue(good, scene, 3, brief, true, true)).isEmpty();
        assertThat(CueBrief.class.getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("situationSummary", "partnerUtterance", "focus");
        assertThat(JsonCodec.shared().write(brief)).doesNotContain("judgmentCriteria", "answer", "guardian");
        assertThat(brief.toString()).doesNotContain("블록", "차례");
        assertThatThrownBy(() -> new CueBrief("", "발화", "초점")).isInstanceOf(IllegalArgumentException.class);
    }
}
