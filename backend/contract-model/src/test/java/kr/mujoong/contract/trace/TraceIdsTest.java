package kr.mujoong.contract.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** traceId 형식 · 생성(OD-7 · NFR5.7 · SD-4 F1). */
@Tag("u1")
class TraceIdsTest {

    @Test
    void newTraceIdIs32LowercaseHexAndUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String traceId = TraceIds.newTraceId();
            assertThat(traceId).hasSize(TraceIds.LENGTH).matches("^[0-9a-f]{32}$");
            assertThat(TraceIds.isValid(traceId)).isTrue();
            seen.add(traceId);
        }
        assertThat(seen).hasSize(200);
    }

    @Test
    void wellFormedIncomingValueIsKept() {
        String incoming = "4bf92f3577b34da6a3ce929d0e0e4736";

        assertThat(TraceIds.isValid(incoming)).isTrue();
        assertThat(TraceIds.parse(incoming)).contains(incoming);
        assertThat(TraceIds.orNew(incoming)).isEqualTo(incoming);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "4BF92F3577B34DA6A3CE929D0E0E4736",
        "4bf92f3577b34da6a3ce929d0e0e473",
        "4bf92f3577b34da6a3ce929d0e0e47360",
        "4bf92f35-77b3-4da6-a3ce-929d0e0e4736",
        "4bf92f3577b34da6a3ce929d0e0e473g",
        "4bf92f3577b34da6a3ce929d0e0e473\n",
        "4bf92f3577b34da6\"a3ce929d0e0e4736"
    })
    void malformedValuesAreReplacedWithANewTraceId(String candidate) {
        assertThat(TraceIds.isValid(candidate)).isFalse();
        assertThat(TraceIds.parse(candidate)).isEmpty();
        String replaced = TraceIds.orNew(candidate);
        assertThat(TraceIds.isValid(replaced)).isTrue();
        assertThat(replaced).isNotEqualTo(candidate);
    }

    @Test
    void headerAndMdcNamesAreFixed() {
        assertThat(TraceIds.HEADER).isEqualTo("X-Trace-Id");
        assertThat(TraceIds.MDC_KEY).isEqualTo("traceId");
    }
}
