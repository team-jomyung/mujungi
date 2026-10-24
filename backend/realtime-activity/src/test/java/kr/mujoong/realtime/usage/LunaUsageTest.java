package kr.mujoong.realtime.usage;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("ai-usage")
class LunaUsageTest {
    private AiUsageRecord read(String body) {
        return LunaUsage.read(JsonCodec.shared(), body, UUID.randomUUID(), UUID.randomUUID(), "requested-model", Instant.EPOCH);
    }

    @Test void extractsUsageEvenWhenTheBusinessOutputIsInvalidAndDoesNotAddReasoningTwice() {
        var r = read("""
                {"id":"synthetic-response","model":"synthetic-model","status":"incomplete",
                 "usage":{"input_tokens":120,"output_tokens":80,"input_tokens_details":{"cached_tokens":20},
                 "output_tokens_details":{"reasoning_tokens":60}}}
                """);
        assertThat(r.status()).isEqualTo(AiUsageRecord.Status.COMPLETE);
        assertThat(r.inputTokens()).isEqualTo(120);
        assertThat(r.outputTokens()).isEqualTo(80);
        assertThat(r.reasoningTokens()).isEqualTo(60);
        assertThat(r.cachedInputTokens()).isEqualTo(20);
    }

    @Test void missingMalformedFractionalAndNegativeValuesAreNotZero() {
        for (String body : new String[] {null, "bad", "null", "{}", "{\"usage\":{\"input_tokens\":-1,\"output_tokens\":1.5}}"}) {
            var r = read(body);
            assertThat(r.status()).isEqualTo(AiUsageRecord.Status.UNKNOWN);
            assertThat(r.inputTokens()).isNull();
            assertThat(r.outputTokens()).isNull();
        }
    }

    @Test void actualZeroIsCompleteAndOneMissingSideIsPartial() {
        assertThat(read("{\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}").status())
                .isEqualTo(AiUsageRecord.Status.COMPLETE);
        assertThat(read("{\"usage\":{\"input_tokens\":12}}").status())
                .isEqualTo(AiUsageRecord.Status.PARTIAL);
    }
}
