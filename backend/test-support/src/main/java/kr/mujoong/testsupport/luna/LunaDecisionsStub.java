package kr.mujoong.testsupport.luna;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.mujoong.contract.json.JsonCodec;

/**
 * OpenAI Decisions API 루프백 대역 (FT-1023-d). {@link LunaResponsesStub}과 같은 방식으로 JDK {@link HttpServer}가 루프백 무작위 포트에 뜨고
 * {@code POST /v1/decisions}만 받는다.
 *
 * <ul>
 *   <li>응답 대본({@link Reply})을 순서대로 쓰고, 다 쓰면 마지막 응답을 되풀이한다.
 *   <li>요청 검사 모드(기본 켜짐): 최상위 키가 {@code model} · {@code input} · {@code questions} 밖이면(예: {@code store} · {@code temperature} ·
 *       {@code instructions}), 입력 메시지 · 파트 모양(user는 {@code input_text} · {@code input_json}, developer는 {@code input_text}만)이 틀리면,
 *       질문이 1 ~ 400개가 아니거나 {@code choice} 질문 모양이 틀리면 대본을 쓰지 않고 400으로 답한다. 공식 문서에 적힌 형식이고 실공급자 검증은
 *       아니다.
 *   <li>받은 요청을 보관해 시험이 금지 키 · 이름 유출을 확인한다.
 * </ul>
 *
 * <p>시험 전용이며 운영 코드는 이 모듈에 의존하지 않는다(R-F8). 실제 공급자를 부르지 않는다(R-F1).
 */
public final class LunaDecisionsStub implements AutoCloseable {

    /** 대역 경로. */
    public static final String PATH = "/v1/decisions";

    private static final JsonCodec JSON = JsonCodec.shared();
    private static final Set<String> TOP_LEVEL_KEYS = Set.of("model", "input", "questions");
    private static final Set<String> QUESTION_KEYS = Set.of("type", "name", "instructions", "choices");
    private static final Set<String> CHOICE_KEYS = Set.of("value", "description");
    private static final int MAX_QUESTIONS = 400;

    private final HttpServer server;
    private final ExecutorService executor;
    private final List<Reply> script = new ArrayList<>();
    private final List<LunaResponsesStub.RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final CountDownLatch closed = new CountDownLatch(1);
    private volatile boolean requestCheck = true;
    private int position;

