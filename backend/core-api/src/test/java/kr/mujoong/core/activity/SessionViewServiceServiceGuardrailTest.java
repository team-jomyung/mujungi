package kr.mujoong.core.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.*;
import kr.mujoong.contract.guardrail.GuardrailPresentation;
import kr.mujoong.contract.status.*;
import kr.mujoong.core.activity.RealtimeSessionClient.*;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.support.TestEvents;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.Tag("service-guardrail")
class SessionViewServiceServiceGuardrailTest {
    private final MutableClock clock = new MutableClock();
    private final TestEvents events = new TestEvents(clock);
    private final UUID guardian = UUID.randomUUID(), session = UUID.randomUUID();
    private final ActivityCopyRepository copies = mock(ActivityCopyRepository.class);
    private final RealtimeSessionClient realtime = mock(RealtimeSessionClient.class);
    private final SessionViewService service = new SessionViewService(copies, realtime,
            new StaleEvaluator(Duration.ofSeconds(30), events.metrics(), events.logger()), events.metrics(), clock);
    private void owned() { when(copies.findOwned(session, guardian)).thenReturn(Optional.of(ActivityCopies.copy(session, guardian, SessionStatus.CLOSING, 5, clock.now()))); }
    private Map<String,Object> facts(String participation, String goodbye) {
        var map = new LinkedHashMap<String,Object>(); map.put("schemaVersion", 1); map.put("notice", "COMPLETED");
        map.put("participation", participation); map.put("goodbye", goodbye); map.put("confirmedStopBubbleSeq", null); return map;
    }
    private void result(Map<String,Object> facts) {
        when(realtime.view(session, 0)).thenReturn(new ViewResult(ViewKind.OK, new InternalSessionView(session, ActivityType.FREE_TALK,
                SessionStatus.CLOSING, EndReason.CHILD_STOP, null, false, clock.now(), null, null, 5L, clock.now(),
                List.of(), 0L, List.of(), null, null, null, facts)));
    }
    @Test void successfulInternalFactsAreRelayed() { owned(); result(facts("ACCEPTED", "PLAYING")); assertThat(service.view(guardian, session, 0).guardrail().goodbye()).isEqualTo(GuardrailPresentation.Goodbye.PLAYING); }
    @Test void legacyMissingObjectIsNull() { owned(); result(null); assertThat(service.view(guardian, session, 0).guardrail()).isNull(); }
    @Test void fallbackIsNullEvenWhenStaleIsFalse() {
        owned(); result(facts("ACCEPTED", "PLAYING")); service.view(guardian, session, 0);
        when(realtime.view(session, 0)).thenReturn(new ViewResult(ViewKind.UNAVAILABLE, null));
        var view = service.view(guardian, session, 0); assertThat(view.stale()).isFalse(); assertThat(view.guardrail()).isNull();
    }
    @Test void sameStatusVersionStillAcceptsNewGoodbyeFacts() {
        owned(); result(facts("ACCEPTED", "PLAYING")); service.view(guardian, session, 0);
        result(facts("ACCEPTED", "COMPLETED")); assertThat(service.view(guardian, session, 0).guardrail().goodbye()).isEqualTo(GuardrailPresentation.Goodbye.COMPLETED);
    }
    @Test void refusalAndNoResponseRemainDistinct() {
        owned(); result(facts("REFUSED", "NONE")); assertThat(service.view(guardian, session, 0).guardrail().participation()).isEqualTo(GuardrailPresentation.Participation.REFUSED);
        result(facts("NO_RESPONSE", "NONE")); assertThat(service.view(guardian, session, 0).guardrail().participation()).isEqualTo(GuardrailPresentation.Participation.NO_RESPONSE);
    }
    @Test void malformedFactsCannotInventSuccess() {
        owned(); var raw = facts("ACCEPTED", "FUTURE"); result(raw); assertThat(service.view(guardian, session, 0).guardrail()).isNull();
        raw = facts("ACCEPTED", "PLAYING"); raw.put("rawTranscript", "secret"); result(raw); assertThat(service.view(guardian, session, 0).guardrail()).isNull();
    }
    @Test void anUnknownObjectStaysUnknown() { owned(); result(facts("UNKNOWN", "UNKNOWN")); assertThat(service.view(guardian, session, 0).guardrail().participation()).isEqualTo(GuardrailPresentation.Participation.UNKNOWN); }
    @Test void ownershipIsCheckedBeforeTheInternalCall() {
        when(copies.findOwned(session, guardian)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.view(guardian, session, 0)).isInstanceOf(CoreApiException.class);
        verifyNoInteractions(realtime);
    }
}
