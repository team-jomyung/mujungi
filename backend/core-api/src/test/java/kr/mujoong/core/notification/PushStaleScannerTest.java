package kr.mujoong.core.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.status.RegistrationStatus;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.activity.ActivityCopyRepository;
import kr.mujoong.core.activity.ActivityCopyRepository.ActivityCopy;
import kr.mujoong.core.activity.StaleEvaluator;
import kr.mujoong.core.common.db.DbRetry;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.device.DeviceJdbcRepository;
import kr.mujoong.core.device.DeviceJdbcRepository.DeviceRecord;
import kr.mujoong.core.identity.GuardianWriteLock;
import kr.mujoong.core.observability.CoreMetrics;
import kr.mujoong.core.observability.EventLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@Tag("u7")
class PushStaleScannerTest {
    private final PushEventRepository events = mock(PushEventRepository.class);
    private final ActivityCopyRepository activities = mock(ActivityCopyRepository.class);
    private final DeviceJdbcRepository devices = mock(DeviceJdbcRepository.class);
    private final GuardianWriteLock guardians = mock(GuardianWriteLock.class);
    private final MujoongClock clock = mock(MujoongClock.class);
    private final CoreProperties properties = mock(CoreProperties.class, RETURNS_DEEP_STUBS);
    private final ActivityCopy copy = mock(ActivityCopy.class);
    private final DeviceRecord device = mock(DeviceRecord.class);
    private final UUID session = UUID.randomUUID(), guardian = UUID.randomUUID(), deviceId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-14T01:00:00Z");
    private PushStaleScanner scanner;

    @BeforeEach
    void setup() {
        when(properties.cleanup().batchSize()).thenReturn(2);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        scanner = new PushStaleScanner(events, activities, devices, guardians,
                new StaleEvaluator(Duration.ofSeconds(30), mock(CoreMetrics.class), mock(EventLogger.class)),
                clock, new DbRetry(0, new java.util.Random(), duration -> { }), manager, properties);
        when(events.candidatesAfter(null, 2)).thenReturn(List.of(new PushEventRepository.Candidate(session, guardian)));
        when(guardians.statusForShare(guardian)).thenReturn(Optional.of("ACTIVE"));
        when(events.lockActivity(session)).thenReturn(true);
        when(activities.findById(session)).thenReturn(Optional.of(copy));
        when(copy.sessionId()).thenReturn(session);
        when(copy.guardianId()).thenReturn(guardian);
        when(copy.deviceId()).thenReturn(deviceId);
        when(copy.status()).thenReturn(SessionStatus.ACTIVE);
        when(copy.requestedAt()).thenReturn(now.minusSeconds(60));
        when(devices.findById(deviceId)).thenReturn(Optional.of(device));
        when(device.guardianId()).thenReturn(guardian);
        when(device.registrationStatus()).thenReturn(RegistrationStatus.REGISTERED);
        when(device.reportedAt()).thenReturn(now.minusSeconds(30));
        when(clock.now()).thenReturn(now);
    }

    @Test
    void exactlyThirtySecondsIsFreshAndBeyondIsStale() {
        scanner.runOnce();
        verify(events, never()).enqueue(any(), any(), any(), any());
        when(device.reportedAt()).thenReturn(now.minusSeconds(30).minusNanos(1));
        scanner.runOnce();
        verify(events).enqueue(copy, PushEventType.DEVICE_STATUS_UNKNOWN, session + ":STALE", now);
    }

    @Test
    void initialReportUsesRequestTimeAndMissingDeviceDoesNotEmit() {
        when(device.reportedAt()).thenReturn(null);
        scanner.runOnce();
        verify(events).enqueue(copy, PushEventType.DEVICE_STATUS_UNKNOWN, session + ":STALE", now);
        clearInvocations(events);
        when(devices.findById(deviceId)).thenReturn(Optional.empty());
        scanner.runOnce();
        verify(events, never()).enqueue(any(), any(), any(), any());
    }

    @Test
    void deletingGuardianOrTerminalSessionOrUnregisteredDeviceDoesNotEmit() {
        when(guardians.statusForShare(guardian)).thenReturn(Optional.of("DELETING"));
        scanner.runOnce();
        verify(events, never()).lockActivity(any());
        when(guardians.statusForShare(guardian)).thenReturn(Optional.of("ACTIVE"));
        when(copy.status()).thenReturn(SessionStatus.ENDED);
        scanner.runOnce();
        verify(devices, never()).findById(any());
        when(copy.status()).thenReturn(SessionStatus.ACTIVE);
        when(device.registrationStatus()).thenReturn(RegistrationStatus.UNREGISTERED);
        scanner.runOnce();
        verify(events, never()).enqueue(any(), any(), any(), any());
    }

    @Test
    void allPagesAreScannedRatherThanRepeatingFirstPageForever() {
        UUID next = UUID.randomUUID();
        when(events.candidatesAfter(null, 2)).thenReturn(List.of(new PushEventRepository.Candidate(session, guardian),
                new PushEventRepository.Candidate(next, guardian)));
        when(events.candidatesAfter(next, 2)).thenReturn(List.of());
        scanner.runOnce();
        verify(events).candidatesAfter(next, 2);
    }
}
