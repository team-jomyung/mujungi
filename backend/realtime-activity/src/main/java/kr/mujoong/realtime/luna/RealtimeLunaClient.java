package kr.mujoong.realtime.luna;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
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
            return start(request);
        } catch (RuntimeException unexpected) {
            return unexpectedFailure(unexpected);
        }
    }

    private CompletableFuture<LunaResult> unexpectedFailure(RuntimeException unexpected) {
        try {
            // 원인 메시지 · 스택은 입력을 담을 수 있어 남기지 않는다(BR6.2). 예외 종류 이름만 APP_ERROR로 남긴다
            events.appError(EventLog.Component.REALTIME_LUNA_CLIENT, unexpected);
        } catch (RuntimeException loggingFailed) {
            // 기록 자체가 실패해도 호출자에게 던지지 않는다(던지지 않는다는 계약이 먼저다). 실패 완료는 아래에서 그대로 돌려준다
        }
        return CompletableFuture.failedFuture(new LunaException(LunaFailureReason.FORBIDDEN_INPUT, 0));
    }

    private CompletionStage<LunaResult> start(LunaRequest request) {
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
        Optional<LunaFailureReason> notSent = transport.rejectBeforeSend(limit);
        if (notSent.isPresent()) {
            audit(request, input.sentFieldNames(), notSent.get().name(), null, Duration.ZERO, 0);
            return CompletableFuture.failedFuture(new LunaException(notSent.get(), 0));
        }
        LunaSchemas.OutputSchemaPair schema = schemas.pair(request.purpose());
        String body = json.write(new ResponsesRequest(properties.model(), request.instructions(), inputJson, schema,
                request.purpose(), properties.options(request.purpose())).body());
        long started = clock.monotonicNanos();
        CompletableFuture<LunaTransport.RawResponse> sent = transport.send(body, limit);
        CompletableFuture<LunaResult> result = sent.handle((response, failure) -> {
            Duration latency = clock.elapsedSince(started);
            Outcome outcome = failure == null
                    ? classify(response, request.purpose())
                    : new Outcome(LunaTransport.translate(failure).reason(), null, null);
            audit(request, input.sentFieldNames(), outcome.failure == null ? "OK" : outcome.failure.name(),
                    outcome.responseModel, latency, 1);
            if (outcome.failure != null) {
                throw new CompletionException(new LunaException(outcome.failure, 1));
            }
            return new LunaResult(outcome.outputJson, outcome.responseModel, request.promptVersion(), latency, 1);
        });
        // 호출자가 결과 Future를 취소하면(판정 예산 초과 등) 진행 중인 요청도 취소한다
        result.whenComplete((value, failure) -> {
            if (result.isCancelled()) {
                sent.cancel(true);
            }
        });
        return result;
    }

    /** 응답 분류(BR4.4 → BR4.1 → BR4.3 → BR4.2). */
    private Outcome classify(LunaTransport.RawResponse response, LunaPurpose purpose) {
        int status = response.status();
        if (status == 401 || status == 403) {
            return Outcome.failed(LunaFailureReason.AUTH_FAILED, null);
        }
        if (status == 429) {
            return Outcome.failed(LunaFailureReason.RATE_LIMITED, null);
        }
        if (status >= 500) {
            return Outcome.failed(LunaFailureReason.SERVER_ERROR, null);
        }
        if (status < 200 || status >= 300) {
            return Outcome.failed(LunaFailureReason.REQUEST_REJECTED, null);
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
        return new Outcome(null, reply.model(), reply.outputText());
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
    private record Outcome(LunaFailureReason failure, String responseModel, String outputJson) {

        static Outcome failed(LunaFailureReason reason, String responseModel) {
            return new Outcome(reason, responseModel, null);
        }
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
}
