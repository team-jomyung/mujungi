package kr.mujoong.realtime.practiceflow;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PracticeProcedureQuestionTest {
    @ParameterizedTest
    @ValueSource(strings={"무슨 놀이를 하고 싶은지 물어봐야 하는 거야", "이렇게 물어보면 돼?", "뭐라고 대답해야 해?", "어떻게 말해야 해", "어떤 놀이 하고 싶어라고 말하면 되는 거야?"})
    void explicitQuestionsAboutWhatToSayAreNotRolePerformance(String text) {
        assertThat(PracticeProcedureQuestion.matches(text)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings={"무슨 놀이를 하고 싶어?", "어떤 놀이 같이 할까?", "나한테 물어봐도 돼?", "친구가 무슨 말을 하는 거야?", "같이 블록 놀이 하자", "응, 연습하자", "무중아 그만할래"})
    void ordinaryRoleQuestionsAndOtherIntentsAreLeftToTheJudge(String text) {
        assertThat(PracticeProcedureQuestion.matches(text)).isFalse();
    }
}
