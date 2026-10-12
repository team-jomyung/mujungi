package kr.mujoong.core.consent;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import kr.mujoong.core.identity.Consent;

/**
 * 동의 상태 판정 규칙 (U2 · R6 · FR1.2 · 편차 (U2-g) · U2-J8 · U2-J10).
 *
 * <p>보호자 한 명의 동의 행(아이 등록 전 행 포함)과 현재 정책 버전으로 판정한다. 항목마다 그 정책 버전의 효력 있는 행 중
 * consent_version이 가장 큰 행(같으면 효력 시작이 늦은 행)이 그 항목의 현재 상태다(U1 ConsentRepository.hasAllGiven과 같은 기준).
 *
 * <ul>
 *   <li>GIVEN: 현재 정책 버전 4항목이 모두 GIVEN.
 *   <li>OUTDATED: GIVEN은 아니고, 다른(예전) 정책 버전 하나에서 네 항목의 최신 행이 모두 GIVEN이다 — 02 재동의(리뷰 7: 예전 버전도 항목별
 *       최신 행으로 본다. 그 버전의 철회 행이 뒤에 쌓였거나 일부 항목만 있으면 동의가 아니었다).
 *   <li>NOT_GIVEN: 그 밖(동의 행 없음, 일부만, 철회).
 * </ul>
 *
 * <p>상태가 없는 정적 규칙이다. 활동 시작 게이트는 ConsentPolicyService.check(계약 8절 포트)로 이 판정을 쓴다(U4a). 정책 버전을 모르면 NOT_GIVEN(닫힌 쪽)이다.
 */
public final class ConsentStatusRule {

    private ConsentStatusRule() {
    }

    /** 동의 상태(계약 me-response.schema.json ConsentStatus). */
    public enum Status {
        NOT_GIVEN,
        GIVEN,
        OUTDATED
    }

    /** 동의 4항목(계약 ConsentType, 화면 순서). */
    public enum Type {
        VOICE_AND_TEXT,
        GUARDIAN_VIEW,
        REPORT_SHARING,
        RAW_AUDIO;

        /** 선 형식 문자열을 읽는다. 모르면 빈 값. */
        public static Optional<Type> fromWire(String value) {
            for (Type type : values()) {
                if (type.name().equals(value)) {
                    return Optional.of(type);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * 판정 결과.
     *
     * @param status 전체 상태
     * @param givenByType 현재 정책 버전 기준 항목별 GIVEN 여부(4항목 모두 들어 있다)
     */
    public record Evaluation(Status status, Map<Type, Boolean> givenByType) {

        public Evaluation {
            Objects.requireNonNull(status, "status");
            givenByType = Map.copyOf(givenByType);
        }

        /** 시작 게이트 통과인가(GIVEN만). */
        public boolean ok() {
            return status == Status.GIVEN;
        }
    }

    /**
     * 판정한다.
     *
     * @param consents 보호자의 동의 행 전부
     * @param currentPolicyVersion 현재 정책 버전(없으면 빈 값 → NOT_GIVEN)
     * @param at 판정 시각(Clock)
     */
    public static Evaluation evaluate(Collection<Consent> consents, Optional<String> currentPolicyVersion, Instant at) {
        Objects.requireNonNull(consents, "consents");
        Objects.requireNonNull(at, "at");
        Map<Type, Boolean> given = new EnumMap<>(Type.class);
        for (Type type : Type.values()) {
            given.put(type, false);
        }
        if (currentPolicyVersion == null || currentPolicyVersion.isEmpty() || currentPolicyVersion.get().isBlank()) {
            return new Evaluation(Status.NOT_GIVEN, given);
        }
        String policyVersion = currentPolicyVersion.get();
        for (Type type : Type.values()) {
            given.put(type, givenAt(consents, type, policyVersion, at));
        }
        if (given.values().stream().allMatch(Boolean::booleanValue)) {
            return new Evaluation(Status.GIVEN, given);
        }
        boolean olderGiven = consents.stream()
                .map(Consent::getPolicyVersion)
                .filter(version -> version != null && !policyVersion.equals(version))
                .distinct()
                .anyMatch(version -> allGivenAt(consents, version, at));
        return new Evaluation(olderGiven ? Status.OUTDATED : Status.NOT_GIVEN, given);
    }

    /** 그 정책 버전의 네 항목이 모두 GIVEN인가(항목별 최신 행 기준). */
    private static boolean allGivenAt(Collection<Consent> consents, String policyVersion, Instant at) {
        for (Type type : Type.values()) {
            if (!givenAt(consents, type, policyVersion, at)) {
                return false;
            }
        }
        return true;
    }

    /** 그 정책 버전 · 항목의 최신 효력 행이 GIVEN인가. */
    private static boolean givenAt(Collection<Consent> consents, Type type, String policyVersion, Instant at) {
        return latest(consents, type, policyVersion, at).map(row -> row.isGivenAt(at)).orElse(false);
    }

    private static Optional<Consent> latest(Collection<Consent> consents, Type type, String policyVersion, Instant at) {
        Comparator<Consent> order = Comparator.comparingInt(Consent::getConsentVersion)
                .thenComparing(Consent::getEffectiveFrom);
        List<Consent> effective = consents.stream()
                .filter(row -> type.name().equals(row.getConsentType()))
                .filter(row -> policyVersion.equals(row.getPolicyVersion()))
                .filter(row -> !row.getEffectiveFrom().isAfter(at))
                .filter(row -> row.getEffectiveTo() == null || row.getEffectiveTo().isAfter(at))
                .toList();
        return effective.stream().max(order);
    }
}
