package kr.mujoong.realtime.luna;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.usage.AiUsageCollector;
import kr.mujoong.realtime.usage.LunaUsage;

/**
 * Realtime Luna 판정 호출 (U9 FR1.1 · FR1.2 · FR1.4 · BR2 ~ BR6 · SD1 ~ SD6, FT-1023-d). 진행 중 판정(U11 {@code DecisionsJudgeClient})은
 * Decisions API({@code POST {base-url}/decisions}) 한 길뿐이다. 비동기 {@link #decide}는 {@code CompletionStage}를 돌려준다(C-DS4).
 * {@code JUDGMENT_LIVE}는 재시도 분류 NONE이라 한 번만 부른다(BR3.1). core에 Responses 호출부가 따로 있다(코드 공유 없음, R-F5 · BR7.3).
 *
 * <ol>
 *   <li>입력 정리(BR2.1 ~ BR2.3) → 요청 스키마({@code ai-judgment-request}) 검증(BR2.4). 실패면 공급자를 부르지 않고 FORBIDDEN_INPUT.
 *   <li>시한 = min(마감까지 남은 시간, {@code mujoong.timing.judgment.live-budget})(BR5.1). 0 이하면 부르지 않고 TIMEOUT. 주소 재확인 ·
 *       빈 키로 거절되면 부르지 않고 그 사유(시도 0, 검토 R-04). 넘으면 요청을 취소한다. 돌려받은 Future를 호출자가 취소해도 요청을 취소한다.
 *   <li>요청 조립: 지시는 질문 지시문 자리, 정리된 데이터는 {@code input_json} 파트(지시와 데이터 분리, BR2.5). {@code store} ·
 *       {@code temperature}는 Decisions에 없는 매개변수라 보내지 않는다.
 *   <li>응답 분류({@link #classifyDecision})와 {@code LUNA_CALL} 한 줄(BR6.1).
 * </ol>
 */
public class RealtimeLunaClient {

    private final LunaProperties properties;
    private final Duration liveBudget;
    private final LunaSchemas schemas;
    private final LunaInputSanitizer sanitizer;
    private final LunaTransport transport;
    private final EventLogger events;
    private final MujoongClock clock;
    private final JsonCodec json;
    private AiUsageCollector usage = AiUsageCollector.NOOP;

    /** 예열용 합성 판정 입력(보내지 않는다, 아동 데이터 없음). */
    private static final Map<String, Object> WARM_UP_INPUT = Map.of(
            "judgmentPoint", "J2",
            "optionSetVersion", "warm-up",
            "options", List.of("FULL", "PARTIAL"),
            "childUtterance", "예열",
            "recentTurns", List.of(),
            "criteria", Map.of("coreFunction", "예열", "judgmentCriteria", "예열"));
    private static final String WARM_UP_INSTRUCTIONS = "예열";
    private static final LunaDecisionQuestion WARM_UP_QUESTION = new LunaDecisionQuestion("judgment",
            List.of(new LunaDecisionQuestion.Choice("FULL", null), new LunaDecisionQuestion.Choice("PARTIAL", null)));

