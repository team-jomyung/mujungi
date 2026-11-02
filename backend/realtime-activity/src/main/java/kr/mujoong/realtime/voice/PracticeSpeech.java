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
        OutputKind kind, String partnerRole, String text, boolean generated, ReadingCue readingCue) {

    /** 출력 경계에서 앞부분이 잘려도 본문을 온전히 받기 위한 서버 고정 안내. */
    public static final String READING_LEAD = "음, 이제 들어봐.";

    /** Server-owned contextual cues buffer the provider boundary without repeating the same instruction. */
    public enum ReadingCue {
        LEGACY(READING_LEAD), SCENE("지금부터 상상 속 상황이야."), PEER("이런 말을 건넸어."),
        REEXPLANATION("다시 설명해 줄게."), ROLE_NOTICE("잠깐 안내해 줄게.");
        private final String text;
        ReadingCue(String text) { this.text = text; }
        public String text() { return text; }
    }

    public PracticeSpeech(UUID correlationId, UUID scenarioId, long generation, OutputKind kind, String partnerRole, String text, boolean generated) {
        this(correlationId, scenarioId, generation, kind, partnerRole, text, generated, ReadingCue.LEGACY);
    }

    /** U12 이전 모양(승인 발화). 기존 호출처 · 시험이 그대로 쓴다. */
    public PracticeSpeech(UUID correlationId, UUID scenarioId, long generation, OutputKind kind, String partnerRole, String text) {
        this(correlationId, scenarioId, generation, kind, partnerRole, text, false);
    }

    public PracticeSpeech {
        Objects.requireNonNull(correlationId); Objects.requireNonNull(scenarioId); Objects.requireNonNull(kind); Objects.requireNonNull(readingCue);
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
     * {@link OutputKind}를 직접 가리키지 않게 하는 얇은 공장이다. 재생 증거 표지는 SCENARIO다.
     */
    public static PracticeSpeech scenario(UUID correlationId, UUID scenarioId, long generation, String partnerRole, String text) {
        return new PracticeSpeech(correlationId, scenarioId, generation, OutputKind.SCENARIO, partnerRole, sentence(text));
    }

    public static PracticeSpeech scenario(UUID correlationId, UUID scenarioId, long generation, String partnerRole, String text, ReadingCue cue) {
        return new PracticeSpeech(correlationId, scenarioId, generation, OutputKind.SCENARIO, partnerRole, sentence(text), false, cue);
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
        if (generated || actual == null) return false;
        if (sameWithoutAsciiWhitespace(text, actual)) return true;
        String body = text.replaceAll("\\s+", "");
        String received = actual.replaceAll("\\s+", "");
        if (!received.endsWith(body)) return false;
        String prefix = received.substring(0, received.length() - body.length());
        String lead = readingCue.text().replaceAll("\\s+", "");
        // 본문은 완전 일치해야 한다. 앞에 올 수 있는 것은 서버 고정 안내의 끝부분뿐이다.
        return !prefix.isEmpty() && lead.endsWith(prefix);
    }

    /**
     * 승인 낭독을 조각 단위로 내보낼 때 쓴다(B1): 지금까지 받은 전사가 승인 원문의 앞부분인가. 공백만 무시하고, 앞에는 {@link #matches}처럼 서버 고정
     * 안내의 끝부분만 올 수 있다. 원문 전체와 같은지는 응답이 끝난 뒤 {@link #matches}로 따로 본다. 생성형 슬롯은 언제나 거짓.
     */
    public boolean matchesPrefix(String actual) {
        if (generated || actual == null) return false;
        String received = actual.replaceAll("\\s+", "");
        if (received.isEmpty()) return true;
        String body = text.replaceAll("\\s+", "");
        String lead = readingCue.text().replaceAll("\\s+", "");
        for (int cut = lead.length(); cut >= 0; cut--) {
            String expected = lead.substring(cut) + body;
            if (expected.startsWith(received)) return true;
        }
        return false;
    }

    /** 다르게 읽힌 승인 낭독을 '같은 문장을 조금 다르게 읽음'으로 볼 최소 허용 편집 수. */
    static final int NEAR_READING_MIN_EDITS = 3;
    /** 허용 편집 수를 받은 글자 수의 이 몫만큼 늘린다(받은 글자 8자마다 1). */
    static final int NEAR_READING_CHARS_PER_EDIT = 8;

    /**
     * FT-1101-c: 원문과 다르게 읽힌 승인 낭독(지금까지 받은 전사 전체)이 원문 앞부분을 조금 다르게 읽은 것인가. 공백 · 문장부호를 지우고, 앞에 서버 고정
     * 안내의 끝부분이 와도 되며, 원문의 어느 앞부분과 견줘 편집 수가 {@code max(3, 글자 수 / 8)} 이하면 참이다. 끝맺음 · 조사 차이는 참이고, 다른
     * 승인 문장(장면 안내)을 다시 읽거나 원문 뒤에 말을 덧붙이면 거짓이다. 생성형 슬롯은 언제나 거짓.
     */
    public boolean nearPrefix(String actual) {
        if (generated || actual == null) return false;
        String received = compact(actual);
        if (received.isEmpty()) return true;
        String lead = compact(readingCue.text());
        String expected = lead + compact(text);
        int allowed = Math.max(NEAR_READING_MIN_EDITS, received.length() / NEAR_READING_CHARS_PER_EDIT);
        // 받은 전사와 '안내 끝부분 + 원문 앞부분' 사이 최소 편집 수: 안내 안 어디서 시작해도 비용 0, 원문의 어느 앞부분에서 끝나도 된다
        int[] previous = new int[expected.length() + 1];
        int[] current = new int[expected.length() + 1];
        for (int j = 0; j <= expected.length(); j++) previous[j] = Math.max(0, j - lead.length());
        for (int i = 1; i <= received.length(); i++) {
            current[0] = i;
            int rowMin = current[0];
            for (int j = 1; j <= expected.length(); j++) {
                int substitute = previous[j - 1] + (received.charAt(i - 1) == expected.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitute, Math.min(previous[j] + 1, current[j - 1] + 1));
                rowMin = Math.min(rowMin, current[j]);
            }
            if (rowMin > allowed) return false;
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        int best = Integer.MAX_VALUE;
        for (int j = 0; j <= expected.length(); j++) best = Math.min(best, previous[j]);
        return best <= allowed;
    }

    private static String compact(String text) {
        return text.replaceAll("[\\s\\p{Punct}…“”‘’·~～]", "");
    }

    private static String sentence(String text) {
        if (text == null || text.isBlank()) return text;
        return text.matches("(?s).*[.!?。！？…][\"”’']?$") ? text : text + ".";
    }

    /**
     * 승인 원문과 실제 전사가 공백만 빼고 정확히 같은가(U5b 규칙, U8 LiveContentRegistry도 같은 규칙을 쓴다 — SD6). 정규식
     * {@code \s}는 ASCII 공백(space · \t · \n · \x0B · \f · \r)만이라 NBSP · 전각 공백 · 제로폭 문자가 끼면 다르다(안전 쪽).
     */
    public static boolean sameWithoutAsciiWhitespace(String approved, String actual) {
        return approved != null && actual != null && approved.replaceAll("\\s+", "").equals(actual.replaceAll("\\s+", ""));
    }
    @Override public String toString() { return "PracticeSpeech[kind=" + kind + ", generation=" + generation + "]"; }
}
