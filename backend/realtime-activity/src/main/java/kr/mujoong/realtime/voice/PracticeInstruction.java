package kr.mujoong.realtime.voice;

import java.util.Objects;

/**
 * 연습 확장점이 공급자에 더하는 지시문 한 문장 (U4b BR10.4 · functional-spec 5.1 {@code instructProvider} · SD-20).
 *
 * <p>자유 문자열 대신 이 값만 {@link ProviderRequestFactory#practiceInstruction}에 들어간다. 문장은 확장점(U5b)이 자기 승인된 템플릿에서 만든
 * 것이어야 하고, 여기서는 비어 있지 않음 · 제어 문자 없음 · 길이 상한({@value #MAX_LENGTH}자)만 지킨다. 문장 원문은 문자열 표현 · 로그에 넣지
 * 않는다(BR12.1).
 */
public record PracticeInstruction(String text) {

    /** 지시문 상한(코드 포인트). */
    public static final int MAX_LENGTH = 500;

    public PracticeInstruction {
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new IllegalArgumentException("지시문이 비었다");
        }
        if (text.codePointCount(0, text.length()) > MAX_LENGTH) {
            throw new IllegalArgumentException("지시문이 " + MAX_LENGTH + "자를 넘는다");
        }
        if (text.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("지시문에 제어 문자가 있다");
        }
    }

    /** 원문은 문자열 표현에 넣지 않는다. */
    @Override
    public String toString() {
        return "PracticeInstruction[length=" + text.length() + "]";
    }
}
