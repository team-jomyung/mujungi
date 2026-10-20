package kr.mujoong.realtime.guardrail;

import static kr.mujoong.realtime.guardrail.GuardrailFixtures.POLICY;
import static kr.mujoong.realtime.guardrail.GuardrailFixtures.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionStage;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.runtime.TaskLauncher;
import kr.mujoong.realtime.testing.TestEventLoggers;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 제브 대역 · 비활성 구현 · 결과 값 · 장치 묶음 시험 (U13 BR1.2 · BR1.4 · BR1.5 · BR1.6). 대역은 제브 단계의 모양만 지나며 대역 표시가 붙은 ALLOW를 즉시
 * 돌려주고, 어댑터가 없는 REAL은 자동 ALLOW가 아니라 막힘(UNAVAILABLE)으로 끝나며, 장치를 넣지 않는 기본값은 '검사 없음'(OFF)이다. 이 파일의 성공은
 * 테스트 대역 증거이고 실제 검사 성공이 아니다(R-M11).
 */
@Tag("jev-guardrail")
class StubGuardrailClientTest {

    private static final GuardrailRequest REQUEST = GuardrailRequest.of(GuardrailRequest.Mode.FREE_TALK, "합성 후보 문장", POLICY, List.of());
    private static final Instant DEADLINE = T0.plus(Duration.ofSeconds(1));

    private static GuardrailVerdict done(CompletionStage<GuardrailVerdict> stage) {
        return stage.toCompletableFuture().getNow(null);
    }

    @Test
    void theStubAllowsImmediatelyWithTheStubReasonCodeAndTheRequestsPolicyVersionAndIsMarkedAsAStub() {
        StubGuardrailClient stub = new StubGuardrailClient();

        GuardrailVerdict verdict = done(stub.check(REQUEST, DEADLINE));

        assertThat(verdict).as("즉시 완료").isNotNull();
        assertThat(verdict.isAllow()).isTrue();
        assertThat(verdict.reasonCode()).isEqualTo(GuardrailVerdict.STUB_ALLOW);
        assertThat(verdict.policyVersion()).isEqualTo(POLICY);
        assertThat(verdict.latencyMs()).isZero();
        assertThat(stub.mode()).as("모든 기록 · 지표에 대역 표시").isEqualTo(GuardrailMode.STUB);
        assertThat(stub.available()).isTrue();
        assertThat(verdict.eventDecision()).isEqualTo(GuardrailEventRecord.Decision.ALLOW);
    }

    @Test
    void anAdapterlessRealClientNeverAllowsItBlocksWithAnUnavailableFailure() {
        UnavailableGuardrailClient unavailable = new UnavailableGuardrailClient(GuardrailMode.REAL);

        GuardrailVerdict verdict = done(unavailable.check(REQUEST, DEADLINE));

        assertThat(unavailable.available()).isFalse();
        assertThat(unavailable.mode()).isEqualTo(GuardrailMode.REAL);
        assertThat(verdict.decision()).isEqualTo(GuardrailVerdict.Decision.FAILED);
        assertThat(verdict.failure()).isEqualTo(GuardrailVerdict.Failure.UNAVAILABLE);
        assertThat(verdict.isAllow()).as("자동 ALLOW 없음(BR1.2)").isFalse();
        assertThat(verdict.eventDecision()).isEqualTo(GuardrailEventRecord.Decision.ERROR);
        assertThatThrownBy(() -> new UnavailableGuardrailClient(GuardrailMode.STUB)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theDefaultSupportMeansNoCheckAndIsMarkedOffRatherThanAnAutoAllowingReal() {
        GuardrailSupport none = GuardrailSupport.DISABLED;

        assertThat(none.mode()).isEqualTo(GuardrailMode.OFF);
        assertThat(none.enabled()).isFalse();
        assertThat(none.recorder()).as("OFF는 기록이 없다").isNull();
        assertThat(none.client().available()).isFalse();
        assertThat(none.budget()).isEqualTo(Duration.ZERO);
    }

    @Test
    void aSupportMustCarryAClientOfItsOwnModeAnOffSupportCannotRecordAndAnActiveOneNeedsAPositiveBudget() {
        StubGuardrailClient stub = new StubGuardrailClient();
        UnavailableGuardrailClient real = new UnavailableGuardrailClient(GuardrailMode.REAL);
        GuardrailEventRecorder recorder = new GuardrailEventRecorder(record -> { }, TaskLauncher.inline(),
                TestEventLoggers.strict(new MutableClock(), LogCapture.detached(), RealtimeMetrics.inMemory()));
        Duration budget = Duration.ofMillis(1);

        assertThat(new GuardrailSupport(GuardrailMode.STUB, stub, recorder, budget).enabled()).isTrue();
        assertThatThrownBy(() -> new GuardrailSupport(GuardrailMode.REAL, stub, null, budget))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GuardrailSupport(GuardrailMode.OFF, new UnavailableGuardrailClient(GuardrailMode.OFF), recorder, budget))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GuardrailSupport(GuardrailMode.REAL, real, null, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aVerdictCarriesAFailureOnlyWhenFailedAndKeepsTheReasonCodeAsACodeNotASentence() {
        assertThatThrownBy(() -> new GuardrailVerdict(GuardrailVerdict.Decision.ALLOW, GuardrailVerdict.Failure.TIMEOUT, null, null, "OK",
                POLICY, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GuardrailVerdict(GuardrailVerdict.Decision.FAILED, null, null, null, "OK", POLICY, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GuardrailVerdict.block("문장 사유", POLICY, 1, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GuardrailVerdict.allow("OK", POLICY, -1, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyFailureKindIsABlockAndOnlyATimeoutIsRecordedAsATimeout() {
        for (GuardrailVerdict.Failure failure : GuardrailVerdict.Failure.values()) {
            GuardrailVerdict verdict = GuardrailVerdict.failed(failure, POLICY, 800);

            assertThat(verdict.isAllow()).as(failure.name()).isFalse();
            assertThat(verdict.reasonCode()).isEqualTo(failure.name());
            assertThat(verdict.eventDecision()).as(failure.name()).isEqualTo(failure == GuardrailVerdict.Failure.TIMEOUT
                    ? GuardrailEventRecord.Decision.TIMEOUT : GuardrailEventRecord.Decision.ERROR);
        }
        assertThat(GuardrailVerdict.block("SELF_HARM", POLICY, 300, List.of("SELF_HARM"), List.of()).eventDecision())
                .isEqualTo(GuardrailEventRecord.Decision.BLOCK);
        assertThat(GuardrailVerdict.review("NEEDS_REVIEW", POLICY, 300, List.of(), List.of("FLAG")).eventDecision())
                .isEqualTo(GuardrailEventRecord.Decision.REVIEW);
        assertThat(GuardrailVerdict.review("NEEDS_REVIEW", POLICY, 300, List.of(), List.of("FLAG")).isAllow()).isFalse();
    }
}
