package kr.mujoong.realtime.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 공급자 응답 식별자 형식 시험(U3-J11 · SD-20): 형식을 지킨 값만 공급자 취소 요청에 되돌려 보낸다. */
@Tag("u3")
class ProviderResponseIdTest {

    @ParameterizedTest
    @ValueSource(strings = {"resp-1", "r", "resp_2026.10:15-a", "ABCxyz0123"})
    void wellFormedIdsAreAccepted(String raw) {
        assertThat(ProviderResponseId.of(raw)).map(ProviderResponseId::value).contains(raw);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "resp 1", "응답", "resp\n1", "a\"b", "{\"x\":1}", "../etc"})
    void anythingElseIsRefused(String raw) {
        assertThat(ProviderResponseId.of(raw)).isEmpty();
        assertThatThrownBy(() -> new ProviderResponseId(raw)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theLengthIsCappedAt128AndNullIsEmpty() {
        assertThat(ProviderResponseId.of("a".repeat(128))).isPresent();
        assertThat(ProviderResponseId.of("a".repeat(129))).isEmpty();
        assertThat(ProviderResponseId.of(null)).isEmpty();
        assertThatThrownBy(() -> new ProviderResponseId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theFactoryTurnsAMissingIdIntoACancelOfTheCurrentResponse() {
        ProviderRequestFactory factory = new ProviderRequestFactory();

        assertThat(factory.responseCancel(null)).isEqualTo(
                new ProviderRequestFactory.ResponseCancel(ProviderRequestFactory.TYPE_RESPONSE_CANCEL, null));
        assertThat(factory.responseCancel(new ProviderResponseId("resp-9")).responseId()).isEqualTo("resp-9");
    }
}
