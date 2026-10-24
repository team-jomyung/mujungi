package kr.mujoong.realtime.judgment;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.luna.LunaDecision;
import kr.mujoong.realtime.luna.LunaDecisionQuestion;
import kr.mujoong.realtime.luna.LunaException;
import kr.mujoong.realtime.luna.LunaFailureReason;
import kr.mujoong.realtime.luna.LunaPurpose;
import kr.mujoong.realtime.luna.LunaRequest;

/**
 * 진행 중 판정 구현 — Decisions API (U11 BR1.1 ~ BR1.7 · BR4.3, FT-1023-d). 진행 중 판정은 이 한 길뿐이다(2026-10-23 결정, 대체 경로 없음).
 * 판독 불가 입력(전사 공백 · 신뢰도 낮음)은 공급자 없이 UNREADABLE_INPUT 보류다. 그 밖에는 U9 호출부를 정확히 한 번 부른다: 시한은 호출자
 * 마감과 live-budget 중 짧은 쪽(마감이 이미 닿았으면 부르지 않고 TIMEOUT), 재시도 없음. 실패는 TIMEOUT · SCHEMA_INVALID · PROVIDER_ERROR
 * 보류로 줄여 예외로 던지지 않고, 결과를 취소하면 진행 중인 요청도 취소한다.
 *
 * <p>질문은 판정 지시문 · 선택지 판({@link JudgmentPrompt})에서 만든다: 지시 본문 + 그 지점 질문이 질문 지시문이고, 선택지는 요청 선택지(판
 * 순서)에 판의 뜻을 {@code description}으로 붙인다. 데이터({@link JudgeRequest#toLunaInput()})는 질문이 아니라 입력 자리로 간다(지시와 데이터
 * 분리, U9 BR2.5). 1 · 2순위는 선택지 확률 내림차순이고(같으면 공급자가 고른 값, 그다음 판 순서), 하한 · 최소 차이는
 * {@link JudgeOutcomeValidator#validateRanked}로 확인한다. 입력 · 출력 본문은 로그에 쓰지 않는다(R-F14).
 */
public final class DecisionsJudgeClient implements JudgeClient {

    /** 질문 이름(응답 답과 맞추는 이름, 공급자로 가지만 데이터가 아니다). */
    static final String QUESTION_NAME = "judgment";

    private final BiFunction<LunaRequest, LunaDecisionQuestion, CompletionStage<LunaDecision>> decisions;
    private final JudgmentPrompt prompt;
    private final JudgeOutcomeValidator validator;
    private final MujoongClock clock;
    private final Map<JudgmentPoint, String> instructions = new EnumMap<>(JudgmentPoint.class);

