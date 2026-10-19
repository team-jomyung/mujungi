package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Stream;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.FailureCode;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.realtime.session.TransitionTable.Trigger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * U1 세션 전이 표 시험 (U1 3.5 Step 29 · BR6.8 · BR6.12 · BR6.13 · J59 (h) · J126).
 */
@Tag("skeleton")
class TransitionTableTest {

    private static final Instant AT = Instant.parse("2026-10-15T09:00:00Z");

    static Stream<Arguments> allowed() {
        return Stream.of(
                Arguments.of(SessionStatus.PREPARING, SessionStatus.DEVICE_ACK),
                // U4b(FR11.3 활성화 경로): 준비가 끝나면 ACTIVE가 아니라 참여 확인(WAITING_CHILD)으로 간다
                Arguments.of(SessionStatus.DEVICE_ACK, SessionStatus.WAITING_CHILD),
                Arguments.of(SessionStatus.PREPARING, SessionStatus.FAILED),
                Arguments.of(SessionStatus.DEVICE_ACK, SessionStatus.FAILED),
                Arguments.of(SessionStatus.ACTIVE, SessionStatus.FAILED),
                Arguments.of(SessionStatus.CLOSING, SessionStatus.FAILED),
                Arguments.of(SessionStatus.ACTIVE, SessionStatus.CLOSING),
                Arguments.of(SessionStatus.CLOSING, SessionStatus.ENDED),
                Arguments.of(SessionStatus.CLOSING, SessionStatus.ENDED_UNCONFIRMED),
                Arguments.of(SessionStatus.ACTIVE, SessionStatus.ABORTED));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allowed")
    void theU1TransitionsAreAllowed(SessionStatus from, SessionStatus to) {
        assertThat(TransitionTable.allowed(from, to)).isTrue();
    }

    static Stream<Arguments> forbidden() {
        return Stream.of(
                Arguments.of(SessionStatus.PREPARING, SessionStatus.ACTIVE),
                Arguments.of(SessionStatus.DEVICE_ACK, SessionStatus.CLOSING),
                Arguments.of(SessionStatus.ACTIVE, SessionStatus.ENDED),
                Arguments.of(SessionStatus.CLOSING, SessionStatus.ABORTED),
                Arguments.of(SessionStatus.ENDED, SessionStatus.ACTIVE),
                Arguments.of(SessionStatus.FAILED, SessionStatus.PREPARING),
                Arguments.of(SessionStatus.ACTIVE, SessionStatus.WAITING_CHILD),
                Arguments.of(SessionStatus.ACTIVE, SessionStatus.DECLINED),
                Arguments.of(SessionStatus.REQUESTED, SessionStatus.PREPARING),
                Arguments.of(null, SessionStatus.FAILED));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("forbidden")
    void transitionsOutsideTheTableAndOutOfTerminalStatesAreNotAllowed(SessionStatus from, SessionStatus to) {
        assertThat(TransitionTable.allowed(from, to)).isFalse();
    }

    @Test
    void terminalStatesNeverAppearAsOrigins() {
        Set<SessionStatus> terminal = EnumSet.of(SessionStatus.ENDED, SessionStatus.ENDED_UNCONFIRMED,
                SessionStatus.ABORTED, SessionStatus.FAILED);
        for (Trigger trigger : Trigger.values()) {
            assertThat(trigger.origins()).doesNotContainAnyElementsOf(terminal);
        }
    }

    @Test
    void endReasonsFollowTheTargetState() {
        // U4b(FR1.2 · BR1.4): ENDED는 CLOSING에 들어갈 때 사건이 쓴 사유를 그대로 둔다(도착 상태로 정하지 않음)
        assertThat(TransitionTable.endReason(SessionStatus.ENDED)).isNull();
        assertThat(TransitionTable.endReason(SessionStatus.ENDED_UNCONFIRMED)).isEqualTo(EndReason.UNCONFIRMED);
        assertThat(TransitionTable.endReason(SessionStatus.ABORTED)).isEqualTo(EndReason.DISCONNECT);
        assertThat(TransitionTable.endReason(SessionStatus.FAILED)).isEqualTo(EndReason.FAILED);
        assertThat(TransitionTable.endReason(SessionStatus.ACTIVE)).isNull();
        assertThat(TransitionTable.endReason(null)).isNull();
    }

    @Test
    void failureCodesPerTriggerIncludingCoreAbandonedWithout() {
        assertThat(TransitionTable.changeFor(Trigger.CORE_ABANDONED, null, AT).failureCode()).isNull();
        assertThat(TransitionTable.changeFor(Trigger.CORE_ABANDONED, null, AT).endReason()).isEqualTo(EndReason.FAILED);
        assertThat(TransitionTable.changeFor(Trigger.SERVER_RESTART, null, AT).failureCode())
                .isEqualTo(FailureCode.SERVER_RESTART);
        assertThat(TransitionTable.changeFor(Trigger.PROVIDER_FAILED, null, AT).failureCode())
                .isEqualTo(FailureCode.PROVIDER_UNAVAILABLE);
        assertThat(TransitionTable.changeFor(Trigger.START_SEND_FAILED, null, AT).failureCode())
                .isEqualTo(FailureCode.DEVICE_NO_ACK);
        assertThat(TransitionTable.changeFor(Trigger.READY, null, AT)).isEqualTo(SessionChange.none());
        assertThat(TransitionTable.changeFor(Trigger.DISCONNECT, null, AT).endedAt()).isEqualTo(AT);
    }

    @Test
    void theStartTimeoutCodeDependsOnWhatArrived() {
        assertThat(TransitionTable.startTimeoutCode(false, false)).isEqualTo(FailureCode.DEVICE_NO_ACK);
        assertThat(TransitionTable.startTimeoutCode(true, false)).isEqualTo(FailureCode.DEVICE_NO_ACK);
        assertThat(TransitionTable.startTimeoutCode(true, true)).isEqualTo(FailureCode.PROVIDER_UNAVAILABLE);
    }

    @Test
    void multiOriginTriggersAreTheFailedOnesAndStartPhaseIsPreparingOrDeviceAck() {
        assertThat(Trigger.SERVER_RESTART.multiOrigin()).isTrue();
        assertThat(Trigger.CORE_ABANDONED.multiOrigin()).isTrue();
        assertThat(Trigger.START_TIMEOUT.multiOrigin()).isTrue();
        // U4b(FR1.3 · AC3.3.8): 보호자 끝내기는 WAITING_CHILD에서도 받는다
        assertThat(Trigger.GUARDIAN_STOP.multiOrigin()).isTrue();
        assertThat(TransitionTable.isStartPhase(SessionStatus.PREPARING)).isTrue();
        assertThat(TransitionTable.isStartPhase(SessionStatus.DEVICE_ACK)).isTrue();
        assertThat(TransitionTable.isStartPhase(SessionStatus.ACTIVE)).isFalse();
    }
}
