package kr.mujoong.core.luna;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.textai.TextAiProperties;

/**
 * Core Luna 공통 호출 (U9 FR1.1 · FR1.2 · FR1.4 · BR2 ~ BR6 · SD1 ~ SD6). 동기 {@link #call(LunaRequest)} 하나다 — 기존 게이트웨이가 이미 가상
 * 스레드 실행기에서 부른다(NFR 설계 가정 2).
 *
 * <ol>
 *   <li>입력 정리(BR2.1 ~ BR2.4). 실패면 공급자를 부르지 않고 FORBIDDEN_INPUT(시도 0).
 *   <li>요청 조립(BR1.6 · BR1.8 · BR2.5 · BR4.5): 지시는 {@code instructions}, 데이터는 {@code input}(JSON 문자열), {@code store=false},
 *       {@code temperature}는 보내지 않는다.
 *   <li>시도마다 시한 = min(마감까지 남은 시간, 목적 시한 키)(BR5.1). 0 이하면 부르지 않고 TIMEOUT.
 *   <li>응답 분류(BR4.4 → BR4.1 → BR4.3 → BR4.2): 상태 코드 → 미완료 · 거절 → 빈 출력 → 모델 → 원본 계약 스키마.
 *   <li>재시도(BR3): REQUEST_BOUNDED만 일시 실패 기술 재시도 1 · 스키마 불일치 재요청 1(합계 ≤ 3). 대기 = max(backoff, Retry-After)이고
 *       남은 시간이 대기보다 길 때만 한다(BR3.6 · NFR1.3). NONE · JOB_ATTEMPT · 기존 어댑터 요청은 1회.
 *   <li>시도마다 {@code LUNA_CALL} 한 줄(BR6.1). 본문 · 이름 · 키는 남기지 않는다(BR6.2).
 * </ol>
 *
 * <p>시각은 {@link MujoongClock}으로만 읽고 시간 값은 {@code mujoong.text-ai.*} 설정에서만 온다(BR5.2 · R-F6 · R-F7).
 */
public class CoreLunaClient {

    private final LunaProperties properties;
    private final TextAiProperties timing;
    private final LunaSchemas schemas;
    private final LunaInputSanitizer sanitizer;
    private final LunaTransport transport;
    private final EventLogger events;
    private final MujoongClock clock;
    private final JsonCodec json;
    private final Sleeper sleeper;

    /** 재시도 대기(시험은 시계를 앞당기는 대역을 넣는다). */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** 운영 대기: 설정 Duration(backoff 또는 Retry-After)을 나노초로 넘긴다. */
    public static final Sleeper SYSTEM_SLEEPER = duration -> TimeUnit.NANOSECONDS.sleep(duration.toNanos());

