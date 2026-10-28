package kr.mujoong.core.practicegoal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 목표 후보 입력에서 AI가 같은 말을 되풀이한 구간을 뺀다(FT-1027-i). AI 발화가 바로 앞 AI 발화와 같은 문장이면, 되풀이된 그 문장의 첫 발화부터
 * 다음 다른 AI 발화 전까지 AI 발화와 그 사이 아이 발화를 뺀다. 그 구간의 아이 반응은 AI 쪽 문제 뒤의 반응이라 근거로 쓰지 않고, 남겨 두면 모델이 앞 대목의
 * 지원 필요까지 함께 제외한다(docs/goal-candidate-rubric-criteria.md 9절).
 */
public final class RepeatedAiSpan {
    private static final Pattern LEADING_FILLER = Pattern.compile("^\\s*(음|응|어)[\\s,.]+");
    private static final Pattern SPACING = Pattern.compile("[\\s.,!?~]+");

    private RepeatedAiSpan() {
    }

    public static List<Map<String, Object>> without(List<Map<String, Object>> transcript) {
        List<Integer> ai = new ArrayList<>();
        for (int i = 0; i < transcript.size(); i++) {
            if ("AI".equals(transcript.get(i).get("speaker"))) {
                ai.add(i);
            }
        }
        Set<Integer> dropped = new HashSet<>();
        for (int k = 0; k + 1 < ai.size(); k++) {
            String repeated = normalized(transcript.get(ai.get(k)));
            if (repeated.isEmpty() || !repeated.equals(normalized(transcript.get(ai.get(k + 1))))) {
                continue;
            }
            int end = transcript.size();
            for (int j = k + 2; j < ai.size(); j++) {
                if (!repeated.equals(normalized(transcript.get(ai.get(j))))) {
                    end = ai.get(j);
                    break;
                }
            }
            for (int i = ai.get(k); i < end; i++) {
                dropped.add(i);
            }
        }
        if (dropped.isEmpty()) {
            return transcript;
        }
        List<Map<String, Object>> kept = new ArrayList<>();
        for (int i = 0; i < transcript.size(); i++) {
            if (!dropped.contains(i)) {
                kept.add(transcript.get(i));
            }
        }
        return List.copyOf(kept);
    }

    private static String normalized(Map<String, Object> row) {
        Object text = row.get("text");
        if (!(text instanceof String value)) {
            return "";
        }
        return SPACING.matcher(LEADING_FILLER.matcher(value).replaceFirst("")).replaceAll("");
    }
}
