package kr.mujoong.core.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 승인 문구 버전 {@code core.core_approved_content_version} (U1 3.5 Step 10 · entities.md ApprovedContentVersion · E7 · J60).
 *
 * <p>(content_id, version)이 유일하고 세션은 시작 때 쓸 버전을 고정한다(BR6.5). {@code audio_path}는 승인 음성 정적 경로
 * {@value #AUDIO_URL_PREFIX} 아래 파일 이름이고(V1 CHECK와 같은 형식), 기기 · Realtime에는 같은 origin 상대 URL로 건넨다.
 */
@Entity
@Table(name = "core_approved_content_version")
public class ApprovedContentVersion {

    /** 초안. */
    public static final String STATUS_DRAFT = "DRAFT";
    /** 승인됨(세션 패키지 · 정책 버전 읽기 대상). */
    public static final String STATUS_APPROVED = "APPROVED";
    /** 폐기됨. */
    public static final String STATUS_RETIRED = "RETIRED";

    /** 승인 음성 정적 경로(Nginx {@code ^~ /media/approved/}, contracts/ports.md). */
    public static final String AUDIO_URL_PREFIX = "/media/approved/";

    /** V1 CHECK {@code core_approved_content_version_audio_path_ck}와 같은 형식(계약 ApprovedAudioUrl의 파일 이름 부분). */
    private static final Pattern AUDIO_FILE_NAME = Pattern.compile("^[A-Za-z0-9._-]+\\.wav$");

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "content_id", nullable = false, updatable = false)
    private UUID contentId;

    @Column(name = "version", nullable = false, updatable = false)
    private int version;

    @Column(name = "content_text", nullable = false)
    private String contentText;

    @Column(name = "audio_path", length = 500)
    private String audioPath;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA 전용. */
    protected ApprovedContentVersion() {
    }

    /**
     * 새 버전 행.
     *
     * @param audioPath 승인 음성 파일 이름(예: {@code closing-v1.wav}) 또는 null(음성 없음)
     * @param now 주입한 Clock에서 읽은 값
     */
    public ApprovedContentVersion(
            UUID id, UUID contentId, int version, String contentText, String audioPath, String status, Instant now) {
        this.id = Objects.requireNonNull(id, "id");
        this.contentId = Objects.requireNonNull(contentId, "contentId");
        if (version < 1) {
            throw new IllegalArgumentException("version은 1 이상이어야 한다: " + version);
        }
        this.version = version;
        this.contentText = Objects.requireNonNull(contentText, "contentText");
        if (audioPath != null && !AUDIO_FILE_NAME.matcher(audioPath).matches()) {
            throw new IllegalArgumentException("audioPath는 승인 음성 파일 이름(.wav)이어야 한다");
        }
        this.audioPath = audioPath;
        if (!STATUS_DRAFT.equals(status) && !STATUS_APPROVED.equals(status) && !STATUS_RETIRED.equals(status)) {
            throw new IllegalArgumentException("알 수 없는 버전 상태: " + status);
        }
        this.status = status;
        this.createdAt = Objects.requireNonNull(now, "now");
    }

    public UUID getId() {
        return id;
    }

    public UUID getContentId() {
        return contentId;
    }

    public int getVersion() {
        return version;
    }

    public String getContentText() {
        return contentText;
    }

    /** 승인 음성 파일 이름(없으면 null). */
    public String getAudioPath() {
        return audioPath;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isApproved() {
        return STATUS_APPROVED.equals(status);
    }

    /** 기기 · Realtime에 건넬 같은 origin 상대 URL(예: {@code /media/approved/closing-v1.wav}). 음성이 없으면 빈 값. */
    public Optional<String> audioUrl() {
        return audioPath == null ? Optional.empty() : Optional.of(AUDIO_URL_PREFIX + audioPath);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof ApprovedContentVersion that && id != null && id.equals(that.id));
    }

    @Override
    public int hashCode() {
        return ApprovedContentVersion.class.hashCode();
    }

    @Override
    public String toString() {
        return "ApprovedContentVersion[id=" + id + ", contentId=" + contentId + ", version=" + version + ", status=" + status
                + "]";
    }
}
