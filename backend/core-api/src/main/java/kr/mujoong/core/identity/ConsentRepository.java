package kr.mujoong.core.identity;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 동의 기록 {@code core.core_consent} JPA 저장소 (U1 3.5 Step 15 · BR6.1 ① · J114).
 *
 * <p>시작 검사는 매번 DB를 읽는다(캐시 없음, PD-7은 B1 이후). 현재 정책 버전 문자열은 승인 콘텐츠 CONSENT_POLICY에서 읽는다(J114,
 * {@code ApprovedContentRepository.findCurrentPolicyVersion}). 동의 기록 · 철회 로직은 U2가 완성한다.
 */
public interface ConsentRepository extends JpaRepository<Consent, UUID> {

    /** {@code at}에 효력이 있는 그 아이 · 그 정책 버전의 동의 행(상태 무관). 효력 끝은 배타다. */
    @Query("""
            select c from Consent c
             where c.childId = :childId and c.policyVersion = :policyVersion
               and c.effectiveFrom <= :at and (c.effectiveTo is null or c.effectiveTo > :at)
            """)
    List<Consent> findEffective(
            @Param("childId") UUID childId, @Param("policyVersion") String policyVersion, @Param("at") Instant at);

    /**
     * BR6.1 ①: 현재 정책 버전의 4항목이 모두 GIVEN인가. 항목마다 효력 있는 행 중 consent_version이 가장 큰 행(같으면 효력 시작이 늦은
     * 행)이 그 항목의 현재 상태다.
     */
    default boolean hasAllGiven(UUID childId, String policyVersion, Instant at) {
        Objects.requireNonNull(childId, "childId");
        if (policyVersion == null || policyVersion.isBlank()) {
            return false;
        }
        Map<String, Consent> latestByType = findEffective(childId, policyVersion, Objects.requireNonNull(at, "at")).stream()
                .collect(Collectors.toMap(Consent::getConsentType, Function.identity(), ConsentRepository::later));
        return Consent.TYPES.stream()
                .allMatch(type -> latestByType.containsKey(type) && latestByType.get(type).isGivenAt(at));
    }

    private static Consent later(Consent left, Consent right) {
        Comparator<Consent> order = Comparator.comparingInt(Consent::getConsentVersion)
                .thenComparing(Consent::getEffectiveFrom);
        return order.compare(left, right) >= 0 ? left : right;
    }
}
