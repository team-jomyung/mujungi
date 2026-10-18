package kr.mujoong.realtime.voice;

import java.util.Objects;
import java.util.UUID;
import kr.mujoong.realtime.practicelegacy.PracticePorts.OutputKind;

/**
 * 승인된 연습 발화. 상관 식별자는 서버 안에만 남긴다.
 *
 * <p>{@code generated}가 참이면 GPT-Live가 그 자리에서 만드는 역할 발화의 슬롯이다(U12 D6 · 기능 설계 3.5). 이때 {@code partnerRole}은
 * {@code PEER}/{@code COACH}, {@code text}는 서버가 만든 지시문 템플릿 문장이며(아이 발화 · 모델 출력 원문이 아니다) 원문 일치로 제브를 건너뛸 수
 * 없다({@link #matches}는 언제나 거짓).
 */
public record PracticeSpeech(UUID correlationId, UUID scenarioId, long generation,
        OutputKind kind, String partnerRole, String text, boolean generated) {

    /** U12 이전 모양(승인 발화). 기존 호출처 · 시험이 그대로 쓴다. */
    public PracticeSpeech(UUID correlationId, UUID scenarioId, long generation, OutputKind kind, String partnerRole, String text) {
        this(correlationId, scenarioId, generation, kind, partnerRole, text, false);
    }

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

    /**
     * U12 D6: 생성형 역할 발화 슬롯 만들기. 표지 종류는 코치 발화면 CUE, 친구 발화면 SCENARIO다(재생 증거 표지로만 쓰이고 제브 요청 · 기록에
     * 영향을 주지 않는다).
     */
    public static PracticeSpeech generatedSlot(UUID correlationId, UUID scenarioId, long generation, String role, String instructionText) {
        if (!"PEER".equals(role) && !"COACH".equals(role)) throw new IllegalArgumentException("연습 출력 형식 오류");
        return new PracticeSpeech(correlationId, scenarioId, generation,
                "COACH".equals(role) ? OutputKind.CUE : OutputKind.SCENARIO, role, instructionText, true);
    }

    /** 생성형 슬롯은 원문 일치로 제브를 건너뛸 수 없다(항상 거짓). */
    public boolean matches(String actual) {
        return !generated && sameWithoutAsciiWhitespace(text, actual);
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
