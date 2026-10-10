package kr.mujoong.realtime.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 세션이 시작 때 고정한 종료 표현 {@code realtime.realtime_session_stop_expression} (U1 3.5 Step 11 · entities.md
 * SessionStopExpression · BR6.5).
 *
 * <p>U1은 세션마다 기본 종료 표현 한 행(is_default true)만 쓴다. 표현 문자열은 Core가 세션 패키지의 stopExpressions로 건넨
 * 코드 상수이고, 추가 표현(is_default false)은 U2 · U4가 더한다. 세션 행이 먼저 있어야 한다(외래 키, ON DELETE RESTRICT).
 */
@Entity
@Table(name = "realtime_session_stop_expression")
public class SessionStopExpression {

    /** V1 열 길이 {@code varchar(100)}. */
    public static final int MAX_EXPRESSION_LENGTH = 100;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "expression_text", nullable = false, length = MAX_EXPRESSION_LENGTH)
    private String expressionText;

    @Column(name = "is_default", nullable = false)
    private boolean defaultExpression;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA 전용. */
    protected SessionStopExpression() {
    }

    /**
     * 새 종료 표현 행.
     *
     * @param defaultExpression 기본 종료 표현이면 true
     * @param now 주입한 Clock에서 읽은 값
     */
    public SessionStopExpression(
            UUID id, UUID sessionId, String expressionText, boolean defaultExpression, int sortOrder, Instant now) {
        this.id = Objects.requireNonNull(id, "id");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        if (expressionText == null || expressionText.isBlank()) {
            throw new IllegalArgumentException("expressionText가 비어 있다");
        }
        if (expressionText.length() > MAX_EXPRESSION_LENGTH) {
            throw new IllegalArgumentException("expressionText는 " + MAX_EXPRESSION_LENGTH + "자 이하여야 한다");
        }
        this.expressionText = expressionText;
        this.defaultExpression = defaultExpression;
        this.sortOrder = sortOrder;
        this.createdAt = Objects.requireNonNull(now, "now");
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String getExpressionText() {
        return expressionText;
    }

    /** 기본 종료 표현인가(열 is_default). */
    public boolean isDefaultExpression() {
        return defaultExpression;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof SessionStopExpression that && id != null && id.equals(that.id));
    }

    @Override
    public int hashCode() {
        return SessionStopExpression.class.hashCode();
    }

    @Override
    public String toString() {
        return "SessionStopExpression[id=" + id + ", sessionId=" + sessionId + ", default=" + defaultExpression + "]";
    }
}
