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
 * U4b 전이표 시험 (L2 · FR1.1 · FR1.2 · FR1.5 · BR1.1 ~ BR1.4 · BR1.8 · BR1.9 · functional-spec 3.1).
 *
 * <p>전이표 칸마다 (사건, 출발 상태)가 성립하는지와 성립하지 않는지, 사건이 정하는 end_reason · end_detail을 본다. 표에 없는 (사건, 상태)는
 * 빈 값이다(호출자가 TRANSITION_IGNORED를 남긴다).
 */
@Tag("u4b")
class TransitionTableU4bTest {

    private static final Instant AT = Instant.parse("2026-10-13T09:00:00Z");
    private static final Set<SessionStatus> LIVE = EnumSet.of(SessionStatus.PREPARING, SessionStatus.DEVICE_ACK,
            SessionStatus.WAITING_CHILD, SessionStatus.ACTIVE, SessionStatus.CLOSING);

    /** 설계 3.1 전이표 칸(사건 · 출발 집합 · 도착). */
    static Stream<Arguments> cells() {
        return Stream.of(
                Arguments.of(Trigger.DEVICE_ACK, EnumSet.of(SessionStatus.PREPARING), SessionStatus.DEVICE_ACK),
                Arguments.of(Trigger.READY, EnumSet.of(SessionStatus.DEVICE_ACK), SessionStatus.WAITING_CHILD),
                Arguments.of(Trigger.JOIN_ACCEPTED, EnumSet.of(SessionStatus.WAITING_CHILD), SessionStatus.ACTIVE),
                Arguments.of(Trigger.JOIN_DECLINED, EnumSet.of(SessionStatus.WAITING_CHILD), SessionStatus.DECLINED),
                Arguments.of(Trigger.GUARDIAN_STOP, EnumSet.of(SessionStatus.WAITING_CHILD, SessionStatus.ACTIVE),
                        SessionStatus.CLOSING),
                Arguments.of(Trigger.CONSENT_WITHDRAWN, EnumSet.of(SessionStatus.WAITING_CHILD, SessionStatus.ACTIVE),
                        SessionStatus.CLOSING),
                Arguments.of(Trigger.CHILD_STOP_CONFIRMED, EnumSet.of(SessionStatus.ACTIVE), SessionStatus.CLOSING),
                Arguments.of(Trigger.PRACTICE_COMPLETED, EnumSet.of(SessionStatus.ACTIVE), SessionStatus.CLOSING),
                Arguments.of(Trigger.SILENCE_TIMEOUT, EnumSet.of(SessionStatus.ACTIVE), SessionStatus.CLOSING),
                Arguments.of(Trigger.VOICE_CLOSED, EnumSet.of(SessionStatus.CLOSING), SessionStatus.ENDED),
                Arguments.of(Trigger.CLOSING_TIMEOUT, EnumSet.of(SessionStatus.CLOSING), SessionStatus.ENDED_UNCONFIRMED),
                Arguments.of(Trigger.DISCONNECT, EnumSet.of(SessionStatus.WAITING_CHILD, SessionStatus.ACTIVE),
                        SessionStatus.ABORTED),
                Arguments.of(Trigger.CONSENT_WITHDRAWN_START,
                        EnumSet.of(SessionStatus.PREPARING, SessionStatus.DEVICE_ACK), SessionStatus.FAILED),
                Arguments.of(Trigger.CORE_ABANDONED, EnumSet.of(SessionStatus.PREPARING, SessionStatus.DEVICE_ACK,
                        SessionStatus.WAITING_CHILD, SessionStatus.ACTIVE), SessionStatus.FAILED),
                Arguments.of(Trigger.SERVER_RESTART, EnumSet.copyOf(LIVE), SessionStatus.FAILED));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cells")
    void eachCellHoldsOnlyFromItsOriginsAndNeverFromOtherStates(Trigger trigger, Set<SessionStatus> origins,
            SessionStatus target) {
        for (SessionStatus from : SessionStatus.values()) {
            if (origins.contains(from)) {
                assertThat(TransitionTable.decide(trigger, from)).as(trigger + " from " + from).contains(target);
                assertThat(TransitionTable.allowed(from, target)).isTrue();
            } else {
                assertThat(TransitionTable.decide(trigger, from)).as(trigger + " from " + from).isEmpty();
            }
        }
        assertThat(TransitionTable.decide(trigger, null)).isEmpty();
    }

    @Test
    void theEventDecidesTheReasonForEveryClosingEntry() {
        assertThat(TransitionTable.changeFor(Trigger.GUARDIAN_STOP, null, AT).endReason())
                .isEqualTo(EndReason.GUARDIAN_STOP);
        assertThat(TransitionTable.changeFor(Trigger.CONSENT_WITHDRAWN, null, AT).endReason())
                .isEqualTo(EndReason.CONSENT_WITHDRAWN);
        assertThat(TransitionTable.changeFor(Trigger.CHILD_STOP_CONFIRMED, null, AT).endReason())
                .isEqualTo(EndReason.CHILD_STOP);
        assertThat(TransitionTable.changeFor(Trigger.SILENCE_TIMEOUT, null, AT).endReason())
                .isEqualTo(EndReason.SILENCE);
        // CLOSING은 종료 상태가 아니라 ended_at을 쓰지 않는다
        assertThat(TransitionTable.changeFor(Trigger.GUARDIAN_STOP, null, AT).endedAt()).isNull();
        assertThat(TransitionTable.changeFor(Trigger.GUARDIAN_STOP, null, AT).endDetailValue()).isNull();
    }

    @Test
    void thePracticeCompletionReusesChildStopWithItsOwnDetail() {
        SessionChange change = TransitionTable.changeFor(Trigger.PRACTICE_COMPLETED, null, AT);

        assertThat(change.endReason()).isEqualTo(EndReason.CHILD_STOP);
        assertThat(change.endDetailValue()).isEqualTo(TransitionTable.END_DETAIL_PRACTICE_COMPLETED);
        assertThat(Trigger.PRACTICE_COMPLETED.target()).isEqualTo(SessionStatus.CLOSING);
    }

    @Test
    void theEndedTransitionKeepsTheClosingReasonByWritingNoReason() {
        SessionChange ended = TransitionTable.changeFor(Trigger.VOICE_CLOSED, null, AT);

        assertThat(ended.endReason()).as("ENDED는 CLOSING의 사유를 그대로 둔다(BR1.4)").isNull();
        assertThat(ended.endDetailValue()).isNull();
        assertThat(ended.endedAt()).isEqualTo(AT);
        assertThat(TransitionTable.endReason(SessionStatus.ENDED)).isNull();
    }

    @Test
    void theUnconfirmedEndOverwritesTheReasonAndKeepsTheFirstOneInTheDetail() {
        assertThat(TransitionTable.closingTimeoutChange(EndReason.CHILD_STOP, null, AT).endDetailValue())
                .isEqualTo("CHILD_STOP");
        SessionChange practice = TransitionTable.closingTimeoutChange(EndReason.CHILD_STOP,
                TransitionTable.END_DETAIL_PRACTICE_COMPLETED, AT);
        assertThat(practice.endReason()).isEqualTo(EndReason.UNCONFIRMED);
        assertThat(practice.endDetailValue()).isEqualTo("CHILD_STOP/PRACTICE_COMPLETED");
        assertThat(practice.endedAt()).isEqualTo(AT);
        // 처음 사유를 모르면 세부를 비운다
        assertThat(TransitionTable.closingTimeoutChange(null, null, AT).endDetailValue()).isNull();
        assertThat(TransitionTable.closingTimeoutChange(EndReason.UNCONFIRMED, null, AT).endDetailValue()).isNull();
    }

    @Test
    void terminalTransitionsWriteTheirReasonAndEndTime() {
        assertThat(TransitionTable.changeFor(Trigger.JOIN_DECLINED, null, AT).endReason())
                .isEqualTo(EndReason.DECLINED);
        assertThat(TransitionTable.changeFor(Trigger.DISCONNECT, null, AT).endReason())
                .isEqualTo(EndReason.DISCONNECT);
        assertThat(TransitionTable.changeFor(Trigger.CONSENT_WITHDRAWN_START, null, AT).endReason())
                .as("시작 단계 동의 철회만 FAILED의 사유가 CONSENT_WITHDRAWN").isEqualTo(EndReason.CONSENT_WITHDRAWN);
        assertThat(TransitionTable.changeFor(Trigger.CONSENT_WITHDRAWN_START, null, AT).failureCode()).isNull();
        assertThat(TransitionTable.changeFor(Trigger.SERVER_RESTART, null, AT).endDetailValue())
                .isEqualTo(FailureCode.SERVER_RESTART.name());
        assertThat(TransitionTable.changeFor(Trigger.CORE_ABANDONED, null, AT).endReason()).isEqualTo(EndReason.FAILED);
        assertThat(TransitionTable.changeFor(Trigger.JOIN_DECLINED, null, AT).endedAt()).isEqualTo(AT);
    }

    @Test
    void nonEndingEventsLeaveTheReasonColumnsAlone() {
        assertThat(TransitionTable.changeFor(Trigger.READY, null, AT)).isEqualTo(SessionChange.none());
        assertThat(TransitionTable.changeFor(Trigger.JOIN_ACCEPTED, null, AT)).isEqualTo(SessionChange.none());
        assertThat(TransitionTable.changeFor(Trigger.DEVICE_ACK, null, AT)).isEqualTo(SessionChange.none());
    }

    @Test
    void theTargetDefaultReasonsCoverEveryTerminalStateExceptEnded() {
        assertThat(TransitionTable.endReason(SessionStatus.DECLINED)).isEqualTo(EndReason.DECLINED);
        assertThat(TransitionTable.endReason(SessionStatus.ENDED_UNCONFIRMED)).isEqualTo(EndReason.UNCONFIRMED);
        assertThat(TransitionTable.endReason(SessionStatus.ABORTED)).isEqualTo(EndReason.DISCONNECT);
        assertThat(TransitionTable.endReason(SessionStatus.FAILED)).isEqualTo(EndReason.FAILED);
        assertThat(TransitionTable.endReason(SessionStatus.WAITING_CHILD)).isNull();
        assertThat(TransitionTable.endReason(SessionStatus.CLOSING)).isNull();
    }

    @Test
    void closingIsLeftOnlyByTheVoiceCloseTheThirtySecondsOrARestart() {
        assertThat(Stream.of(Trigger.values()).filter(trigger -> trigger.accepts(SessionStatus.CLOSING))
                .map(Trigger::target)).containsExactlyInAnyOrder(SessionStatus.ENDED, SessionStatus.ENDED_UNCONFIRMED,
                        SessionStatus.FAILED);
        assertThat(Trigger.CORE_ABANDONED.accepts(SessionStatus.CLOSING)).as("Core 취소는 CLOSING을 바꾸지 않는다(BR1.9)")
                .isFalse();
        assertThat(Trigger.DISCONNECT.accepts(SessionStatus.CLOSING)).as("CLOSING에는 끊김 전이가 없다(BR6.3)").isFalse();
    }

    @Test
    void noTerminalStateIsEverAnOrigin() {
        for (Trigger trigger : Trigger.values()) {
            assertThat(trigger.origins()).allMatch(status -> !status.isTerminal());
        }
    }
}
