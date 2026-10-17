package kr.mujoong.core.textai;

import java.util.List;
import java.util.Set;

/** J7: 자유 사유나 생성물의 자기 승인을 받지 않는 별도 검토 결과. */
public record ScenarioReview(String verdict, List<String> reasons) {
    public static final Set<String> REASONS = Set.of("ANSWER_INCLUDED", "EARLY_CUE", "PRESSURE",
            "AGE_MISMATCH", "GOAL_MISMATCH", "REPETITION", "FORBIDDEN_CONTENT", "INCOMPLETE_SCENE",
            "INVARIANT_MISMATCH", "SKILL_MISMATCH", "TRANSFER_MISMATCH", "UNCHANGED_VARIANT");
    public ScenarioReview {
        if (!Set.of("APPROVE", "REJECT", "UNCERTAIN").contains(verdict) || reasons == null
                || reasons.stream().anyMatch(reason -> !REASONS.contains(reason))
                || reasons.stream().distinct().count() != reasons.size()
                || ("APPROVE".equals(verdict) && !reasons.isEmpty()))
            throw new IllegalArgumentException("invalid scenario review");
        reasons = List.copyOf(reasons);
    }
    public boolean approved() { return "APPROVE".equals(verdict) && reasons.isEmpty(); }
}
