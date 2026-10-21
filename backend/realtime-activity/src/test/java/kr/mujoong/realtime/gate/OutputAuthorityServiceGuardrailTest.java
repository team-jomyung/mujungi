package kr.mujoong.realtime.gate;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import kr.mujoong.contract.status.ActivityType;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.Tag("service-guardrail")
class OutputAuthorityServiceGuardrailTest {
    private final UUID session = UUID.randomUUID();
    private final Object candidate = new Object();
    private OutputAuthority authority(OutputAuthority.Purpose purpose) {
        return new OutputAuthority(session, ActivityType.FREE_TALK, 3, 7, "service-0.1", "jev-guardrail-v2", purpose);
    }
    private boolean permits(OutputAuthority value, boolean active, boolean resting, Object body) {
        return value.permits(session, ActivityType.FREE_TALK, 3, 7, "service-0.1", "jev-guardrail-v2", active, resting, body);
    }
    @Test void currentNormalOutputIsPermitted() { assertThat(permits(authority(OutputAuthority.Purpose.NORMAL), true, false, candidate)).isTrue(); }
    @Test void endingRevokesEvenAnAllowedCandidate() { assertThat(permits(authority(OutputAuthority.Purpose.NORMAL), false, false, candidate)).isFalse(); }
    @Test void restRevokesNormalOutput() { assertThat(permits(authority(OutputAuthority.Purpose.NORMAL), true, true, candidate)).isFalse(); }
    @Test void restAckAllowsOnlyOneCandidateAndItsFrames() {
        var token = authority(OutputAuthority.Purpose.REST_ACK);
        assertThat(permits(token, true, true, candidate)).isTrue();
        assertThat(permits(token, true, true, candidate)).isTrue();
        assertThat(permits(token, true, true, new Object())).isFalse();
    }
    @Test void ackCannotBecomeAnOrdinaryReplyAfterNewInput() { assertThat(permits(authority(OutputAuthority.Purpose.REST_ACK), true, false, candidate)).isFalse(); }
    @Test void aDifferentSessionCannotReuseAuthority() {
        assertThat(authority(OutputAuthority.Purpose.NORMAL).permits(UUID.randomUUID(), ActivityType.FREE_TALK, 3, 7,
                "service-0.1", "jev-guardrail-v2", true, false, candidate)).isFalse();
    }
    @Test void modeOrGenerationChangesRevokeAuthority() {
        var token = authority(OutputAuthority.Purpose.NORMAL);
        assertThat(token.permits(session, ActivityType.PRACTICE, 3, 7, "service-0.1", "jev-guardrail-v2", true, false, candidate)).isFalse();
        assertThat(token.permits(session, ActivityType.FREE_TALK, 4, 7, "service-0.1", "jev-guardrail-v2", true, false, candidate)).isFalse();
        assertThat(token.permits(session, ActivityType.FREE_TALK, 3, 8, "service-0.1", "jev-guardrail-v2", true, false, candidate)).isFalse();
    }
    @Test void serviceAndJevVersionsAreSeparateAuthorities() {
        var token = authority(OutputAuthority.Purpose.NORMAL);
        assertThat(token.permits(session, ActivityType.FREE_TALK, 3, 7, "other", "jev-guardrail-v2", true, false, candidate)).isFalse();
        assertThat(token.permits(session, ActivityType.FREE_TALK, 3, 7, "service-0.1", "other", true, false, candidate)).isFalse();
    }
}
