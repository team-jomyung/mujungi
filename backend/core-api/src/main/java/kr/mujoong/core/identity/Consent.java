package kr.mujoong.core.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 동의 기록 {@code core.core_consent} (U1 3.5 Step 10 · entities.md Consent).
 *
 * <p>U1은 dev 시드로 4항목을 GIVEN으로 넣고 시작 게이트(ConsentGate, BR6.1 ①)가 읽는다. 동의 로직 · 철회 · 정책 버전 관리는 U2가 맡는다.
 * 현재 정책 버전 문자열의 자리는 승인 콘텐츠 {@code CONSENT_POLICY}다(J114).
 */
@Entity
@Table(name = "core_consent")
public class Consent {

    /** 음성 · 텍스트 처리 동의. */
    public static final String TYPE_VOICE_AND_TEXT = "VOICE_AND_TEXT";
    /** 보호자 열람 동의. */
    public static final String TYPE_GUARDIAN_VIEW = "GUARDIAN_VIEW";
    /** 보고서 공유 동의. */
    public static final String TYPE_REPORT_SHARING = "REPORT_SHARING";
    /** 원음 저장 동의. */
    public static final String TYPE_RAW_AUDIO = "RAW_AUDIO";
    /** 4항목 전부(V1 CHECK와 같은 목록). */
    public static final Set<String> TYPES =
            Set.of(TYPE_VOICE_AND_TEXT, TYPE_GUARDIAN_VIEW, TYPE_REPORT_SHARING, TYPE_RAW_AUDIO);

    /** 동의함. */
    public static final String STATUS_GIVEN = "GIVEN";
    /** 철회함. */
    public static final String STATUS_WITHDRAWN = "WITHDRAWN";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "actor_guardian_id", nullable = false, updatable = false)
    private UUID actorGuardianId;

    @Column(name = "child_id", nullable = false, updatable = false)
    private UUID childId;

    @Column(name = "consent_type", nullable = false, length = 50)
    private String consentType;

    @Column(name = "consent_status", nullable = false, length = 30)
    private String consentStatus;

    @Column(name = "consent_version", nullable = false)
    private int consentVersion;

    @Column(name = "policy_version", length = 50)
    private String policyVersion;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA 전용. */
    protected Consent() {
    }

    /**
     * 새 동의 행(효력 끝 없음).
     *
     * @param consentType {@link #TYPES} 중 하나
     * @param consentStatus {@value #STATUS_GIVEN} 또는 {@value #STATUS_WITHDRAWN}
     * @param now 주입한 Clock에서 읽은 값(효력 시작 · 생성 시각)
     */
    public Consent(
            UUID id,
            UUID actorGuardianId,
            UUID childId,
            String consentType,
            String consentStatus,
            int consentVersion,
            String policyVersion,
            Instant now) {
        this.id = Objects.requireNonNull(id, "id");
        this.actorGuardianId = Objects.requireNonNull(actorGuardianId, "actorGuardianId");
        this.childId = Objects.requireNonNull(childId, "childId");
        // Set.of(...).contains(null)은 NPE를 던지므로 null을 먼저 거른다
        if (consentType == null || !TYPES.contains(consentType)) {
            throw new IllegalArgumentException("알 수 없는 동의 항목: " + consentType);
        }
        if (!STATUS_GIVEN.equals(consentStatus) && !STATUS_WITHDRAWN.equals(consentStatus)) {
            throw new IllegalArgumentException("알 수 없는 동의 상태: " + consentStatus);
        }
        this.consentType = consentType;
        this.consentStatus = consentStatus;
        this.consentVersion = consentVersion;
        this.policyVersion = policyVersion;
        this.effectiveFrom = Objects.requireNonNull(now, "now");
        this.createdAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getActorGuardianId() {
        return actorGuardianId;
    }

    public UUID getChildId() {
        return childId;
    }

    public String getConsentType() {
        return consentType;
    }

    public String getConsentStatus() {
        return consentStatus;
    }

    public int getConsentVersion() {
        return consentVersion;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    public Instant getEffectiveTo() {
        return effectiveTo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** {@code at} 시각에 효력이 있는 GIVEN 동의인가(효력 끝은 배타). */
    public boolean isGivenAt(Instant at) {
        Objects.requireNonNull(at, "at");
        return STATUS_GIVEN.equals(consentStatus)
                && !effectiveFrom.isAfter(at)
                && (effectiveTo == null || effectiveTo.isAfter(at));
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof Consent that && id != null && id.equals(that.id));
    }

    @Override
    public int hashCode() {
        return Consent.class.hashCode();
    }

    @Override
    public String toString() {
        return "Consent[id=" + id + ", type=" + consentType + ", status=" + consentStatus + "]";
    }
}
