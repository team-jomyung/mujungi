package kr.mujoong.realtime.luna;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.observability.EventLogger;

/**
 * Realtime Luna 공통 호출 (U9 FR1.1 · FR1.2 · FR1.4 · BR2 ~ BR6 · SD1 ~ SD6). 판정 턴(U11 {@code JudgeClient} 구현)이 그 위에 얹힌다.
 * 비동기 {@link #call(LunaRequest)}은 {@code CompletionStage}를 돌려준다(C-DS4). {@code JUDGMENT_LIVE}는 재시도 분류 NONE이라 한 번만
 * 부른다(BR3.1). core에 같은 규칙의 동기 호출부가 따로 있다(코드 공유 없음, R-F5 · BR7.3).
 *
 * <ol>
 *   <li>입력 정리(BR2.1 ~ BR2.3) → 요청 스키마({@code ai-judgment-request}) 검증(BR2.4). 실패면 공급자를 부르지 않고 FORBIDDEN_INPUT.
 *   <li>요청 조립(BR1.6 · BR1.8 · BR2.5 · BR4.5): {@code store=false}, {@code temperature} 없음, 지시와 데이터 자리 분리, 요청용 스키마 사본.
 *   <li>시한 = min(마감까지 남은 시간, {@code mujoong.timing.judgment.live-budget})(BR5.1). 0 이하면 부르지 않고 TIMEOUT. 주소 재확인 ·
 *       빈 키로 거절되면 부르지 않고 그 사유(시도 0, 검토 R-04). 넘으면 요청을 취소한다. 돌려받은 Future를 호출자가 취소해도 요청을 취소한다.
 *   <li>응답 분류(BR4.4 → BR4.1 → BR4.3 → BR4.2)와 {@code LUNA_CALL} 한 줄(BR6.1).
 * </ol>
 *
 * <p>판정은 Responses({@link #call}) 또는 Decisions({@link #decide}, FT-1023-d) 끝점으로 부를 수 있다. 위 단계는 두 끝점이 같은 코드로 지나고
 * 요청 본문 모양과 2xx 응답 검증만 다르다. 어느 쪽을 쓸지는 {@code mujoong.luna.judge-endpoint}로 판정 빈이 고른다.
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

    /**
     * 공급자를 한 번 부른다. 실패는 {@link LunaException}(사유 코드만)을 원인으로 하는 예외 완료다. 이 메서드는 호출 스레드로 던지지 않는다(검토
     * R-05): 보내기 전 준비(정리 · 스키마 검증 · 본문 조립) 중 예기치 못한 런타임 오류도 {@code APP_ERROR}(예외 종류 이름만) 한 줄을 남기고
     * {@code FORBIDDEN_INPUT}(시도 0) 예외 완료로 바꾼다. 준비하지 못한 요청은 보내지 않는다는 fail-closed 판단이다.
     */
    public CompletionStage<LunaResult> call(LunaRequest request) {
        try {
            return start(request, LunaTransport.Endpoint.RESPONSES,
                    (input, inputJson) -> new ResponsesRequest(properties.model(), request.instructions(), inputJson,
                            schemas.pair(request.purpose()), request.purpose(), properties.options(request.purpose())).body(),
                    response -> classify(response, request.purpose()),
                    (outputJson, responseModel, latency) -> new LunaResult(outputJson, responseModel, request.promptVersion(), latency, 1));
        } catch (RuntimeException unexpected) {
            return unexpectedFailure(unexpected);
        }
    }

    /**
     * Decisions API({@code POST {base-url}/decisions})로 {@code choice} 질문 한 개를 한 번 묻는다(FT-1023-d). 입력 정리 → 요청 스키마 검증 → 시한 →
     * 주소 재확인 · 빈 키 거절 → 전송 → 응답 분류 → {@code LUNA_CALL} 한 줄은 {@link #call}과 같은 단계를 같은 코드로 지난다. 다른 것은 본문 모양과
     * 2xx 응답 검증뿐이다. 지시({@link LunaRequest#instructions()})는 질문 지시문 자리로, 정리된 데이터는 {@code input}의 {@code input_json}
     * 파트로 간다(지시와 데이터 분리, BR2.5). {@code call}처럼 호출 스레드로 던지지 않는다.
     */
    public CompletionStage<LunaDecision> decide(LunaRequest request, LunaDecisionQuestion question) {
        try {
            Objects.requireNonNull(question, "question");
            return start(request, LunaTransport.Endpoint.DECISIONS,
                    (input, inputJson) -> new DecisionsRequest(properties.model(), request.instructions(), input.payload(), question)
                            .body(),
                    response -> classifyDecision(response, question),
                    (answer, responseModel, latency) -> new LunaDecision(answer.choice(), answer.probabilities(), responseModel,
                            request.promptVersion(), latency, 1));
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

    /**
     * 두 끝점이 함께 쓰는 한 번의 시도. {@code body}는 정리된 입력으로 요청 본문을 만들고, {@code classifier}는 받은 응답을 분류하며,
     * {@code completion}은 성공 값을 결과로 바꾼다.
     */
    private <T, R> CompletionStage<R> start(LunaRequest request, LunaTransport.Endpoint endpoint,
            BiFunction<SanitizedInput, String, Map<String, Object>> body,
            Function<LunaTransport.RawResponse, Outcome<T>> classifier, Completion<T, R> completion) {
        Objects.requireNonNull(request, "request");
        SanitizedInput input;
        try {
            input = sanitizer.sanitize(request.input(), request.aliases());
        } catch (LunaException rejected) {
            audit(request, List.of(), LunaFailureReason.FORBIDDEN_INPUT.name(), null, Duration.ZERO, 0);
            return CompletableFuture.failedFuture(rejected);
        }
        String inputJson = json.write(input.payload());
        if (!schemas.isValidRequest(request.purpose(), inputJson)) {
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
        Optional<LunaFailureReason> notSent = transport.rejectBeforeSend(endpoint, limit);
        if (notSent.isPresent()) {
            audit(request, input.sentFieldNames(), notSent.get().name(), null, Duration.ZERO, 0);
            return CompletableFuture.failedFuture(new LunaException(notSent.get(), 0));
        }
        String requestBody = json.write(body.apply(input, inputJson));
        long started = clock.monotonicNanos();
        CompletableFuture<LunaTransport.RawResponse> sent = transport.send(endpoint, requestBody, limit);
        CompletableFuture<R> result = sent.handle((response, failure) -> {
            Duration latency = clock.elapsedSince(started);
            Outcome<T> outcome = failure == null
                    ? classifier.apply(response)
                    : Outcome.failed(LunaTransport.translate(failure).reason(), null);
            audit(request, input.sentFieldNames(), outcome.failure == null ? "OK" : outcome.failure.name(),
                    outcome.responseModel, latency, 1);
            if (outcome.failure != null) {
                throw new CompletionException(new LunaException(outcome.failure, 1));
            }
            return completion.complete(outcome.value, outcome.responseModel, latency);
        });
        // 호출자가 결과 Future를 취소하면(판정 예산 초과 등) 진행 중인 요청도 취소한다
        result.whenComplete((value, failure) -> {
            if (result.isCancelled()) {
                sent.cancel(true);
            }
        });
        return result;
    }

    /** 비 2xx 상태의 실패 사유(BR4.4 → BR4.1). 2xx면 null. 두 끝점이 같은 표를 쓴다. */
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

    /** 응답 분류(BR4.4 → BR4.1 → BR4.3 → BR4.2). */
    private Outcome<String> classify(LunaTransport.RawResponse response, LunaPurpose purpose) {
        LunaFailureReason rejected = statusFailure(response.status());
        if (rejected != null) {
            return Outcome.failed(rejected, null);
        }
        ResponsesReply reply = ResponsesReply.parse(json, response.body());
        if (reply == null) {
            return Outcome.failed(LunaFailureReason.SERVER_ERROR, null);
        }
        if ("incomplete".equals(reply.status())) {
            return Outcome.failed(LunaFailureReason.LENGTH_EXCEEDED, reply.model());
        }
        if (reply.refused()) {
            return Outcome.failed(LunaFailureReason.REFUSED, reply.model());
        }
        if (!"completed".equals(reply.status())) {
            return Outcome.failed(LunaFailureReason.SERVER_ERROR, reply.model());
        }
        if (reply.outputText().isBlank()) {
            return Outcome.failed(LunaFailureReason.EMPTY_OUTPUT, reply.model());
        }
        if (reply.model() == null || reply.model().isBlank()) {
            return Outcome.failed(LunaFailureReason.MODEL_MISSING, null);
        }
        if (!schemas.isValidOutput(purpose, reply.outputText())) {
            return Outcome.failed(LunaFailureReason.SCHEMA_INVALID, reply.model());
        }
        return new Outcome<>(null, reply.model(), reply.outputText());
    }

    /**
     * Decisions 응답 분류(FT-1023-d). 비 2xx는 {@link #classify}와 같은 표다. 2xx는 JSON이 아니면 SERVER_ERROR, 질문 이름이 맞는 답이 없으면
     * EMPTY_OUTPUT, 같은 이름의 답이 여럿이면 SCHEMA_INVALID, 거절(refusal)이면 REFUSED, {@code model}이 없으면 MODEL_MISSING, 그 밖에
     * 선택지 · 확률 규칙({@link DecisionsReply#answer})을 어기면 SCHEMA_INVALID다. 새 사유 코드는 없다.
     */
    private Outcome<DecisionAnswer> classifyDecision(LunaTransport.RawResponse response, LunaDecisionQuestion question) {
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
        return new Outcome<>(null, reply.model(), valid);
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

    /** 시도 하나의 결과(실패면 failure가 있다). */
    private record Outcome<T>(LunaFailureReason failure, String responseModel, T value) {

        static <T> Outcome<T> failed(LunaFailureReason reason, String responseModel) {
            return new Outcome<>(reason, responseModel, null);
        }
    }

    /** 성공 값 → 호출 결과. */
    @FunctionalInterface
    private interface Completion<T, R> {
        R complete(T value, String responseModel, Duration latency);
    }

    /** 검증을 통과한 Decisions 답(고른 선택지와 보낸 선택지 순서의 확률). */
    private record DecisionAnswer(String choice, List<LunaDecision.Probability> probabilities) {
    }

    /**
     * Responses API 요청 본문(SD2). 이 레코드에는 {@code temperature} 칸이 없고 {@code store}는 언제나 false다(BR1.6 · BR1.8).
     *
     * @param input 정리된 데이터의 JSON 문자열(지시와 다른 자리, BR2.5)
     */
    record ResponsesRequest(String model, String instructions, String input, LunaSchemas.OutputSchemaPair schema,
            LunaPurpose purpose, LunaProperties.PurposeOptions options) {

        Map<String, Object> body() {
            Map<String, Object> format = new LinkedHashMap<>();
            format.put("type", "json_schema");
            format.put("name", purpose.name().toLowerCase(java.util.Locale.ROOT));
            format.put("schema", schema.requestSchema());
            format.put("strict", schema.strict());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("instructions", instructions);
            body.put("input", input);
            body.put("text", Map.of("format", format));
            body.put("max_output_tokens", options.maxOutputTokens());
            body.put("reasoning", Map.of("effort", options.reasoningEffort()));
            body.put("store", false);
            return body;
        }

        @Override
        public String toString() {
            return "ResponsesRequest[redacted]";
        }
    }

    /** Responses API 응답에서 분류에 쓰는 값만 읽는다(모르는 필드 무시). */
    record ResponsesReply(String status, String model, String outputText, boolean refused) {

        @SuppressWarnings("unchecked")
        static ResponsesReply parse(JsonCodec json, String body) {
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
            StringBuilder text = new StringBuilder();
            boolean refused = false;
            if (root.get("output") instanceof List<?> items) {
                for (Object item : items) {
                    if (!(item instanceof Map<?, ?> message) || !"message".equals(message.get("type"))
                            || !(message.get("content") instanceof List<?> contents)) {
                        continue;
                    }
                    for (Object content : contents) {
                        if (!(content instanceof Map<?, ?> part)) {
                            continue;
                        }
                        if ("refusal".equals(part.get("type"))) {
                            refused = true;
                        } else if ("output_text".equals(part.get("type")) && part.get("text") instanceof String piece) {
                            text.append(piece);
                        }
                    }
                }
            }
            Object status = root.get("status");
            Object model = root.get("model");
            return new ResponsesReply(status instanceof String s ? s : null, model instanceof String m ? m : null,
                    text.toString(), refused);
        }
    }

    /**
     * Decisions API 요청 본문(FT-1023-d). Responses와 같은 최소 필드(정리된 판정 입력)만 보낸다. {@code store} · {@code temperature} ·
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
