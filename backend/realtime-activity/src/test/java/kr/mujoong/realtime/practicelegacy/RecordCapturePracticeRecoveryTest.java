package kr.mujoong.realtime.practicelegacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.realtime.common.db.DbRetry;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.log.AsyncRecorder;
import kr.mujoong.realtime.log.SessionGapRepository;
import kr.mujoong.realtime.log.TranscriptRepository;
import kr.mujoong.realtime.messaging.RealtimeOutboxWriter;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.recording.AudioRecordingRepository;
import kr.mujoong.realtime.session.*;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@Tag("u5b")
class RecordCapturePracticeRecoveryTest {
    private final ActivitySessionRepository sessions = mock(ActivitySessionRepository.class);
    private final ActivitySessionRow session = mock(ActivitySessionRow.class);
    private final ResultInputSnapshotRepository snapshots = mock(ResultInputSnapshotRepository.class);
    private final RealtimeOutboxWriter outbox = mock(RealtimeOutboxWriter.class);
    private final PracticeRecordRecovery recovery = mock(PracticeRecordRecovery.class);
    private final UUID sessionId = UUID.randomUUID();

    private RecordCaptureService service(boolean recoveryEnabled) {
        var transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var properties = mock(RealtimeProperties.class, RETURNS_DEEP_STUBS);
        when(properties.timing().session().recordSettle()).thenReturn(Duration.ofSeconds(2));
        when(sessions.find(sessionId)).thenReturn(Optional.of(session));
        when(session.id()).thenReturn(sessionId);
        when(session.status()).thenReturn(SessionStatus.ENDED);
        when(session.activityType()).thenReturn(ActivityType.PRACTICE);
        when(session.startedAt()).thenReturn(PracticeModelTest.T);
        when(session.endedAt()).thenReturn(PracticeModelTest.T.plusSeconds(10));
        when(session.endReason()).thenReturn(EndReason.GUARDIAN_STOP);
        when(session.policyVersion()).thenReturn("1");
        var service = new RecordCaptureService(sessions, mock(TranscriptRepository.class), mock(SessionGapRepository.class),
                mock(AudioRecordingRepository.class), snapshots, outbox, mock(AsyncRecorder.class), new SessionRegistry(),
                transactionManager, new DbRetry(0, new java.util.Random(1), ignored -> {}),
                new MutableClock(PracticeModelTest.T.plusSeconds(15)), mock(EventLogger.class), properties);
        if (recoveryEnabled) service.setPracticeRecovery(recovery);
        return service;
    }

    @Test
    void terminalPracticeWithoutALiveExecutorLoadsItsOriginalRecordBeforeWriting() {
        var service = service(true);
        when(recovery.recover(sessionId)).thenReturn(Map.of("goals", java.util.List.of()));

        service.capture(sessionId, null, PracticeModelTest.T.plusSeconds(15));

        verify(recovery).recover(sessionId);
        var saved = ArgumentCaptor.forClass(ResultInputSnapshotRepository.NewSnapshot.class);
        verify(snapshots).insert(saved.capture(), any());
        assertThat(saved.getValue().recordJson()).contains("\"practice\":{\"goals\":[]}");
    }

    @Test
    void missingRecoveryOrUnsettledSourceCannotWriteANullPracticeSnapshot() {
        assertThatThrownBy(() -> service(false).capture(sessionId, null, PracticeModelTest.T.plusSeconds(15)))
                .isInstanceOf(IllegalStateException.class);
        var service = service(true);
        when(recovery.recover(sessionId)).thenThrow(new IllegalStateException("원래 판정 시한 대기"));
        assertThatThrownBy(() -> service.capture(sessionId, null, PracticeModelTest.T.plusSeconds(15)))
                .isInstanceOf(IllegalStateException.class);
        verify(snapshots, never()).insert(any(), any());
        verifyNoInteractions(outbox);
    }

    @Test
    void freeTalkAndBeforeActivePracticeKeepTheirEmptyPracticeContract() {
        var service = service(true);
        when(session.activityType()).thenReturn(ActivityType.FREE_TALK);
        service.capture(sessionId, null, PracticeModelTest.T.plusSeconds(15));
        when(session.activityType()).thenReturn(ActivityType.PRACTICE);
        when(session.startedAt()).thenReturn(null);
        when(session.createdAt()).thenReturn(PracticeModelTest.T);
        service.capture(sessionId, null, PracticeModelTest.T.plusSeconds(15));
        verifyNoInteractions(recovery);
        verify(snapshots, times(2)).insert(any(), any());
    }

    @Test
    void anExistingSnapshotAndLivePracticeRecordDoNotInvokeRecovery() {
        var service = service(true);
        when(snapshots.exists(sessionId)).thenReturn(true);
        assertThat(service.capture(sessionId, null, PracticeModelTest.T.plusSeconds(15))).isFalse();
        when(snapshots.exists(sessionId)).thenReturn(false);
        service.capture(sessionId, Map.of("goals", java.util.List.of()), PracticeModelTest.T.plusSeconds(15));
        verifyNoInteractions(recovery);
        verify(snapshots, times(1)).insert(any(), any());
    }

    @Test
    void anActivePracticeCannotBeSettledByTheRecoveryEntryPoint() {
        var service = service(true);
        when(session.status()).thenReturn(SessionStatus.ACTIVE);
        assertThatThrownBy(() -> service.capture(sessionId, null, PracticeModelTest.T.plusSeconds(15)))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(recovery);
        verify(snapshots, never()).insert(any(), any());
    }
}
