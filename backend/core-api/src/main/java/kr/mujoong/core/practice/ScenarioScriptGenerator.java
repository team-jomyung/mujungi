package kr.mujoong.core.practice;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import kr.mujoong.core.textai.GeneratedScenario;
import kr.mujoong.core.textai.ScenarioScript;
import kr.mujoong.core.textai.ScenarioScriptProvider;
import kr.mujoong.core.textai.TextAiProperties;

/**
 * 생성 · 별도 검토 · 서버 검사. 내용 슬롯 1 · 기술 슬롯 1은 모든 호출이 공유한다(호출 상한 6).
 *
 * <p>그래프 경로는 첫 생성에서 BASE만 받아 단독 검토하고, 승인되면 그 BASE를 다음 생성에 {@code baseToPreserve}로 보내 VARIANT를 붙인다. 다음 생성이
 * 보존 BASE의 문구를 고쳐 쓰면 검토 전에 보존 BASE로 되돌리고(생성된 VARIANT는 그대로), 그래도 변형 때문에 실패하면 단독 승인된 같은 BASE를 추가 검토
 * 없이 발급한다. 이때 다시 부르지 않는 근거는 검토에 보낼 입력(대본 포함)이 단독 검토 때와 같다는 것이고, 코드는 그 입력을 그대로 비교한다. 서버
 * 검사는 언제나 다시 돈다.
 */
public final class ScenarioScriptGenerator {

    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    public record Input(Map<String, Object> payload, Map<String, String> aliases, ScenarioScriptValidation.Rules rules,
            boolean graphContextUsed, String goalContextJson) {
        public Input {
            payload = Map.copyOf(payload);
            aliases = Map.copyOf(aliases);
        }

        @Override
        public String toString() {
            return "ScenarioGenerationInput[redacted]";
        }
    }

    /**
     * 생성 경로에서 실제로 일어난 일(사유 코드만, 내용 없음). 결과와 실패에 실려 하니스 진단이 읽고, 발급 경로는 저장 기록
     * {@code generation.basePreservation}에도 남는다.
     */
    public enum Note {
        /** 첫 생성 BASE가 단독 검토에서 승인됐다. 다음 생성에 보존 BASE로 보낸다. */
        BASE_APPROVED_ALONE,
        /** 다음 생성이 보존 BASE를 고쳐 써서 검토 전에 보존 BASE로 되돌렸다(생성된 VARIANT는 그대로). */
        BASE_REPLACED,
        /** 검토에 보낼 입력이 단독 검토 때와 같아 그 검토 결과를 다시 썼다(검토 호출 없음). */
        REVIEW_REUSED,
        /** 서버 검사가 VARIANT를 뺐다(계획 없는 변형 · 계획 불일치 포함). */
        VARIANT_INVALID,
        /** 검토가 변형 사유로만 거절했다. */
        VARIANT_REJECTED,
        /** 서버 검사가 BASE를 거절했다. */
        BASE_INVALID,
        /** 검토가 승인하지 않았다(변형 사유만의 거절이 아님). */
        REVIEW_NOT_APPROVED,
        /** 그래프 맥락이 끝까지 오지 않아 그래프 없이 다시 만든다. */
        GRAPH_FALLBACK,
        /** BASE만 발급했다: 단독 검토 승인을 그대로 쓴다(추가 검토 없음). */
        BASE_ONLY_EARLIER_REVIEW,
        /** BASE만 발급했다: BASE만 담아 다시 검토했다. */
        BASE_ONLY_REVIEWED
    }

    /**
     * 발급 결과. {@code notes}는 생성 경로의 사유 코드이고, 발급 경로({@link Note#BASE_REPLACED} · {@link Note#BASE_ONLY_EARLIER_REVIEW} ·
     * {@link Note#BASE_ONLY_REVIEWED})는 저장 기록 {@code generation.basePreservation}에도 남는다.
     */
    public record Result(GeneratedScenario legacy, ScenarioRepository.Details details, List<Note> notes) {
        public Result {
            notes = List.copyOf(notes);
        }

