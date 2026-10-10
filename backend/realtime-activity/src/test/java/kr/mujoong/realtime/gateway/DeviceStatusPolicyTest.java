package kr.mujoong.realtime.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.realtime.gateway.DeviceStatusPolicy.CommandTimeout;
import kr.mujoong.realtime.testing.SessionHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 기기 연결 원장 상태 규칙 시험 (U1 3.5 Step 29 · BR4.7 · BR4.8 · J55 · AC2.1.10 · AC2.1.11).
 */
@Tag("u1")
class DeviceStatusPolicyTest {

    @ParameterizedTest(name = "code={0} server={1} ready={2} -> {3}")
    @CsvSource({
            "1000, false, true, true",
            "1001, false, true, true",
            "1001, true, true, false",
            "1000, false, false, false",
            "1006, false, true, false",
            "1011, true, true, false",
            "1013, true, true, false",
            "4001, true, true, false",
            "4400, true, true, false",
            "4401, true, true, false",
            "4408, true, false, false"})
    void onlyADeviceSentNormalCloseAfterHelloOkGoesOfflineAtOnce(int code, boolean serverInitiated,
            boolean reachedReady, boolean offline) {
        assertThat(DeviceStatusPolicy.closesToOffline(code, serverInitiated, reachedReady)).isEqualTo(offline);
    }

    @ParameterizedTest
    @EnumSource(CommandTimeout.class)
    void onlyTheTwoCommandDeadlinesWriteUnresponsive(CommandTimeout timeout) {
        boolean expected = timeout == CommandTimeout.START_NO_ACK || timeout == CommandTimeout.CLOSING_NO_CONFIRM;
        assertThat(DeviceStatusPolicy.marksUnresponsive(timeout)).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(ConnectionState.class)
    void aHeartbeatOnTheSameConnectionRecoversOnlyUnresponsiveAndDisconnected(ConnectionState state) {
        boolean expected = state == ConnectionState.UNRESPONSIVE || state == ConnectionState.DISCONNECTED;
        assertThat(DeviceStatusPolicy.heartbeatRecovers(state)).isEqualTo(expected);
        assertThat(DeviceStatusPolicy.heartbeatRecovers(null)).isFalse();
    }

    @Test
    void anUnresponsiveLedgerOrStoppedReportsAloneDoNotMoveAnActiveSession() {
        // BR4.7 · AC2.1.11: 세션을 움직이는 연결 사건은 세션별 ④ 시계뿐이다
        SessionHarness h = new SessionHarness();
        h.activate();
        h.link.touch(h.clock.now());
        h.advance(java.time.Duration.ofSeconds(30));

        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(h.unresponsive).isEmpty();
    }
}
