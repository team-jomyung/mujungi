package kr.mujoong.realtime.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 기기 연결의 비교 후 풀기 시험(3차 리뷰 4): 지금 묶인 세션이 기대한 값일 때만 풀고, 값으로 비교한다. */
@Tag("voice-session")
class DeviceConnectionTest {

    private final DeviceConnection connection =
            new DeviceConnection(UUID.randomUUID(), "0123456789abcdef0123456789abcdef", Instant.EPOCH);

    @Test
    void unbindsOnlyWhenTheExpectedSessionIsStillBoundComparingByValue() {
        UUID first = UUID.randomUUID();
        connection.bindSession(first);

        assertThat(connection.unbindSessionIf(UUID.randomUUID())).as("다른 세션").isFalse();
        assertThat(connection.boundSessionId()).isEqualTo(first);
        assertThat(connection.unbindSessionIf(new UUID(first.getMostSignificantBits(), first.getLeastSignificantBits())))
                .as("값이 같은 다른 객체").isTrue();
        assertThat(connection.boundSessionId()).isNull();
        assertThat(connection.unbindSessionIf(first)).as("이미 풀림").isFalse();
        assertThat(connection.unbindSessionIf(null)).isFalse();
    }
}