        public Result(GeneratedScenario legacy, ScenarioRepository.Details details) {
            this(legacy, details, List.of());
        }
    }

    private final ScenarioScriptProvider provider;
    private final ScenarioScriptValidation validation;
    private final TextAiProperties timing;
    private final ExecutorService executor;
    private final Sleeper sleeper;

    public ScenarioScriptGenerator(ScenarioScriptProvider provider, ScenarioScriptValidation validation, TextAiProperties timing,
            ExecutorService executor, Sleeper sleeper) {
        this.provider = provider;
        this.validation = validation;
        this.timing = timing;
        this.executor = executor;
        this.sleeper = sleeper;
    }

    private static final class Budget {
        int calls;
        boolean technical;
    }

    /** 단독 검토에서 승인된 BASE의 검토 입력(대본 포함)과 그 결과. 같은 입력이면 같은 검토라 다시 부르지 않는다. */
    private record ApprovedAlone(Map<String, Object> reviewPayload, ScenarioScriptProvider.Reviewed reviewed) {
        boolean sameReview(Map<String, Object> payload) {
            return reviewPayload.equals(payload);
        }
    }

    public static final class ContentExhausted extends kr.mujoong.core.common.error.CoreApiException {
        private static final long serialVersionUID = 1L;
        boolean retryableDuplicate;
        private final List<Note> notes;

        ContentExhausted(List<Note> notes) {
            super(kr.mujoong.contract.error.ErrorCode.SCENARIO_GEN_FAILED);
            this.notes = List.copyOf(notes);
        }

        public boolean permanent() {
            return !retryableDuplicate;
        }

        /** 실패까지 일어난 일(사유 코드만). */
        public List<Note> notes() {
            return notes;
        }
    }

