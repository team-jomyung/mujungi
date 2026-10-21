package kr.mujoong.realtime.dialog;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.Tag("service-guardrail")
class ConversationPolicyServiceGuardrailTest {
    private final ConversationPolicyState state = new ConversationPolicyState();
    private final UUID request = UUID.randomUUID();

    @Test void noHelpWithoutAnActualRequest() { assertThat(state.allowHelp(true)).isFalse(); }
    @Test void noHelpWithoutAnActualUnderstandingFailure() {
        state.selectRequest(request, false);
        assertThat(state.allowHelp(false)).isFalse();
        assertThat(state.allowHelp(true)).isTrue();
    }
    @Test void helpIsConsumedBeforeTheEffectAndCannotRepeat() {
        state.selectRequest(request, false);
        assertThat(state.allowHelp(true)).isTrue();
        assertThat(state.allowHelp(true)).isFalse();
    }
    @Test void aTurnOrRephraseDoesNotInventANewSemanticRequest() {
        state.selectRequest(request, false);
        state.allowHelp(true);
        state.selectRequest(UUID.randomUUID(), false);
        assertThat(state.currentRequest()).isEqualTo(request);
        assertThat(state.allowHelp(true)).isFalse();
    }
    @Test void anExplicitlyDifferentRequestHasItsOwnBudget() {
        state.selectRequest(request, false);
        state.refuseCurrent();
        state.selectRequest(UUID.randomUUID(), true);
        assertThat(state.allowHelp(true)).isTrue();
    }
    @Test void refusalPreventsReaskingTheSameInformation() {
        state.selectRequest(request, false);
        state.refuseCurrent();
        state.selectRequest(request, true);
        assertThat(state.refusedRequest()).isEqualTo(request);
        assertThat(state.allowHelp(true)).isFalse();
    }
    @Test void restAllowsExactlyOneAcknowledgmentAndNoHelp() {
        state.selectRequest(request, false);
        assertThat(state.rest()).isTrue();
        assertThat(state.rest()).isFalse();
        assertThat(state.claimRestAck()).isTrue();
        assertThat(state.claimRestAck()).isFalse();
        assertThat(state.allowHelp(true)).isFalse();
    }
    @Test void onlyNewInputResumesRestWithoutResettingHelp() {
        state.selectRequest(request, false);
        state.allowHelp(true);
        state.rest();
        state.resumeOnNewInput();
        assertThat(state.resting()).isFalse();
        assertThat(state.claimRestAck()).isFalse();
        assertThat(state.allowHelp(true)).isFalse();
    }
}
