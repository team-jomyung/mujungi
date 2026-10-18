package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.event.payload.DeletionCompleted;
import kr.mujoong.contract.event.payload.DeletionRequested;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.log.RealtimeVoiceDeletionParticipant;
import kr.mujoong.realtime.messaging.InboxEventRow;
import kr.mujoong.realtime.messaging.OutboxEventRow;
import kr.mujoong.realtime.messaging.RealtimeDeletionParticipant;
import kr.mujoong.realtime.messaging.RealtimeMessagingDeletionParticipant;
import kr.mujoong.realtime.messaging.RealtimeOutboxWriter;
import kr.mujoong.realtime.observability.EventLogger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * Realtime 삭제 참가자 순서 · 처리기 결정 시험 (U4b L6 · BR9.1 ~ BR9.5 · FD-Q5 · R-12).
 *
 * <p>참가자는 순서 값(음성 100 → 연습 150 → 세션 200 → 메시징 900)으로 불리고, 처리기가 그 건수를 DeletionCompleted.counts로 모으며, 이미 쓴 요청은
 * 다시 쓰지 않고, 정지 상한을 넘기면 예외로 재시도에 맡기며, 발행 뒤 메시징 참가자를 한 번 더 부르는지 본다.
 */
@Tag("u4b")
class RealtimeDeletionParticipantOrderTest {

    private static final UUID GUARDIAN = UUID.randomUUID();
    private static final UUID REQUEST = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-13T09:00:00Z");

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final RealtimeOutboxWriter outbox = mock(RealtimeOutboxWriter.class);
    private final RealtimeMessagingDeletionParticipant messaging = mock(RealtimeMessagingDeletionParticipant.class);
    private final EventLogger events = mock(EventLogger.class);
    private final SessionRegistry registry = new SessionRegistry();
    private final MujoongClock clock = new MujoongClock() {
        @Override
        public Instant now() {
            return NOW;
        }

        @Override
        public long monotonicNanos() {
            return System.nanoTime();
        }
    };

    private final RealtimeDeletionParticipant voice = participant(RealtimeDeletionParticipant.ORDER_VOICE,
            Map.of(RealtimeVoiceDeletionParticipant.TRANSCRIPT, 7, RealtimeVoiceDeletionParticipant.AUDIO_RECORDING, 2,
                    RealtimeVoiceDeletionParticipant.AUDIO_FILES, 1));
    private final RealtimeDeletionParticipant practice = participant(RealtimeDeletionParticipant.ORDER_PRACTICE,
            Map.of("practice_attempt", 3, "practice_goal_result", 2));
    private final RealtimeDeletionParticipant session = participant(RealtimeDeletionParticipant.ORDER_SESSION,
            Map.of(RealtimeSessionDeletionParticipant.SESSION, 4));
    private final RealtimeDeletionParticipant lastMessaging = participant(RealtimeDeletionParticipant.ORDER_MESSAGING,
            Map.of("realtime_outbox_event", 9));

    @Test
    void participantsAreCalledInOrderValueWhateverTheInjectionOrder() {
        DeletionRequestedHandler handler = handler(List.of(lastMessaging, session, voice, practice), Duration.ofSeconds(1));
        stubSessions(List.of());

        handler.handle(row(), NOW);

        assertThat(handler.participants()).extracting(RealtimeDeletionParticipant::order)
                .containsExactly(100, 150, 200, 900);
        InOrder order = inOrder(voice, practice, session, lastMessaging);
        order.verify(voice).deleteByGuardian(GUARDIAN, REQUEST);
        order.verify(practice).deleteByGuardian(GUARDIAN, REQUEST);
        order.verify(session).deleteByGuardian(GUARDIAN, REQUEST);
        order.verify(lastMessaging).deleteByGuardian(GUARDIAN, REQUEST);
    }

    @Test
    void theCountsComeFromTheVoiceSessionAndPracticeParticipants() {
        DeletionRequestedHandler handler = handler(List.of(voice, practice, session, lastMessaging), Duration.ofSeconds(1));
        stubSessions(List.of());

        handler.handle(row(), NOW);

        ArgumentCaptor<DeletionCompleted> completed = ArgumentCaptor.forClass(DeletionCompleted.class);
        verify(outbox).write(completed.capture(), eq(GUARDIAN), eq(NOW), any());
        assertThat(completed.getValue().deletionRequestId()).isEqualTo(REQUEST);
        assertThat(completed.getValue().counts()).isEqualTo(new DeletionCompleted.Counts(4, 7, 2, 5));
        assertThat(completed.getValue().problems()).isEmpty();
    }

