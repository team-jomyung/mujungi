package kr.mujoong.realtime.guardrail;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 제브 요청 구성 (U13 FR2.2 · BR2.4 · BR2.5, 순수 함수). 후보 문장과 익명화한 최근 맥락 3턴 이하만 싣는다.
 *
 * <ul>
 *   <li>아이 이름은 '아이'로 바꾼다. 이름이 여럿이거나 서로 포함해도 긴 이름부터 한 번만 훑으며 바꾼다(바꿔 넣은 '아이'를 다시 바꾸지 않는다).
 *   <li>아동 · 보호자 · 세션 · 기기 식별자와 키, Luna 생성물, U6 결과는 입력으로 받지도 않는다 — {@link GuardrailRequest}에 그 칸이 없다.
 *   <li>후보 문장과 맥락 안의 지시문은 데이터 필드의 문자열 값일 뿐 시스템 지시(정책 리소스의 {@code systemPrompt})에 섞이지 않는다. 이 클래스는
 *       시스템 지시를 만지지 않는다.
 *   <li>후보 문장이 1 ~ {@value GuardrailRequest#CANDIDATE_MAX}자를 벗어나면(이름 치환 뒤 길이) 요청을 만들지 않는다 — 호출자는 이를 막힘
 *       ({@code INVALID_REQUEST})으로 센다. 맥락 한 줄은 {@value GuardrailRequest#CONTEXT_TEXT_MAX}자까지만 남긴다.
 * </ul>
 */
public final class GuardrailRequestFactory {

    /** 아이 이름을 바꿔 넣는 말. */
    public static final String CHILD_ALIAS = "아이";

    /**
     * 대화 기록에서 골라 온 원본 한 턴(아직 이름 치환 · 길이 정리 전). 정리된 값은 {@link GuardrailRequest.ContextTurn}이다.
     *
     * @param text 원문(null이면 건너뛴다)
     */
    public record Turn(GuardrailRequest.Speaker speaker, String text) {

        /** 문자열 표현에 원문을 내지 않는다(R-F14). */
        @Override
        public String toString() {
            return "Turn[speaker=" + speaker + ", chars=" + (text == null ? 0 : text.length()) + "]";
        }
    }

    /**
     * 요청 하나를 만든다.
     *
     * @param recentTurns 최근 대화(오래된 것부터). 비어 있지 않은 마지막 {@value GuardrailRequest#CONTEXT_MAX_TURNS}턴만 쓴다
     * @param childNames 바꿀 아이 이름들(빈 이름은 무시)
     * @return 만들 수 없으면(후보 문장 길이 · 정책 판 길이) 빈 값
     */
    public Optional<GuardrailRequest> create(GuardrailRequest.Mode mode, String candidate, List<Turn> recentTurns,
            String policyVersion, Collection<String> childNames) {
        Objects.requireNonNull(mode, "mode");
        if (candidate == null || policyVersion == null || policyVersion.isEmpty()
                || policyVersion.length() > GuardrailEventRecord.POLICY_VERSION_MAX) {
            return Optional.empty();
        }
        Pattern names = namePattern(childNames);
        String replaced = replace(names, candidate.strip());
        if (replaced.isEmpty() || replaced.length() > GuardrailRequest.CANDIDATE_MAX) {
            return Optional.empty();
        }
        return Optional.of(GuardrailRequest.of(mode, replaced, policyVersion, context(names, recentTurns)));
    }

    private static List<GuardrailRequest.ContextTurn> context(Pattern names, List<Turn> recentTurns) {
        if (recentTurns == null || recentTurns.isEmpty()) {
            return List.of();
        }
        List<GuardrailRequest.ContextTurn> kept = new ArrayList<>();
        for (Turn turn : recentTurns) {
            if (turn == null || turn.speaker() == null || turn.text() == null) {
                continue;
            }
            String text = cut(replace(names, turn.text().strip()), GuardrailRequest.CONTEXT_TEXT_MAX);
            if (!text.isEmpty()) {
                kept.add(new GuardrailRequest.ContextTurn(turn.speaker(), text));
            }
        }
        int from = Math.max(0, kept.size() - GuardrailRequest.CONTEXT_MAX_TURNS);
        return List.copyOf(kept.subList(from, kept.size()));
    }

    /** 긴 이름부터(같은 길이면 사전순) 고르는 한 번 훑기 패턴. 이름이 없으면 null. */
    private static Pattern namePattern(Collection<String> childNames) {
        if (childNames == null) {
            return null;
        }
        List<String> sorted = childNames.stream().filter(name -> name != null && !name.isBlank()).map(String::strip).distinct()
                .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder())).toList();
        if (sorted.isEmpty()) {
            return null;
        }
        return Pattern.compile(String.join("|", sorted.stream().map(Pattern::quote).toList()));
    }

    private static String replace(Pattern names, String text) {
        if (names == null) {
            return text;
        }
        Matcher matcher = names.matcher(text);
        return matcher.replaceAll(Matcher.quoteReplacement(CHILD_ALIAS));
    }

    /** 글자 수 상한으로 자른다(짝을 이루는 글자 가운데에서 끊지 않는다). */
    private static String cut(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int end = max;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end).stripTrailing();
    }
}
