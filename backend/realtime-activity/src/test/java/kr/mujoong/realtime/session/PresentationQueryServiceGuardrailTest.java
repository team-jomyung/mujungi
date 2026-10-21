package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.UUID;
import kr.mujoong.contract.guardrail.GuardrailPresentation;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.SessionStatus;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.Tag("service-guardrail")
class PresentationQueryServiceGuardrailTest {
    private final Instant now = Instant.parse("2026-10-21T00:00:00Z");
    private final UUID id = UUID.randomUUID();
    private ActivitySessionRow row(SessionStatus status) {
        var row = mock(ActivitySessionRow.class);
        when(row.id()).thenReturn(id); when(row.status()).thenReturn(status); when(row.statusVersion()).thenReturn(5);
        when(row.endReason()).thenReturn(EndReason.CHILD_STOP);
        return row;
    }
    private SessionGuardrailFacts facts(SessionGuardrailFacts.Goodbye goodbye) {
        return new SessionGuardrailFacts(1, id, 3, 5, SessionGuardrailFacts.Notice.COMPLETED,
                SessionGuardrailFacts.Participation.ACCEPTED, goodbye, 7, now);
    }
    private GuardrailProjection actor(SessionGuardrailFacts facts) {
        return new GuardrailProjection(facts, SessionStatus.CLOSING, UUID.randomUUID(), now.plusSeconds(20));
    }
    @Test void legacyFactsAreNull() { assertThat(SessionViewQuery.presentation(row(SessionStatus.CLOSING), null, null, now, false)).isNull(); }
    @Test void matchingCurrentActorShowsPlayingAndAnExistingChildReference() {
        var facts = facts(SessionGuardrailFacts.Goodbye.PLAYING);
        var view = SessionViewQuery.presentation(row(SessionStatus.CLOSING), facts, actor(facts), now, true);
        assertThat(view.goodbye()).isEqualTo(GuardrailPresentation.Goodbye.PLAYING);
        assertThat(view.confirmedStopBubbleSeq()).isEqualTo(7);
    }
    @Test void anAbsentActorCannotRestorePlayingAfterRestart() {
        assertThat(SessionViewQuery.presentation(row(SessionStatus.CLOSING), facts(SessionGuardrailFacts.Goodbye.PLAYING), null, now, true).goodbye())
                .isEqualTo(GuardrailPresentation.Goodbye.UNKNOWN);
    }
    @Test void terminalStatusCannotClaimPlaying() {
        var facts = facts(SessionGuardrailFacts.Goodbye.PLAYING);
        assertThat(SessionViewQuery.presentation(row(SessionStatus.ENDED), facts, actor(facts), now, true).goodbye()).isEqualTo(GuardrailPresentation.Goodbye.UNKNOWN);
    }
    @Test void aDifferentStatusVersionIsNotCombined() {
        var row = row(SessionStatus.CLOSING); when(row.statusVersion()).thenReturn(6);
        assertThat(SessionViewQuery.presentation(row, facts(SessionGuardrailFacts.Goodbye.PLAYING), null, now, true)).isNull();
    }
    @Test void sameStatusVersionWithANewerDynamicRevisionReplacesPlaying() {
        var old = facts(SessionGuardrailFacts.Goodbye.PLAYING);
        var latest = new SessionGuardrailFacts(1, id, 4, 5, old.notice(), old.participation(), SessionGuardrailFacts.Goodbye.COMPLETED, 7, now);
        assertThat(SessionViewQuery.presentation(row(SessionStatus.CLOSING), latest, actor(old), now, true).goodbye()).isEqualTo(GuardrailPresentation.Goodbye.COMPLETED);
    }
    @Test void deletedUncertainOrNonChildReferenceIsOmitted() {
        assertThat(SessionViewQuery.presentation(row(SessionStatus.CLOSING), facts(SessionGuardrailFacts.Goodbye.COMPLETED), null, now, false).confirmedStopBubbleSeq()).isNull();
        var row = row(SessionStatus.CLOSING); when(row.endReason()).thenReturn(EndReason.GUARDIAN_STOP);
        assertThat(SessionViewQuery.presentation(row, facts(SessionGuardrailFacts.Goodbye.COMPLETED), null, now, true).confirmedStopBubbleSeq()).isNull();
    }
    @Test void expiredOrConflictingActorEvidenceIsUnknown() {
        var facts = facts(SessionGuardrailFacts.Goodbye.PENDING);
        assertThat(SessionViewQuery.presentation(row(SessionStatus.CLOSING), facts, actor(facts), now.plusSeconds(20), false).goodbye())
                .isEqualTo(GuardrailPresentation.Goodbye.UNKNOWN);
        var old = facts(SessionGuardrailFacts.Goodbye.PLAYING);
        assertThat(SessionViewQuery.presentation(row(SessionStatus.CLOSING), facts, actor(old), now, false).goodbye())
                .isEqualTo(GuardrailPresentation.Goodbye.UNKNOWN);
    }
}
