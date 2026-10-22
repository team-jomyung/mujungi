package kr.mujoong.realtime.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import kr.mujoong.realtime.practicelegacy.PracticePorts.OutputKind;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * U5b 원문 일치 규칙을 U8 LiveContentRegistry와 나눠 쓰는 {@link PracticeSpeech#sameWithoutAsciiWhitespace} (U8 NFR2.1 · SD6). 꺼낸 정적
 * 비교가 기존 {@link PracticeSpeech#matches}와 같은 답을 내고, ASCII 공백만 무시하며, 레코드 검증은 그대로인지 본다.
 */
@Tag("live-content")
class PracticeSpeechMatchTest {

    private static final String TEXT = "다시 친구 역할로 돌아갈게";

    @Test
    void onlyFixedReadingLeadMayPrecedeTheEntireApprovedBody() {
        var speech = PracticeSpeech.scenario(UUID.randomUUID(), UUID.randomUUID(), 0, "친구", "이제 그만하자");
        assertThat(speech.text()).isEqualTo("이제 그만하자.");
        assertThat(speech.matches("음, 이제 들어봐. 이제 그만하자.")).isTrue();
        assertThat(speech.matches("들어봐. 이제 그만하자.")).isTrue();
        assertThat(speech.matches("이제 그만하자.")).isTrue();
        assertThat(speech.matches("그만하자.")).isFalse();
        assertThat(speech.matches("정답은 이거야. 이제 그만하자.")).isFalse();
        assertThat(speech.matches("음, 이제 들어봐. 이제 그만하자. 더 해봐.")).isFalse();
        assertThat(PracticeSpeech.generatedSlot(UUID.randomUUID(),UUID.randomUUID(),0,"PEER","지시").matches("음, 이제 들어봐. 지시")).isFalse();
    }

    @Test
    void theSharedRuleIgnoresOnlyAsciiWhitespaceAndAgreesWithMatches() {
        PracticeSpeech speech = new PracticeSpeech(UUID.randomUUID(), UUID.randomUUID(), 0, OutputKind.SCENARIO, "친구", TEXT);

        for (String actual : new String[] {"다시친구역할로돌아갈게", " 다시\t친구\n역할로\r돌아갈게\f", "다시\u000B친구 역할로 돌아갈게"}) {
            assertThat(PracticeSpeech.sameWithoutAsciiWhitespace(TEXT, actual)).as(actual).isTrue();
            assertThat(speech.matches(actual)).isTrue();
        }
        for (String actual : new String[] {"다시 친구 역할로 돌아갈게", "다시　친구 역할로 돌아갈게", "다시​친구 역할로 돌아갈게",
                "다시 친구 역할로 돌아갈게.", null}) {
            assertThat(PracticeSpeech.sameWithoutAsciiWhitespace(TEXT, actual)).as(String.valueOf(actual)).isFalse();
            assertThat(speech.matches(actual)).isFalse();
        }
        assertThat(PracticeSpeech.sameWithoutAsciiWhitespace(null, TEXT)).isFalse();
    }

    @Test
    void theRecordStillRefusesBadSpeechAndHidesItsText() {
        assertThatThrownBy(() -> new PracticeSpeech(UUID.randomUUID(), UUID.randomUUID(), 0, OutputKind.CUE, "친구", " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PracticeSpeech(UUID.randomUUID(), UUID.randomUUID(), -1, OutputKind.CUE, "친구", TEXT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PracticeSpeech(UUID.randomUUID(), UUID.randomUUID(), 0, OutputKind.CUE, "친구", "a\u0007b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PracticeSpeech(UUID.randomUUID(), UUID.randomUUID(), 0, OutputKind.CUE, "가".repeat(201), TEXT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new PracticeSpeech(UUID.randomUUID(), UUID.randomUUID(), 0, OutputKind.CUE, "친구", TEXT).toString())
                .doesNotContain("돌아갈게");
    }
}
