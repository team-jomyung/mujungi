package kr.mujoong.core.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 승인 문구 종류 {@code core.core_approved_content} (U1 3.5 Step 10 · entities.md ApprovedContent · J52 · J60 · J114).
 *
 * <p>U1 시드는 세 건이다: {@value #CODE_CLOSING}(마무리 인사) · {@value #CODE_WAITING_FILLER}(게이트 폐기 뒤 대체 문구) ·
 * {@value #CODE_CONSENT_POLICY}(현재 정책 버전 자리). {@code content_code}는 유일하다. 버전 관리와 정식 문구는 U2가 맡는다.
 */
@Entity
@Table(name = "core_approved_content")
public class ApprovedContent {

    /** 끝내기 마무리 인사(BR6.10). */
    public static final String CODE_CLOSING = "CLOSING";
    /** 게이트 폐기 뒤 대기 · 대체 안내(BR7.4). */
    public static final String CODE_WAITING_FILLER = "WAITING_FILLER";
    /** 현재 정책 버전 문자열의 자리(최고 APPROVED 버전 행의 content_text, J114). */
    public static final String CODE_CONSENT_POLICY = "CONSENT_POLICY";

    /** 승인 음성이 딸린 문구(J117). */
    public static final String TYPE_APPROVED_AUDIO = "APPROVED_AUDIO";
    /** 정책 버전 문자열(음성 없음, J114 · J 후보). */
    public static final String TYPE_POLICY_VERSION = "POLICY_VERSION";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "content_code", nullable = false, updatable = false, length = 100)
    private String contentCode;

    @Column(name = "content_type", nullable = false, length = 50)
    private String contentType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA 전용. */
    protected ApprovedContent() {
    }

    /** 새 승인 문구 종류. {@code now}는 주입한 Clock에서 읽은 값이다. */
    public ApprovedContent(UUID id, String contentCode, String contentType, Instant now) {
        this.id = Objects.requireNonNull(id, "id");
        this.contentCode = requireText(contentCode, "contentCode");
        this.contentType = requireText(contentType, "contentType");
        this.createdAt = Objects.requireNonNull(now, "now");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "가 비어 있다");
        }
        return value;
    }

    public UUID getId() {
        return id;
    }

    public String getContentCode() {
        return contentCode;
    }

    public String getContentType() {
        return contentType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof ApprovedContent that && id != null && id.equals(that.id));
    }

    @Override
    public int hashCode() {
        return ApprovedContent.class.hashCode();
    }

    @Override
    public String toString() {
        return "ApprovedContent[id=" + id + ", code=" + contentCode + "]";
    }
}
