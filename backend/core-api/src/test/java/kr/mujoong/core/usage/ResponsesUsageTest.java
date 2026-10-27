package kr.mujoong.core.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Responses 응답 본문 → 사용량 행. 입력은 합성 본문이다. 증거 종류: 테스트 대역(R-M11). */
@Tag("ai-usage")
class ResponsesUsageTest {

    private static final JsonCodec JSON = JsonCodec.shared();
    private static final Instant AT = Instant.parse("2026-10-24T00:00:00Z");
    private static final AiUsageLink LINK = new AiUsageLink(UUID.randomUUID(), UUID.randomUUID());

    private static AiUsageRecord read(String body, String outcome) {
        return ResponsesUsage.read(JSON, new ResponsesUsage.Attempt(UUID.randomUUID(), 1, "RESULT_TEXT", LINK, "v1",
                "gpt-6-luna", outcome, body, AT));
    }

    @Test
    void aCompletedResponseKeepsAllTokenCountsWithoutAddingTheDetailsTwice() {
        AiUsageRecord record = read("""
                {"id":"resp_1","model":"gpt-6-luna-2026-10","status":"completed",
                 "usage":{"input_tokens":1200,"input_tokens_details":{"cached_tokens":200,"cache_write_tokens":700},
                          "output_tokens":300,"output_tokens_details":{"reasoning_tokens":40},"total_tokens":1500}}
                """, "OK");

        assertThat(record.status()).isEqualTo(AiUsageRecord.Status.COMPLETE);
        assertThat(record.inputTokens()).isEqualTo(1200L);
        assertThat(record.outputTokens()).isEqualTo(300L);
        assertThat(record.cachedInputTokens()).isEqualTo(200L);
        assertThat(record.cacheWriteTokens()).as("캐시 쓰기는 별도 단가라 따로 보관한다").isEqualTo(700L);
        assertThat(record.reasoningTokens()).isEqualTo(40L);
        assertThat(record.responseId()).isEqualTo("resp_1");
        assertThat(record.model()).isEqualTo("gpt-6-luna-2026-10");
        assertThat(record.link()).isEqualTo(LINK);
        assertThat(record.observedAt()).isEqualTo(AT);
    }

    @Test
    void aRejectedBusinessResponseStillRecordsTheUsageItReturned() {
        AiUsageRecord record = read("""
                {"id":"resp_2","model":"gpt-6-luna","status":"incomplete","usage":{"input_tokens":900}}
                """, "LENGTH_EXCEEDED");

        assertThat(record.status()).isEqualTo(AiUsageRecord.Status.PARTIAL);
        assertThat(record.inputTokens()).isEqualTo(900L);
        assertThat(record.cacheWriteTokens()).as("응답에 없으면 미확인").isNull();
        assertThat(record.outputTokens()).isNull();
        assertThat(record.outcome()).isEqualTo("LENGTH_EXCEEDED");
    }

    @Test
    void noBodyOrABrokenBodyIsUnknownNotZero() {
        for (String body : new String[] {null, "not json", "[]", "{\"usage\":{\"input_tokens\":-1,\"output_tokens\":\"7\"}}"}) {
            AiUsageRecord record = read(body, "TIMEOUT");

            assertThat(record.status()).as(String.valueOf(body)).isEqualTo(AiUsageRecord.Status.UNKNOWN);
            assertThat(record.inputTokens()).isNull();
            assertThat(record.outputTokens()).isNull();
            assertThat(record.model()).as("응답 모델이 없으면 요청 모델").isEqualTo("gpt-6-luna");
            assertThat(record.responseId()).isNull();
        }
    }

    @Test
    void detailsLargerThanTheirTotalAreDroppedInsteadOfRejectingTheRecord() {
        AiUsageRecord record = read("""
                {"usage":{"input_tokens":10,"input_tokens_details":{"cached_tokens":11,"cache_write_tokens":11},
                          "output_tokens":5,"output_tokens_details":{"reasoning_tokens":6}}}
                """, "OK");

        assertThat(record.status()).isEqualTo(AiUsageRecord.Status.COMPLETE);
        assertThat(record.cachedInputTokens()).isNull();
        assertThat(record.cacheWriteTokens()).isNull();
        assertThat(record.reasoningTokens()).isNull();
    }

    @Test
    void cacheReadPlusCacheWriteBeyondTheInputDropsOnlyTheWrite() {
        AiUsageRecord record = read("""
                {"usage":{"input_tokens":100,"input_tokens_details":{"cached_tokens":60,"cache_write_tokens":50},
                          "output_tokens":5}}
                """, "OK");

        assertThat(record.cachedInputTokens()).isEqualTo(60L);
        assertThat(record.cacheWriteTokens()).isNull();
        assertThat(record.status()).isEqualTo(AiUsageRecord.Status.COMPLETE);
    }

    @Test
    void theRecordTextNeverCarriesIdentifiersOrNumbers() {
        AiUsageRecord record = read("{\"id\":\"resp_3\",\"usage\":{\"input_tokens\":1,\"output_tokens\":2}}", "OK");

        assertThat(record.toString()).doesNotContain("resp_3").doesNotContain(LINK.guardianId().toString());
    }
}
