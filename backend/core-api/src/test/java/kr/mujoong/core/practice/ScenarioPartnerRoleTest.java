package kr.mujoong.core.practice;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import kr.mujoong.core.knowledge.KnowledgeSeed;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** 장면 partnerRole 라벨 표(relation → 짧은 역할 이름). 지시문이 이 칸에 들어가지 않는지 본다. 합성 값만 쓴다. */
@Tag("scenario-generation")
class ScenarioPartnerRoleTest {

    private static final Map<String, String> TABLE = Map.of(
            "PEER", "친구",
            "FRIEND_CASUAL", "친구",
            "FRIEND_CLOSE", "친한 친구",
            "CLASSMATE", "같은 반 친구",
            "CLUB_PEER", "동아리 친구",
            "CLUB_SENIOR", "동아리 선배",
            "CLUB_JUNIOR", "동아리 후배",
            "TEAMMATE", "같은 팀 친구",
            "TEACHER", "선생님",
            "FAMILY", "가족");

    @Test
    void everyRelationCodeMapsToItsShortLabel() {
        TABLE.forEach((code, label) -> assertThat(ScenarioContentPolicy.partnerRoleName(code)).as(code).isEqualTo(label));
    }

    @Test
    void theTableCoversEveryKnowledgeGraphRelationshipCode() {
        assertThat(TABLE.keySet()).containsAll(KnowledgeSeed.RELATIONSHIPS);
    }

    @Test
    void anUnknownUpperCaseCodeFallsBackToFriend() {
        assertThat(ScenarioContentPolicy.partnerRoleName("NEIGHBOR")).isEqualTo("친구");
        assertThat(ScenarioContentPolicy.partnerRoleName(" FRIEND_CLOSE ")).as("앞뒤 공백은 지운 뒤 본다").isEqualTo("친한 친구");
    }

    @Test
    void aShortFreeTextPartnerFromACandidateGoalIsKeptWithTidiedSpaces() {
        assertThat(ScenarioContentPolicy.partnerRoleName("친한 친구")).isEqualTo("친한 친구");
        assertThat(ScenarioContentPolicy.partnerRoleName("  빨간 블록으로   성을 쌓는 친구 ")).isEqualTo("빨간 블록으로 성을 쌓는 친구");
        String twenty = "가".repeat(ScenarioContentPolicy.PARTNER_ROLE_MAX);
        assertThat(ScenarioContentPolicy.partnerRoleName(twenty)).as("상한(20자)과 같으면 그대로").isEqualTo(twenty);
        String astral = "😀".repeat(ScenarioContentPolicy.PARTNER_ROLE_MAX);
        assertThat(ScenarioContentPolicy.partnerRoleName(astral)).as("길이는 코드 포인트로 센다").isEqualTo(astral);
    }

    @Test
    void anInstructionLikeOrOverlongFreeTextFallsBackToFriend() {
        assertThat(ScenarioContentPolicy.partnerRoleName(ScenarioContentPolicy.PARTNER_ROLE)).as("레거시 지시문").isEqualTo("친구");
        assertThat(ScenarioContentPolicy.partnerRoleName("가".repeat(ScenarioContentPolicy.PARTNER_ROLE_MAX + 1))).isEqualTo("친구");
        assertThat(ScenarioContentPolicy.partnerRoleName("친구")).isEqualTo("친구");
    }

    @ParameterizedTest
    @ValueSource(strings = {"친구야.", "누구?", "친구!", "친구。", "친\u0000구", "친\u0007구"})
    void sentencePunctuationOrControlCharactersFallBackToFriend(String relation) {
        assertThat(ScenarioContentPolicy.partnerRoleName(relation)).isEqualTo("친구");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    void aMissingOrBlankRelationFallsBackToFriend(String relation) {
        assertThat(ScenarioContentPolicy.partnerRoleName(relation)).isEqualTo("친구");
    }

    @Test
    void theLegacyFixedInstructionIsUnchangedForThePartnerRoleTextColumn() {
        assertThat(ScenarioContentPolicy.PARTNER_ROLE).isEqualTo("제시된 상황의 상대 역할을 맡아. 아이의 답을 대신 말하거나 평가하지 마.");
    }
}
