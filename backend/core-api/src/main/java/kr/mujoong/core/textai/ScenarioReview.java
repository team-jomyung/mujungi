package kr.mujoong.core.textai;

import java.util.List;
import java.util.Set;

/** J7: 자유 사유나 생성물의 자기 승인을 받지 않는 별도 검토 결과. */
public record ScenarioReview(String verdict, List<String> reasons, List<Finding> findings) {
    public static final Set<String> REASONS = Set.of("ANSWER_INCLUDED", "EARLY_CUE", "PRESSURE",
            "AGE_MISMATCH", "GOAL_MISMATCH", "REPETITION", "FORBIDDEN_CONTENT", "INCOMPLETE_SCENE",
            "INVARIANT_MISMATCH", "SKILL_MISMATCH", "TRANSFER_MISMATCH", "UNCHANGED_VARIANT");
    public static final Set<String> CHECKS = Set.of("INTRODUCTION", "OPENING_LINK", "OPPORTUNITY", "GOAL_SPECIFIC",
            "COACH_MATERIAL", "STRUCTURE", "HISTORY", "SAFETY");
    public static final Set<String> FIELDS = Set.of("sceneDescription", "spokenIntroduction", "openingLine",
            "opportunitySentence", "reexplanation", "focusPoints", "expressionExamples", "preservation", "scene");
    /** 거절 사유가 걸린 점검 항목과 필드(닫힌 값만). 진단 기록용이며 승인 판단에 쓰지 않는다(FT-1028-c). */
    public record Finding(String reason, String check, String field) {
        public Finding {
            if (!REASONS.contains(reason) || !CHECKS.contains(check) || !FIELDS.contains(field))
                throw new IllegalArgumentException("invalid scenario review finding");
        }
    }
    public ScenarioReview {
        if (!Set.of("APPROVE", "REJECT", "UNCERTAIN").contains(verdict) || reasons == null
                || reasons.stream().anyMatch(reason -> !REASONS.contains(reason))
                || reasons.stream().distinct().count() != reasons.size()
                || ("APPROVE".equals(verdict) && !reasons.isEmpty()))
            throw new IllegalArgumentException("invalid scenario review");
        reasons = List.copyOf(reasons);
        // 진단 자료라 빠지거나 사유와 어긋나도 판정은 verdict · reasons로만 한다
        findings = findings == null ? List.of() : List.copyOf(findings);
    }
    public ScenarioReview(String verdict, List<String> reasons) { this(verdict, reasons, List.of()); }
    public boolean approved() { return "APPROVE".equals(verdict) && reasons.isEmpty(); }
}
