package kr.mujoong.core.device;

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
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.event.payload.DeviceReleased;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.contract.status.RegistrationStatus;
import kr.mujoong.core.activity.StaleEvaluator;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.device.DeviceJdbcRepository.DeviceRecord;
import kr.mujoong.core.device.RegistrationService.RegistrationResult;
import kr.mujoong.core.identity.Guardian;
import kr.mujoong.core.identity.GuardianRepository;
import kr.mujoong.core.messaging.CoreOutboxWriter;
import kr.mujoong.core.security.ratelimit.SlidingWindowRateLimiter;
import kr.mujoong.core.support.NoTransactions;
import kr.mujoong.core.support.TestEvents;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;

/** 등록 분기 ① ② ③ · 같은 보호자 재생 (m) · 교체 판정 7종 · 교체 순서 (BR2.1 ~ BR2.6 · J46 · A4 · AC2.2.x). */
@Tag("skeleton")
class RegistrationDecisionTest {

    private static final Instant NOW = Instant.parse("2001-03-01T02:00:00Z");
    private static final String CODE = "7F3K92QD";

    private final UUID guardianId = UUID.randomUUID();
    private final MutableClock clock = new MutableClock(NOW);
    private final TestEvents events = new TestEvents(clock);
    private final GuardianRepository guardians = mock(GuardianRepository.class);
    private final DeviceJdbcRepository devices = mock(DeviceJdbcRepository.class);
    private final CoreOutboxWriter outbox = mock(CoreOutboxWriter.class);
    private SlidingWindowRateLimiter failures;
    private RegistrationService service;

    @BeforeEach
    void setUp() {
        failures = new SlidingWindowRateLimiter(SlidingWindowRateLimiter.Limiter.REGISTER_FAILURE, 10,
                Duration.ofMinutes(10), 100, clock, null);
        DeviceViewAssembler assembler = new DeviceViewAssembler(devices,
                new StaleEvaluator(Duration.ofSeconds(30), events.metrics(), events.logger()), clock);
        service = new RegistrationService(guardians, devices, assembler, outbox, failures, events.logger(),
                NoTransactions.noRetry(), new NoTransactions(), clock);
        when(guardians.findById(guardianId)).thenReturn(Optional.of(new Guardian(guardianId, NOW)));
        when(devices.lockGuardianStatusForShare(guardianId)).thenReturn(Optional.of(Guardian.STATUS_ACTIVE));
        when(devices.lockActiveByGuardian(guardianId)).thenReturn(Optional.empty());
    }

    @Test
    void pendingCodeRegistersAndReturnsPrefixOnly() {
        DeviceRecord pending = DeviceRecords.pending(CODE, NOW.plusSeconds(60));
        when(devices.lockPendingByCode(CODE, NOW)).thenReturn(Optional.of(pending));
        when(devices.register(pending.id(), guardianId, NOW)).thenReturn(1);

        RegistrationResult result = service.register(guardianId, "7f3k 92qd");

        assertThat(result.deviceId()).isEqualTo(pending.id());
        assertThat(result.codePrefix()).isEqualTo("7F3K");
        assertThat(result.registeredAt()).isEqualTo(NOW);
        assertThat(failures.count(guardianId.toString())).isZero();
        assertThat(events.capture().anyLineContains(CODE)).isFalse();
    }

    @Test
    void sameGuardianRepeatReplays201WithoutCountingFailure() {
        DeviceRecord mine = DeviceRecords.row(UUID.randomUUID(), guardianId, CODE, RegistrationStatus.REGISTERED,
                ConnectionState.CONNECTED, NOW);
        when(devices.lockPendingByCode(CODE, NOW)).thenReturn(Optional.empty());
        when(devices.findBoundByCode(CODE)).thenReturn(List.of(mine));

        RegistrationResult replay = service.register(guardianId, CODE);

        assertThat(replay.deviceId()).isEqualTo(mine.id());
        assertThat(replay.registeredAt()).isEqualTo(mine.registeredAt());
        assertThat(failures.count(guardianId.toString())).isZero();
        verify(devices, never()).register(any(), any(), any());
    }

