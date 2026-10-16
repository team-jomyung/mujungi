package kr.mujoong.core.knowledge;

import java.util.Objects;
import java.util.Set;

/** BR2·BR4: 원문과 활동 출처를 포함하지 않는 그래프 투영. */
public record KnowledgeSeed(String seedId, Origin origin, String label, StructureSignature signature,
        String invariantSentence, String family, String preservationRule, String relationship,
        String situation, boolean enabled) {
    public enum Origin { POC, GENERATED }
    public static final Set<String> RELATIONSHIPS = Set.of("FRIEND_CLOSE", "FRIEND_CASUAL",
            "CLASSMATE", "CLUB_PEER", "CLUB_SENIOR", "CLUB_JUNIOR", "TEAMMATE", "TEACHER", "FAMILY");
    public static final Set<String> SITUATIONS = Set.of("FRIEND_PRIVATE", "SCHOOL_GROUP", "CLUB", "FAMILY");

    public KnowledgeSeed {
        Objects.requireNonNull(origin);
        Objects.requireNonNull(signature);
        if (seedId == null || !seedId.matches("seed:(S[0-9]+|X[0-9]+|G[0-9]{4,})")
                || label == null || label.isBlank() || label.codePointCount(0, label.length()) > 80
                || invariantSentence == null || invariantSentence.isBlank()
                || family == null || family.isBlank()
                || preservationRule == null || preservationRule.isBlank()
                || !RELATIONSHIPS.contains(relationship) || !SITUATIONS.contains(situation)) {
            throw new IllegalArgumentException("Invalid knowledge seed");
        }
        if (origin == Origin.GENERATED && (!seedId.startsWith("seed:G")
                || invariantSentence.codePointCount(0, invariantSentence.length()) > 60)) {
            throw new IllegalArgumentException("Invalid generated seed");
        }
    }

    public String misreadCost() { return "LOW"; }
    public boolean recoverable() { return true; }
    @Override public String toString() { return "KnowledgeSeed[" + seedId + "," + origin + "]"; }
}