    public Result generate(Input input, ScenarioDeadline deadline) {
        var budget = new Budget();
        List<Note> notes = new ArrayList<>();
        ScenarioScript.Scene preserved = null;
        ApprovedAlone alone = null;
        String model = null;
        Input active = input;
        for (int correction = 0; correction <= 1; correction++) {
            final Input current = active;
            try {
                Map<String, Object> payload = new LinkedHashMap<>(current.payload());
                if (correction > 0) {
                    payload.put("correction", true);
                }
                if (preserved != null) {
                    payload.put("baseToPreserve", preserved);
                }
                var generated = invoke(() -> provider.generate(call(payload, current, deadline)), budget, deadline);
                if (generated == null || generated.script() == null || !metadata(generated.model())) {
                    throw content();
                }
                model = generated.model();
                var script = generated.script();
                boolean replaced = false;
                if (preserved != null && !script.scenes().isEmpty() && !preserved.equals(script.scenes().getFirst())) {
                    // 다음 생성이 보존 BASE의 문구(예: focusPoints)를 고쳐 썼다. 이미 승인된 BASE를 버리지 않고 되돌린 뒤 생성된 VARIANT만 함께 본다.
                    // 검토는 실제로 발급할 모습을 본다
                    script = withBase(script, preserved);
                    replaced = true;
                    notes.add(Note.BASE_REPLACED);
                }
                Input reviewedInput = current;
                boolean planMismatch = false;
                if (current.graphContextUsed() && current.rules().variants() instanceof ScenarioGraphContext.Session graph
                        && !script.scenes().isEmpty()) {
                    var plan = graph.plan(script.scenes().getFirst());
                    if (plan.isPresent()) {
                        var actual = withPlan(current, plan.get());
                        planMismatch = current.payload().containsKey("variantPlan")
                                && !current.payload().get("variantPlan").equals(actual.payload().get("variantPlan"));
                        if (!planMismatch) {
                            reviewedInput = actual;
                        }
                    }
                }
                Map<String, Object> reviewPayload = reviewPayload(script, reviewedInput);
                boolean reused = alone != null && alone.sameReview(reviewPayload);
                ScenarioScriptProvider.Reviewed reviewed;
                if (reused) {
                    reviewed = alone.reviewed();
                    notes.add(Note.REVIEW_REUSED);
                } else {
                    reviewed = review(reviewPayload, reviewedInput, budget, deadline);
                }
                var result = validation.inspect(script, current.rules());
                if (current.graphContextUsed() && script.scenes().size() > 1 && !current.payload().containsKey("variantPlan")
                        && result != ScenarioScriptValidation.Result.INVALID_BASE) {
                    result = ScenarioScriptValidation.Result.INVALID_VARIANT;
                }
                if (planMismatch && script.scenes().size() > 1 && result != ScenarioScriptValidation.Result.INVALID_BASE) {
                    result = ScenarioScriptValidation.Result.INVALID_VARIANT;
                }
                if (current.graphContextUsed() && current.rules().variants() instanceof ScenarioGraphContext.Session graph
                        && graph.fallbackRequired()) {
                    if (correction == 1) {
                        throw ScenarioDeadline.failed();
                    }
                    notes.add(Note.GRAPH_FALLBACK);
                    active = withoutGraph(current);
                    preserved = null;
                    alone = null;
                    continue;
                }
                if (correction == 0 && reviewedInput.payload().containsKey("variantPlan")
                        && result != ScenarioScriptValidation.Result.INVALID_BASE) {
                    active = reviewedInput;
                    boolean approved = reviewed.review().approved();
                    preserved = approved ? script.scenes().getFirst() : null;
                    // 단독 검토: 대본이 BASE 하나뿐이었다. 그 입력 그대로를 기억해 둔다
                    alone = approved && script.scenes().size() == 1 ? new ApprovedAlone(reviewPayload, reviewed) : null;
                    if (alone != null) {
                        notes.add(Note.BASE_APPROVED_ALONE);
                    } else if (!approved) {
                        note(notes, result, reviewed);
                    }
                    continue;
                }
                if (result == ScenarioScriptValidation.Result.VALID && reviewed.review().approved()) {
                    Note path = reused ? Note.BASE_ONLY_EARLIER_REVIEW : replaced ? Note.BASE_REPLACED : null;
                    if (reused) {
                        notes.add(Note.BASE_ONLY_EARLIER_REVIEW);
                    }
                    return approved(script, model, reviewed.model(), current, budget, deadline, notes, path);
                }
                note(notes, result, reviewed);
                boolean variantOnly = result == ScenarioScriptValidation.Result.INVALID_VARIANT
                        || (!reviewed.review().reasons().isEmpty()
                                && reviewed.review().reasons().stream().allMatch(ScenarioScriptGenerator::variantReason));
                preserved = variantOnly && result != ScenarioScriptValidation.Result.INVALID_BASE && !script.scenes().isEmpty()
                        ? script.scenes().getFirst() : null;
            } catch (ScenarioScriptProvider.Failure failure) {
                if (failure.kind() != ScenarioScriptProvider.Failure.Kind.CONTENT) {
                    throw ScenarioDeadline.failed();
                }
            }
        }
        if (preserved != null && model != null) {
            try {
                var reduced = new ScenarioScript(List.of(preserved));
                Map<String, Object> reviewPayload = reviewPayload(reduced, active);
                // 단독 승인된 같은 BASE이고 검토 입력도 그때와 같으면 같은 검토를 다시 부르지 않는다
                boolean reused = alone != null && alone.sameReview(reviewPayload);
                if (reused) {
                    notes.add(Note.REVIEW_REUSED);
                }
                var review = reused ? alone.reviewed() : review(reviewPayload, active, budget, deadline);
                var result = validation.inspect(reduced, active.rules());
                if (review.review().approved() && result == ScenarioScriptValidation.Result.VALID) {
                    Note path = reused ? Note.BASE_ONLY_EARLIER_REVIEW : Note.BASE_ONLY_REVIEWED;
                    notes.add(path);
                    return approved(reduced, model, review.model(), active, budget, deadline, notes, path);
                }
                note(notes, result, review);
            } catch (ScenarioScriptProvider.Failure failure) {
                if (failure.kind() != ScenarioScriptProvider.Failure.Kind.CONTENT) {
                    throw ScenarioDeadline.failed();
                }
            }
        }
        throw new ContentExhausted(notes);
    }

