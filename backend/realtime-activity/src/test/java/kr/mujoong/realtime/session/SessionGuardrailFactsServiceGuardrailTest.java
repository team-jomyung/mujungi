package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.session.SessionGuardrailFacts.Goodbye;
import kr.mujoong.realtime.session.SessionGuardrailFacts.Notice;
import kr.mujoong.realtime.session.SessionGuardrailFacts.Participation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("service-guardrail")
class SessionGuardrailFactsServiceGuardrailTest {

    private static final UUID SESSION = UUID.randomUUID();
    private static final Instant AT = Instant.parse("2026-10-21T00:00:00Z");

    @Test
    void happyPathRoundTripsOnlyMinimalFacts() {
        SessionGuardrailFacts facts = value(1, 3, Notice.COMPLETED, Participation.ACCEPTED, 1);
        String encoded = JsonCodec.shared().write(facts);
        assertThat(JsonCodec.shared().read(encoded, SessionGuardrailFacts.class)).isEqualTo(facts);
        assertThat(encoded).doesNotContain("text", "audio", "prompt", "token", "transcript");
    }

    @Test
    void cannotAcceptBeforeNoticeCompletion() {
        assertThatThrownBy(() -> value(1, 3, Notice.PENDING, Participation.ACCEPTED, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void revisionMustBePositive() {
        assertThatThrownBy(() -> value(0, 3, Notice.UNKNOWN, Participation.UNKNOWN, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void statusVersionMustBePositive() {
        assertThatThrownBy(() -> value(1, 0, Notice.UNKNOWN, Participation.UNKNOWN, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unsupportedSchemaAndMissingEnumsFail() {
        assertThatThrownBy(() -> new SessionGuardrailFacts(2, SESSION, 1, 1, Notice.UNKNOWN,
                Participation.UNKNOWN, Goodbye.NONE, null, AT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> value(1, 1, null, Participation.UNKNOWN, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void transcriptReferenceMustBePositiveWhenPresent() {
        assertThatThrownBy(() -> value(1, 1, Notice.UNKNOWN, Participation.UNKNOWN, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(value(1, 1, Notice.UNKNOWN, Participation.UNKNOWN, null).confirmedStopBubbleSeq()).isNull();
    }

    @Test
    void changingStatusVersionPreservesRevisionAndImmutableOriginal() {
        SessionGuardrailFacts before = value(2, 3, Notice.COMPLETED, Participation.ACCEPTED, null);
        SessionGuardrailFacts after = before.atVersion(4);
        assertThat(before.statusVersion()).isEqualTo(3);
        assertThat(after.statusVersion()).isEqualTo(4);
        assertThat(after.revision()).isEqualTo(2);
        assertThat(after.sessionId()).isEqualTo(SESSION);
    }

    private static SessionGuardrailFacts value(long revision, int version, Notice notice,
            Participation participation, Integer seq) {
        return new SessionGuardrailFacts(1, SESSION, revision, version, notice, participation, Goodbye.NONE, seq, AT);
    }
}
