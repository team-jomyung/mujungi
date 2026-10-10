package kr.mujoong.core.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.contract.status.RegistrationStatus;
import kr.mujoong.core.activity.StaleEvaluator;
import kr.mujoong.core.device.DeviceJdbcRepository.DeviceRecord;
import kr.mujoong.core.device.DeviceVerdict.NetworkState;
import kr.mujoong.core.device.DeviceVerdict.ReasonCode;
import kr.mujoong.core.device.DeviceViewAssembler.DeviceView;
import kr.mujoong.core.support.TestEvents;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** 기기 판정 7행 · 30초 경계 · canStart · 교체 허용 · DeviceView 노출 범위 (BR4.3 · BR4.4 · BR4.6 · BR2.4 · AC2.1.1 ~ 2.1.6). */
@Tag("u1")
class DeviceVerdictTest {

    private static final Duration STALE = Duration.ofSeconds(30);
    private static final Instant NOW = Instant.parse("2001-03-01T01:00:00Z");
    private static final UUID GUARDIAN = UUID.randomUUID();

    static Stream<Arguments> table() {
        Instant fresh = NOW.minusSeconds(5);
        return Stream.of(
                Arguments.of("① 활성 기기 없음", null, NetworkState.NONE, ReasonCode.NO_DEVICE),
                Arguments.of("① RELEASED는 활성 아님",
                        DeviceRecords.row(UUID.randomUUID(), GUARDIAN, "7F3K92QD", RegistrationStatus.RELEASED,
                                ConnectionState.CONNECTED, fresh), NetworkState.NONE, ReasonCode.NO_DEVICE),
                Arguments.of("② 사본 없음", DeviceRecords.registered(GUARDIAN, null, null),
                        NetworkState.UNRESPONSIVE, ReasonCode.DEVICE_UNRESPONSIVE),
                Arguments.of("③ 30초 넘음", DeviceRecords.registered(GUARDIAN, ConnectionState.CONNECTED, NOW.minusSeconds(31)),
                        NetworkState.UNKNOWN, ReasonCode.DEVICE_STATUS_UNKNOWN),
                Arguments.of("④ CONNECTED", DeviceRecords.registered(GUARDIAN, ConnectionState.CONNECTED, fresh),
                        NetworkState.STABLE, null),
                Arguments.of("⑤ UNSTABLE", DeviceRecords.registered(GUARDIAN, ConnectionState.UNSTABLE, fresh),
                        NetworkState.UNSTABLE, ReasonCode.DEVICE_UNSTABLE),
                Arguments.of("⑥ DISCONNECTED", DeviceRecords.registered(GUARDIAN, ConnectionState.DISCONNECTED, fresh),
                        NetworkState.DISCONNECTED, ReasonCode.DEVICE_DISCONNECTED),
                Arguments.of("⑦ UNRESPONSIVE", DeviceRecords.registered(GUARDIAN, ConnectionState.UNRESPONSIVE, fresh),
                        NetworkState.UNRESPONSIVE, ReasonCode.DEVICE_UNRESPONSIVE),
                Arguments.of("⑦ OFFLINE", DeviceRecords.registered(GUARDIAN, ConnectionState.OFFLINE, fresh),
                        NetworkState.UNRESPONSIVE, ReasonCode.DEVICE_UNRESPONSIVE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("table")
    void firstMatchingRowWins(String row, DeviceRecord active, NetworkState network, ReasonCode reason) {
        DeviceVerdict verdict = DeviceVerdict.evaluate(active, NOW, STALE);

        assertThat(verdict.network()).isEqualTo(network);
        assertThat(verdict.reasonCode()).isEqualTo(reason);
        assertThat(verdict.canStart()).isEqualTo(network == NetworkState.STABLE);
    }

    @Test
    void thirtySecondsExactlyIsStillFreshAndOneMillisecondLaterIsUnknown() {
        DeviceRecord atBoundary = DeviceRecords.registered(GUARDIAN, ConnectionState.CONNECTED, NOW.minus(STALE));
        DeviceRecord justAfter = DeviceRecords.registered(GUARDIAN, ConnectionState.CONNECTED,
                NOW.minus(STALE).minusMillis(1));

        assertThat(DeviceVerdict.evaluate(atBoundary, NOW, STALE).canStart()).isTrue();
        assertThat(DeviceVerdict.evaluate(justAfter, NOW, STALE).network()).isEqualTo(NetworkState.UNKNOWN);
    }

    @Test
    void staleBeatsConnectionStateEvenWhenDisconnected() {
        DeviceRecord old = DeviceRecords.registered(GUARDIAN, ConnectionState.DISCONNECTED, NOW.minusSeconds(120));

        assertThat(DeviceVerdict.evaluate(old, NOW, STALE).reasonCode()).isEqualTo(ReasonCode.DEVICE_STATUS_UNKNOWN);
    }

    @Test
    void replacementIsBlockedOnlyWhileStableOrUnstable() {
        Instant fresh = NOW.minusSeconds(1);
        assertThat(DeviceVerdict.evaluate(DeviceRecords.registered(GUARDIAN, ConnectionState.CONNECTED, fresh), NOW, STALE)
                .allowsReplacement()).isFalse();
        assertThat(DeviceVerdict.evaluate(DeviceRecords.registered(GUARDIAN, ConnectionState.UNSTABLE, fresh), NOW, STALE)
                .allowsReplacement()).isFalse();
        assertThat(DeviceVerdict.evaluate(DeviceRecords.registered(GUARDIAN, ConnectionState.DISCONNECTED, fresh), NOW,
                STALE).allowsReplacement()).isTrue();
        assertThat(DeviceVerdict.evaluate(DeviceRecords.registered(GUARDIAN, ConnectionState.CONNECTED,
                NOW.minusSeconds(31)), NOW, STALE).allowsReplacement()).isTrue();
        assertThat(DeviceVerdict.evaluate(DeviceRecords.registered(GUARDIAN, null, null), NOW, STALE)
                .allowsReplacement()).isTrue();
    }

    @Test
    void deviceViewExposesNoHashTokenOrFullCodeAndCountsFirstStaleOnce() {
        MutableClock clock = new MutableClock(NOW);
        TestEvents events = new TestEvents(clock);
        DeviceJdbcRepository repository = mock(DeviceJdbcRepository.class);
        DeviceRecord active = DeviceRecords.registered(GUARDIAN, ConnectionState.CONNECTED, NOW.minusSeconds(40));
        when(repository.findActiveByGuardian(GUARDIAN)).thenReturn(Optional.of(active));
        DeviceViewAssembler assembler = new DeviceViewAssembler(repository,
                new StaleEvaluator(STALE, events.metrics(), events.logger()), clock);

        DeviceView view = assembler.view(GUARDIAN);
        assembler.view(GUARDIAN);

        assertThat(view.canStart()).isFalse();
        assertThat(view.verdict().reasonCode()).isEqualTo(ReasonCode.DEVICE_STATUS_UNKNOWN);
        assertThat(view.registration().codePrefix()).isEqualTo("7F3K");
        assertThat(view.toString()).doesNotContain("7F3K92QD");
        assertThat(componentNames(DeviceView.class, DeviceViewAssembler.Registration.class,
                DeviceViewAssembler.Network.class, DeviceViewAssembler.Verdict.class))
                .noneMatch(name -> name.contains("hash") || name.contains("token") || name.contains("stale")
                        || name.equals("registrationcode"));
        assertThat(events.metrics().count("mujoong.timeout.fired", "type", "device_status_stale")).isEqualTo(1.0);
    }

    @Test
    void readinessCarriesDeviceIdOnlyWhenRegistered() {
        MutableClock clock = new MutableClock(NOW);
        TestEvents events = new TestEvents(clock);
        DeviceJdbcRepository repository = mock(DeviceJdbcRepository.class);
        when(repository.findActiveByGuardian(GUARDIAN)).thenReturn(Optional.empty());
        DeviceViewAssembler assembler = new DeviceViewAssembler(repository,
                new StaleEvaluator(STALE, events.metrics(), events.logger()), clock);

        DeviceViewAssembler.Readiness none = assembler.readiness(GUARDIAN);

        assertThat(none.deviceId()).isNull();
        assertThat(none.canStart()).isFalse();
        assertThat(none.reasonCode()).isEqualTo(ReasonCode.NO_DEVICE);
    }

    private static Stream<String> componentNames(Class<?>... records) {
        return Arrays.stream(records)
                .flatMap(type -> Arrays.stream(type.getRecordComponents()))
                .map(RecordComponent::getName)
                .map(name -> name.toLowerCase(Locale.ROOT));
    }
}
