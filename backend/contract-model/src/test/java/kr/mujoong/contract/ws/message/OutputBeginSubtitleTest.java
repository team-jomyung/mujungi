package kr.mujoong.contract.ws.message;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** OUTPUT_BEGIN.text(기기 자막) 다듬기 · 검증 · 로그 숨김. */
@Tag("device-subtitle")
class OutputBeginSubtitleTest {

    private static final UUID PLAYBACK_ID = UUID.fromString("00000000-0000-4000-8000-000000000080");

    @Test
    void 빈_글은_필드를_만들지_않는다() {
        assertThat(OutputBegin.subtitle(null)).isNull();
        assertThat(OutputBegin.subtitle("   ")).isNull();
        assertThat(JsonCodec.shared().write(new OutputBegin(PLAYBACK_ID, OutputBegin.Source.GPT_LIVE, null))).doesNotContain("text");
    }

    @Test
    void 앞뒤_공백을_지우고_그대로_싣는다() {
        assertThat(OutputBegin.subtitle("  같이 놀자고 말해 볼까?  ")).isEqualTo("같이 놀자고 말해 볼까?");
    }

    @Test
    void 긴_글은_코드_포인트_경계에서_500자로_자른다() {
        String emoji = "😀";
        String longText = emoji.repeat(OutputBegin.TEXT_MAX_CHARS + 20);
        String cut = OutputBegin.subtitle(longText);
        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(OutputBegin.TEXT_MAX_CHARS);
        assertThat(Character.isHighSurrogate(cut.charAt(cut.length() - 1))).isFalse();
        assertThat(new OutputBegin(PLAYBACK_ID, OutputBegin.Source.GPT_LIVE, cut).problems()).isEmpty();
    }

    @Test
    void 계약보다_긴_글이나_빈_글은_문제로_본다() {
        String tooLong = "가".repeat(OutputBegin.TEXT_MAX_CHARS + 1);
        assertThat(new OutputBegin(PLAYBACK_ID, OutputBegin.Source.GPT_LIVE, tooLong).problems()).contains("text:invalid");
        assertThat(new OutputBegin(PLAYBACK_ID, OutputBegin.Source.GPT_LIVE, "").problems()).contains("text:invalid");
    }

    @Test
    void 문자열_표현에는_글을_넣지_않는다() {
        String shown = new OutputBegin(PLAYBACK_ID, OutputBegin.Source.GPT_LIVE, "하늘이 비밀 이야기").toString();
        assertThat(shown).doesNotContain("하늘이").contains("textChars=10");
    }
}
