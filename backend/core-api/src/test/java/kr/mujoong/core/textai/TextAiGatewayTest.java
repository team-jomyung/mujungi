package kr.mujoong.core.textai;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.*;

@Tag("practice-core")
class TextAiGatewayTest {
    private final MutableClock clock = new MutableClock();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ForbiddenExpressionPolicy policy = new ForbiddenExpressionPolicy();
    private final ScenarioPrompt prompt = new ScenarioPrompt("목표", "행동", "친구가 멈추자고 말한다",
            "놀이", "친구", 8, List.of("공룡"), null);

    @AfterEach void close() { executor.shutdownNow(); Thread.interrupted(); }

    private TextAiGateway gateway(TextAiProvider provider) {
        return gateway(provider, Duration.ofSeconds(3), Duration.ZERO);
    }
    private TextAiGateway gateway(TextAiProvider provider, Duration timeout, Duration backoff) {
        return new TextAiGateway(provider, new TextAiProperties(timeout, new TextAiProperties.Retry(1, backoff)),
                policy, clock, executor);
    }
    private GeneratedScenario valid() {
        return new GeneratedScenario("만들어진 이야기야. 친구가 멈추자고 말한다", "이제 그만하자",
                "친구", "친구가 멈추자고 말한다", "test-model-v1", "scenario-v1");
    }
    @Test void typedRequestHasOnlyMinimumFieldsAndOutputRetainsVersions() {
        AtomicReference<ScenarioPrompt> captured = new AtomicReference<>();
        AtomicReference<Duration> limit = new AtomicReference<>();
        TextAiGateway gateway = gateway((request, timeout) -> {
            captured.set(request); limit.set(timeout); return valid();
        });
        assertThat(gateway.generateScenario(prompt, clock.now().plusSeconds(15))).isEqualTo(valid());
        assertThat(captured.get()).isSameAs(prompt);
        assertThat(limit.get()).isEqualTo(Duration.ofSeconds(3));
        assertThat(ScenarioPrompt.class.getRecordComponents()).extracting("name")
                .containsExactly("goalDisplayName", "behaviorDefinition", "opportunityCondition",
                        "scenarioCondition", "relationType", "age", "interests", "previousScenarioText");
        // U5a 리뷰 7: 상대 역할 지시는 Core 고정 문장이라 공급자 출력에 그 칸이 없다(검사하지 않는 값으로 정상 상황을 거절하지 않는다)
        assertThat(GeneratedScenario.class.getRecordComponents()).extracting("name")
                .containsExactly("scenarioText", "triggerScript", "relationType", "opportunityCondition",
                        "modelVersion", "promptVersion");
        assertThat(prompt.toString()).doesNotContain("공룡");
        assertThat(valid().toString()).doesNotContain("친구");
    }
    @Test void transientFailureRetriesOnceAndReturnsSecondResult() {
        AtomicInteger calls = new AtomicInteger();
        var gateway = gateway((request, timeout) -> {
            if (calls.incrementAndGet() == 1) throw new TextAiException(TextAiException.Reason.UNAVAILABLE, true);
            return valid();
        });
        assertThat(gateway.generateScenario(prompt, clock.now().plusSeconds(15))).isEqualTo(valid());
        assertThat(calls).hasValue(2);
    }
    @Test void repeatedTransientFailureStopsAtTwoCalls() {
        AtomicInteger calls = new AtomicInteger();
        var gateway = gateway((request, timeout) -> {
            calls.incrementAndGet(); throw new TextAiException(TextAiException.Reason.UNAVAILABLE, true);
        });
        assertThatThrownBy(() -> gateway.generateScenario(prompt, clock.now().plusSeconds(15)))
                .isInstanceOf(TextAiException.class).hasMessageContaining("UNAVAILABLE");
        assertThat(calls).hasValue(2);
    }
    @Test void providerFailureDoesNotExposeMessageOrRetryUnknownFailure() {
        AtomicInteger calls = new AtomicInteger();
        var gateway = gateway((request, timeout) -> {
            calls.incrementAndGet(); throw new IllegalStateException("secret child transcript");
        });
        assertThatThrownBy(() -> gateway.generateScenario(prompt, clock.now().plusSeconds(15)))
                .isInstanceOf(TextAiException.class).hasMessage("Text AI failure: UNAVAILABLE").hasNoCause();
        assertThat(calls).hasValue(1);
    }
    @Test void forbiddenOutputDoesNotConsumeTechnicalRetry() {
        AtomicInteger calls = new AtomicInteger();
        var gateway = gateway((request, timeout) -> {
            calls.incrementAndGet();
            return new GeneratedScenario("꼭 해야 해요", "그만", "친구", "기회", "m1", "p1");
        });
        assertThatThrownBy(() -> gateway.generateScenario(prompt, clock.now().plusSeconds(15)))
                .isInstanceOf(TextAiException.class).hasMessageContaining("FORBIDDEN_OUTPUT");
        assertThat(calls).hasValue(1);
    }
    @Test void missingMetadataAndMissingOutputAreRejected() {
        for (GeneratedScenario invalid : new GeneratedScenario[]{
                null, new GeneratedScenario("본문", "트리거", "관계", "기회", null, "p1"),
                new GeneratedScenario("본문", "트리거", "관계", "기회", "private metadata", "p1"),
                new GeneratedScenario("본문", "트리거", "관계", "기회", "m1", "")}) {
            assertThatThrownBy(() -> gateway((request, timeout) -> invalid)
                    .generateScenario(prompt, clock.now().plusSeconds(15)))
                    .isInstanceOf(TextAiException.class).hasMessageContaining("INVALID_OUTPUT");
        }
    }
    @Test void expiredDeadlinePreventsAnyProviderCall() {
        AtomicInteger calls = new AtomicInteger();
        var gateway = gateway((request, timeout) -> { calls.incrementAndGet(); return valid(); });
        assertThatThrownBy(() -> gateway.generateScenario(prompt, clock.now()))
                .hasMessageContaining("TIMEOUT");
        assertThat(calls).hasValue(0);
    }
    @Test void sharedDeadlineBoundsAttemptTimeoutAndRejectsLateSuccess() {
        AtomicReference<Duration> limit = new AtomicReference<>();
        var gateway = gateway((request, timeout) -> {
            limit.set(timeout); clock.advance(Duration.ofSeconds(2)); return valid();
        });
        assertThatThrownBy(() -> gateway.generateScenario(prompt, clock.now().plusSeconds(1)))
                .hasMessageContaining("TIMEOUT");
        assertThat(limit.get()).isEqualTo(Duration.ofSeconds(1));
    }
    @Test void backoffCannotCrossRemainingBudget() {
        AtomicInteger calls = new AtomicInteger();
        var gateway = gateway((request, timeout) -> {
            calls.incrementAndGet(); clock.advance(Duration.ofMillis(900));
            throw new TextAiException(TextAiException.Reason.UNAVAILABLE, true);
        }, Duration.ofSeconds(3), Duration.ofMillis(250));
        assertThatThrownBy(() -> gateway.generateScenario(prompt, clock.now().plusSeconds(1)))
                .hasMessageContaining("TIMEOUT");
        assertThat(calls).hasValue(1);
    }
    @Test void timedOutProviderIsInterruptedAndRetriesAreBounded() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger interrupted = new AtomicInteger();
        CountDownLatch interruptions = new CountDownLatch(2);
        var gateway = gateway((request, timeout) -> {
            calls.incrementAndGet();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException stop) { interrupted.incrementAndGet(); interruptions.countDown(); }
            return valid();
        }, Duration.ofMillis(80), Duration.ZERO);
        assertThatThrownBy(() -> gateway.generateScenario(prompt, clock.now().plusSeconds(2)))
                .hasMessageContaining("TIMEOUT");
        try { assertThat(interruptions.await(2, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException stop) { throw new AssertionError(stop); }
        assertThat(calls).hasValue(2);
        assertThat(interrupted).hasValue(2);
    }
    @Test void cancelledCallerStartsNoWork() {
        AtomicInteger calls = new AtomicInteger();
        Thread.currentThread().interrupt();
        assertThatThrownBy(() -> gateway((request, timeout) -> { calls.incrementAndGet(); return valid(); })
                .generateScenario(prompt, clock.now().plusSeconds(15))).hasMessageContaining("TIMEOUT");
        assertThat(Thread.interrupted()).isTrue();
        assertThat(calls).hasValue(0);
    }
    @Test void inFlightCallerCancellationInterruptsProvider() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var gateway = gateway((request, timeout) -> {
            started.countDown();
            try { new CountDownLatch(1).await(); } catch (InterruptedException stop) { interrupted.countDown(); }
            return valid();
        });
        Thread caller = Thread.ofVirtual().start(() -> {
            try { gateway.generateScenario(prompt, clock.now().plusSeconds(15)); }
            catch (Throwable caught) { failure.set(caught); }
        });
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        caller.interrupt(); caller.join(2000);
        assertThat(caller.isAlive()).isFalse();
        assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isInstanceOf(TextAiException.class).hasMessageContaining("TIMEOUT");
    }

    @Test void outputClassificationCannotOptIntoTechnicalRetry() {
        AtomicInteger calls = new AtomicInteger();
        var gateway = gateway((request, timeout) -> {
            calls.incrementAndGet(); throw new TextAiException(TextAiException.Reason.INVALID_OUTPUT,true);
        });
        assertThatThrownBy(() -> gateway.generateScenario(prompt,clock.now().plusSeconds(15)))
                .hasMessageContaining("INVALID_OUTPUT");
        assertThat(calls).hasValue(1);
    }
    @Test void oversizedOutputIsRejectedBeforeFurtherProcessing() {
        var gateway = gateway((request, timeout) ->
                new GeneratedScenario("가".repeat(8001),"트리거","관계","기회","m1","p1"));
        assertThatThrownBy(() -> gateway.generateScenario(prompt,clock.now().plusSeconds(15)))
                .hasMessageContaining("INVALID_OUTPUT");
    }
    @Test void stoppedExecutorReturnsSanitizedUnavailable() {
        executor.shutdownNow();
        assertThatThrownBy(() -> gateway((request, timeout) -> valid()).generateScenario(prompt,clock.now().plusSeconds(15)))
                .isInstanceOf(TextAiException.class).hasMessage("Text AI failure: UNAVAILABLE").hasNoCause();
    }
    @Test void wallClockRollbackCannotExtendTheMonotonicBudget() {
        Instant start=clock.now();
        var gateway=gateway((request,timeout) -> {
            clock.advance(Duration.ofSeconds(2));
            clock.setInstant(start.minusSeconds(100));
            return valid();
        });
        assertThatThrownBy(() -> gateway.generateScenario(prompt,start.plusSeconds(1)))
                .hasMessageContaining("TIMEOUT");
    }
}
