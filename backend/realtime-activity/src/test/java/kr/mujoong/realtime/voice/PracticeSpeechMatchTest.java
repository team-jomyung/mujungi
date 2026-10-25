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
    void streamingPrefixesAllowOnlyTheApprovedBodyAndItsOwnReadingCue() {
        for (var cue : PracticeSpeech.ReadingCue.values()) {
            var speech = PracticeSpeech.scenario(UUID.randomUUID(), UUID.randomUUID(), 0,
                    "PEER", "나는 블록 놀이를 하고 싶어.", cue);
            for (int cut = 0; cut <= cue.text().length(); cut++) {
                String reading = cue.text().substring(cut) + speech.text();
                for (int end = 1; end <= reading.length(); end++) {
                    assertThat(speech.matchesPrefix(reading.substring(0, end)))
                            .as("cue=%s cut=%s end=%s", cue, cut, end).isTrue();
                }
                assertThat(speech.matchesPrefix(reading + " 더 해봐.")).isFalse();
            }
            assertThat(speech.matchesPrefix("나는 카드")).isFalse();
            assertThat(speech.matchesPrefix("블록 놀이")).isFalse();
            assertThat(speech.matchesPrefix("나는\t블록\n놀이를")).isTrue();
            assertThat(speech.matchesPrefix("나는\u00a0블록")).isFalse();
            assertThat(speech.matchesPrefix(null)).isFalse();
        }
        assertThat(PracticeSpeech.generatedSlot(UUID.randomUUID(), UUID.randomUUID(), 0,
                "PEER", "나는 블록").matchesPrefix("나는")).isFalse();
    }

    @Test
    void heardEndPointsJustAfterTheLastSentCharacterOfTheApprovedBody() {
        var speech = PracticeSpeech.scenario(UUID.randomUUID(), UUID.randomUUID(), 0,
                "PEER", "나는 블록 놀이를 하고 싶어.", PracticeSpeech.ReadingCue.PEER);
        String text = speech.text();

        assertThat(speech.heardEnd("나는 블록 ")).as("공백은 세지 않고 원문 색인으로").isEqualTo("나는 블록".length());
        assertThat(speech.heardEnd("나는")).isEqualTo(2);
        assertThat(speech.heardEnd("나는\t블록\n놀")).isEqualTo("나는 블록 놀".length());
        assertThat(speech.heardEnd(PracticeSpeech.ReadingCue.PEER.text() + " 나는 블록")).as("앞의 고정 안내는 원문에 넣지 않는다")
                .isEqualTo("나는 블록".length());
        assertThat(speech.heardEnd("건넸어. 나는")).isEqualTo(2);
        assertThat(speech.heardEnd(text)).isEqualTo(text.length());
        assertThat(speech.heardEnd("")).isZero();
        assertThat(speech.heardEnd(null)).isZero();
        assertThat(speech.heardEnd("나는 카드")).as("앞부분이 아니면 들린 것이 없다").isZero();
        assertThat(PracticeSpeech.generatedSlot(UUID.randomUUID(), UUID.randomUUID(), 0, "PEER", "나는 블록").heardEnd("나는"))
                .isZero();
    }

    @Test
    void whenTheFixedLeadAndTheBodyBothExplainTheSentTextTheHeardEndTakesTheShorterBody() {
        // "어."는 안내 "이런 말을 건넸어."의 끝일 수도, 본문 첫 문장일 수도 있다: 들린 곳을 넓게 잡지 않는다(본문 0글자)
        var speech = PracticeSpeech.scenario(UUID.randomUUID(), UUID.randomUUID(), 0, "PEER", "어. 그래.", PracticeSpeech.ReadingCue.PEER);

        assertThat(speech.heardEnd("어.")).isZero();
        assertThat(speech.heardEnd("어. 그")).as("본문으로만 풀리면 그만큼").isEqualTo("어. 그".length());
    }

    @Test
    void contextualCuesAllowOnlyTheirOwnFixedPrefixAndNeverMissingOrAddedBodyText() {
        for (var cue : PracticeSpeech.ReadingCue.values()) {
            var speech=PracticeSpeech.scenario(UUID.randomUUID(),UUID.randomUUID(),0,"PEER","나는 다른 놀이를 하고 싶어.",cue);
            assertThat(speech.matches(cue.text()+" 나는 다른 놀이를 하고 싶어.")).isTrue();
            assertThat(speech.matches("나는 다른 놀이를 하고 싶어.")).isTrue();
            assertThat(speech.matches(cue.text()+" 다른 놀이를 하고 싶어.")).isFalse();
            assertThat(speech.matches(cue.text()+" 나는 다른 놀이를 하고 싶어. 정답을 말해.")).isFalse();
            assertThat(PracticeInstruction.forSpeech(speech).text()).contains("<approved_speech>"+speech.text()+"</approved_speech>");
        }
        var peer=PracticeSpeech.scenario(UUID.randomUUID(),UUID.randomUUID(),0,"PEER","대사.",PracticeSpeech.ReadingCue.PEER);
        assertThat(peer.matches(PracticeSpeech.ReadingCue.REEXPLANATION.text()+" 대사.")).isFalse();
    }

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