    @Test
    void codeBoundToAnotherGuardianIs409AndCountsFailure() {
        DeviceRecord theirs = DeviceRecords.row(UUID.randomUUID(), UUID.randomUUID(), CODE, RegistrationStatus.REGISTERED,
                ConnectionState.CONNECTED, NOW);
        when(devices.lockPendingByCode(CODE, NOW)).thenReturn(Optional.empty());
        when(devices.findBoundByCode(CODE)).thenReturn(List.of(theirs));

        assertThatThrownBy(() -> service.register(guardianId, CODE)).isInstanceOfSatisfying(CoreApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.CODE_ALREADY_REGISTERED));
        assertThat(failures.count(guardianId.toString())).isEqualTo(1);
        assertThat(events.capture().events(kr.mujoong.contract.log.EventCode.SECURITY_AUTH_FAILED)).hasSize(1);
    }

    @Test
    void unknownCodeIs404AndMalformedIs400BothCounted() {
        when(devices.lockPendingByCode(anyString(), eq(NOW))).thenReturn(Optional.empty());
        when(devices.findBoundByCode(anyString())).thenReturn(List.of());

        assertThatThrownBy(() -> service.register(guardianId, CODE)).isInstanceOfSatisfying(CoreApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.CODE_NOT_FOUND));
        assertThatThrownBy(() -> service.register(guardianId, "7F3K-92Q")).isInstanceOfSatisfying(CoreApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> service.register(guardianId, "0F3K92QD")).isInstanceOfSatisfying(CoreApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.CODE_NOT_FOUND));
        assertThat(failures.count(guardianId.toString())).isEqualTo(3);
    }

    @Test
    void tenFailuresInTenMinutesBlockTheEleventhAndSuccessClearsTheCounter() {
        when(devices.lockPendingByCode(anyString(), any())).thenReturn(Optional.empty());
        when(devices.findBoundByCode(anyString())).thenReturn(List.of());
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> service.register(guardianId, CODE)).isInstanceOf(CoreApiException.class);
        }

        assertThatThrownBy(() -> service.register(guardianId, CODE)).isInstanceOfSatisfying(CoreApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.TOO_MANY_ATTEMPTS));

        clock.advance(Duration.ofMinutes(10).plusMillis(1));
        DeviceRecord pending = DeviceRecords.pending(CODE, clock.now().plusSeconds(60));
        when(devices.lockPendingByCode(CODE, clock.now())).thenReturn(Optional.of(pending));
        when(devices.register(pending.id(), guardianId, clock.now())).thenReturn(1);
        service.register(guardianId, CODE);
        assertThat(failures.count(guardianId.toString())).isZero();
    }

    static Stream<Arguments> replacementVerdicts() {
        return Stream.of(
                Arguments.of("사본 없음", null, null, true),
                Arguments.of("확인 불가(30초 넘음)", ConnectionState.CONNECTED, NOW.minusSeconds(31), true),
                Arguments.of("STABLE", ConnectionState.CONNECTED, NOW.minusSeconds(1), false),
                Arguments.of("UNSTABLE", ConnectionState.UNSTABLE, NOW.minusSeconds(1), false),
                Arguments.of("DISCONNECTED", ConnectionState.DISCONNECTED, NOW.minusSeconds(1), true),
                Arguments.of("UNRESPONSIVE", ConnectionState.UNRESPONSIVE, NOW.minusSeconds(1), true),
                Arguments.of("OFFLINE", ConnectionState.OFFLINE, NOW.minusSeconds(1), true));
    }

    @ParameterizedTest(name = "옛 기기 {0} → 교체 {3}")
    @MethodSource("replacementVerdicts")
    void replacementFollowsVerdictAndReleasesOldDeviceFirst(
            String label, ConnectionState state, Instant reportedAt, boolean replaced) {
        DeviceRecord old = DeviceRecords.registered(guardianId, state, reportedAt);
        DeviceRecord pending = DeviceRecords.pending(CODE, NOW.plusSeconds(60));
        when(devices.lockPendingByCode(CODE, NOW)).thenReturn(Optional.of(pending));
        when(devices.lockActiveByGuardian(guardianId)).thenReturn(Optional.of(old));
        when(devices.releaseRegistered(old.id(), DeviceReleased.Reason.REREGISTERED, NOW)).thenReturn(1);
        when(devices.register(pending.id(), guardianId, NOW)).thenReturn(1);

        if (replaced) {
            assertThat(service.register(guardianId, CODE).deviceId()).isEqualTo(pending.id());
            InOrder order = inOrder(devices, outbox);
            order.verify(devices).lockGuardianStatusForShare(guardianId);
            order.verify(devices).lockPendingByCode(CODE, NOW);
            order.verify(devices).lockActiveByGuardian(guardianId);
            order.verify(devices).releaseRegistered(old.id(), DeviceReleased.Reason.REREGISTERED, NOW);
            order.verify(outbox).write(new DeviceReleased(old.id(), guardianId, DeviceReleased.Reason.REREGISTERED),
                    guardianId, NOW);
            order.verify(devices).register(pending.id(), guardianId, NOW);
        } else {
            assertThatThrownBy(() -> service.register(guardianId, CODE)).isInstanceOfSatisfying(CoreApiException.class,
                    e -> assertThat(e.code()).isEqualTo(ErrorCode.DEVICE_ALREADY_ACTIVE));
            verify(devices, never()).releaseRegistered(any(), any(), any());
            verify(outbox, never()).write(any(), any(), any());
        }
    }

    @Test
    void lostRaceOnRegisterRereadsAndDeletionRequestedGuardianIs403() {
        DeviceRecord pending = DeviceRecords.pending(CODE, NOW.plusSeconds(60));
        DeviceRecord mine = DeviceRecords.row(pending.id(), guardianId, CODE, RegistrationStatus.REGISTERED,
                ConnectionState.CONNECTED, NOW);
        when(devices.lockPendingByCode(CODE, NOW)).thenReturn(Optional.of(pending));
        when(devices.register(pending.id(), guardianId, NOW)).thenReturn(0);
        when(devices.findBoundByCode(CODE)).thenReturn(List.of(mine));

        assertThat(service.register(guardianId, CODE).deviceId()).isEqualTo(pending.id());

        UUID leaving = UUID.randomUUID();
        Guardian deleting = mock(Guardian.class);
        when(deleting.isDeletionRequested()).thenReturn(true);
        when(guardians.findById(leaving)).thenReturn(Optional.of(deleting));
        assertThatThrownBy(() -> service.register(leaving, CODE)).isInstanceOfSatisfying(CoreApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.ACCOUNT_DELETION_IN_PROGRESS));
    }
}
