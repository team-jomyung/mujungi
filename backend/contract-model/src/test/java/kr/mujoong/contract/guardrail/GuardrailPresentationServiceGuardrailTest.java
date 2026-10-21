package kr.mujoong.contract.guardrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.*;
import kr.mujoong.contract.json.JsonCodec;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.Tag("service-guardrail")
class GuardrailPresentationServiceGuardrailTest {
    private Map<String,Object> raw() {
        var raw = new LinkedHashMap<String,Object>(); raw.put("schemaVersion", 1); raw.put("notice", "COMPLETED");
        raw.put("participation", "ACCEPTED"); raw.put("goodbye", "NONE"); raw.put("confirmedStopBubbleSeq", null); return raw;
    }
    @Test void validFactsRoundTripWithAnExplicitNullableSequence() {
        var parsed = GuardrailPresentation.parse(raw());
        assertThat(JsonCodec.shared().write(parsed)).contains("\"confirmedStopBubbleSeq\":null");
        assertThat(parsed.participation()).isEqualTo(GuardrailPresentation.Participation.ACCEPTED);
    }
    @Test void legacyAbsenceIsNull() { assertThat(GuardrailPresentation.parse(null)).isNull(); }
    @Test void futureSchemaIsNotAssumedToMeanSuccess() { var raw = raw(); raw.put("schemaVersion", 2); assertThat(GuardrailPresentation.parse(raw)).isNull(); }
    @Test void unknownFieldsCannotExposeRawText() { var raw = raw(); raw.put("text", "secret"); assertThat(GuardrailPresentation.parse(raw)).isNull(); }
    @Test void missingFieldsAreNotDefaultedToSuccess() { var raw = raw(); raw.remove("notice"); assertThat(GuardrailPresentation.parse(raw)).isNull(); }
    @Test void invalidEnumsAndTypesAreRejected() {
        var raw = raw(); raw.put("goodbye", "FUTURE"); assertThat(GuardrailPresentation.parse(raw)).isNull();
        raw.put("goodbye", 1); assertThat(GuardrailPresentation.parse(raw)).isNull();
    }
    @Test void sequenceMustBeAPositiveIntegerInRange() {
        for (Object seq : List.of(0, -1, 1.5, Long.MAX_VALUE, "7")) {
            var raw = raw(); raw.put("confirmedStopBubbleSeq", seq); assertThat(GuardrailPresentation.parse(raw)).isNull();
        }
        var raw = raw(); raw.put("confirmedStopBubbleSeq", 7); assertThat(GuardrailPresentation.parse(raw).confirmedStopBubbleSeq()).isEqualTo(7);
    }
    @Test void constructorRejectsInvalidSchemaAndSequence() {
        assertThatThrownBy(() -> new GuardrailPresentation(2, GuardrailPresentation.Notice.UNKNOWN,
                GuardrailPresentation.Participation.UNKNOWN, GuardrailPresentation.Goodbye.UNKNOWN, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GuardrailPresentation(1, GuardrailPresentation.Notice.UNKNOWN,
                GuardrailPresentation.Participation.UNKNOWN, GuardrailPresentation.Goodbye.UNKNOWN, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