    /**
     * @param liveBudget 목적 시한 키 {@code mujoong.timing.judgment.live-budget}의 값
     */
    public RealtimeLunaClient(LunaProperties properties, Duration liveBudget, LunaSchemas schemas, LunaInputSanitizer sanitizer,
            LunaTransport transport, EventLogger events, MujoongClock clock, JsonCodec json) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.liveBudget = Objects.requireNonNull(liveBudget, "liveBudget");
        this.schemas = Objects.requireNonNull(schemas, "schemas");
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.json = Objects.requireNonNull(json, "json");
    }

    public RealtimeLunaClient(LunaProperties properties, Duration liveBudget, LunaSchemas schemas, LunaInputSanitizer sanitizer,
            LunaTransport transport, EventLogger events, MujoongClock clock, JsonCodec json, AiUsageCollector usage) {
        this(properties, liveBudget, schemas, sanitizer, transport, events, clock, json);
        this.usage = Objects.requireNonNull(usage);
    }

    /**
     * Decisions API로 {@code choice} 질문 한 개를 한 번 묻는다. 실패는 {@link LunaException}(사유 코드만)을 원인으로 하는 예외 완료다. 이 메서드는
     * 호출 스레드로 던지지 않는다(검토 R-05): 보내기 전 준비(정리 · 스키마 검증 · 본문 조립) 중 예기치 못한 런타임 오류도 {@code APP_ERROR}(예외
     * 종류 이름만) 한 줄을 남기고 {@code FORBIDDEN_INPUT}(시도 0) 예외 완료로 바꾼다. 준비하지 못한 요청은 보내지 않는다는 fail-closed 판단이다.
     */
    public CompletionStage<LunaDecision> decide(LunaRequest request, LunaDecisionQuestion question) {
        return decide(request, question, null);
    }

    /**
     * 판정 전에 호출 경로를 미리 데운다. 합성 판정 입력으로 정리 · 요청 스키마 검증 · 본문 조립을 한 번 돌리고(보내지 않음), 공급자 연결을 맺는다
     * ({@link LunaTransport#warmUp}). 판정 · 과금 · 데이터 전송이 없고 실패하지 않으며, 연결 시한은 판정과 같은 live-budget이다. 앱 기동 뒤와
     * 세션 준비 때 불러, 처음 도는 코드 · 새 연결 비용 때문에 세션 첫 판정이 시한을 넘지 않게 한다.
     */
    public CompletionStage<Void> warmUp() {
        try {
            SanitizedInput input = sanitizer.sanitize(WARM_UP_INPUT, Map.of());
            String payload = json.write(input.payload());
            if (schemas.isValidRequest(LunaPurpose.JUDGMENT_LIVE, payload)) {
                json.write(new DecisionsRequest(properties.model(), WARM_UP_INSTRUCTIONS, input.payload(), WARM_UP_QUESTION).body());
            }
        } catch (RuntimeException ignored) {
            // 예열은 최선 노력이다: 준비 경로가 실패하면 실제 판정이 같은 경로에서 같은 사유로 끝난다
        }
        return transport.warmUp(liveBudget);
    }

    /** turnId는 사용량 연결용 서버 내부 값이며 공급자 요청 본문에 넣지 않는다. */
    public CompletionStage<LunaDecision> decide(LunaRequest request, LunaDecisionQuestion question, UUID turnId) {
        try {
            Objects.requireNonNull(question, "question");
            return start(request, question, turnId);
        } catch (RuntimeException unexpected) {
            return unexpectedFailure(unexpected);
        }
    }

    private <T> CompletableFuture<T> unexpectedFailure(RuntimeException unexpected) {
        try {
            // 원인 메시지 · 스택은 입력을 담을 수 있어 남기지 않는다(BR6.2). 예외 종류 이름만 APP_ERROR로 남긴다
            events.appError(EventLog.Component.REALTIME_LUNA_CLIENT, unexpected);
        } catch (RuntimeException loggingFailed) {
            // 기록 자체가 실패해도 호출자에게 던지지 않는다(던지지 않는다는 계약이 먼저다). 실패 완료는 아래에서 그대로 돌려준다
        }
        return CompletableFuture.failedFuture(new LunaException(LunaFailureReason.FORBIDDEN_INPUT, 0));
    }

    /** 한 번의 시도: 정리 → 요청 스키마 → 시한 → 보내기 전 거절 → 전송 → 분류 → 감사 한 줄. */
    private CompletionStage<LunaDecision> start(LunaRequest request, LunaDecisionQuestion question, UUID turnId) {
        Objects.requireNonNull(request, "request");
        SanitizedInput input;
        try {
            input = sanitizer.sanitize(request.input(), request.aliases());
        } catch (LunaException rejected) {
            audit(request, List.of(), LunaFailureReason.FORBIDDEN_INPUT.name(), null, Duration.ZERO, 0);
            return CompletableFuture.failedFuture(rejected);
        }
        if (!schemas.isValidRequest(request.purpose(), json.write(input.payload()))) {
            audit(request, input.sentFieldNames(), LunaFailureReason.FORBIDDEN_INPUT.name(), null, Duration.ZERO, 0);
            return CompletableFuture.failedFuture(new LunaException(LunaFailureReason.FORBIDDEN_INPUT, 0));
        }
        Duration remaining = Duration.between(clock.now(), request.deadline());
        Duration limit = remaining.compareTo(liveBudget) <= 0 ? remaining : liveBudget;
        if (limit.isNegative() || limit.isZero()) {
            audit(request, input.sentFieldNames(), LunaFailureReason.TIMEOUT.name(), null, Duration.ZERO, 0);
            return CompletableFuture.failedFuture(new LunaException(LunaFailureReason.TIMEOUT, 0));
        }
        // 주소 재확인 · 빈 키로 보내기 전에 끝나면 공급자 시도로 세지 않는다(LUNA_CALL attempt=0, 검토 R-04)
        Optional<LunaFailureReason> notSent = transport.rejectBeforeSend(limit);
        if (notSent.isPresent()) {
            audit(request, input.sentFieldNames(), notSent.get().name(), null, Duration.ZERO, 0);
            return CompletableFuture.failedFuture(new LunaException(notSent.get(), 0));
        }
        String requestBody = json.write(
                new DecisionsRequest(properties.model(), request.instructions(), input.payload(), question).body());
        long started = clock.monotonicNanos();
        CompletableFuture<LunaTransport.RawResponse> sent = transport.send(requestBody, limit);
        if (turnId != null && usage.enabled()) {
            UUID callId = UUID.randomUUID();
            sent.whenComplete((response, failure) -> {
                try {
                    usage.offer(LunaUsage.read(json, response == null ? null : response.body(), callId, turnId,
                            properties.model(), clock.now()));
                } catch (RuntimeException unavailable) {
                    // 사용량 실패를 판정 실패로 전파하지 않는다.
                }
            });
        }
        CompletableFuture<LunaDecision> result = sent.handle((response, failure) -> {
            Duration latency = clock.elapsedSince(started);
            Outcome outcome = failure == null
                    ? classifyDecision(json, response, question)
                    : Outcome.failed(LunaTransport.translate(failure).reason(), null);
            audit(request, input.sentFieldNames(), outcome.failure() == null ? "OK" : outcome.failure().name(),
                    outcome.responseModel(), latency, 1);
            if (outcome.failure() != null) {
                throw new CompletionException(new LunaException(outcome.failure(), 1));
            }
            return new LunaDecision(outcome.answer().choice(), outcome.answer().probabilities(), outcome.responseModel(),
                    request.promptVersion(), latency, 1);
        });
        // 호출자가 결과 Future를 취소하면(판정 예산 초과 등) 진행 중인 요청도 취소한다
        result.whenComplete((value, failure) -> {
            if (result.isCancelled()) {
                sent.cancel(true);
            }
        });
        return result;
    }

    /** 비 2xx 상태의 실패 사유(BR4.4 → BR4.1). 2xx면 null. */
    private static LunaFailureReason statusFailure(int status) {
        if (status == 401 || status == 403) {
            return LunaFailureReason.AUTH_FAILED;
        }
        if (status == 429) {
            return LunaFailureReason.RATE_LIMITED;
        }
        if (status >= 500) {
            return LunaFailureReason.SERVER_ERROR;
        }
        if (status < 200 || status >= 300) {
            return LunaFailureReason.REQUEST_REJECTED;
        }
        return null;
    }

    /**
     * Decisions 응답 분류(FT-1023-d). 비 2xx는 401 · 403 → AUTH_FAILED, 429 → RATE_LIMITED, 5xx → SERVER_ERROR, 그 밖(3xx 포함) →
     * REQUEST_REJECTED다. 2xx는 JSON이 아니면 SERVER_ERROR, 질문 이름이 맞는 답이 없으면 EMPTY_OUTPUT(이름 없는 거절만 있으면 REFUSED), 같은
     * 이름의 답이 여럿이면 SCHEMA_INVALID, 거절(refusal)이면 REFUSED, {@code model}이 없으면 MODEL_MISSING, 그 밖에 선택지 · 확률 규칙
     * ({@link DecisionsReply#answer})을 어기면 SCHEMA_INVALID다. 실호출 확인 도구도 이 분류를 그대로 쓴다.
     */
    static Outcome classifyDecision(JsonCodec json, LunaTransport.RawResponse response, LunaDecisionQuestion question) {
        LunaFailureReason rejected = statusFailure(response.status());
        if (rejected != null) {
            return Outcome.failed(rejected, null);
        }
        DecisionsReply reply = DecisionsReply.parse(json, response.body(), question.name());
        if (reply == null) {
            return Outcome.failed(LunaFailureReason.SERVER_ERROR, null);
        }
        if (reply.matching().isEmpty()) {
            return Outcome.failed(reply.anyRefusal() ? LunaFailureReason.REFUSED : LunaFailureReason.EMPTY_OUTPUT, reply.model());
        }
        if (reply.matching().size() > 1) {
            return Outcome.failed(LunaFailureReason.SCHEMA_INVALID, reply.model());
        }
        Map<?, ?> answer = reply.matching().getFirst();
        if ("refusal".equals(answer.get("type"))) {
            return Outcome.failed(LunaFailureReason.REFUSED, reply.model());
        }
        if (reply.model() == null || reply.model().isBlank()) {
            return Outcome.failed(LunaFailureReason.MODEL_MISSING, null);
        }
        DecisionAnswer valid = DecisionsReply.answer(answer, question);
        if (valid == null) {
            return Outcome.failed(LunaFailureReason.SCHEMA_INVALID, reply.model());
        }
        return new Outcome(null, reply.model(), valid);
    }

    private void audit(LunaRequest request, List<String> sentFields, String outcome, String responseModel, Duration latency,
            int attempt) {
        events.log(EventCode.LUNA_CALL, EventLog.Component.REALTIME_LUNA_CLIENT, null, entry -> {
            entry.attr(AttrKey.LUNA_PURPOSE, request.purpose())
                    .attr(AttrKey.LUNA_OUTCOME, outcome)
                    .attr(AttrKey.MODEL, properties.model())
                    .attr(AttrKey.PROMPT_VERSION, request.promptVersion())
                    .attr(AttrKey.SENT_FIELDS, sentFields)
                    .attr(AttrKey.LATENCY_MS, latency.toMillis())
                    .attr(AttrKey.ATTEMPT, attempt);
            if (responseModel != null && !responseModel.equals(properties.model())) {
                entry.attr(AttrKey.RESPONSE_MODEL, responseModel);
            }
        });
    }

    /** 시도 하나의 분류 결과(실패면 failure가 있고 answer는 null). */
    record Outcome(LunaFailureReason failure, String responseModel, DecisionAnswer answer) {

        static Outcome failed(LunaFailureReason reason, String responseModel) {
            return new Outcome(reason, responseModel, null);
        }
    }

    /** 검증을 통과한 Decisions 답(고른 선택지와 보낸 선택지 순서의 확률). */
    record DecisionAnswer(String choice, List<LunaDecision.Probability> probabilities) {
    }

    /**
     * Decisions API 요청 본문(FT-1023-d). 최소 필드(정리된 판정 입력)만 보낸다. {@code store} · {@code temperature} ·
     * {@code reasoning} · {@code max_output_tokens}는 Decisions에 없는 매개변수라 보내지 않는다. 지시는 질문 지시문 자리, 데이터는 사용자 메시지의
     * {@code input_json} 파트로 간다(BR2.5).
     *
     * @param payload 정리된 데이터(요청 스키마 검증을 통과한 값)
     */
    record DecisionsRequest(String model, String instructions, Map<String, Object> payload, LunaDecisionQuestion question) {

        Map<String, Object> body() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", "input_json");
            data.put("data", payload);
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "user");
            message.put("content", List.of(data));
            List<Map<String, Object>> choices = new ArrayList<>();
            for (LunaDecisionQuestion.Choice choice : question.choices()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("value", choice.value());
                if (choice.description() != null) {
                    item.put("description", choice.description());
                }
                choices.add(item);
            }
            Map<String, Object> asked = new LinkedHashMap<>();
            asked.put("type", "choice");
            asked.put("name", question.name());
            asked.put("instructions", instructions);
            asked.put("choices", choices);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("input", List.of(message));
            body.put("questions", List.of(asked));
            return body;
        }

        @Override
        public String toString() {
            return "DecisionsRequest[redacted]";
        }
    }

    /**
     * Decisions API 응답에서 분류에 쓰는 값만 읽는다(모르는 필드 · {@code usage} 무시).
     *
     * @param matching 질문 이름이 맞는 답들
     * @param anyRefusal 이름과 관계없이 거절 답이 있는가
     */
    record DecisionsReply(String model, List<Map<?, ?>> matching, boolean anyRefusal) {

        @SuppressWarnings("unchecked")
        static DecisionsReply parse(JsonCodec json, String body, String questionName) {
            Map<String, Object> root;
            try {
                root = json.read(body, Map.class);
            } catch (RuntimeException e) {
                // 공급자 본문이 JSON이 아니면 분류할 수 없다(원인 메시지는 본문 일부를 담을 수 있어 버린다, BR6.2)
                return null;
            }
            if (root == null) {
                return null;
            }
            List<Map<?, ?>> matching = new ArrayList<>();
            boolean anyRefusal = false;
            if (root.get("answers") instanceof List<?> answers) {
                for (Object item : answers) {
                    if (!(item instanceof Map<?, ?> answer)) {
                        continue;
                    }
                    anyRefusal |= "refusal".equals(answer.get("type"));
                    if (questionName.equals(answer.get("name"))) {
                        matching.add(answer);
                    }
                }
            }
            Object model = root.get("model");
            return new DecisionsReply(model instanceof String m ? m : null, List.copyOf(matching), anyRefusal);
        }

        /**
         * {@code choice} 답 하나를 검증한다. 맞지 않으면 null(SCHEMA_INVALID): 형식이 {@code choice}가 아님, 고른 값이 보낸 선택지 밖,
         * 확률 항목이 보낸 선택지 밖 · 중복 · 숫자 아님 · 0 ~ 1 밖, 보낸 선택지 중 확률이 빠진 것이 있음, 고른 값의 확률이 가장 높지 않음(같으면 허용).
         * 공급자 {@code confidence}는 읽지 않는다(판정 규칙은 선택지 확률 기준).
         */
        static DecisionAnswer answer(Map<?, ?> answer, LunaDecisionQuestion question) {
            if (!"choice".equals(answer.get("type")) || !(answer.get("choice") instanceof String choice)
                    || !question.values().contains(choice) || !(answer.get("probabilities") instanceof List<?> items)) {
                return null;
            }
            Map<String, Double> byValue = new HashMap<>();
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> entry) || !(entry.get("value") instanceof String value)
                        || !question.values().contains(value) || byValue.containsKey(value)
                        || !(entry.get("probability") instanceof Number number)) {
                    return null;
                }
                double probability = number.doubleValue();
                if (Double.isNaN(probability) || probability < 0 || probability > 1) {
                    return null;
                }
                byValue.put(value, probability);
            }
            if (byValue.size() != question.values().size()) {
                return null;
            }
            double chosen = byValue.get(choice);
            if (byValue.values().stream().anyMatch(probability -> probability > chosen)) {
                return null;
            }
            List<LunaDecision.Probability> ordered = question.values().stream()
                    .map(value -> new LunaDecision.Probability(value, byValue.get(value))).toList();
            return new DecisionAnswer(choice, ordered);
        }
    }
}
