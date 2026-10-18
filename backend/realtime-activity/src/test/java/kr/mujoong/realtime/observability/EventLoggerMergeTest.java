package kr.mujoong.realtime.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.realtime.testing.TestEventLoggers;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 사건 로그 반복 병합의 식별 값 (U1 3.5 Step 35에서 드러난 결함 · SD-15 · OD-3 4번 · NFR9.14).
 *
 * <p>쿠키 없는 업그레이드(SECURITY_AUTH_FAILED INFO)와 무효 · 해제 토큰(같은 attrs, WARN)은 서로 다른 줄이어야 한다 — 수준이 병합 키에
 * 없으면 WARN이 INFO 줄의 repeat로 묻힌다. 같은 수준 · 같은 attrs의 반복은 여전히 첫 줄 + repeat 한 줄로 합친다.
 *
 * <p>3.6 수정(BT-TC-10)에서 병합 창 경계를 더했다: 반복이 없던 창은 repeat 줄을 남기지 않는다 · 창은 병합 창 길이(운영 기본 10초)
 * 정각에 닫힌다(한 틱 전은 같은 창) · 호출마다 달라지는 값(durationMs · attempt)은 병합 키가 아니고 INTERNAL_HTTP 성공 줄은 병합하지 않는다.
 */
@Tag("skeleton")
class EventLoggerMergeTest {

    private final MutableClock clock = new MutableClock();
    private final LogCapture logs = LogCapture.detached();
    private final EventLogger events = TestEventLoggers.strict(clock, logs, RealtimeMetrics.inMemory());

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

    @Test
    void aWindowWithoutRepeatsClosesWithoutARepeatLine() {
        authFailed(null);

        clock.advance(TestEventLoggers.MERGE_WINDOW.plusSeconds(1));
        events.flushMerged();
        events.flushMerged();

        assertThat(logs.events(EventCode.SECURITY_AUTH_FAILED)).hasSize(1)
                .allSatisfy(line -> assertThat(LogCapture.attrs(line)).doesNotContainKey("repeat"));
    }

    @Test
    void theWindowClosesExactlyAtItsLengthAndNotOneTickEarlier() {
        appError("ProviderDisconnected");
        clock.advance(TestEventLoggers.MERGE_WINDOW.minusMillis(1));
        appError("ProviderDisconnected");
        events.flushMerged();
        // 한 틱 전: 같은 창이라 첫 줄만 있다
        assertThat(logs.events(EventCode.APP_ERROR)).hasSize(1);

        clock.advance(Duration.ofMillis(1));
        appError("ProviderDisconnected");

        // 창 정각: 지난 창의 repeat 줄(1) 다음에 새 창의 첫 줄이 바로 나온다
        assertThat(logs.events(EventCode.APP_ERROR)).extracting(line -> LogCapture.attrs(line).get("repeat"))
                .containsExactly(null, 1, null);
    }

    @Test
    void internalHttpFailuresMergeAcrossDurationAndAttemptButSuccessLinesAreNeverMerged() {
        internalHttp("TIMEOUT", 1_000L, 1L);
        internalHttp("TIMEOUT", 1_003L, 2L);
        internalHttp("TIMEOUT", 998L, 3L);
        internalHttp("OK", 12L, 1L);
        internalHttp("OK", 12L, 1L);

        clock.advance(TestEventLoggers.MERGE_WINDOW);
        events.flushMerged();

        List<Map<String, Object>> lines = logs.events(EventCode.INTERNAL_HTTP);
        assertThat(lines).extracting(line -> LogCapture.attrs(line).get("outcome"))
                .containsExactly("TIMEOUT", "OK", "OK", "TIMEOUT");
        assertThat(LogCapture.attrs(lines.getLast())).containsEntry("repeat", 2).containsEntry("durationMs", 1_000)
                .containsEntry("attempt", 1);
        assertThat(lines).extracting(line -> line.get("level")).containsExactly("WARN", "INFO", "INFO", "WARN");
    }

    private void appError(String kind) {
        events.appError(EventLog.Component.VOICE_RELAY, kind, EventCode.Level.WARN);
    }

    private void internalHttp(String outcome, long durationMs, long attempt) {
        events.log(EventCode.INTERNAL_HTTP, EventLog.Component.DEVICE_GATEWAY, null, entry -> entry
                .attr(AttrKey.TARGET, "DEVICE_STATUS").attr(AttrKey.OUTCOME, outcome)
                .attr(AttrKey.DURATION_MS, durationMs).attr(AttrKey.ATTEMPT, attempt));
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
