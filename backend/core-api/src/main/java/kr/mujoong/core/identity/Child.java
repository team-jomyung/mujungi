package kr.mujoong.core.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 아이 {@code core.core_child} (U1 3.5 Step 10 · entities.md Child).
 *
 * <p>보호자당 한 명(guardian_id 유일). U1은 dev 시드 한 명을 세션 패키지의 대화 문맥(displayName · age · interests 세 필드뿐,
 * BR6.5 · NFR3.1)으로만 쓰고, 이름 1 ~ 10자 · 나이 6 ~ 12 검증과 편집은 U2가 맡는다. {@code interests_json}은 문자열 JSON 배열
 * 원문이며 해석은 {@code kr.mujoong.contract.json.JsonCodec}이 한다(J139: Jackson 직접 import는 한 곳).
 */
@Entity
@Table(name = "core_child")
public class Child {

    /** 사용 중인 아이 프로필. */
    public static final String STATUS_ACTIVE = "ACTIVE";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "guardian_id", nullable = false, updatable = false)
    private UUID guardianId;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "age")
    private Integer age;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "interests_json")
    private String interestsJson;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** JPA 전용. */
    protected Child() {
    }

    /**
     * 새 아이 행(상태 {@value #STATUS_ACTIVE}).
     *
     * @param interestsJson 관심사 JSON 배열 원문(예: {@code ["공룡","그림"]}) 또는 null
     * @param now 주입한 Clock에서 읽은 값
     */
    public Child(UUID id, UUID guardianId, String displayName, Integer age, String interestsJson, Instant now) {
        this.id = Objects.requireNonNull(id, "id");
        this.guardianId = Objects.requireNonNull(guardianId, "guardianId");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.age = age;
        this.interestsJson = interestsJson;
        this.status = STATUS_ACTIVE;
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getGuardianId() {
        return guardianId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Integer getAge() {
        return age;
    }

    /** 관심사 JSON 배열 원문(없으면 null). */
    public String getInterestsJson() {
        return interestsJson;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof Child that && id != null && id.equals(that.id));
    }

    @Override
    public int hashCode() {
        return Child.class.hashCode();
    }

    /** 이름 · 관심사(개인정보)는 문자열 표현에 넣지 않는다(BR8.7). */
    @Override
    public String toString() {
        return "Child[id=" + id + ", status=" + status + "]";
    }
}
