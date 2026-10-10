package kr.mujoong.core.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 보호자 계정 {@code core.core_guardian} (U1 3.5 Step 10 · entities.md Guardian · J44).
 *
 * <p>U1은 dev 시드 한 명만 쓰고(DevSeedRunner, BR8.4) 구글 로그인 · 삭제 접수는 U2가 완성한다. 이 행을 가리키는 외래 키는
 * ON DELETE RESTRICT라 보호자 행 삭제는 기기 · 활동 행 삭제 뒤 맨 마지막이다(BR2.10 · J58). 시각은 앱 Clock 값만 쓴다(R2).
 */
@Entity
@Table(name = "core_guardian")
public class Guardian {

    /** 정상 계정. */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 계정 삭제 접수됨(U2). */
    public static final String STATUS_DELETION_REQUESTED = "DELETION_REQUESTED";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA 전용. */
    protected Guardian() {
    }

    /** 새 보호자 행(상태 {@value #STATUS_ACTIVE}). {@code now}는 주입한 Clock에서 읽은 값이다. */
    public Guardian(UUID id, Instant now) {
        this.id = Objects.requireNonNull(id, "id");
        this.status = STATUS_ACTIVE;
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
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

    /** 삭제 접수 중인가(BR6.1 ⓪ · U2). */
    public boolean isDeletionRequested() {
        return STATUS_DELETION_REQUESTED.equals(status);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof Guardian that && id != null && id.equals(that.id));
    }

    @Override
    public int hashCode() {
        return Guardian.class.hashCode();
    }

    @Override
    public String toString() {
        return "Guardian[id=" + id + ", status=" + status + "]";
    }
}
