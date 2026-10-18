package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.FailureCode;
import kr.mujoong.contract.status.SessionStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 세션 데이터 접근 값 타입 단위 시험(U1 3.5 Step 18, DB 없음).
 *
 * <p>증명: BR6.5 · NFR3.1(아이 맥락은 세 필드뿐, 문자열 표현에 값 없음) · entities ActivitySession(동의 스냅샷 다섯 값 · 정책 버전 길이 ·
 * 키 길이) · BR6.8(실패 코드는 end_detail, confirmed는 ENDED · ENDED_UNCONFIRMED만) · SessionChange의 null = 기존 값 유지.
 */
@Tag("skeleton")
class SessionValueTypesTest {

    private static final Instant T0 = Instant.parse("2026-10-15T09:00:00Z");
    private static final ConsentSnapshot CONSENT = new ConsentSnapshot("1.0", true, true, false, false);
    private static final ChildContext CHILD = new ChildContext("하늘", 8, List.of("공룡"));

    @Test
    void childContextKeepsOnlyItsThreeFieldsAsAnImmutableCopy() {
        List<String> interests = new ArrayList<>(List.of("공룡"));
        ChildContext context = new ChildContext("하늘", 8, interests);
        interests.add("그림");

        assertThat(context.interests()).containsExactly("공룡");
        assertThatThrownBy(() -> context.interests().add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(ChildContext.class.getRecordComponents()).extracting(c -> c.getName())
                .containsExactly("displayName", "age", "interests");
        assertThat(new ChildContext("별", 0, null).interests()).isEmpty();
        assertThatThrownBy(() -> new ChildContext(" ", 8, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChildContext("하늘", -1, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void consentSnapshotNeedsAPolicyVersionThatFitsTheColumn() {
        String longest = "v".repeat(ConsentSnapshot.MAX_POLICY_VERSION_LENGTH);

        assertThat(new ConsentSnapshot(longest, false, false, false, false).policyVersion()).hasSize(50);
        assertThatThrownBy(() -> new ConsentSnapshot(longest + "v", true, true, true, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConsentSnapshot("", true, true, true, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConsentSnapshot(null, true, true, true, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNewSessionValidatesItsIdentityAndHidesPersonalValues() {
        NewActivitySession session = newSession(ActivityType.FREE_TALK, UUID.randomUUID().toString());

        assertThat(session.toString()).contains(session.id().toString()).doesNotContain("하늘").doesNotContain("1.0");
        assertThatThrownBy(() -> newSession(ActivityType.UNRECOGNIZED, "key")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> newSession(null, "key")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> newSession(ActivityType.FREE_TALK, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> newSession(ActivityType.FREE_TALK, "k".repeat(NewActivitySession.MAX_KEY_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(newSession(ActivityType.PRACTICE, "k".repeat(NewActivitySession.MAX_KEY_LENGTH)).activityType())
                .isEqualTo(ActivityType.PRACTICE);
        assertThatThrownBy(() -> new NewActivitySession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), ActivityType.FREE_TALK, "key", null, CHILD, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NewActivitySession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), ActivityType.FREE_TALK, "key", CONSENT, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void sessionChangeBuildersSetOneFieldEachAndRejectUnknownValues() {
        SessionChange change = SessionChange.none()
                .withEndReason(EndReason.UNCONFIRMED)
                .withFailureCode(FailureCode.SERVER_RESTART)
                .withProviderSessionId("p-1")
                .withStartedAt(T0)
                .withEndedAt(T0.plusSeconds(1))
                .withTerminationRequestedAt(T0.plusSeconds(2))
                .withOutputStoppedAt(T0.plusSeconds(3))
                .withTransportClosedAt(T0.plusSeconds(4))
                .withProviderClosedAt(T0.plusSeconds(5));

        assertThat(change).isEqualTo(new SessionChange(EndReason.UNCONFIRMED, FailureCode.SERVER_RESTART, "p-1", T0,
                T0.plusSeconds(1), T0.plusSeconds(2), T0.plusSeconds(3), T0.plusSeconds(4), T0.plusSeconds(5)));
        assertThat(SessionChange.none()).isEqualTo(new SessionChange(null, null, null, null, null, null, null, null, null));
        assertThat(SessionChange.failed(null, T0).endReason()).isEqualTo(EndReason.FAILED);
        assertThat(SessionChange.failed(null, T0).failureCode()).isNull();
        assertThat(SessionChange.ended(EndReason.DISCONNECT, T0).endedAt()).isEqualTo(T0);
        assertThat(change.toString()).doesNotContain("p-1");
        assertThatThrownBy(() -> SessionChange.none().withEndReason(EndReason.UNRECOGNIZED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SessionChange.none().withFailureCode(FailureCode.UNRECOGNIZED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SessionChange.none().withProviderSessionId(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SessionChange.none().withProviderSessionId("p".repeat(256)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRowReadsItsFailureCodeAndConfirmationFromTheStatus() {
        assertThat(row(SessionStatus.FAILED, EndReason.FAILED, "DEVICE_NO_ACK").failureCode())
                .isEqualTo(FailureCode.DEVICE_NO_ACK);
        assertThat(row(SessionStatus.FAILED, EndReason.FAILED, null).failureCode()).isNull();
        assertThat(row(SessionStatus.FAILED, EndReason.FAILED, "SOMETHING_ELSE").failureCode())
                .isEqualTo(FailureCode.UNRECOGNIZED);
        // FAILED가 아니면 end_detail이 있어도 실패 코드가 아니다
        assertThat(row(SessionStatus.ENDED, EndReason.GUARDIAN_STOP, "DEVICE_NO_ACK").failureCode()).isNull();
        assertThat(row(SessionStatus.ENDED, EndReason.GUARDIAN_STOP, null).confirmed()).isTrue();
        assertThat(row(SessionStatus.ENDED_UNCONFIRMED, EndReason.UNCONFIRMED, null).confirmed()).isFalse();
        assertThat(row(SessionStatus.ABORTED, EndReason.DISCONNECT, null).confirmed()).isNull();
        assertThat(row(SessionStatus.ACTIVE, null, null).childContext()).isNull();
    }

    private static NewActivitySession newSession(ActivityType type, String key) {
        return new NewActivitySession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), type, key,
                CONSENT, CHILD, null, null);
    }

    private static ActivitySessionRow row(SessionStatus status, EndReason endReason, String endDetail) {
        return new ActivitySessionRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ActivityType.FREE_TALK, status, 2, "key", null, null, T0, "{}", "1.0", true, true, null, null, null,
                endReason, endDetail, null, null, null, null, null, null, 1L, T0, T0);
    }
}
