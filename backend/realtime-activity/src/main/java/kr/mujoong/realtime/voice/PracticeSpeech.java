package kr.mujoong.realtime.voice;

import java.util.Objects;
import java.util.UUID;
import kr.mujoong.realtime.practice.PracticePorts.OutputKind;

/** 승인된 연습 발화. 상관 식별자는 서버 안에만 남긴다. */
public record PracticeSpeech(UUID correlationId, UUID scenarioId, long generation,
        OutputKind kind, String partnerRole, String text) {
    public PracticeSpeech {
        Objects.requireNonNull(correlationId); Objects.requireNonNull(scenarioId); Objects.requireNonNull(kind);
        validate(partnerRole, 200); validate(text, 10201);
        if (generation < 0) throw new IllegalArgumentException("연습 출력 세대 오류");
    }
    private static void validate(String text, int max) {
        if (text == null || text.isBlank() || text.length() > max
                || text.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t' && c != '\r')) {
            throw new IllegalArgumentException("연습 출력 형식 오류");
        }
    }
    /**
     * U12: 대본의 승인 문장(상황 · 기회 문장 · 상황 다시 설명) 승인 발화 만들기. 새 연습 흐름이 레거시 {@code practice} 패키지의
     * {@link OutputKind}를 직접 가리키지 않게 하는 얇은 공장이다(BR10.2). 재생 증거 표지는 SCENARIO다.
     */
    public static PracticeSpeech scenario(UUID correlationId, UUID scenarioId, long generation, String partnerRole, String text) {
        return new PracticeSpeech(correlationId, scenarioId, generation, OutputKind.SCENARIO, partnerRole, text);
    }

    public boolean matches(String actual) {
        return sameWithoutAsciiWhitespace(text, actual);
    }

    /**
     * 승인 원문과 실제 전사가 공백만 빼고 정확히 같은가(U5b 규칙, U8 LiveContentRegistry도 같은 규칙을 쓴다 — NFR2.1 · SD6). 정규식
     * {@code \s}는 ASCII 공백(space · \t · \n · \x0B · \f · \r)만이라 NBSP · 전각 공백 · 제로폭 문자가 끼면 다르다(안전 쪽).
     */
    public static boolean sameWithoutAsciiWhitespace(String approved, String actual) {
        return approved != null && actual != null && approved.replaceAll("\\s+", "").equals(actual.replaceAll("\\s+", ""));
    }
    @Override public String toString() { return "PracticeSpeech[kind=" + kind + ", generation=" + generation + "]"; }
}
