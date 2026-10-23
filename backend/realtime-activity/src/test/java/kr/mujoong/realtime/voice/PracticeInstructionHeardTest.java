package kr.mujoong.realtime.voice;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import kr.mujoong.contract.json.JsonCodec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 3회차 V1: 미리 만든 음성으로 아이에게 들린 대본 줄을 다음 생성형 역할 지시 앞에 데이터로 싣는다(contracts/ai-io.md 10.16). 지시가 아니라 데이터임을
 * 밝히는 머리 + JSON이고, 지시문 상한 500자 안에서 최근 줄부터 담으며, 줄이 없거나 하나도 들어가지 않으면 지시문만 보낸다. 원문은 문자열 표현에 없다.
 */
@Tag("practice-flow")
class PracticeInstructionHeardTest {

    private static final String INSTRUCTION = "너는 아이와 같은 모둠 친구다. 아이에게 무엇을 줄지 열린 질문 하나로 묻는다.";

    @Test
    void heardLinesGoInFrontAsDataInTheOrderTheChildHeardThem() {
        List<PracticeInstruction.Heard> heard = List.of(new PracticeInstruction.Heard("무중", "미술 시간이야."),
                new PracticeInstruction.Heard("친구", "자, 여기 초록색!"));

        PracticeInstruction instruction = PracticeInstruction.withHeard(heard, INSTRUCTION);

        assertThat(PracticeInstruction.heardThatFits(heard, INSTRUCTION)).isEqualTo(2);
        assertThat(instruction.text()).startsWith(PracticeInstruction.HEARD_LEAD).endsWith(" " + INSTRUCTION);
        String json = instruction.text().substring(PracticeInstruction.HEARD_LEAD.length(),
                instruction.text().length() - INSTRUCTION.length() - 1);
        assertThat(JsonCodec.shared().read(json, Map.class)).isEqualTo(Map.of("heard", List.of(
                Map.of("speaker", "무중", "speech", "미술 시간이야."), Map.of("speaker", "친구", "speech", "자, 여기 초록색!"))));
        assertThat(instruction.toString()).doesNotContain("초록색");
        assertThat(heard.getFirst().toString()).doesNotContain("미술");
    }

    @Test
    void withoutHeardLinesTheInstructionIsSentAsItIs() {
        assertThat(PracticeInstruction.withHeard(List.of(), INSTRUCTION)).isEqualTo(new PracticeInstruction(INSTRUCTION));
        assertThat(PracticeInstruction.withHeard(null, INSTRUCTION)).isEqualTo(new PracticeInstruction(INSTRUCTION));
        assertThat(PracticeInstruction.heardThatFits(List.of(), INSTRUCTION)).isZero();
    }

    @Test
    void onlyTheNewestLinesThatFitTheFiveHundredLimitAreCarriedAndEachLineIsCut() {
        String line = "가".repeat(120);
        List<PracticeInstruction.Heard> heard = List.of(new PracticeInstruction.Heard("무중", "첫째줄" + line),
                new PracticeInstruction.Heard("친구", "둘째줄" + line), new PracticeInstruction.Heard("무중", "셋째줄" + line),
                new PracticeInstruction.Heard("친구", "넷째줄" + line));

        PracticeInstruction instruction = PracticeInstruction.withHeard(heard, INSTRUCTION);
        int fits = PracticeInstruction.heardThatFits(heard, INSTRUCTION);

        assertThat(instruction.text().codePointCount(0, instruction.text().length())).isLessThanOrEqualTo(PracticeInstruction.MAX_LENGTH);
        assertThat(fits).isBetween(1, 3);
        assertThat(instruction.text()).as("줄마다 80자에서 자른다").doesNotContain("가".repeat(PracticeInstruction.HEARD_LINE_MAX))
                .contains("가".repeat(PracticeInstruction.HEARD_LINE_MAX - "넷째줄".length())).endsWith(" " + INSTRUCTION);
        assertThat(instruction.text()).as("최근 줄부터 담고 오래된 줄을 뺀다").contains("넷째줄").doesNotContain("첫째줄");
        String tooLong = "나".repeat(PracticeInstruction.MAX_LENGTH - 10);
        assertThat(PracticeInstruction.withHeard(heard, tooLong).text()).as("들어갈 자리가 없으면 지시문만").isEqualTo(tooLong);
    }

    @Test
    void controlCharactersInAHeardLineAreFlattenedSoTheInstructionStaysValid() {
        PracticeInstruction instruction = PracticeInstruction.withHeard(
                List.of(new PracticeInstruction.Heard("친구\n", "자,\t여기\u0007초록색!")), INSTRUCTION);

        assertThat(instruction.text()).doesNotContain("\n", "\t", "\u0007").contains("\"speaker\":\"친구\"", "자, 여기 초록색!");
    }
}