    /** U13 BR8.3: 제브 검사 기록 참가자(순서 185)는 연습 150과 세션 200 사이에서 불리고 그 건수는 DeletionCompleted.Counts에 들어가지 않는다. */
    @Test
    void aGuardrailParticipantAtOrder185RunsBetweenPracticeAndSessionAndItsCountsAreNotReported() {
        RealtimeDeletionParticipant guardrail = participant(185, Map.of("guardrail_event", 6));
        DeletionRequestedHandler handler = handler(List.of(lastMessaging, session, guardrail, voice, practice), Duration.ofSeconds(1));
        stubSessions(List.of());

        handler.handle(row(), NOW);

        assertThat(handler.participants()).extracting(RealtimeDeletionParticipant::order)
                .containsExactly(100, 150, 185, 200, 900);
        InOrder order = inOrder(practice, guardrail, session);
        order.verify(practice).deleteByGuardian(GUARDIAN, REQUEST);
        order.verify(guardrail).deleteByGuardian(GUARDIAN, REQUEST);
        order.verify(session).deleteByGuardian(GUARDIAN, REQUEST);
        ArgumentCaptor<DeletionCompleted> completed = ArgumentCaptor.forClass(DeletionCompleted.class);
        verify(outbox).write(completed.capture(), eq(GUARDIAN), eq(NOW), any());
        assertThat(completed.getValue().counts()).as("185의 6건은 건수에 더해지지 않는다(default 가지)")
                .isEqualTo(new DeletionCompleted.Counts(4, 7, 2, 5));
    }

    /** U12 BR7.4: 새 연습 흐름 참가자(순서 190)는 제브 185와 세션 200 사이에서 불리고 그 건수는 DeletionCompleted.Counts에 들어가지 않는다. */
    @Test
    void aPracticeFlowParticipantAtOrder190RunsBetweenGuardrailAndSessionAndItsCountsAreNotReported() {
        RealtimeDeletionParticipant guardrail = participant(185, Map.of("guardrail_event", 6));
        RealtimeDeletionParticipant practiceFlow = participant(190,
                Map.of("realtime_practice_help", 3, "realtime_practice_scene_run", 2));
        DeletionRequestedHandler handler = handler(List.of(lastMessaging, session, practiceFlow, guardrail, voice, practice),
                Duration.ofSeconds(1));
        stubSessions(List.of());

        handler.handle(row(), NOW);

        assertThat(handler.participants()).extracting(RealtimeDeletionParticipant::order)
                .containsExactly(100, 150, 185, 190, 200, 900);
        InOrder order = inOrder(guardrail, practiceFlow, session);
        order.verify(guardrail).deleteByGuardian(GUARDIAN, REQUEST);
        order.verify(practiceFlow).deleteByGuardian(GUARDIAN, REQUEST);
        order.verify(session).deleteByGuardian(GUARDIAN, REQUEST);
        ArgumentCaptor<DeletionCompleted> completed = ArgumentCaptor.forClass(DeletionCompleted.class);
        verify(outbox).write(completed.capture(), eq(GUARDIAN), eq(NOW), any());
        assertThat(completed.getValue().counts()).as("190의 건수는 건수에 더해지지 않는다(default 가지, Counts 모양 불변)")
                .isEqualTo(new DeletionCompleted.Counts(4, 7, 2, 5));
    }

    @Test
    void withoutAPracticeParticipantItIsSkippedAndCountsZero() {
        DeletionRequestedHandler handler = handler(List.of(voice, session, lastMessaging), Duration.ofSeconds(1));
        stubSessions(List.of());

        handler.handle(row(), NOW);

        ArgumentCaptor<DeletionCompleted> completed = ArgumentCaptor.forClass(DeletionCompleted.class);
        verify(outbox).write(completed.capture(), eq(GUARDIAN), eq(NOW), any());
        assertThat(completed.getValue().counts().practiceRecords()).isZero();
    }