    private LunaDecisionsStub() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    /** 대역을 띄운다. 대본이 없으면 답이 없는 응답({@code answers: []})을 준다. */
    public static LunaDecisionsStub start() {
        try {
            return new LunaDecisionsStub();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 호출부 설정 {@code mujoong.luna.base-url}로 쓸 주소(예: {@code http://127.0.0.1:41234/v1}). */
    public URI baseUrl() {
        return URI.create("http://" + server.getAddress().getAddress().getHostAddress() + ":" + server.getAddress().getPort()
                + "/v1");
    }

    /** 응답 대본을 바꾼다. */
    public synchronized LunaDecisionsStub respond(Reply... replies) {
        if (replies.length == 0) {
            throw new IllegalArgumentException("응답이 하나 이상 있어야 한다");
        }
        script.clear();
        script.addAll(List.of(replies));
        position = 0;
        return this;
    }

    /** 요청 검사 모드를 끈다(검사 자체를 시험할 때만). */
    public LunaDecisionsStub withoutRequestCheck() {
        this.requestCheck = false;
        return this;
    }

    /** 받은 요청 전부(도착 순서). */
    public List<LunaResponsesStub.RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    /** 받은 요청 수. */
    public int requestCount() {
        return requests.size();
    }

    /** 받은 요청 본문 중 하나라도 {@code needle}을 담았는가. */
    public boolean anyBodyContains(String needle) {
        return requests.stream().anyMatch(request -> request.body().contains(needle));
    }

    @Override
    public void close() {
        closed.countDown();
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            String path = exchange.getRequestURI().getRawPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, List<String>> headers = new TreeMap<>();
            exchange.getRequestHeaders().forEach((name, values) ->
                    headers.put(name.toLowerCase(Locale.ROOT), List.copyOf(values)));
            List<String> violations = new ArrayList<>();
            Reply reply;
            if (!method.equals("POST") || !path.equals(PATH)) {
                reply = Reply.status(404);
            } else {
                if (requestCheck) {
                    violations.addAll(violations(body));
                }
                reply = violations.isEmpty() ? next() : Reply.status(400);
            }
            requests.add(new LunaResponsesStub.RecordedRequest(path, Collections.unmodifiableMap(headers), body,
                    List.copyOf(violations), reply.status()));
            if (!reply.delay().isZero() && closed.await(reply.delay().toNanos(), TimeUnit.NANOSECONDS)) {
                return;
            }
            reply.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // 호출부가 시한으로 연결을 끊으면 쓰기가 실패한다(대본의 의도된 결과). 시험 대역이라 조용히 끝낸다.
        }
    }

    private synchronized Reply next() {
        if (script.isEmpty()) {
            return Reply.json(200, "{\"model\":\"gpt-6-luna\",\"answers\":[]}");
        }
        Reply reply = script.get(Math.min(position, script.size() - 1));
        position++;
        return reply;
    }

    /** 공식 문서의 요청 형식에 맞지 않는 곳(비었으면 맞음). */
    static List<String> violations(String body) {
        List<String> problems = new ArrayList<>();
        Map<?, ?> request;
        try {
            request = JSON.read(body, Map.class);
        } catch (RuntimeException e) {
            return List.of("body: JSON이 아니다");
        }
        if (request == null) {
            return List.of("body: 비었다");
        }
        for (Object key : request.keySet()) {
            if (!TOP_LEVEL_KEYS.contains(key)) {
                problems.add(key + ": Decisions 요청에 없는 매개변수");
            }
        }
        if (!(request.get("model") instanceof String model) || model.isBlank()) {
            problems.add("model: 문자열이 아니다");
        }
        Object input = request.get("input");
        if (!(input instanceof String text && !text.isBlank())) {
            if (!(input instanceof List<?> messages) || messages.isEmpty()) {
                problems.add("input: 문자열 또는 메시지 배열이 아니다");
            } else {
                messages.forEach(message -> checkMessage(message, problems));
            }
        }
        if (!(request.get("questions") instanceof List<?> questions) || questions.isEmpty() || questions.size() > MAX_QUESTIONS) {
            problems.add("questions: 1 ~ 400개 배열이 아니다");
        } else {
            questions.forEach(question -> checkQuestion(question, problems));
        }
        return problems;
    }

    private static void checkMessage(Object message, List<String> problems) {
        if (!(message instanceof Map<?, ?> item) || !(item.get("role") instanceof String role)
                || !List.of("developer", "user").contains(role)) {
            problems.add("input: 잘못된 메시지");
            return;
        }
        Object content = item.get("content");
        if (content instanceof String text && !text.isBlank()) {
            return;
        }
        if (!(content instanceof List<?> parts) || parts.isEmpty()) {
            problems.add("input: 메시지 내용이 없다");
            return;
        }
        for (Object part : parts) {
            if (!(part instanceof Map<?, ?> piece)) {
                problems.add("input: 잘못된 파트");
            } else if ("input_text".equals(piece.get("type"))) {
                if (!(piece.get("text") instanceof String)) {
                    problems.add("input: input_text에 text가 없다");
                }
            } else if ("input_json".equals(piece.get("type")) && role.equals("user")) {
                if (!(piece.get("data") instanceof Map<?, ?>)) {
                    problems.add("input: input_json의 data가 객체가 아니다");
                }
            } else {
                problems.add("input: " + role + " 메시지가 받지 않는 파트 " + piece.get("type"));
            }
        }
    }

    private static void checkQuestion(Object question, List<String> problems) {
        if (!(question instanceof Map<?, ?> item) || !"choice".equals(item.get("type"))) {
            problems.add("questions: choice 질문이 아니다");
            return;
        }
        for (Object key : item.keySet()) {
            if (!QUESTION_KEYS.contains(key)) {
                problems.add("questions: 모르는 키 " + key);
            }
        }
        if (!(item.get("name") instanceof String name) || name.isBlank()) {
            problems.add("questions: name이 없다");
        }
        if (!(item.get("instructions") instanceof String instructions) || instructions.isBlank()) {
            problems.add("questions: instructions가 없다");
        }
        if (!(item.get("choices") instanceof List<?> choices) || choices.isEmpty()) {
            problems.add("questions: choices가 없다");
            return;
        }
        for (Object choice : choices) {
            if (!(choice instanceof Map<?, ?> option) || !(option.get("value") instanceof String value) || value.isBlank()
                    || !CHOICE_KEYS.containsAll(option.keySet().stream().map(String::valueOf).toList())
                    || (option.containsKey("description") && !(option.get("description") instanceof String))) {
                problems.add("questions: 잘못된 선택지");
            }
        }
    }

    /**
     * 대본 응답 하나.
     *
     * @param delay 응답 머리를 보내기 전 대기
     */
    public record Reply(int status, String body, Map<String, String> headers, Duration delay) {

        public Reply {
            Objects.requireNonNull(body, "body");
            headers = Map.copyOf(headers);
            Objects.requireNonNull(delay, "delay");
        }

        /** {@code choice} 답 하나(확률은 넣은 순서대로, {@code confidence}는 대역 고정값). */
        public static Reply choice(String model, String name, String choice, Map<String, Double> probabilities) {
            List<Map<String, Object>> items = new ArrayList<>();
            probabilities.forEach((value, probability) -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("value", value);
                item.put("probability", probability);
                items.add(item);
            });
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("type", "choice");
            answer.put("name", name);
            answer.put("choice", choice);
            answer.put("probabilities", items);
            answer.put("confidence", 0.99);
            return answers(model, List.of(answer));
        }

        /** 거절(refusal) 답 하나. */
        public static Reply refusal(String model, String name) {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("type", "refusal");
            answer.put("name", name);
            answer.put("refusal", "대역 거절");
            return answers(model, List.of(answer));
        }

        /** 임의 답 목록(model이 null이면 뺀다). */
        public static Reply answers(String model, List<Map<String, Object>> answers) {
            Map<String, Object> response = new LinkedHashMap<>();
            if (model != null) {
                response.put("model", model);
            }
            response.put("answers", answers);
            response.put("usage", Map.of("input_tokens", 10));
            return json(200, JSON.write(response));
        }

        /** 상태 코드만 있는 오류 응답. */
        public static Reply status(int status) {
            return json(status, "{\"error\":{\"type\":\"stub_error\",\"code\":\"" + status + "\"}}");
        }

        /** 429와 {@code Retry-After}(초). */
        public static Reply rateLimited(long retryAfterSeconds) {
            return status(429).withHeader("Retry-After", Long.toString(retryAfterSeconds));
        }

        /** 임의 본문 응답. */
        public static Reply json(int status, String body) {
            return new Reply(status, body, Map.of(), Duration.ZERO);
        }

        /** 응답 머리를 {@code delayBy} 뒤에 보낸다. */
        public Reply delayed(Duration delayBy) {
            return new Reply(status, body, headers, delayBy);
        }

        /** 응답 머리를 더한다. */
        public Reply withHeader(String name, String value) {
            Map<String, String> merged = new LinkedHashMap<>(headers);
            merged.put(name, value);
            return new Reply(status, body, merged, delay);
        }
    }
}
