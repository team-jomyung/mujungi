package kr.mujoong.realtime.guardrail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.MujoongClock;

/**
 * 실제 제브 어댑터 (U13 FR2.1 · FR1.2 · BR2.1 ~ BR2.3 · BR2.6 · BR2.8). 제브로 나가는 유일한 길이다.
 *
 * <ul>
 *   <li>HTTPS POST 한 번, 재시도 없음(BR2.1). 시간 상한은 {@code min(deadline까지 남은 시간, jev-budget)}이고 본문 수신 끝까지 덮는다. 넘으면
 *       진행 중인 요청을 끊고 {@code TIMEOUT}으로 끝낸다. 숫자 시간 값은 코드에 없다(R-F7).
 *   <li>앱마다 하나인 {@link HttpClient}: 리다이렉트를 따르지 않고 프록시를 쓰지 않는다. 보내기 직전에 대상 주소를 기동 때와 같은
 *       {@link JevHosts#check(URI, JevHosts.Mode)}로 다시 확인한다(BR2.2).
 *   <li>키는 호출 때마다 공급자({@code apiKey})에서 읽어 {@code Authorization} 머리에만 쓰고 필드 · 로그 · 본문 · 문자열 표현에 두지 않는다(BR2.8).
 *       이 클래스는 아무것도 기록하지 않는다 — 결과 기록은 호출자 몫이고 원문을 남기지 않는다(R-F14).
 *   <li>모든 실패는 예외가 아니라 {@link GuardrailVerdict.Decision#FAILED} 결과로 완료한다. 자동 {@code ALLOW}는 없다.
 *   <li>공급자 응답 모양에 의존하는 코드는 {@link #convert}뿐이다(BR2.6). 실호출이 가정과 다르면 이 변환과 그 시험만 고친다.
 * </ul>
 *
 * <p><b>형식 가정(실제 제브 응답 형식은 아직 모른다):</b> 요청 본문은 {@code {model, systemPrompt, mode, candidateText, recentContext[],
 * policyVersion}}이고 {@value #CHECK_PATH}로 보낸다. 응답 본문은 정책 리소스의 시스템 지시가 요구하는 모양 그대로 최상위에
 * {@code decision · categories · policyFlags · reasonCode · reviewNeeded · policyVersion}을 가진 JSON 객체 하나다. 인증은 {@code Bearer}다.
 * 시험({@code JevGuardrailClientTest})이 이 가정을 못 박는다.
 */
public final class JevGuardrailClient implements GuardrailClient, AutoCloseable {

    /** 제브 키 환경 변수 이름. */
    public static final String API_KEY_ENV = GuardrailStartupRules.API_KEY_ENV;
    /** 검사 요청 경로(형식 가정, 기준 주소 뒤에 붙인다). */
    public static final String CHECK_PATH = "/v1/guardrail/check";
    /** 응답 본문 바이트 상한(넘으면 TRANSPORT_ERROR). 시간 값이 아니다. */
    static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private static final Set<String> CATEGORIES = Set.of("DIAGNOSIS", "TREATMENT", "SCORE", "CAUSE", "GENERALIZATION", "EMOTION_CLAIM",
            "PRESSURE");
    private static final Set<String> DECISIONS = Set.of("ALLOW", "BLOCK", "REVIEW");
    private static final Pattern FLAG = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    private final HttpClient http;
    private final URI endpoint;
    private final JevHosts.Mode hostMode;
    private final Optional<JevModels.Problem> modelProblem;
    private final String model;
    private final Supplier<String> apiKey;
    private final Function<String, Optional<String>> systemPrompts;
    private final Duration budget;
    private final MujoongClock clock;
    private final JsonCodec json;

    /**
     * @param properties 모드 · 주소 · 모델 설정(모델 이름은 요청에 그대로 실린다, BR2.3)
     * @param hostMode 주소 규칙(기동 때와 같은 모드)
     * @param apiKey 호출 때마다 환경에서 읽는 키 공급자(값을 보관하지 않음)
     * @param systemPrompts 정책 판 → 시스템 지시(세션에 고정된 정책 리소스). 없는 판이면 빈 값
     * @param budget 검사 시한 {@code mujoong.timing.guardrail.jev-budget}의 값
     */
    public JevGuardrailClient(GuardrailProperties properties, JevHosts.Mode hostMode, Supplier<String> apiKey,
            Function<String, Optional<String>> systemPrompts, Duration budget, MujoongClock clock, JsonCodec json) {
        Objects.requireNonNull(properties, "properties");
        this.hostMode = Objects.requireNonNull(hostMode, "hostMode");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.systemPrompts = Objects.requireNonNull(systemPrompts, "systemPrompts");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.json = Objects.requireNonNull(json, "json");
        this.endpoint = endpointOf(properties.baseUrl());
        this.modelProblem = JevModels.problem(properties.model());
        this.model = properties.model().strip();
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(HttpClient.Builder.NO_PROXY)
                .build();
    }