    @Test
    void aCompletionAlreadyWrittenForTheSameRequestIsNotWrittenAgain() {
        DeletionRequestedHandler handler = handler(List.of(voice, session), Duration.ofSeconds(1));
        stubSessions(List.of());
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Boolean.class))).thenReturn(true);

        handler.handle(row(), NOW);

        verify(outbox, never()).write(any(), any(), any(), any());
    }

    @Test
    void aParticipantFailureFailsTheWholeAttemptForTheInboxRetry() {
        RealtimeDeletionParticipant failing = mock(RealtimeDeletionParticipant.class);
        when(failing.order()).thenReturn(RealtimeDeletionParticipant.ORDER_VOICE);
        when(failing.deleteByGuardian(any(), any()))
                .thenThrow(new RealtimeVoiceDeletionParticipant.AudioFilesRemainException(1));
        DeletionRequestedHandler handler = handler(List.of(failing, session), Duration.ofSeconds(1));
        stubSessions(List.of());

        assertThatThrownBy(() -> handler.handle(row(), NOW))
                .isInstanceOf(RealtimeVoiceDeletionParticipant.AudioFilesRemainException.class);
        verify(session, never()).deleteByGuardian(any(), any());
        verify(outbox, never()).write(any(), any(), any(), any());
    }

    @Test
    void anExecutorThatDoesNotStopWithinTheLimitFailsTheAttempt() {
        UUID live = UUID.randomUUID();
        registry.register(live, () -> new SessionMailbox(10));
        DeletionRequestedHandler handler = handler(List.of(voice), Duration.ofMillis(50));
        stubSessions(List.of(live));

        assertThatThrownBy(() -> handler.handle(row(), NOW))
                .isInstanceOf(DeletionRequestedHandler.DeletionStopTimeoutException.class);
        verify(voice, never()).deleteByGuardian(any(), any());
    }

    @Test
    void anExecutorThatAlreadyEndedCountsAsStoppedAndAMissingPayloadIsRejected() {
        UUID ended = UUID.randomUUID();
        SessionRegistry.Registration registration = registry.register(ended, () -> new SessionMailbox(10));
        registration.entry().mailbox().closeAndRelease();
        DeletionRequestedHandler handler = handler(List.of(voice), Duration.ofMillis(50));
        stubSessions(List.of(ended));

        handler.handle(row(), NOW);
        verify(voice).deleteByGuardian(GUARDIAN, REQUEST);

        InboxEventRow empty = new InboxEventRow(UUID.randomUUID(), "CORE", "DeletionRequested", 1, NOW, REQUEST,
                GUARDIAN, "{}", "0".repeat(64), InboxEventRow.Status.RECEIVED, 0, NOW, NOW, null, null, null, null);
        assertThatThrownBy(() -> handler.handle(empty, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPublishedCompletionCallsTheMessagingParticipantOnceMoreAndOtherEventsDoNot() {
        DeletionRequestedHandler handler = handler(List.of(voice), Duration.ofSeconds(1));
        // 그 요청의 DeletionRequested Inbox 행이 없으면(예: 기동 복구가 되살린 완료 행) 정리하지 않는다
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Boolean.class))).thenReturn(false);
        handler.published(outboxRow("DeletionCompleted"));
        verify(messaging, never()).deleteByGuardian(any(), any());
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Boolean.class))).thenReturn(true);

        handler.published(outboxRow("DeletionCompleted"));
        handler.published(outboxRow("SessionStateChanged"));
        when(messaging.deleteByGuardian(any(), any())).thenThrow(new IllegalStateException("시험용 실패"));
        handler.published(outboxRow("DeletionCompleted"));

        verify(messaging, org.mockito.Mockito.times(2)).deleteByGuardian(GUARDIAN, REQUEST);
        verify(events).appError(any(), any(Throwable.class));
    }

    private DeletionRequestedHandler handler(List<RealtimeDeletionParticipant> participants, Duration stopWait) {
        return new DeletionRequestedHandler(registry, jdbc, participants, messaging, outbox, JsonCodec.shared(), clock,
                events, stopWait);
    }

    @SuppressWarnings("unchecked")
    private void stubSessions(List<UUID> ids) {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class))).thenReturn((List) ids);
    }

    private static RealtimeDeletionParticipant participant(int order, Map<String, Integer> counts) {
        RealtimeDeletionParticipant participant = mock(RealtimeDeletionParticipant.class);
        when(participant.order()).thenReturn(order);
        when(participant.deleteByGuardian(any(), any())).thenReturn(counts);
        return participant;
    }

    private static InboxEventRow row() {
        String payload = JsonCodec.shared().write(new DeletionRequested(REQUEST, GUARDIAN, null, NOW));
        return new InboxEventRow(UUID.randomUUID(), "CORE", "DeletionRequested", 1, NOW, REQUEST, GUARDIAN, payload,
                "0".repeat(64), InboxEventRow.Status.RECEIVED, 0, NOW, NOW, null, null, null, null);
    }

    private static OutboxEventRow outboxRow(String type) {
        return new OutboxEventRow(UUID.randomUUID(), type, 1, NOW, "Deletion", REQUEST, GUARDIAN, "{}", "0".repeat(64),
                OutboxEventRow.Status.PUBLISHED, 0, NOW, NOW, null, null, NOW, null);
    }
}
