package kr.mujoong.core.knowledge;
import java.util.UUID;
/** Approved advice travels with its applicability and limitations. */
public record SupportItem(UUID id, String behaviorKey, String conditionText, String contentText,
        String limitationText) {}