    /** 승인되지 않은 시도의 사유 코드를 남긴다. */
    private static void note(List<Note> notes, ScenarioScriptValidation.Result result, ScenarioScriptProvider.Reviewed reviewed) {
        if (result == ScenarioScriptValidation.Result.INVALID_BASE) {
            notes.add(Note.BASE_INVALID);
        } else if (result == ScenarioScriptValidation.Result.INVALID_VARIANT) {
            notes.add(Note.VARIANT_INVALID);
        }
        if (!reviewed.review().approved()) {
            boolean variantOnly = !reviewed.review().reasons().isEmpty()
                    && reviewed.review().reasons().stream().allMatch(ScenarioScriptGenerator::variantReason);
            notes.add(variantOnly ? Note.VARIANT_REJECTED : Note.REVIEW_NOT_APPROVED);
        }
    }

    /** 대본의 첫 장면(BASE)을 보존 BASE로 바꾼다. 나머지 장면은 그대로다. */
    private static ScenarioScript withBase(ScenarioScript script, ScenarioScript.Scene base) {
        List<ScenarioScript.Scene> scenes = new ArrayList<>(script.scenes());
        scenes.set(0, base);
        return new ScenarioScript(scenes);
    }

    private Input withoutGraph(Input input) {
        var payload = new LinkedHashMap<>(input.payload());
        payload.remove("structureContext");
        payload.remove("variantPlan");
        payload.put("baseOnly", true);
        var r = input.rules();
        return new Input(payload, input.aliases(), new ScenarioScriptValidation.Rules(r.relation(), r.opportunity(), r.targetExpression(),
                r.originalNames(), r.sourceText(), r.seen(), (a, b) -> false), false, input.goalContextJson());
    }

    private Input withPlan(Input input, kr.mujoong.core.knowledge.VariantPlanner.Plan plan) {
        var projected = Map.<String, Object>of("grade", plan.grade(), "changedField", plan.changedField(), "context", plan.context(),
                "signature", plan.signature(), "invariantSentence", plan.invariantSentence(), "preservationRule", plan.preservationRule(),
                "misreadCost", plan.misreadCost(), "recoverable", plan.recoverable());
        var clean = new kr.mujoong.core.luna.LunaInputSanitizer(kr.mujoong.contract.json.JsonCodec.shared()).sanitize(
                Map.of("variantPlan", projected), input.aliases(), kr.mujoong.core.luna.LunaPurpose.SCENARIO_GENERATION).payload();
        var payload = new LinkedHashMap<>(input.payload());
        payload.remove("baseOnly");
        payload.put("variantPlan", clean.get("variantPlan"));
        return new Input(payload, input.aliases(), input.rules(), input.graphContextUsed(), input.goalContextJson());
    }

    private ScenarioScriptProvider.Call call(Map<String, Object> payload, Input input, ScenarioDeadline deadline) {
        return new ScenarioScriptProvider.Call(payload, input.aliases(), deadline.callDeadline(timing.requestTimeout()));
    }

    /** 검토에 보낼 입력 그대로(대본 포함). 단독 검토 재사용 여부도 이 값을 비교한다. */
    private static Map<String, Object> reviewPayload(ScenarioScript script, Input input) {
        var payload = new LinkedHashMap<>(input.payload());
        // 변형 장면이 없는 대본(첫 BASE 검토 · BASE만 남긴 재검토)에 다음 생성용 변형 계획을 보이면 검토가 BASE를 변형으로 읽고
        // UNCHANGED_VARIANT · TRANSFER_MISMATCH로 거절한다. 계획은 검토할 변형이 있을 때만 넘긴다
        if (script.scenes().stream().noneMatch(s -> "VARIANT".equals(s.sceneKind()))) {
            payload.remove("variantPlan");
        }
        payload.put("script", script);
        return payload;
    }

