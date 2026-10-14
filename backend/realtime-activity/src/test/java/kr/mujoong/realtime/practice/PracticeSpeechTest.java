package kr.mujoong.realtime.practice;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import kr.mujoong.realtime.voice.PracticeSpeech;
import kr.mujoong.realtime.voice.ProviderRequestFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u5b")
class PracticeSpeechTest {
    @Test void voiceRequestContainsOnlyQuotedSpeechAndRoleAndNoIdentifiers() {
        var speech = new PracticeSpeech(UUID.randomUUID(), UUID.randomUUID(), 3,
                PracticePorts.OutputKind.SCENARIO, "친구", "놀이터야.\n내 차례야.");
        var request = new ProviderRequestFactory().practiceSpeech(speech);
        assertThat(request.text()).contains("놀이터야.", "내 차례야.", "친구")
                .doesNotContain(speech.correlationId().toString(), speech.scenarioId().toString(), "judgmentCriteria");
        assertThat(speech.toString()).doesNotContain("놀이터", "친구");
    }
    @Test void gateAcceptsOnlyTheApprovedTextIgnoringWhitespaceAndRejectsAddedAdvice() {
        var speech = new PracticeSpeech(UUID.randomUUID(), UUID.randomUUID(), 1,
                PracticePorts.OutputKind.HELP_QUESTION, "친구", "도움을 받아 볼까?");
        assertThat(speech.matches("도움을  받아\n볼까?")).isTrue();
        assertThat(speech.matches("도움을 받아 볼까? 이렇게 말해 봐.")).isFalse();
        assertThat(speech.matches("")).isFalse();
    }
    @Test void speechHasBoundedTypedInput() {
        assertThatThrownBy(() -> new PracticeSpeech(UUID.randomUUID(), UUID.randomUUID(), 1,
                PracticePorts.OutputKind.CUE, "친구", "x".repeat(10202))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PracticeSpeech(UUID.randomUUID(), UUID.randomUUID(), 1,
                PracticePorts.OutputKind.CUE, "친구", "x\u0000y")).isInstanceOf(IllegalArgumentException.class);
    }
}
