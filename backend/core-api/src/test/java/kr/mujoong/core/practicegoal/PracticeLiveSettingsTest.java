package kr.mujoong.core.practicegoal;

import static org.assertj.core.api.Assertions.assertThat;

import kr.mujoong.core.luna.LunaPurpose;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 실제 AI 하니스가 운영 설정을 그대로 재는지 확인한다. 하니스({@link PracticeQualityLiveTest})는 키가 없으면 돌지 않으므로, 하니스가 읽는
 * application.yml 묶음({@code ProductionBudgets.load})을 기본 시험에서 따로 읽어 상황 생성 · 검토 추론 강도가 낮춘 값인지 본다.
 * 공급자를 부르지 않는다(R-F1).
 */
@Tag("scenario-generation")
class PracticeLiveSettingsTest {

    @Test
    void theHarnessReadsTheLoweredScenarioReasoningEffortFromApplicationYml() {
        var purposes = PracticeQualityLiveTest.ProductionBudgets.load().purposes();

        assertThat(purposes.of(LunaPurpose.SCENARIO_GENERATION).reasoningEffort()).isEqualTo("low");
        assertThat(purposes.of(LunaPurpose.SCENARIO_REVIEW).reasoningEffort()).isEqualTo("low");
    }
}
