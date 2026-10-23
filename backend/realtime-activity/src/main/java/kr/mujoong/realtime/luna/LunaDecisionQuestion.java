package kr.mujoong.realtime.luna;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Decisions API {@code choice} 질문 한 개의 이름과 선택지 — realtime (FT-1023-d). 질문 지시문은 {@link LunaRequest#instructions()}가 맡고
 * (요청 {@code questions[0].instructions} 자리), 데이터는 {@link LunaRequest#input()}이 맡는다(지시와 데이터 분리, U9 BR2.5). 선택지 뜻은 판
 * 리소스의 고정 글이고 아이 데이터가 아니다. 문자열 표현은 선택지 글을 숨긴다(BR6.2).
 *
 * @param name 질문 이름(응답 {@code answers[].name}과 맞춘다)
 * @param choices 보낼 선택지(판 순서 그대로, 중복 없음)
 */
public record LunaDecisionQuestion(String name, List<Choice> choices) {

    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    public LunaDecisionQuestion {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("질문 이름 형식이 올바르지 않다");
        }
        Objects.requireNonNull(choices, "choices");
        choices = List.copyOf(choices);
        if (choices.isEmpty() || new LinkedHashSet<>(values(choices)).size() != choices.size()) {
            throw new IllegalArgumentException("선택지는 비지 않고 중복이 없어야 한다");
        }
    }

    /** 선택지 값(순서 그대로). */
    public List<String> values() {
        return values(choices);
    }

    private static List<String> values(List<Choice> choices) {
        return choices.stream().map(Choice::value).toList();
    }

    @Override
    public String toString() {
        return "LunaDecisionQuestion[name=" + name + ", choices=" + choices.size() + "]";
    }

    /**
     * 선택지 하나.
     *
     * @param value 선택지 코드(판의 코드 그대로)
     * @param description 선택지 뜻(없으면 null, 요청에서 뺀다)
     */
    public record Choice(String value, String description) {

        public Choice {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("선택지 값이 비었다");
            }
            description = description == null || description.isBlank() ? null : description;
        }

        @Override
        public String toString() {
            return "Choice[" + value + "]";
        }
    }
}
