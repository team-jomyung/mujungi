package kr.mujoong.realtime.judgment;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.luna.LunaException;
import kr.mujoong.realtime.luna.LunaFailureReason;
import kr.mujoong.realtime.luna.LunaPurpose;
import kr.mujoong.realtime.luna.LunaRequest;
import kr.mujoong.realtime.luna.LunaResult;

/**
 * U9 {@code RealtimeLunaClient}(목적 JUDGMENT_LIVE)로 판정하는 구현 (U11 BR1.1 ~ BR1.7 · BR4.3). 판독 불가 입력(전사 공백 · 신뢰도 낮음)은
 * 공급자를 부르지 않고 UNREADABLE_INPUT 보류다. 그 밖에는 Luna를 정확히 한 번 부른다: 시한은 호출자 마감과 live-budget 중 짧은 쪽(마감이
 * 이미 닿았으면 부르지 않고 TIMEOUT), 재시도 없음. 실패 사유는 TIMEOUT · SCHEMA_INVALID · PROVIDER_ERROR로 줄이고 어떤 경우도 예외로 던지지
 * 않는다. 입력 · 출력 본문은 로그에 쓰지 않는다(R-F14).
 */
public final class LunaJudgeClient implements JudgeClient {

    private final BiFunction<LunaRequest, UUID, CompletionStage<LunaResult>> luna;
    private final JudgmentPrompt prompt;
    private final JudgeOutcomeValidator validator;
    private final MujoongClock clock;

    /**
     * @param luna 보통 {@code RealtimeLunaClient::call}. 시험에서는 루프백 대역에 붙은 클라이언트를 쓴다
     */
    public LunaJudgeClient(Function<LunaRequest, CompletionStage<LunaResult>> luna, JudgmentPrompt prompt,
            JudgeOutcomeValidator validator, MujoongClock clock) {
        this(withoutUsage(luna), prompt, validator, clock);
    }

    private LunaJudgeClient(BiFunction<LunaRequest, UUID, CompletionStage<LunaResult>> luna, JudgmentPrompt prompt,
            JudgeOutcomeValidator validator, MujoongClock clock) {
        this.luna = Objects.requireNonNull(luna, "luna");
        this.prompt = Objects.requireNonNull(prompt, "prompt");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static LunaJudgeClient withUsage(BiFunction<LunaRequest, UUID, CompletionStage<LunaResult>> luna,
            JudgmentPrompt prompt, JudgeOutcomeValidator validator, MujoongClock clock) {
        return new LunaJudgeClient(luna, prompt, validator, clock);
    }

    private static BiFunction<LunaRequest, UUID, CompletionStage<LunaResult>> withoutUsage(
            Function<LunaRequest, CompletionStage<LunaResult>> luna) {
        Objects.requireNonNull(luna, "luna");
        return (request, turnId) -> luna.apply(request);
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public CompletionStage<JudgeOutcome> judge(JudgeRequest request, UUID turnId) {
        Objects.requireNonNull(request, "request");
        if (request.childUtterance().isBlank() || request.uncertain()) {
            return CompletableFuture.completedFuture(
                    JudgeOutcome.abstained(AbstainReason.UNREADABLE_INPUT, Duration.ZERO, null, prompt.version()));
        }
        long started = clock.monotonicNanos();
        CompletionStage<LunaResult> call;
        try {
            call = luna.apply(new LunaRequest(LunaPurpose.JUDGMENT_LIVE, prompt.version(), prompt.instructions(),
                    request.toLunaInput(), request.aliases(), request.deadline()), turnId);
        } catch (RuntimeException unexpected) {
            return CompletableFuture.completedFuture(failure(unexpected, clock.elapsedSince(started)));
        }
        CompletableFuture<LunaResult> lunaFuture = call.toCompletableFuture();
        CompletableFuture<JudgeOutcome> outcome = new CompletableFuture<>();
        lunaFuture.whenComplete((result, failure) -> {
            Duration latency = clock.elapsedSince(started);
            if (failure != null) {
                outcome.complete(failure(failure, latency));
                return;
            }
            try {
                outcome.complete(
                        validator.validate(result.outputJson(), request.options(), latency, result.responseModel(), prompt.version()));
            } catch (RuntimeException unexpected) {
                outcome.complete(JudgeOutcome.abstained(AbstainReason.SCHEMA_INVALID, latency, result.responseModel(), prompt.version()));
            }
        });
        // 판정 턴이 닫히며 결과를 취소하면 진행 중인 공급자 요청도 취소한다(U9 호출부가 취소를 받아 연결을 끊는다)
        outcome.whenComplete((value, failure) -> {
            if (outcome.isCancelled()) {
                lunaFuture.cancel(true);
            }
        });
        return outcome;
    }

    private JudgeOutcome failure(Throwable failure, Duration latency) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        AbstainReason reason = AbstainReason.PROVIDER_ERROR;
        if (cause instanceof LunaException luna) {
            LunaFailureReason lunaReason = luna.reason();
            if (lunaReason == LunaFailureReason.TIMEOUT) {
                reason = AbstainReason.TIMEOUT;
            } else if (lunaReason == LunaFailureReason.SCHEMA_INVALID) {
                reason = AbstainReason.SCHEMA_INVALID;
            }
        }
        return JudgeOutcome.abstained(reason, latency, null, prompt.version());
    }
}
