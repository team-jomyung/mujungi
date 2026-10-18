package kr.mujoong.realtime.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import kr.mujoong.contract.status.ConnectionState;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * U3 기기 연결 원장 규칙 시험(BR4.8 ① · U3-R1 · U3-J2 · AC2.1.4): 끊김 사건으로 세는 닫힘, 왕복 초과 판정, 창 안 횟수에 따른 복귀 상태.
 */
@Tag("voice-session")
class DeviceStatusPolicyVoiceSessionTest {

    @ParameterizedTest(name = "code={0} transportFailed={1} ready={2} -> counts={3}")
    @CsvSource({
            "1006, false, true, true",
            "1011, true, true, true",
            "1006, true, true, true",
            "1011, false, true, false",
            "1000, false, true, false",
            "1001, false, true, false",
            "4001, false, true, false",
            "4400, false, true, false",
            "4401, false, true, false",
            "1013, false, true, false",
            "1006, false, false, false",
            "1011, true, false, false"})
    void onlyANetworkDropOfAReadyConnectionCountsAsADisconnect(int code, boolean transportFailed, boolean reachedReady,
            boolean counts) {
        assertThat(DeviceStatusPolicy.countsAsDisconnect(code, transportFailed, reachedReady)).isEqualTo(counts);
    }

    @Test
    void aRoundTripIsUnstableOnlyWhenItExceedsTheThresholdAndAMissingValueNeverIs() {
        Duration threshold = Duration.ofSeconds(1);

        assertThat(DeviceStatusPolicy.isUnstableRtt(null, threshold)).isFalse();
        assertThat(DeviceStatusPolicy.isUnstableRtt(0L, threshold)).isFalse();
        assertThat(DeviceStatusPolicy.isUnstableRtt(1_000L, threshold)).isFalse();
        assertThat(DeviceStatusPolicy.isUnstableRtt(1_001L, threshold)).isTrue();
        assertThat(DeviceStatusPolicy.isUnstableRtt(1_001L, null)).isFalse();
    }

    @ParameterizedTest(name = "recent={0} threshold={1} -> {2}")
    @CsvSource({
            "0, 2, CONNECTED",
            "1, 2, CONNECTED",
            "2, 2, UNSTABLE",
            "5, 2, UNSTABLE",
            "1, 1, UNSTABLE",
            "0, 1, CONNECTED"})
    void theRecoveredStateIsUnstableFromTheThresholdCount(int recent, int threshold, ConnectionState expected) {
        assertThat(DeviceStatusPolicy.recoveredState(recent, threshold)).isEqualTo(expected);
    }
}
