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
 *   <li>공급자 응답 모양에 의존하는 코드는 {@link #convert}뿐이다(BR2.6).
 * </ul>
 *
 * <p><b>선 형식(제브 공식 문서, 선 형식 정정 FR1 · FR2):</b> {@code POST {기준 주소}}{@value #SYSTEMONE_PATH}, 인증 {@code Bearer}. 요청 본문은
 * {@code {model, state, questions}}다 — {@code state}는 {@code {candidate, context[{speaker,text}]}}이고 {@code questions}는 질문 ID
 * {@value #QUESTION_ID} 하나의 {@code {type: "choice", instructions, criteria{라벨 → 설명}}}이다(D2 · D3). 응답은
 * {@code answers.guardrail.{choice, probabilities}}를 읽는다({@code confidence}는 읽지 않고 판정에 쓰지 않는다). 라벨은 7개 범주와 {@value
 * JevQuestion#PASS}다. 판정(D1): {@code choice}가 {@code PASS}이고 {@code PASS} 확률이 문턱 이상이면서 다른 어느 라벨보다 엄격히 클 때만
 * {@code ALLOW}, 동률이면 {@code REVIEW}(사유 {@code PASS_TIED}), 문턱 미만이면 {@code REVIEW}(사유 {@code PASS_BELOW_THRESHOLD}), 다른
 * 라벨이 1순위면 {@code BLOCK}(사유 = 그 라벨)이다. 비 2xx는 오류 본문을 읽지 않고 모두 {@code TRANSPORT_ERROR}다. 응답에 정책 판이 없으므로
 * {@code POLICY_MISMATCH}는 더 만들지 않는다. 실제 응답 · 오류 본문 · 고정 버전 모델 요청은 실호출({@code jevLiveTest})로만 확인된다.
 */
public final class JevGuardrailClient implements GuardrailClient, AutoCloseable {

    /** 제브 키 환경 변수 이름. */
    public static final String API_KEY_ENV = GuardrailStartupRules.API_KEY_ENV;
    /** 제브 {@code systemone} 요청 경로(공식 문서, 기준 주소 뒤에 붙인다). */
    public static final String SYSTEMONE_PATH = "/v1/systemone";
    /** 요청 · 응답에서 우리 질문을 가리키는 질문 ID(코드 상수, D3). */
    public static final String QUESTION_ID = "guardrail";
    /** 질문 형식(이 어댑터는 {@code choice}만 쓴다). */
    static final String QUESTION_TYPE = "choice";
    /** 응답 본문 바이트 상한(넘으면 TRANSPORT_ERROR). 시간 값이 아니다. */
    static final int MAX_RESPONSE_BYTES = 64 * 1024;
    /**
     * 확률 합 허용 오차(D7). 합이 1에서 이만큼 넘게 벗어나면 INVALID_JSON이다. <b>근거 없는 임시값이다 — 실호출 결과로 조정한다.</b> 시간 값이 아니다.
     */
    static final double PROBABILITY_SUM_TOLERANCE = 0.001;
    /** 문턱 미만 REVIEW의 사유 코드. */
    static final String REASON_BELOW_THRESHOLD = "PASS_BELOW_THRESHOLD";
    /** PASS가 다른 라벨과 동률 1순위인 REVIEW의 사유 코드. */
    static final String REASON_PASS_TIED = "PASS_TIED";

    /**
     * 한 번의 검사 관측(읽기 전용). {@code verdict}는 {@link #check}가 돌려주는 결과와 같다. {@code choice}(고른 라벨) · {@code passProbability}
     * · {@code responseModel}은 응답을 읽은 경우에만 있고 실호출 도구({@code jevLiveTest}) 보고서에만 쓴다 — {@link GuardrailVerdict}나
     * {@code guardrail_event}에는 넣지 않는다(Q2 답 A).
     *
     * @param verdict 판정(실패 포함)
     * @param choice 응답이 고른 라벨(검증을 통과한 경우), 없으면 null
     * @param passProbability {@code PASS} 라벨의 확률(검증을 통과한 경우), 없으면 null
     * @param responseModel 응답의 {@code model}(모델 이름 형식일 때만), 없으면 null
     */
    public record Inspection(GuardrailVerdict verdict, String choice, Double passProbability, String responseModel) {

        public Inspection {
            Objects.requireNonNull(verdict, "verdict");
        }

        static Inspection of(GuardrailVerdict verdict) {
            return new Inspection(verdict, null, null, null);
        }

        /** 문자열 표현에 값(확률 · 모델 이름)을 내지 않는다. */
        @Override
        public String toString() {
            return "Inspection[decision=" + verdict.decision() + "]";
        }
    }

    private final HttpClient http;
    private final URI endpoint;
    private final JevHosts.Mode hostMode;
    private final Optional<JevModels.Problem> modelProblem;
    private final String model;
    private final Supplier<String> apiKey;
    private final Function<String, Optional<JevQuestion>> questions;
    private final double passThreshold;
    private final Duration budget;
    private final MujoongClock clock;
    private final JsonCodec json;

    /**
     * @param properties 모드 · 주소 · 모델 설정(모델 이름은 요청에 그대로 실린다, BR2.3)
     * @param hostMode 주소 규칙(기동 때와 같은 모드)
     * @param apiKey 호출 때마다 환경에서 읽는 키 공급자(값을 보관하지 않음)
     * @param questions 정책 판 → 제브 {@code choice} 질문(검사 질문 + 라벨 설명, 세션에 고정된 정책 리소스). 없는 판이면 빈 값
     * @param budget 검사 시한 {@code mujoong.timing.guardrail.jev-budget}의 값
     */
    public JevGuardrailClient(GuardrailProperties properties, JevHosts.Mode hostMode, Supplier<String> apiKey,
            Function<String, Optional<JevQuestion>> questions, Duration budget, MujoongClock clock, JsonCodec json) {
        Objects.requireNonNull(properties, "properties");
        this.hostMode = Objects.requireNonNull(hostMode, "hostMode");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.questions = Objects.requireNonNull(questions, "questions");
        this.passThreshold = properties.passThreshold();
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

    /** 기준 주소 뒤에 {@code systemone} 경로를 붙인다. 주소 모양이 아니면 null(보내기 직전 확인이 거절한다). */
    private static URI endpointOf(String baseUrl) {
        try {
            String base = baseUrl.strip();
            return URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + SYSTEMONE_PATH);
        } catch (IllegalArgumentException e) {
            // 잘못된 주소 설정은 값을 남기지 않고 null로 표시한다. check가 TRANSPORT_ERROR로 막는다
            return null;
        }
    }

    @Override
    public GuardrailMode mode() {
        return GuardrailMode.REAL;
    }

    /** 모델 · 주소 · 문턱 설정이 맞고 키가 비어 있지 않은가. 키는 읽기만 하고 보관하지 않는다. */
    @Override
    public boolean available() {
        return modelProblem.isEmpty() && GuardrailProperties.validThreshold(passThreshold) && endpoint != null
                && JevHosts.check(endpoint, hostMode).isEmpty() && keyPresent();
    }

    private boolean keyPresent() {
        String key = apiKey.get();
        return key != null && !key.isBlank();
    }

    @Override
    public CompletionStage<GuardrailVerdict> check(GuardrailRequest request, Instant deadline) {
        CompletableFuture<Inspection> inspection = inspectFuture(request, deadline);
        CompletableFuture<GuardrailVerdict> verdict = inspection.thenApply(Inspection::verdict);
        // 호출자가 결과 Future를 취소하면 진행 중인 검사(요청)도 끊는다
        verdict.whenComplete((value, failure) -> {
            if (verdict.isCancelled()) {
                inspection.cancel(true);
            }
        });
        return verdict;
    }

    /**
     * 읽기 전용 검사 관측(선 형식 정정 FR5.2). {@link #check}와 같은 호출을 하고 같은 판정을 {@link Inspection#verdict}에 담되, 응답에서 읽은 고른
     * 라벨 · {@code PASS} 확률 · 응답 모델을 함께 돌려준다. 실호출 도구({@code jevLiveTest}) 보고서용이며 운영 경로는 쓰지 않는다. 호출 한 번 ·
     * 재시도 없음 · 모든 실패는 예외가 아니라 {@code FAILED} 판정이다.
     */
    public CompletionStage<Inspection> inspect(GuardrailRequest request, Instant deadline) {
        return inspectFuture(request, deadline);
    }

    private CompletableFuture<Inspection> inspectFuture(GuardrailRequest request, Instant deadline) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(deadline, "deadline");
        try {
            return start(request, deadline);
        } catch (RuntimeException unexpected) {
            // 보내기 전 준비 중 예기치 못한 오류도 호출자에게 던지지 않고 막힘으로 끝낸다(fail-closed). 원인 메시지는 입력을 담을 수 있어 버린다
            return done(fail(GuardrailVerdict.Failure.TRANSPORT_ERROR, request, 0L));
        }
    }

    private CompletableFuture<Inspection> start(GuardrailRequest request, Instant deadline) {
        if (modelProblem.isPresent() || !GuardrailProperties.validThreshold(passThreshold)) {
            return done(fail(GuardrailVerdict.Failure.UNAVAILABLE, request, 0L));
        }
        String key = apiKey.get();
        if (key == null || key.isBlank()) {
            return done(fail(GuardrailVerdict.Failure.UNAVAILABLE, request, 0L));
        }
        Optional<JevQuestion> question = questions.apply(request.policyVersion());
        if (question.isEmpty()) {
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
        String body = json.write(requestBody(request, question.get()));
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(limit)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        long started = clock.monotonicNanos();
        // 2xx만 본문을 모은다. 비 2xx의 오류 본문은 읽지 않고 버린다(FR2.4 · R-F14)
        CompletableFuture<HttpResponse<byte[]>> sent = http.sendAsync(httpRequest,
                info -> info.statusCode() >= 200 && info.statusCode() < 300 ? new BoundedBodySubscriber(MAX_RESPONSE_BYTES)
                        : HttpResponse.BodySubscribers.replacing(new byte[0]));
        CompletableFuture<Inspection> result = sent
                .orTimeout(limit.toNanos(), TimeUnit.NANOSECONDS)
                .handle((response, failure) -> {
                    long latencyMs = clock.elapsedSince(started).toMillis();
                    if (failure != null) {
                        // 시한 초과 · 전송 오류면 진행 중인 요청을 끊는다
                        sent.cancel(true);
                        return fail(classify(failure), request, latencyMs);
                    }
                    return convert(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8), request, question.get(), latencyMs);
                });
        // 호출자가 결과 Future를 취소해도 진행 중인 요청을 끊는다
        result.whenComplete((value, failure) -> {
            if (result.isCancelled()) {
                sent.cancel(true);
            }
        });
        return result;
    }

    private static CompletableFuture<Inspection> done(Inspection inspection) {
        return CompletableFuture.completedFuture(inspection);
    }

    private static Inspection fail(GuardrailVerdict.Failure failure, GuardrailRequest request, long latencyMs) {
        return Inspection.of(GuardrailVerdict.failed(failure, request.policyVersion(), latencyMs));
    }

    /**
     * 공급자 요청 본문(제브 공식 형식, D2 · D3): {@code {model, state{candidate, context[]}, questions{guardrail{type, instructions,
     * criteria}}}}. 후보 문장 · 맥락은 {@code state}의 데이터 값이고 {@code questions}의 지시에는 섞이지 않는다(BR2.5). {@code mode}는 보내지 않는다
     * (운영 호출은 자유 대화뿐이고 연습 모드 검사는 U12가 정한다).
     */
    private Map<String, Object> requestBody(GuardrailRequest request, JevQuestion question) {
        List<Map<String, Object>> context = new ArrayList<>();
        for (GuardrailRequest.ContextTurn turn : request.recentContext()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("speaker", turn.speaker().name());
            item.put("text", turn.text());
            context.add(item);
        }
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("candidate", request.candidate());
        state.put("context", context);
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("type", QUESTION_TYPE);
        choice.put("instructions", question.instructions());
        choice.put("criteria", question.criteria());
        Map<String, Object> askedQuestions = new LinkedHashMap<>();
        askedQuestions.put(QUESTION_ID, choice);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("state", state);
        body.put("questions", askedQuestions);
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
     * 공급자 응답 → 내부 판정, 이 파일에서 응답 모양을 읽는 유일한 곳(BR2.6). 비 2xx(리다이렉트 포함)는 본문을 보지 않고 TRANSPORT_ERROR다. JSON 객체가
     * 아니거나 {@code answers.guardrail}이 검증을 못 넘으면 INVALID_JSON이다: {@code type}이 {@code choice}, {@code choice}가 보낸 라벨 중 하나,
     * {@code probabilities}가 보낸 라벨 전부(모르는 라벨 없음)에 대한 0 ~ 1의 유한한 숫자이고 합이 1에서 {@value #PROBABILITY_SUM_TOLERANCE} 이내,
     * 그리고 {@code choice}의 확률이 최댓값(최댓값 집합 안)이어야 한다. 통과하면 D1 규칙으로 판정한다: PASS이고 문턱 이상이면서 다른 라벨보다 엄격히 크면
     * ALLOW, PASS가 동률이면 REVIEW(PASS_TIED), 문턱 미만이면 REVIEW(PASS_BELOW_THRESHOLD), PASS가 아니면 BLOCK(사유 = 라벨, 동률이어도 BLOCK).
     * 동률이면서 문턱 미만이면 PASS_TIED가 먼저다. 응답에 정책 판이 없으므로 {@code policyVersion}은 요청의 판이다(D8).
     */
    Inspection convert(int status, String body, GuardrailRequest request, JevQuestion question, long latencyMs) {
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
        if (root == null || !(root.get("answers") instanceof Map<?, ?> answers) || !(answers.get(QUESTION_ID) instanceof Map<?, ?> answer)
                || !QUESTION_TYPE.equals(answer.get("type")) || !(answer.get("choice") instanceof String choice)
                || !question.criteria().containsKey(choice)) {
            return fail(GuardrailVerdict.Failure.INVALID_JSON, request, latencyMs);
        }
        Optional<Map<String, Double>> probabilities = probabilitiesOf(answer.get("probabilities"), question);
        if (probabilities.isEmpty()) {
            return fail(GuardrailVerdict.Failure.INVALID_JSON, request, latencyMs);
        }
        Map<String, Double> probability = probabilities.get();
        double max = probability.values().stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
        if (probability.get(choice) != max) {
            // choice가 확률 최댓값 집합 밖이다(정확히 같음 비교, D1). 1순위를 믿을 수 없어 막는다
            return fail(GuardrailVerdict.Failure.INVALID_JSON, request, latencyMs);
        }
        String responseModel = root.get("model") instanceof String name && JevModels.wellFormed(name) ? name : null;
        Double passProbability = probability.get(JevQuestion.PASS);
        GuardrailVerdict verdict;
        try {
            verdict = decide(choice, probability, request, latencyMs);
        } catch (IllegalArgumentException e) {
            // 라벨이 사유 코드 형식이 아니면 판정을 만들 수 없다. 원인 메시지는 버리고 막는다(fail-closed)
            return fail(GuardrailVerdict.Failure.INVALID_JSON, request, latencyMs);
        }
        return new Inspection(verdict, choice, passProbability, responseModel);
    }

    /** D1 판정 규칙(검증을 통과한 {@code choice} · 확률로만 부른다). 이 메서드는 {@code PASS}가 아닌 1순위를 항상 막는다. */
    private GuardrailVerdict decide(String choice, Map<String, Double> probability, GuardrailRequest request, long latencyMs) {
        String version = request.policyVersion();
        if (!JevQuestion.PASS.equals(choice)) {
            return GuardrailVerdict.block(choice, version, latencyMs, List.of(choice), List.of());
        }
        double pass = probability.get(JevQuestion.PASS);
        boolean tied = probability.entrySet().stream().anyMatch(entry -> !JevQuestion.PASS.equals(entry.getKey()) && entry.getValue() == pass);
        if (tied) {
            return GuardrailVerdict.review(REASON_PASS_TIED, version, latencyMs, List.of(), List.of());
        }
        if (pass >= passThreshold) {
            return GuardrailVerdict.allow(JevQuestion.PASS, version, latencyMs, List.of(), List.of());
        }
        return GuardrailVerdict.review(REASON_BELOW_THRESHOLD, version, latencyMs, List.of(), List.of());
    }

    /**
     * {@code probabilities}를 읽는다. 보낸 라벨 전부를 가진 숫자 맵이고 모르는 라벨이 없으며 값이 모두 0 ~ 1의 유한한 수이고 합이 1에서
     * {@value #PROBABILITY_SUM_TOLERANCE} 이내일 때만 값이 있다.
     */
    private static Optional<Map<String, Double>> probabilitiesOf(Object value, JevQuestion question) {
        if (!(value instanceof Map<?, ?> raw) || raw.size() != question.criteria().size()) {
            return Optional.empty();
        }
        Map<String, Double> out = new LinkedHashMap<>();
        double sum = 0;
        for (String label : question.criteria().keySet()) {
            // JSON 숫자만 받는다(문자열 · 불리언 · null 거부). NaN · 무한대는 JSON에 없지만 방어한다
            if (!(raw.get(label) instanceof Number number)) {
                return Optional.empty();
            }
            double p = number.doubleValue();
            if (!Double.isFinite(p) || p < 0 || p > 1) {
                return Optional.empty();
            }
            out.put(label, p);
            sum += p;
        }
        return Math.abs(sum - 1.0) > PROBABILITY_SUM_TOLERANCE ? Optional.empty() : Optional.of(out);
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
