package kr.mujoong.realtime.session;

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
 * 세션 안 명령 · 전이 기록 {@code realtime.realtime_session_event} (U1 3.5 Step 11 · entities.md SessionEvent · BR6.9).
 *
 * <p>끝내기 요청이 수락되면 그 requestId를 {@code op_id}에 남긴다(BR6.9). {@code payload_json}에는 전사 원문 · 원음 · 비밀을
 * 넣지 않는다(BR8.7 · NFR12.3). 값은 JSON 객체 원문이고 해석은 {@code kr.mujoong.contract.json.JsonCodec}이 한다(J139).
 */
@Entity
@Table(name = "realtime_session_event")
public class SessionEvent {

    /** V1 열 길이 {@code varchar(100)}. */
    public static final int MAX_TYPE_LENGTH = 100;
    /** V1 열 길이 {@code varchar(100)}. */
    public static final int MAX_OP_ID_LENGTH = 100;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "event_type", nullable = false, updatable = false, length = MAX_TYPE_LENGTH)
    private String eventType;

    @Column(name = "op_id", updatable = false, length = MAX_OP_ID_LENGTH)
    private String opId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", updatable = false)
    private String payloadJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA 전용. */
    protected SessionEvent() {
    }

    /**
     * 새 기록 행(쓴 뒤 바뀌지 않는다).
     *
     * @param opId 명령 · 요청 식별자(없으면 null)
     * @param payloadJson JSON 객체 원문(없으면 null). 전사 원문을 넣지 않는다
     * @param now 주입한 Clock에서 읽은 값
     */
    public SessionEvent(UUID id, UUID sessionId, String eventType, String opId, String payloadJson, Instant now) {
        this.id = Objects.requireNonNull(id, "id");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        if (eventType == null || eventType.isBlank() || eventType.length() > MAX_TYPE_LENGTH) {
            throw new IllegalArgumentException("eventType은 1 ~ " + MAX_TYPE_LENGTH + "자여야 한다");
        }
        if (opId != null && (opId.isBlank() || opId.length() > MAX_OP_ID_LENGTH)) {
            throw new IllegalArgumentException("opId는 1 ~ " + MAX_OP_ID_LENGTH + "자여야 한다");
        }
        this.eventType = eventType;
        this.opId = opId;
        this.payloadJson = payloadJson;
        this.createdAt = Objects.requireNonNull(now, "now");
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getOpId() {
        return opId;
    }

    /** JSON 객체 원문(없으면 null). */
    public String getPayloadJson() {
        return payloadJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof SessionEvent that && id != null && id.equals(that.id));
    }

    @Override
    public int hashCode() {
        return SessionEvent.class.hashCode();
    }

    /** payload는 문자열 표현에 넣지 않는다. */
    @Override
    public String toString() {
        return "SessionEvent[id=" + id + ", sessionId=" + sessionId + ", type=" + eventType + "]";
    }
}
