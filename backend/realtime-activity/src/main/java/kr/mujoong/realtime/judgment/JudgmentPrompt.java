package kr.mujoong.realtime.judgment;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 판정 지시문과 그 판 번호 (U11 BR1.1). 지시문은 요청 {@code instructions} 자리로만 가고 아이 발화 같은 데이터는 섞지 않는다(BR1.8 U9).
 * 판 번호는 {@code LunaRequest.promptVersion} 형식을 따른다.
 */
public record JudgmentPrompt(String version, String instructions) {

    private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    public JudgmentPrompt {
        if (version == null || !VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("promptVersion 형식이 올바르지 않다");
        }
        Objects.requireNonNull(instructions, "instructions");
        if (instructions.isBlank()) {
            throw new IllegalArgumentException("판정 지시문이 비었다");
        }
    }

    @Override
    public String toString() {
        return "JudgmentPrompt[version=" + version + "]";
    }
}
