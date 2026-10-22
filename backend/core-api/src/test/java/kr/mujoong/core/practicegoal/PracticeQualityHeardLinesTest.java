package kr.mujoong.core.practicegoal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.core.practice.Scenario;
import kr.mujoong.core.textai.ScenarioScript;
import org.junit.jupiter.api.Test;

/**
 * 실호출 품질 하니스의 '아이가 듣는 순서' 보기와 참고 점검({@link PracticeQualityLiveTest.HeardLines}) 시험. 하니스는 키가 있어야 돌므로 이 부분만
 * 합성 대본으로 기본 시험에서 확인한다(실제 AI 없음, 테스트 대역 증거).
 */
class PracticeQualityHeardLinesTest {

    private static final String CONDITION = "친구가 부탁한 것과 다른 것을 줬어.";

    @Test
    void theHeardOrderIsMujoongThenTheFriendAndTheChecksFlagACopiedConditionAHeardTargetAndPraise() {
        var base = new ScenarioScript.Scene("base-1", "BASE", "무중이랑 공룡 그림을 그린다고 생각해 보자. " + CONDITION, "알았어, 잠깐만!", "자, 여기 초록색!",
                "너는 파란색을 달라고 했어.", List.of("달라고 한 것과 받은 것 보기"), List.of("아니, 나 파란색 달라고 했어."), "FRIEND_CLOSE", "교실", "그림",
                null, "같은 모둠 친구");
        var variant = new ScenarioScript.Scene("variant-1", "VARIANT", "그림 다 그렸네. 잘했어! 이번엔 쉬는 시간이야.", "", "아니, 나 딸기 우유 달라고 했어? 자, 초코 우유 여기!",
                "쉬는 시간이야.", List.of(), List.of("아니, 나 딸기 우유 달라고 했어."), "FRIEND_CLOSE", "교실", "간식", null, "같은 모둠 친구");
        Scenario scenario = new Scenario(UUID.randomUUID(), "ORIGINAL", "같은 모둠 친구", CONDITION, "글", "지문", "자, 여기 초록색!",
                new Scenario.Validation(true, true, false), "scenario-script.v1", List.of(base, variant), null, null);

        List<Map<String, Object>> heard = PracticeQualityLiveTest.HeardLines.of(scenario, "아니, 나 파란색 달라고 했어");

        assertThat(heard).hasSize(2);
        assertThat(heard.get(0).get("heardOrder")).isEqualTo(List.of("무중: 무중이랑 공룡 그림을 그린다고 생각해 보자. " + CONDITION,
                "친구: 알았어, 잠깐만!", "친구: 자, 여기 초록색!"));
        assertThat(heard.get(0).get("checks")).isEqualTo(Map.of("targetHeard", false, "opportunityConditionCopied", true, "praiseWords", List.of(),
                "partnerOpenerPresent", true));
        assertThat(heard.get(1).get("heardOrder")).as("변형은 무중의 제안 뒤 친구 기회 문장(빈 첫 대사는 없다)")
                .isEqualTo(List.of("무중: 그림 다 그렸네. 잘했어! 이번엔 쉬는 시간이야. 이어서 해 볼까?", "친구: 아니, 나 딸기 우유 달라고 했어? 자, 초코 우유 여기!"));
        assertThat(heard.get(1).get("checks")).isEqualTo(Map.of("targetHeard", true, "opportunityConditionCopied", false, "praiseWords",
                List.of("잘했")));

        Map<String, Object> totals = PracticeQualityLiveTest.HeardLines.totals(List.of(Map.of("heard", heard)));
        assertThat(totals).containsEntry("scenes", 2).containsEntry("targetHeard", 1).containsEntry("opportunityConditionCopied", 1)
                .containsEntry("praiseWords", 1).containsEntry("baseWithoutPartnerOpener", 0);
        assertThat(PracticeQualityLiveTest.HeardLines.of(null, "x")).isEmpty();
    }
}