    public CoreLunaClient(LunaProperties properties, TextAiProperties timing, LunaSchemas schemas, LunaInputSanitizer sanitizer,
            LunaTransport transport, EventLogger events, MujoongClock clock, JsonCodec json, Sleeper sleeper) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.timing = Objects.requireNonNull(timing, "timing");
        Objects.requireNonNull(timing.result(), "mujoong.text-ai.result");
        this.schemas = Objects.requireNonNull(schemas, "schemas");
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.json = Objects.requireNonNull(json, "json");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    /**
     * 공급자를 부른다.
     *
     * @throws LunaException 실패(사유 코드 · 쓴 시도 수만)
     */
    public LunaResult call(LunaRequest request) {
        Objects.requireNonNull(request, "request");
        SanitizedInput input;
        try {
            input = sanitizer.sanitize(request.input(), request.aliases());
        } catch (LunaException rejected) {
            audit(request, List.of(), LunaFailureReason.FORBIDDEN_INPUT.name(), null, Duration.ZERO, 0);
            throw rejected;
        }
        Optional<LunaSchemas.OutputSchemaPair> schema = request.outputMode() == LunaRequest.OutputMode.CONTRACT
                ? schemas.pair(request.purpose()) : Optional.empty();
        String body = json.write(new ResponsesRequest(properties.model(), request.instructions(), json.write(input.payload()),
                schema.orElse(null), request.purpose(), properties.options(request.purpose())).body());
        boolean retries = request.outputMode() == LunaRequest.OutputMode.CONTRACT
                && request.purpose().retryClass() == LunaPurpose.RetryClass.REQUEST_BOUNDED;
        int attempts = 0;
        boolean technicalRetryUsed = false;
        boolean schemaRetryUsed = false;
        while (true) {
            Duration limit = min(remaining(request.deadline()), purposeTimeout(request.purpose()));
            if (limit.isNegative() || limit.isZero()) {
                if (attempts == 0) {
                    audit(request, input.sentFieldNames(), LunaFailureReason.TIMEOUT.name(), null, Duration.ZERO, 0);
                }
                throw new LunaException(LunaFailureReason.TIMEOUT, attempts);
            }
            attempts++;
            long started = clock.monotonicNanos();
            Attempt attempt = attempt(body, limit, request, schema);
            Duration latency = clock.elapsedSince(started);
            audit(request, input.sentFieldNames(), attempt.failure == null ? "OK" : attempt.failure.name(),
                    attempt.responseModel, latency, attempts);
            if (attempt.failure == null) {
                return new LunaResult(attempt.outputJson, attempt.responseModel, request.promptVersion(), latency, attempts,
                        schemaRetryUsed);
            }
            Duration wait;
            if (retries && attempt.failure.isTransient() && !technicalRetryUsed) {
                technicalRetryUsed = true;
                Duration backoff = timing.retry().backoff();
                wait = attempt.retryAfter.filter(retryAfter -> retryAfter.compareTo(backoff) > 0).orElse(backoff);
            } else if (retries && attempt.failure == LunaFailureReason.SCHEMA_INVALID && !schemaRetryUsed) {
                schemaRetryUsed = true;
                wait = Duration.ZERO;
            } else {
                throw new LunaException(attempt.failure, attempts);
            }
            // 남은 시간이 대기보다 길지 않으면 재시도하지 않고 마지막 실패를 돌려준다(BR3.6 · NFR1.3)
            Duration left = remaining(request.deadline());
            if (left.compareTo(wait) <= 0) {
                throw new LunaException(attempt.failure, attempts);
            }
            if (!wait.isZero()) {
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new LunaException(LunaFailureReason.TIMEOUT, attempts);
                }
            }
        }
    }

    private Attempt attempt(String body, Duration limit, LunaRequest request, Optional<LunaSchemas.OutputSchemaPair> schema) {
        CompletableFuture<LunaTransport.RawResponse> future = transport.send(body, limit);
        LunaTransport.RawResponse response;
        try {
            response = future.get();
        } catch (InterruptedException interrupted) {
            // 바깥 게이트웨이가 시한으로 이 스레드를 끊었다: 요청을 취소하고 시한 초과로 끝낸다
            future.cancel(true);
            Thread.currentThread().interrupt();
            return Attempt.failed(LunaFailureReason.TIMEOUT, null, Optional.empty());
        } catch (ExecutionException failed) {
            return Attempt.failed(LunaTransport.translate(failed).reason(), null, Optional.empty());
        }
        return classify(response, request.purpose(), schema.isPresent());
    }

    /** 응답 분류(BR4.4 → BR4.1 → BR4.3 → BR4.2). */
    private Attempt classify(LunaTransport.RawResponse response, LunaPurpose purpose, boolean contractSchema) {
        int status = response.status();
        if (status == 401 || status == 403) {
            return Attempt.failed(LunaFailureReason.AUTH_FAILED, null, Optional.empty());
        }
        if (status == 429) {
            return Attempt.failed(LunaFailureReason.RATE_LIMITED, null, response.retryAfter());
        }
        if (status >= 500) {
            return Attempt.failed(LunaFailureReason.SERVER_ERROR, null, Optional.empty());
        }
        if (status < 200 || status >= 300) {
            return Attempt.failed(LunaFailureReason.REQUEST_REJECTED, null, Optional.empty());
        }
        ResponsesReply reply = ResponsesReply.parse(json, response.body());
        if (reply == null) {
            return Attempt.failed(LunaFailureReason.SERVER_ERROR, null, Optional.empty());
        }
        String model = reply.model();
        if ("incomplete".equals(reply.status())) {
            return Attempt.failed(LunaFailureReason.LENGTH_EXCEEDED, model, Optional.empty());
        }
        if (reply.refused()) {
            return Attempt.failed(LunaFailureReason.REFUSED, model, Optional.empty());
        }
        if (!"completed".equals(reply.status())) {
            return Attempt.failed(LunaFailureReason.SERVER_ERROR, model, Optional.empty());
        }
        if (reply.outputText().isBlank()) {
            return Attempt.failed(LunaFailureReason.EMPTY_OUTPUT, model, Optional.empty());
        }
        if (model == null || model.isBlank()) {
            return Attempt.failed(LunaFailureReason.MODEL_MISSING, null, Optional.empty());
        }
        boolean valid = contractSchema ? schemas.isValidOutput(purpose, reply.outputText()) : schemas.isJsonObject(reply.outputText());
        if (!valid) {
            return Attempt.failed(LunaFailureReason.SCHEMA_INVALID, model, Optional.empty());
        }
        return new Attempt(null, model, reply.outputText(), Optional.empty());
    }

    private void audit(LunaRequest request, List<String> sentFields, String outcome, String responseModel, Duration latency,
            int attempt) {
        events.log(EventCode.LUNA_CALL, EventLog.Component.CORE_LUNA_CLIENT, null, entry -> {
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

    private Duration purposeTimeout(LunaPurpose purpose) {
        return purpose.usesResultTimeout() ? timing.result().requestTimeout() : timing.requestTimeout();
    }

    private Duration remaining(Instant deadline) {
        return Duration.between(clock.now(), deadline);
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    /** 시도 하나의 결과(실패면 failure가 있다). */
    private record Attempt(LunaFailureReason failure, String responseModel, String outputJson, Optional<Duration> retryAfter) {

        static Attempt failed(LunaFailureReason reason, String responseModel, Optional<Duration> retryAfter) {
            return new Attempt(reason, responseModel, null, retryAfter);
        }
    }

    /**
     * Responses API 요청 본문(SD2). 이 레코드에는 {@code temperature} 칸이 없고 {@code store}는 언제나 false다(BR1.6 · BR1.8).
     *
     * @param input 정리된 데이터의 JSON 문자열(지시와 다른 자리, BR2.5)
     * @param schema 요청용 스키마 사본(없으면 json_object)
     */
    record ResponsesRequest(String model, String instructions, String input, LunaSchemas.OutputSchemaPair schema,
            LunaPurpose purpose, LunaProperties.PurposeOptions options) {

        Map<String, Object> body() {
            Map<String, Object> format = new LinkedHashMap<>();
            if (schema == null) {
                format.put("type", "json_object");
            } else {
                format.put("type", "json_schema");
                format.put("name", purpose.name().toLowerCase(java.util.Locale.ROOT));
                format.put("schema", schema.requestSchema());
                format.put("strict", schema.strict());
            }
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
