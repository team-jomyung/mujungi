package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 11 엔터티 단위 시험(DB 없음): SessionStopExpression · SessionEvent의 생성 규칙. DB 대조와 왕복은 {@code RealtimeSchemaIT}.
 * 근거: entities.md SessionStopExpression · SessionEvent · BR6.5 · BR6.9 · NFR12.3(payload는 문자열 표현에 없음). 3.6 수정(BT-TC-10)에서
 * 길이 상한의 포함 경계(V1 열 varchar(100)과 같은 100자)와 id 없는 JPA 인스턴스의 같음 규칙을 더했다.
 */
@Tag("skeleton")
class SessionRecordEntitiesTest {

    private static final Instant T0 = Instant.parse("2026-10-15T09:00:00Z");

    @Test
    void defaultStopExpressionKeepsItsFields() {
        UUID id = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        SessionStopExpression expression = new SessionStopExpression(id, sessionId, "무중아 그만할래", true, 0, T0);
        assertThat(expression.getId()).isEqualTo(id);
        assertThat(expression.getSessionId()).isEqualTo(sessionId);
        assertThat(expression.getExpressionText()).isEqualTo("무중아 그만할래");
        assertThat(expression.isDefaultExpression()).isTrue();
        assertThat(expression.getSortOrder()).isZero();
        assertThat(expression.getCreatedAt()).isEqualTo(T0);
        assertThat(expression).isEqualTo(new SessionStopExpression(id, sessionId, "다른", false, 1, T0))
                .hasSameHashCodeAs(expression)
                .isNotEqualTo(new SessionStopExpression(UUID.randomUUID(), sessionId, "무중아 그만할래", true, 0, T0));
        assertThat(expression.toString()).contains("default=true");
    }

    @Test
    void stopExpressionRejectsBlankOrOverlongText() {
        UUID sessionId = UUID.randomUUID();
        assertThatThrownBy(() -> new SessionStopExpression(UUID.randomUUID(), sessionId, " ", true, 0, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionStopExpression(UUID.randomUUID(), sessionId, null, true, 0, T0))
                .isInstanceOf(IllegalArgumentException.class);
        String limit = "가".repeat(SessionStopExpression.MAX_EXPRESSION_LENGTH);
        assertThat(new SessionStopExpression(UUID.randomUUID(), sessionId, limit, false, 1, T0).getExpressionText())
                .hasSize(100);
        assertThatThrownBy(() -> new SessionStopExpression(UUID.randomUUID(), sessionId, limit + "가", false, 1, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionStopExpression(UUID.randomUUID(), null, "무중아 그만할래", true, 0, T0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void sessionEventKeepsOpIdButNeverPrintsItsPayload() {
        UUID id = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        SessionEvent event = new SessionEvent(id, sessionId, "END_REQUESTED", "request-1", "{\"reason\":\"GUARDIAN_STOP\"}",
                T0);
        assertThat(event.getId()).isEqualTo(id);
        assertThat(event.getSessionId()).isEqualTo(sessionId);
        assertThat(event.getEventType()).isEqualTo("END_REQUESTED");
        assertThat(event.getOpId()).isEqualTo("request-1");
        assertThat(event.getPayloadJson()).contains("GUARDIAN_STOP");
        assertThat(event.getCreatedAt()).isEqualTo(T0);
        assertThat(event.toString()).doesNotContain("GUARDIAN_STOP").contains("END_REQUESTED");
        assertThat(event).isEqualTo(new SessionEvent(id, sessionId, "OTHER", null, null, T0)).hasSameHashCodeAs(event);
        assertThat(event).isNotEqualTo(new SessionEvent(UUID.randomUUID(), sessionId, "END_REQUESTED", null, null, T0));
        // op_id · payload는 선택
        assertThat(new SessionEvent(UUID.randomUUID(), sessionId, "STATE", null, null, T0).getOpId()).isNull();
    }

    @Test
    void sessionEventRejectsInvalidTypeAndOpId() {
        UUID sessionId = UUID.randomUUID();
        assertThatThrownBy(() -> new SessionEvent(UUID.randomUUID(), sessionId, "", null, null, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionEvent(UUID.randomUUID(), sessionId, "x".repeat(101), null, null, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionEvent(UUID.randomUUID(), sessionId, "STATE", " ", null, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionEvent(UUID.randomUUID(), sessionId, "STATE", "o".repeat(101), null, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionEvent(UUID.randomUUID(), sessionId, "STATE", null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void sessionEventTypeAndOpIdAcceptExactlyTheColumnLength() {
        UUID sessionId = UUID.randomUUID();
        String type = "T".repeat(SessionEvent.MAX_TYPE_LENGTH);
        String opId = "o".repeat(SessionEvent.MAX_OP_ID_LENGTH);

        SessionEvent event = new SessionEvent(UUID.randomUUID(), sessionId, type, opId, null, T0);

        assertThat(event.getEventType()).hasSize(100);
        assertThat(event.getOpId()).hasSize(100);
        assertThat(event.getPayloadJson()).isNull();
        assertThatThrownBy(() -> new SessionEvent(null, sessionId, "STATE", null, null, T0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SessionEvent(UUID.randomUUID(), null, "STATE", null, null, T0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SessionStopExpression(null, sessionId, "무중아 그만할래", true, 0, T0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SessionStopExpression(UUID.randomUUID(), sessionId, "무중아 그만할래", true, 0, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void entitiesWithoutAnIdEqualOnlyThemselvesAndKeepOneHashAcrossTheirLife() {
        // JPA가 쓰는 빈 생성자(id 없음): 서로 같지 않고 자신과만 같다
        SessionEvent blankEvent = new SessionEvent();
        SessionEvent otherBlankEvent = new SessionEvent();
        SessionStopExpression blankExpression = new SessionStopExpression();

        assertThat(blankEvent).isEqualTo(blankEvent).isNotEqualTo(otherBlankEvent).isNotEqualTo(null);
        assertThat(blankExpression).isEqualTo(blankExpression).isNotEqualTo(new SessionStopExpression());
        // 해시는 id와 무관한 고정값이라 저장 전후로 바뀌지 않는다(해시 집합에 넣은 뒤 id가 생겨도 찾을 수 있다)
        SessionEvent persisted = new SessionEvent(UUID.randomUUID(), UUID.randomUUID(), "STATE", null, null, T0);
        assertThat(blankEvent.hashCode()).isEqualTo(persisted.hashCode());
        assertThat(blankExpression.hashCode())
                .isEqualTo(new SessionStopExpression(UUID.randomUUID(), UUID.randomUUID(), "그만", true, 0, T0).hashCode());
        // 다른 엔터티 종류와는 id가 같아도 같지 않다
        UUID shared = UUID.randomUUID();
        assertThat((Object) new SessionEvent(shared, UUID.randomUUID(), "STATE", null, null, T0))
                .isNotEqualTo(new SessionStopExpression(shared, UUID.randomUUID(), "그만", true, 0, T0));
    }
}
