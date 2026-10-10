package kr.mujoong.realtime.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 사건 로그 반복 병합의 식별 값 (U1 3.5 Step 35에서 드러난 결함 · SD-15 · OD-3 4번 · NFR9.14).
 *
 * <p>쿠키 없는 업그레이드(SECURITY_AUTH_FAILED INFO)와 무효 · 해제 토큰(같은 attrs, WARN)은 서로 다른 줄이어야 한다 — 수준이 병합 키에
 * 없으면 WARN이 INFO 줄의 repeat로 묻힌다. 같은 수준 · 같은 attrs의 반복은 여전히 첫 줄 + repeat 한 줄로 합친다.
 */
@Tag("u1")
class EventLoggerMergeTest {

    private final MutableClock clock = new MutableClock();
    private final LogCapture logs = LogCapture.detached();
    private final EventLogger events = EventLogger.forTest(clock, logs, RealtimeMetrics.inMemory());

    @Test
    void anInfoAndAWarnWithTheSameAttrsAreWrittenAsTwoLines() {
        authFailed(EventCode.Level.INFO);
        authFailed(null);

        assertThat(logs.events(EventCode.SECURITY_AUTH_FAILED)).extracting(event -> event.get("level"))
                .containsExactly("INFO", "WARN");
    }

    @Test
    void sameLevelRepeatsInsideTheWindowMergeIntoOneRepeatLine() {
        authFailed(null);
        authFailed(null);
        authFailed(null);
        assertThat(logs.events(EventCode.SECURITY_AUTH_FAILED)).hasSize(1);

        clock.advance(Duration.ofSeconds(11));
        events.flushMerged();

        Map<String, Object> repeat = logs.events(EventCode.SECURITY_AUTH_FAILED).getLast();
        assertThat(logs.events(EventCode.SECURITY_AUTH_FAILED)).hasSize(2);
        assertThat(LogCapture.attrs(repeat)).containsEntry("repeat", 2).containsEntry("kind", "DEVICE_TOKEN");
        assertThat(repeat.get("level")).isEqualTo("WARN");
    }

    @Test
    void differentAttrsAreNeverMergedEvenAtTheSameLevel() {
        authFailed(null);
        events.log(EventCode.SECURITY_AUTH_FAILED, EventLog.Component.DEVICE_GATEWAY, null,
                entry -> entry.attr(AttrKey.KIND, "DEVICE_TOKEN").attr(AttrKey.STATUS_CODE, 1013L));

        assertThat(logs.events(EventCode.SECURITY_AUTH_FAILED)).extracting(event -> LogCapture.attrs(event)
                .get("statusCode")).containsExactly(4401, 1013);
    }

    private void authFailed(EventCode.Level level) {
        events.log(EventCode.SECURITY_AUTH_FAILED, EventLog.Component.DEVICE_GATEWAY, null, entry -> {
            entry.attr(AttrKey.KIND, "DEVICE_TOKEN").attr(AttrKey.STATUS_CODE, 4401L);
            if (level != null) {
                entry.level(level);
            }
        });
    }
}
