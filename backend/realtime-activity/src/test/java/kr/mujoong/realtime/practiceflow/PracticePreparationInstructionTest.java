package kr.mujoong.realtime.practiceflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** FT-1102-c: 사전 연습 지시가 목표 행동(의도 · 쓰이는 때)을 싣고, 정답 표현은 싣지 않는다. */
@Tag("practice-flow")
class PracticePreparationInstructionTest {

    private static final String CANDIDATE_CRITERIA = "상황: 이어지던 대화가 마무리되기 전에 다른 주제로 옮기고 싶을 때 / 상대: 대화 상대"
            + " / 의도: 새로운 주제로 옮기고 싶음을 알리기 / 표현: 나 다른 이야기를 해도 돼?";

    @Test
    void 후보_목표는_의도와_쓰이는_때를_싣고_표현은_버린다() {
        String action = PracticeInstructions.goalAction("화제 전환 알리기", CANDIDATE_CRITERIA);
        assertThat(action).startsWith("새로운 주제로 옮기고 싶음을 알리기")
                .contains("쓰이는 때: 이어지던 대화가 마무리되기 전에 다른 주제로 옮기고 싶을 때")
                .contains("상대: 대화 상대")
                .doesNotContain("다른 이야기를 해도 돼");
    }

    @Test
    void 의도가_없는_기준은_목표_이름을_쓴다() {
        assertThat(PracticeInstructions.goalAction("부탁하기", "원하는 것을 말로 요청한다")).isEqualTo("부탁하기");
        assertThat(PracticeInstructions.goalAction("부탁하기", null)).isEqualTo("부탁하기");
    }

    @Test
    void 사전_연습_지시는_목표_행동을_앞에_두고_정답_표현을_싣지_않는다() {
        var scene = PracticeScriptReader.read(PracticeScriptFixtures.fullScript(UUID.randomUUID())).baseScene();
        for (boolean retry : new boolean[] {false, true}) {
            String instruction = PracticeInstructions.preparation("화제 전환 알리기", CANDIDATE_CRITERIA, scene, retry);
            assertThat(instruction).contains("목표 행동: 새로운 주제로 옮기고 싶음을 알리기")
                    .contains("다른 종류의 말하기(부탁하기 등)로 바꾸지 마")
                    .doesNotContain("다른 이야기를 해도 돼")
                    .doesNotContain("물건·활동");
            assertThat(instruction.codePointCount(0, instruction.length())).isLessThanOrEqualTo(PracticeInstructions.MAX_LENGTH);
        }
    }
}
