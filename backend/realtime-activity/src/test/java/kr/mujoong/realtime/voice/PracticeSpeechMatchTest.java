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

    /**
     * 도입은 기회 문장을 담은 장면 설명 원문 그대로 재생한다. 승인 원문의 일부(기회 문장을 뺀 앞부분)는 승인 발화가 아니므로 원문 일치가 되지
     * 않는다 — 제브 생략 근거를 부분 문자열로 넓히지 않는다.
     */
    @Test
    void aWholeSceneDescriptionMatchesOnlyAsAWholeAndItsTrimmedPartDoesNot() {
        String description = "블록 놀이 중이야. 친구가 멈추려 해. 이제 그만할래.";
        PracticeSpeech intro = PracticeSpeech.scenario(UUID.randomUUID(), UUID.randomUUID(), 0, "친한 친구", description);

        assertThat(intro.matches("블록 놀이 중이야.\n친구가  멈추려 해. 이제 그만할래.")).as("공백만 다르면 일치").isTrue();
        assertThat(intro.matches("블록 놀이 중이야. 친구가 멈추려 해.")).as("기회 문장을 뺀 앞부분은 승인 원문이 아니다").isFalse();
        assertThat(intro.matches("이제 그만할래.")).as("기회 문장만도 이 슬롯의 원문이 아니다").isFalse();
        assertThat(intro.matches("블록 놀이 중이야, 친구가 멈추려 해. 이제 그만할래.")).as("구두점이 다르면 불일치(규칙 무변경)").isFalse();
    }
}
