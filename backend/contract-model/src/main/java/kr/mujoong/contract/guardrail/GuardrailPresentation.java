package kr.mujoong.contract.guardrail;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** C1/C2의 선택 표시 사실. 원문·판정·정책 본문을 담지 않는다. */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
public record GuardrailPresentation(int schemaVersion, Notice notice, Participation participation, Goodbye goodbye,
        Integer confirmedStopBubbleSeq) {
    public enum Notice { UNKNOWN, PENDING, COMPLETED, FAILED }
    public enum Participation { UNKNOWN, PENDING, ACCEPTED, REFUSED, NO_RESPONSE }
    public enum Goodbye { UNKNOWN, NONE, PENDING, PLAYING, COMPLETED, STOPPED, FAILED }
    public GuardrailPresentation {
        Objects.requireNonNull(notice); Objects.requireNonNull(participation); Objects.requireNonNull(goodbye);
        if (schemaVersion != 1 || confirmedStopBubbleSeq != null && confirmedStopBubbleSeq < 1)
            throw new IllegalArgumentException("guardrail 표시 형식이 올바르지 않다");
    }
    public static GuardrailPresentation parse(Map<String, Object> raw) {
        if (raw == null || !raw.keySet().equals(Set.of("schemaVersion", "notice", "participation", "goodbye", "confirmedStopBubbleSeq"))) return null;
        try {
            if (!(raw.get("schemaVersion") instanceof Number version) || version.doubleValue() != 1) return null;
            Object value = raw.get("confirmedStopBubbleSeq");
            Integer seq = null;
            if (value != null) {
                if (!(value instanceof Number number) || number.doubleValue() != number.longValue()
                        || number.longValue() < 1 || number.longValue() > Integer.MAX_VALUE) return null;
                seq = ((Number) value).intValue();
            }
            return new GuardrailPresentation(1, Notice.valueOf((String) raw.get("notice")),
                    Participation.valueOf((String) raw.get("participation")), Goodbye.valueOf((String) raw.get("goodbye")), seq);
        } catch (IllegalArgumentException | NullPointerException | ClassCastException invalid) { return null; }
    }
}
