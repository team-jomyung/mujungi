package kr.mujoong.core.knowledge;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.Map;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.luna.*;
import kr.mujoong.core.support.TestEvents;
import kr.mujoong.core.textai.TextAiProperties;
import kr.mujoong.testsupport.luna.LunaResponsesStub;
import kr.mujoong.testsupport.luna.LunaResponsesStub.Reply;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u15")
class U15LunaBudgetTest {
    static class Fixture implements AutoCloseable {
        final LunaResponsesStub stub = LunaResponsesStub.start();
        final MutableClock clock = new MutableClock();
        final CoreLunaClient client;
        final LunaRequest request;
        Fixture() {
            var options = new LunaProperties.PurposeOptions(4000, "medium");
            var properties = new LunaProperties("fake-model", stub.baseUrl(), 1000000,
                    new LunaProperties.Purposes(options, options, options, options, options, options));
            var timing = new TextAiProperties(Duration.ofSeconds(2), new TextAiProperties.Retry(1, Duration.ofMillis(20)),
                    new TextAiProperties.Result(Duration.ofSeconds(3), 0, 0.2, "v1"));
            var json = JsonCodec.shared();
            client = new CoreLunaClient(properties, timing, LunaSchemas.loadDefault(json), new LunaInputSanitizer(json),
                    new LunaTransport(stub.baseUrl(), LunaHosts.Mode.TEST, () -> "test-key-not-real-u15", 1000000),
                    new TestEvents(clock).logger(), clock, json, clock::advance);
            request = LunaRequest.of(LunaPurpose.SEED_GENERATION, "seed-generation.v2", "JSON만 출력한다",
                    Map.of("candidate_structure", Map.of("target_skill", "RECOGNIZE")), Map.of(), clock.now().plusSeconds(10));
        }
        Reply success() { return Reply.ok("fake-model", "{\"outcome\":\"NONE\",\"none_reason\":\"NO_VALID_SEED\"}"); }
        void ok() { stub.respond(success()); }
        @Override public void close() { stub.close(); }
    }
    @Test void schemaRetryConsumesContentCorrection() {
        try (var f = new Fixture()) {
            f.stub.respond(Reply.ok("fake-model", "{}"), f.success());
            var budget = new LunaCallBudget();
            assertThat(f.client.call(f.request, budget).schemaRetried()).isTrue();
            assertThat(budget.takeCorrection()).isFalse();
            assertThat(f.stub.requests()).hasSize(2);
        }
    }
    @Test void contentCorrectionPreventsSecondSchemaRetry() {
        try (var f = new Fixture()) {
            var budget = new LunaCallBudget();
            f.ok();
            f.client.call(f.request, budget);
            assertThat(budget.takeCorrection()).isTrue();
            f.stub.respond(Reply.ok("fake-model", "{}"));
            assertThatThrownBy(() -> f.client.call(f.request, budget)).isInstanceOf(LunaException.class);
            assertThat(f.stub.requests()).hasSize(2);
        }
    }
    @Test void technicalAndContentTogetherNeverExceedThree() {
        try (var f = new Fixture()) {
            var budget = new LunaCallBudget();
            f.stub.respond(Reply.status(503), f.success());
            f.client.call(f.request, budget);
            assertThat(budget.takeCorrection()).isTrue();
            f.ok();
            f.client.call(f.request, budget);
            assertThatThrownBy(() -> f.client.call(f.request, budget)).isInstanceOf(LunaException.class);
            assertThat(f.stub.requests()).hasSize(3);
        }
    }
    @Test void technicalRetryCannotResetAcrossCalls() {
        try (var f = new Fixture()) {
            var budget = new LunaCallBudget();
            f.stub.respond(Reply.status(503), f.success());
            f.client.call(f.request, budget);
            budget.takeCorrection();
            f.stub.respond(Reply.status(503));
            assertThatThrownBy(() -> f.client.call(f.request, budget)).isInstanceOf(LunaException.class);
            assertThat(f.stub.requests()).hasSize(3);
        }
    }
    @Test void deadlineIsNotExtendedForCorrection() {
        try (var f = new Fixture()) {
            var budget = new LunaCallBudget();
            f.ok();
            f.client.call(f.request, budget);
            budget.takeCorrection();
            f.clock.advance(Duration.ofSeconds(11));
            assertThatThrownBy(() -> f.client.call(f.request, budget)).isInstanceOf(LunaException.class);
            assertThat(f.stub.requests()).hasSize(1);
        }
    }
    @Test void v2SchemaRejectsExtraFields() {
        var schemas = LunaSchemas.loadDefault(JsonCodec.shared());
        assertThat(schemas.isValidOutput(LunaPurpose.SEED_GENERATION, "{\"outcome\":\"NONE\",\"none_reason\":\"NO_VALID_SEED\",\"raw\":\"secret\"}")).isFalse();
        assertThat(schemas.isValidOutput(LunaPurpose.SEED_GENERATION, JsonCodec.shared().write(U15RulesTest.output("뜻을 살핀다")))).isTrue();
    }
}