    private ScenarioScriptProvider.Reviewed review(Map<String, Object> payload, Input input, Budget budget, ScenarioDeadline deadline) {
        var reviewed = invoke(() -> provider.review(call(payload, input, deadline)), budget, deadline);
        if (reviewed == null || reviewed.review() == null || !metadata(reviewed.model())) {
            throw content();
        }
        return reviewed;
    }

    private <T> T invoke(Supplier<T> operation, Budget budget, ScenarioDeadline deadline) {
        for (;;) {
            deadline.check();
            if (budget.calls >= 6) {
                throw ScenarioDeadline.failed();
            }
            budget.calls++;
            try {
                return timedCall(operation, deadline);
            } catch (ScenarioScriptProvider.Failure failure) {
                if (failure.kind() != ScenarioScriptProvider.Failure.Kind.TRANSIENT || budget.technical || timing.retry().maxRetries() < 1) {
                    throw failure;
                }
                budget.technical = true;
                Duration wait = failure.retryAfter().filter(d -> d.compareTo(timing.retry().backoff()) > 0).orElse(timing.retry().backoff());
                var left = deadline.remaining();
                if (left.compareTo(wait) <= 0 || left.minus(wait).compareTo(timing.retry().backoff()) < 0) {
                    throw ScenarioDeadline.failed();
                }
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw ScenarioDeadline.failed();
                }
            }
        }
    }

    private <T> T timedCall(Supplier<T> operation, ScenarioDeadline deadline) {
        try {
            return deadline.call(executor, timing.requestTimeout(), operation::get);
        } catch (kr.mujoong.core.common.error.CoreApiException failure) {
            deadline.check();
            throw new ScenarioScriptProvider.Failure(ScenarioScriptProvider.Failure.Kind.TRANSIENT, Optional.empty());
        }
    }

    private Result approved(ScenarioScript script, String model, String reviewModel, Input input, Budget budget, ScenarioDeadline deadline,
            List<Note> notes, Note path) {
        deadline.check();
        var base = script.scenes().getFirst();
        var legacy = new GeneratedScenario(base.sceneDescription(), base.opportunitySentence(), input.rules().relation(), input.rules().opportunity(),
                model, ScenarioScriptProvider.GENERATION_PROMPT_VERSION);
        // 장면 partnerRole은 지시가 아니라 짧은 역할 이름이다. 모델은 이 칸을 내지 않고 서버가 관계에서 만든다
        var scenes = script.scenes().stream()
                .map(s -> s.withPartnerRole(ScenarioContentPolicy.partnerRoleName(s.relation())))
                .toList();
        String version = input.graphContextUsed() && input.rules().variants() instanceof ScenarioGraphContext.Session graph
                ? graph.graphVersion() : null;
        var generation = new Scenario.Generation(model, ScenarioScriptProvider.GENERATION_PROMPT_VERSION, input.graphContextUsed(), budget.calls,
                version, path == null ? null : path.name());
        var details = new ScenarioRepository.Details("scenario-script.v1", scenes,
                new Scenario.Review("APPROVE", reviewModel, ScenarioScriptProvider.REVIEW_PROMPT_VERSION), generation, input.goalContextJson());
        return new Result(legacy, details, notes);
    }

    private static boolean variantReason(String code) {
        return Set.of("INVARIANT_MISMATCH", "SKILL_MISMATCH", "TRANSFER_MISMATCH", "UNCHANGED_VARIANT").contains(code);
    }

    private static boolean metadata(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,79}");
    }

    private static ScenarioScriptProvider.Failure content() {
        return new ScenarioScriptProvider.Failure(ScenarioScriptProvider.Failure.Kind.CONTENT, Optional.empty());
    }
}
