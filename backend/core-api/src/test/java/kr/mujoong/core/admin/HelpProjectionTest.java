package kr.mujoong.core.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import kr.mujoong.core.admin.HelpProjection.Offer;
import kr.mujoong.core.admin.RealtimeAdminClient.HelpFact;
import kr.mujoong.core.admin.RealtimeAdminClient.SceneRunFacts;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** JIRA-280: 도움 반응은 장면당 한 번, 기록으로 가를 수 없으면 확인 불가. */
@Tag("admin")
class HelpProjectionTest {

    @Test
    void acceptanceStoredOnRoleSwitchAndFirstFocusHelpCountsOnce() {
        SceneRunFacts run = run("AFTER_HELP", List.of("HELP_ACCEPTED", "COACH_SWITCH", "FOCUS_HELP"),
                help("ROLE_SWITCH", "ACCEPT", false), help("FOCUS_HELP", "ACCEPT", true), help("FOCUS_HELP", null, false));

        assertThat(HelpProjection.of(run)).isEqualTo(new HelpProjection.Result(Offer.OFFERED, "ACCEPT"));
    }

    @Test
    void declineHasNoHelpRowAndIsReadFromTheSceneFact() {
        SceneRunFacts run = run("NONE", List.of("HELP_DECLINED"));

        assertThat(HelpProjection.of(run)).isEqualTo(new HelpProjection.Result(Offer.OFFERED, "DECLINE"));
    }

    @Test
    void childAskingFirstIsRequestedNotOffered() {
        SceneRunFacts run = run("AFTER_HELP", List.of("COACH_SWITCH"), help("ROLE_SWITCH", "REQUESTED", false));

        assertThat(HelpProjection.of(run)).isEqualTo(new HelpProjection.Result(Offer.REQUESTED, "REQUESTED"));
    }

    @Test
    void unclearAnswerIsKeptAsUnclear() {
        SceneRunFacts run = run("AFTER_HELP", List.of(), help("ROLE_SWITCH", "UNCLEAR", false));

        assertThat(HelpProjection.of(run)).isEqualTo(new HelpProjection.Result(Offer.OFFERED, "UNCLEAR"));
    }

    @Test
    void scenesThatEndedBeforeTheHelpOfferStepAreNotOffered() {
        assertThat(HelpProjection.of(run("IMMEDIATE", List.of()))).isEqualTo(new HelpProjection.Result(Offer.NOT_OFFERED, null));
        assertThat(HelpProjection.of(run("AFTER_REASK", List.of("REASK_RESOLVED"))))
                .isEqualTo(new HelpProjection.Result(Offer.NOT_OFFERED, null));
    }

    @Test
    void sceneWithoutEvidenceIsUnknownNotUnanswered() {
        // 시간 · 종료 요청으로 끝났거나 판정 보류: 제안 전에 끝났는지 반응이 없었는지 가를 수 없다
        assertThat(HelpProjection.of(run("NONE", List.of("TIMEOUT")))).isEqualTo(new HelpProjection.Result(Offer.UNKNOWN, null));
        assertThat(HelpProjection.of(run("ABSTAINED", List.of()))).isEqualTo(new HelpProjection.Result(Offer.UNKNOWN, null));
        assertThat(HelpProjection.of(run(null, List.of()))).isEqualTo(new HelpProjection.Result(Offer.UNKNOWN, null));
        assertThat(HelpProjection.of(null)).isEqualTo(new HelpProjection.Result(Offer.UNKNOWN, null));
    }

    @Test
    void roleReturnAndUnknownConsentValuesAreNotEvidence() {
        SceneRunFacts run = run("NONE", List.of(), help("ROLE_RETURN", "ACCEPT", false), help("ROLE_SWITCH", "MAYBE", false));

        assertThat(HelpProjection.of(run).offer()).isEqualTo(Offer.UNKNOWN);
    }

    private static SceneRunFacts run(String timing, List<String> facts, HelpFact... helps) {
        return new SceneRunFacts(UUID.randomUUID(), "BASE", timing, null, 0, 0, facts, List.of(helps));
    }

    private static HelpFact help(String kind, String consent, boolean example) {
        return new HelpFact(UUID.randomUUID(), kind, consent, example);
    }
}
