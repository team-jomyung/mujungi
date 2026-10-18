package kr.mujoong.core.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import kr.mujoong.contract.event.payload.SessionStateChanged;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.core.activity.ActivityCopyRepository.ActivityCopy;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("web-push")
class PushEventRulesTest {
    private static final UUID SESSION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-14T01:00:00Z");

    @ParameterizedTest
    @CsvSource({"ENDED,CHILD_STOP,true,true,ACTIVITY_COMPLETED",
            "ENDED,GUARDIAN_STOP,true,true,ACTIVITY_COMPLETED", "ENDED,SILENCE,true,true,ACTIVITY_COMPLETED",
            "ABORTED,DISCONNECT,true,true,ENDED_BY_CONNECTION", "ABORTED,DISCONNECT,true,false,ENDED_BY_CONNECTION"})
    void emitsOnlyEligibleConfirmedEnds(String status, String reason, boolean confirmed, boolean started, String type) {
        var event = PushEventRules.evaluate(copy(status, reason, confirmed, started), null, false).orElseThrow();
        assertThat(event.type().name()).isEqualTo(type);
        assertThat(event.eventKey()).isEqualTo(SESSION + ":" + type);
    }

    @ParameterizedTest
    @CsvSource({"CLOSING,CHILD_STOP,true,true", "ENDED_UNCONFIRMED,CHILD_STOP,false,true",
            "DECLINED,CHILD_STOP,true,false", "FAILED,FAILED,true,true", "ABORTED,CHILD_STOP,true,true",
            "ENDED,CHILD_STOP,true,false", "ENDED,CHILD_STOP,false,true", "ENDED,DISCONNECT,true,true",
            "ABORTED,DISCONNECT,false,true", "ENDED,CONSENT_WITHDRAWN,true,true"})
    void doesNotInventCompletion(String status, String reason, boolean confirmed, boolean started) {
        assertThat(PushEventRules.evaluate(copy(status, reason, confirmed, started), null, false)).isEmpty();
    }

    @Test
    void onlyLatestPauseOfOngoingSessionEmitsAndRepeatedPauseHasStableKey() {
        var payload = mock(SessionStateChanged.class);
        var pause = mock(SessionStateChanged.TechnicalPause.class);
        when(payload.technicalPause()).thenReturn(pause);
        when(pause.kind()).thenReturn(SessionStateChanged.TechnicalPause.Kind.DEVICE);
        when(pause.since()).thenReturn(NOW);
        var copy = copy("ACTIVE", null, false, true);
        var first = PushEventRules.evaluate(copy, payload, true).orElseThrow();
        assertThat(first.type()).isEqualTo(PushEventType.VOICE_PROBLEM);
        assertThat(PushEventRules.evaluate(copy, payload, true)).contains(first);
        assertThat(PushEventRules.evaluate(copy, payload, false)).isEmpty();
        assertThat(PushEventRules.evaluate(copy("FAILED", "FAILED", true, true), payload, true)).isEmpty();
    }

    @Test
    void missingAndUnknownPauseDoesNotEmit() {
        var payload = mock(SessionStateChanged.class);
        assertThat(PushEventRules.evaluate(copy("ACTIVE", null, false, true), payload, true)).isEmpty();
        var pause = mock(SessionStateChanged.TechnicalPause.class);
        when(payload.technicalPause()).thenReturn(pause);
        when(pause.since()).thenReturn(NOW);
        when(pause.kind()).thenReturn(SessionStateChanged.TechnicalPause.Kind.UNRECOGNIZED);
        assertThat(PushEventRules.evaluate(copy("ACTIVE", null, false, true), payload, true)).isEmpty();
    }

    private static ActivityCopy copy(String status, String reason, boolean confirmed, boolean started) {
        var copy = mock(ActivityCopy.class);
        when(copy.sessionId()).thenReturn(SESSION);
        when(copy.status()).thenReturn(SessionStatus.valueOf(status));
        when(copy.endReason()).thenReturn(reason);
        when(copy.confirmed()).thenReturn(confirmed);
        when(copy.startedAt()).thenReturn(started ? NOW : null);
        return copy;
    }
}
