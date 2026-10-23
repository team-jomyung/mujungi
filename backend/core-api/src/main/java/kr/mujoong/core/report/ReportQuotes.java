package kr.mujoong.core.report;

import static kr.mujoong.core.result.ResultData.*;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 리포트 인용 한 줄을 저장 전사 발화에서 만든다(contracts/ai-io.md 6.5, 편차 FT-1023-c).
 *
 * <p>인용 글은 전사 원문에서 앞머리 문장부호 잡음("..", "?", "]" 등) · 뒤끝 괄호 잡음 · 대괄호 비언어 표지("[cry", "[hum]" 등)만
 * 걷어 낸 글이다. 저장 전사 원문은 바꾸지 않는다. 표지가 앞뒤에만 있으면 결과는 원문의 부분 문자열이다.
 * 걷어 낸 뒤 글자가 없거나 추임새("음" 등)뿐이면 인용 후보가 아니다.
 */
public final class ReportQuotes {
    public record Quote(int activityNo, UUID sessionId, long utteranceSeq, String quote, Instant at, String source) {
        public ReportRepository.Citation citation() {
            return new ReportRepository.Citation(sessionId, utteranceSeq, quote, at, source);
        }

        @Override
        public String toString() {
            return "Quote[activityNo=" + activityNo + ", utteranceSeq=" + utteranceSeq + "]";
        }
    }

    /** 전사기가 붙이는 비언어 표지: 닫힌 대괄호 덩어리("[hum]" · "[웃음]")와 닫는 괄호가 잘린 영문 표지("[cry"). */
    private static final Pattern MARKER = Pattern.compile("\\s*(\\[[^\\]\\s]{0,20}]|\\[\\p{IsLatin}*)\\s*");
    /** 앞머리: 글자 · 숫자가 나오기 전의 모든 기호 · 공백. */
    private static final Pattern LEADING = Pattern.compile("^[^\\p{L}\\p{N}]+");
    /** 뒤끝: 괄호 · 구분 기호 · 공백. 문장 끝 부호(. ? ! ~ …)는 말의 일부라 남긴다. */
    private static final Pattern TRAILING = Pattern.compile("[\\s\\[\\](){}<>,;:·|/\\\\_-]+$");
    /** 내용 없는 추임새. 대답("응" · "네")은 내용으로 본다. */
    private static final Set<String> FILLERS = Set.of("음", "음음", "으음", "흠", "흐음", "어", "어어", "으", "으으", "아", "아아", "에", "에에", "엄", "읍");

    private ReportQuotes() {}

    /** 인용 글. 내용이 없으면 빈 글이다. */
    public static String clean(String text) {
        if (text == null) {
            return "";
        }
        String value = MARKER.matcher(text).replaceAll(" ");
        value = LEADING.matcher(value).replaceFirst("");
        value = TRAILING.matcher(value).replaceFirst("");
        var words = Arrays.stream(value.split("[^\\p{L}\\p{N}]+")).filter(w -> !w.isEmpty()).toList();
        if (words.isEmpty() || FILLERS.containsAll(words)) {
            return "";
        }
        return value;
    }

    /** 인용 글의 어절이 3개 이상인 발화(내용 있는 발화, BR5.5 정의를 인용 글에 적용). */
    static boolean meaningful(String text) {
        String value = clean(text);
        return !value.isEmpty() && value.split("\\s+").length >= 3;
    }

    /** 저장 전사의 발화 한 줄을 인용으로 바꾼다. 발화 시각을 읽을 수 없으면 활동 목록 시각을 쓴다. */
    static Quote quote(ReportInput input, Map<String, Object> utterance, String source) {
        return new Quote(input.activityNo(), input.record().sessionId(), number(utterance, "seq"), clean(text(utterance, "text")),
                at(utterance, input.record().listedAt()), source);
    }

    private static Instant at(Map<String, Object> u, Instant fallback) {
        try {
            return Instant.parse(text(u, "startedAt"));
        } catch (RuntimeException e) {
            // 시각이 없거나 읽을 수 없는 발화는 활동 목록 시각으로 둔다.
            return fallback;
        }
    }
}