    /** 기준 주소 뒤에 검사 경로를 붙인다. 주소 모양이 아니면 null(보내기 직전 확인이 거절한다). */
    private static URI endpointOf(String baseUrl) {
        try {
            String base = baseUrl.strip();
            return URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + CHECK_PATH);
        } catch (IllegalArgumentException e) {
            // 잘못된 주소 설정은 값을 남기지 않고 null로 표시한다. check가 TRANSPORT_ERROR로 막는다
            return null;
        }
    }

    @Override
    public GuardrailMode mode() {
        return GuardrailMode.REAL;
    }

    /** 모델 · 주소 설정이 맞고 키가 비어 있지 않은가. 키는 읽기만 하고 보관하지 않는다. */
    @Override
    public boolean available() {
        return modelProblem.isEmpty() && endpoint != null && JevHosts.check(endpoint, hostMode).isEmpty() && keyPresent();
    }

    private boolean keyPresent() {
        String key = apiKey.get();
        return key != null && !key.isBlank();
    }

    @Override
    public CompletionStage<GuardrailVerdict> check(GuardrailRequest request, Instant deadline) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(deadline, "deadline");
        try {
            return start(request, deadline);
        } catch (RuntimeException unexpected) {
            // 보내기 전 준비 중 예기치 못한 오류도 호출자에게 던지지 않고 막힘으로 끝낸다(fail-closed). 원인 메시지는 입력을 담을 수 있어 버린다
            return CompletableFuture.completedFuture(fail(GuardrailVerdict.Failure.TRANSPORT_ERROR, request, 0L));
        }
    }

    private CompletionStage<GuardrailVerdict> start(GuardrailRequest request, Instant deadline) {
        if (modelProblem.isPresent()) {
            return done(fail(GuardrailVerdict.Failure.UNAVAILABLE, request, 0L));
        }
        String key = apiKey.get();
        if (key == null || key.isBlank()) {
            return done(fail(GuardrailVerdict.Failure.UNAVAILABLE, request, 0L));
        }
        Optional<String> systemPrompt = systemPrompts.apply(request.policyVersion());
        if (systemPrompt.isEmpty()) {
            return done(fail(GuardrailVerdict.Failure.INVALID_REQUEST, request, 0L));
        }
        if (endpoint == null || JevHosts.check(endpoint, hostMode).isPresent()) {
            return done(fail(GuardrailVerdict.Failure.TRANSPORT_ERROR, request, 0L));
        }
        Duration remaining = Duration.between(clock.now(), deadline);
        Duration limit = remaining.compareTo(budget) <= 0 ? remaining : budget;
        if (limit.isNegative() || limit.isZero()) {
            return done(fail(GuardrailVerdict.Failure.TIMEOUT, request, 0L));
        }
        String body = json.write(requestBody(request, systemPrompt.get()));
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(limit)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        long started = clock.monotonicNanos();
        CompletableFuture<HttpResponse<byte[]>> sent = http.sendAsync(httpRequest, info -> new BoundedBodySubscriber(MAX_RESPONSE_BYTES));
        CompletableFuture<GuardrailVerdict> result = sent
                .orTimeout(limit.toNanos(), TimeUnit.NANOSECONDS)
                .handle((response, failure) -> {
                    long latencyMs = clock.elapsedSince(started).toMillis();
                    if (failure != null) {
                        // 시한 초과 · 전송 오류면 진행 중인 요청을 끊는다
                        sent.cancel(true);
                        return fail(classify(failure), request, latencyMs);
                    }
                    return convert(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8), request, latencyMs);
                });
        // 호출자가 결과 Future를 취소해도 진행 중인 요청을 끊는다
        result.whenComplete((value, failure) -> {
            if (result.isCancelled()) {
                sent.cancel(true);
            }
        });
        return result;
    }

    private static CompletionStage<GuardrailVerdict> done(GuardrailVerdict verdict) {
        return CompletableFuture.completedFuture(verdict);
    }

    private static GuardrailVerdict fail(GuardrailVerdict.Failure failure, GuardrailRequest request, long latencyMs) {
        return GuardrailVerdict.failed(failure, request.policyVersion(), latencyMs);
    }

    /** 공급자 요청 본문(형식 가정). 후보 문장 · 맥락은 데이터 필드의 값이고 시스템 지시에는 섞이지 않는다(BR2.5). */
    private Map<String, Object> requestBody(GuardrailRequest request, String systemPrompt) {
        List<Map<String, Object>> context = new ArrayList<>();
        for (GuardrailRequest.ContextTurn turn : request.recentContext()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("speaker", turn.speaker().name());
            item.put("text", turn.text());
            context.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("systemPrompt", systemPrompt);
        body.put("mode", request.mode().name());
        body.put("candidateText", request.candidate());
        body.put("recentContext", context);
        body.put("policyVersion", request.policyVersion());
        return body;
    }

    /** 전송 실패 원인을 실패 종류로 바꾼다. */
    private static GuardrailVerdict.Failure classify(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof TimeoutException || cause instanceof HttpTimeoutException || cause instanceof CancellationException) {
            return GuardrailVerdict.Failure.TIMEOUT;
        }
        return GuardrailVerdict.Failure.TRANSPORT_ERROR;
    }

    /**
     * 공급자 응답 → 내부 형식, 이 파일에서 응답 모양을 읽는 유일한 곳(BR2.6). 2xx가 아니면 TRANSPORT_ERROR(리다이렉트 포함), JSON 객체가
     * 아니거나 필수 필드 · 값이 스키마({@code guardrail-result.schema.json})와 다르면 INVALID_JSON, 응답의 정책 판이 요청의 판과 다르면
     * POLICY_MISMATCH다. 판정 ALLOW인데 {@code reviewNeeded}가 참이면 모순이라 INVALID_JSON으로 막는다(fail-closed).
     */
    GuardrailVerdict convert(int status, String body, GuardrailRequest request, long latencyMs) {
        if (status < 200 || status >= 300) {
            return fail(GuardrailVerdict.Failure.TRANSPORT_ERROR, request, latencyMs);
        }
        Map<?, ?> root;
        try {
            root = json.read(body, Map.class);
        } catch (RuntimeException e) {
            // 공급자 본문이 JSON 객체가 아니다. 원인 메시지는 본문 일부를 담을 수 있어 버린다(R-F14)
            return fail(GuardrailVerdict.Failure.INVALID_JSON, request, latencyMs);
        }
        if (root == null) {
            return fail(GuardrailVerdict.Failure.INVALID_JSON, request, latencyMs);
        }
        Object decisionValue = root.get("decision");
        Optional<List<String>> categories = stringList(root.get("categories"), true, CATEGORIES::contains);
        Optional<List<String>> flags = stringList(root.get("policyFlags"), false, value -> FLAG.matcher(value).matches());
        Object reasonValue = root.get("reasonCode");
        Object versionValue = root.get("policyVersion");
        Object reviewNeeded = root.get("reviewNeeded");
        if (!(decisionValue instanceof String decision) || !DECISIONS.contains(decision)
                || categories.isEmpty() || flags.isEmpty()
                || !(reasonValue instanceof String reason) || !GuardrailEventRecord.REASON_CODE.matcher(reason).matches()
                || !(versionValue instanceof String version) || version.isEmpty() || version.length() > GuardrailEventRecord.POLICY_VERSION_MAX
                || (reviewNeeded != null && !(reviewNeeded instanceof Boolean))
                || ("ALLOW".equals(decision) && Boolean.TRUE.equals(reviewNeeded))) {
            return fail(GuardrailVerdict.Failure.INVALID_JSON, request, latencyMs);
        }
        if (!version.equals(request.policyVersion())) {
            return fail(GuardrailVerdict.Failure.POLICY_MISMATCH, request, latencyMs);
        }
        return switch (decision) {
            case "ALLOW" -> GuardrailVerdict.allow(reason, version, latencyMs, categories.get(), flags.get());
            case "BLOCK" -> GuardrailVerdict.block(reason, version, latencyMs, categories.get(), flags.get());
            default -> GuardrailVerdict.review(reason, version, latencyMs, categories.get(), flags.get());
        };
    }

    /** 문자열 배열을 읽는다. 없으면 {@code required}가 거짓일 때 빈 목록, 모양이 틀리면 빈 값(Optional). */
    private static Optional<List<String>> stringList(Object value, boolean required, java.util.function.Predicate<String> valid) {
        if (value == null) {
            return required ? Optional.empty() : Optional.of(List.of());
        }
        if (!(value instanceof List<?> items)) {
            return Optional.empty();
        }
        List<String> out = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof String text) || !valid.test(text) || out.contains(text)) {
                return Optional.empty();
            }
            out.add(text);
        }
        return Optional.of(out);
    }

    /** 앱 컨텍스트가 닫힐 때 {@link HttpClient}를 닫는다. */
    @Override
    public void close() {
        http.close();
    }

    /** 문자열 표현에 키 · 주소 값을 내지 않는다(R-F3). */
    @Override
    public String toString() {
        return "JevGuardrailClient[mode=REAL]";
    }

    /** 응답 본문이 상한을 넘었다. */
    private static final class BodyTooLargeException extends IOException {

        private static final long serialVersionUID = 1L;

        BodyTooLargeException() {
            super("LENGTH_EXCEEDED");
        }
    }

    /** 바이트를 세며 상한까지만 모으는 본문 수신기. */
    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

        private final int maxBytes;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private long received;

        BoundedBodySubscriber(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            this.subscription = value;
            value.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (body.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                received += item.remaining();
                if (received > maxBytes) {
                    subscription.cancel();
                    body.completeExceptionally(new BodyTooLargeException());
                    return;
                }
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                buffer.writeBytes(chunk);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            body.complete(buffer.toByteArray());
        }
    }
}
