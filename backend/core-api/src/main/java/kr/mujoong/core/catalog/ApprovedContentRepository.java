package kr.mujoong.core.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 승인 문구 {@code core.core_approved_content(_version)} JPA 저장소 (U1 3.5 Step 15 · BR6.5 · J52 · J60 · J114 · PD-8 SQ-19).
 *
 * <p>세션 패키지는 CLOSING · WAITING_FILLER의 APPROVED 최고 버전을 쓰고(BR6.5), 현재 정책 버전 문자열은 CONSENT_POLICY의 APPROVED 최고
 * 버전 content_text다(J114). B1은 캐시 없이 DB를 직접 읽는다(PD-7은 B1 이후, R3-21).
 */
public interface ApprovedContentRepository extends JpaRepository<ApprovedContent, UUID> {

    /** 코드로 승인 문구 종류를 찾는다(content_code 유일). */
    Optional<ApprovedContent> findByContentCode(String contentCode);

    /** 코드의 APPROVED 버전을 높은 버전부터(SQ-19: (content_id, version) 유일 색인). */
    @Query("""
            select v from ApprovedContentVersion v, ApprovedContent c
             where c.id = v.contentId and c.contentCode = :contentCode and v.status = 'APPROVED'
             order by v.version desc
            """)
    List<ApprovedContentVersion> findApprovedVersions(@Param("contentCode") String contentCode, Limit limit);

    /** 코드의 APPROVED 최고 버전(BR6.5). */
    default Optional<ApprovedContentVersion> findLatestApproved(String contentCode) {
        if (contentCode == null || contentCode.isBlank()) {
            return Optional.empty();
        }
        return findApprovedVersions(contentCode, Limit.of(1)).stream().findFirst();
    }

    /** 현재 정책 버전 문자열(J114: CONSENT_POLICY의 APPROVED 최고 버전 content_text, 임시 '1.0'). */
    default Optional<String> findCurrentPolicyVersion() {
        return findLatestApproved(ApprovedContent.CODE_CONSENT_POLICY).map(ApprovedContentVersion::getContentText);
    }
}