    /**
     * @param decisions 보통 {@code RealtimeLunaClient::decide}. 시험에서는 루프백 대역에 붙은 클라이언트를 쓴다
     */
    public DecisionsJudgeClient(BiFunction<LunaRequest, LunaDecisionQuestion, CompletionStage<LunaDecision>> decisions,
            JudgmentPrompt prompt, JudgeOutcomeValidator validator, MujoongClock clock) {
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.prompt = Objects.requireNonNull(prompt, "prompt");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.clock = Objects.requireNonNull(clock, "clock");
        // 지점별 질문 지시문을 기동 때 한 번 만든다(자원이 없으면 기동 실패, 판정마다 자원을 읽지 않는다)
        for (JudgmentPoint point : JudgmentPoint.values()) {
            instructions.put(point, prompt.instructions(point));
        }
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public CompletionStage<JudgeOutcome> judge(JudgeRequest request, UUID turnId) {
        Objects.requireNonNull(request, "request");
        String version = prompt.version();
        if (request.childUtterance().isBlank() || request.uncertain()) {
            return CompletableFuture.completedFuture(
                    JudgeOutcome.abstained(AbstainReason.UNREADABLE_INPUT, Duration.ZERO, null, version));
        }
        long started = clock.monotonicNanos();
        CompletionStage<LunaDecision> call;
        try {
            call = decisions.apply(new LunaRequest(LunaPurpose.JUDGMENT_LIVE, version, instructions.get(request.point()),
                    request.toLunaInput(), request.aliases(), request.deadline()), question(request));
        } catch (RuntimeException unexpected) {
            return CompletableFuture.completedFuture(
                    JudgeOutcome.abstained(abstainReason(unexpected), clock.elapsedSince(started), null, version));
        }
        CompletableFuture<LunaDecision> pending = call.toCompletableFuture();
        CompletableFuture<JudgeOutcome> outcome = new CompletableFuture<>();
        pending.whenComplete((decision, failure) -> {
            Duration latency = clock.elapsedSince(started);
            if (failure != null) {
                outcome.complete(JudgeOutcome.abstained(abstainReason(failure), latency, null, version));
                return;
            }
            try {
                outcome.complete(rank(decision, request.options(), latency, version));
            } catch (RuntimeException unexpected) {
                outcome.complete(JudgeOutcome.abstained(AbstainReason.SCHEMA_INVALID, latency, decision.responseModel(), version));
            }
        });
        // 판정 턴이 닫히며 결과를 취소하면 진행 중인 공급자 요청도 취소한다(U9 호출부가 취소를 받아 연결을 끊는다)
        outcome.whenComplete((value, failure) -> {
            if (outcome.isCancelled()) {
                pending.cancel(true);
            }
        });
        return outcome;
    }

    /** 요청 선택지(판 순서)에 판의 선택지 뜻을 붙인 질문. */
    private LunaDecisionQuestion question(JudgeRequest request) {
        List<LunaDecisionQuestion.Choice> choices = request.options().stream()
                .map(code -> new LunaDecisionQuestion.Choice(code, prompt.choiceDescription(request.point(), code))).toList();
        return new LunaDecisionQuestion(QUESTION_NAME, choices);
    }

    /**
     * 확률 내림차순 1 · 2순위를 검증기에 넘긴다. 공급자 응답의 {@code confidence}는 쓰지 않는다: 우리 하한 규칙(minConfidence · minMargin)은
     * 1순위 확률과 1 · 2순위 확률 차이로 정의돼 있고, confidence는 그 정의가 공개되지 않은 별도 값이다.
     */
    private JudgeOutcome rank(LunaDecision decision, List<String> options, Duration latency, String version) {
        List<LunaDecision.Probability> ranked = new ArrayList<>(decision.probabilities());
        // 안정 정렬: 같은 확률이면 공급자가 고른 값이 앞, 그다음 판 순서(받은 순서)
        ranked.sort(Comparator.comparingDouble(LunaDecision.Probability::probability).reversed()
                .thenComparing(item -> !item.value().equals(decision.choice())));
        if (ranked.size() < 2) {
            return JudgeOutcome.abstained(AbstainReason.SCHEMA_INVALID, latency, decision.responseModel(), version);
        }
        LunaDecision.Probability first = ranked.get(0);
        LunaDecision.Probability second = ranked.get(1);
        return validator.validateRanked(first.value(), first.probability(), second.value(), second.probability(), options, latency,
                decision.responseModel(), version);
    }

    /** 호출 실패 → 보류 사유(TIMEOUT · SCHEMA_INVALID, 그 밖은 PROVIDER_ERROR). */
    static AbstainReason abstainReason(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        if (cause instanceof LunaException luna) {
            LunaFailureReason lunaReason = luna.reason();
            if (lunaReason == LunaFailureReason.TIMEOUT) {
                return AbstainReason.TIMEOUT;
            }
            if (lunaReason == LunaFailureReason.SCHEMA_INVALID) {
                return AbstainReason.SCHEMA_INVALID;
            }
        }
        return AbstainReason.PROVIDER_ERROR;
    }
}
