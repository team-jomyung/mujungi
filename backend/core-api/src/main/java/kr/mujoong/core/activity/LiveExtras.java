package kr.mujoong.core.activity;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 12 진행 보기의 덧붙임 값 거르기 (U4a ActivityCoordinator · AC3.2.8 · AC3.2.10 · AC3.2.17 · live-view.schema.json · 설계 R4).
 *
 * <p>Realtime 진행 조회의 gaps · technicalPause · silenceWarning · practice를 보호자 LiveView 모양으로 옮긴다. 계약 스키마에 있는 필드만 남기고
 * 모르는 필드 · 모르는 enum 값 · 시각 형식 오류는 버린다(항목 하나가 틀리면 그 항목만, 묶음이 틀리면 null). 판정값은 이 모양에 들어올 자리가
 * 없다(practice는 몇 번째 목표 · 이름 · 단계뿐).
 */
public final class LiveExtras {

    static final Set<String> GAP_KINDS = Set.of("DISCONNECT", "UNCONFIRMED");
    static final Set<String> PAUSE_KINDS = Set.of("DEVICE", "PROVIDER");
    static final Set<String> PRACTICE_STAGES = Set.of("SCENARIO_INTRO", "FIRST_RESPONSE", "HELP_OFFER", "CUE",
            "AFTER_CUE_RESPONSE", "NEW_SCENARIO_RESPONSE");

    private LiveExtras() {
    }

    public static kr.mujoong.contract.guardrail.GuardrailPresentation guardrail(Map<String, Object> raw) {
        return kr.mujoong.contract.guardrail.GuardrailPresentation.parse(raw);
    }

    /** 끊김 · 미확인 구간 하나(to가 null이면 진행 중). */
    public record Gap(Instant from, Instant to, String kind) {

        public Gap {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** 기술적 일시 정지(기기 · 공급자). */
    public record TechnicalPause(String kind, Instant since) {
    }

    /** 공백 경고(4분째부터, 끝나는 시각). */
    public record SilenceWarning(Instant silentSince, Instant endsAt) {
    }

    /** 연습 머리(U5가 채운다). */
    public record Practice(Integer goalIndex, Integer goalCount, String goalName, String stage) {
    }

    /** gaps를 거른다. 없으면 빈 목록. */
    public static List<Gap> gaps(List<Map<String, Object>> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream()
                .filter(Objects::nonNull)
                .map(LiveExtras::gap)
                .filter(Objects::nonNull)
                .toList();
    }

    private static Gap gap(Map<String, Object> raw) {
        Instant from = instant(raw.get("from"));
        Object kind = raw.get("kind");
        Object toValue = raw.get("to");
        Instant to = instant(toValue);
        if (from == null || !(kind instanceof String k) || !GAP_KINDS.contains(k) || (toValue != null && to == null)) {
            return null;
        }
        return new Gap(from, to, k);
    }

    /** technicalPause를 거른다. 틀리거나 없으면 null. */
    public static TechnicalPause technicalPause(Map<String, Object> raw) {
        if (raw == null) {
            return null;
        }
        Instant since = instant(raw.get("since"));
        Object kind = raw.get("kind");
        return since != null && kind instanceof String k && PAUSE_KINDS.contains(k) ? new TechnicalPause(k, since) : null;
    }

    /** silenceWarning을 거른다. 틀리거나 없으면 null. */
    public static SilenceWarning silenceWarning(Map<String, Object> raw) {
        if (raw == null) {
            return null;
        }
        Instant silentSince = instant(raw.get("silentSince"));
        Instant endsAt = instant(raw.get("endsAt"));
        return silentSince != null && endsAt != null ? new SilenceWarning(silentSince, endsAt) : null;
    }

    /** practice를 거른다(모르는 단계는 비움). 틀리거나 없으면 null. */
    public static Practice practice(Map<String, Object> raw) {
        if (raw == null) {
            return null;
        }
        Integer goalIndex = nonNegative(raw.get("goalIndex"));
        Integer goalCount = nonNegative(raw.get("goalCount"));
        String goalName = raw.get("goalName") instanceof String name ? name : null;
        String stage = raw.get("stage") instanceof String s && PRACTICE_STAGES.contains(s) ? s : null;
        if (goalIndex == null && goalCount == null && goalName == null && stage == null) {
            return null;
        }
        return new Practice(goalIndex, goalCount, goalName, stage);
    }

    private static Integer nonNegative(Object value) {
        if (value instanceof Number number && number.longValue() >= 0 && number.longValue() <= Integer.MAX_VALUE
                && number.doubleValue() == number.longValue()) {
            return number.intValue();
        }
        return null;
    }

    private static Instant instant(Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
