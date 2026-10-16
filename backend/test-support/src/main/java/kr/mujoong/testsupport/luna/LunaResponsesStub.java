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
 * OpenAI Responses API 루프백 대역 (U9 Step 8 · BR9.1 · AC2.3.4 · NFR5.3 · NFR 설계 가정 9).
 *
 * <p>JDK {@link HttpServer}로 루프백 무작위 포트에 뜨고 {@code POST /v1/responses}만 받는다. {@code ContractStubServer}와 같은 방식이다.
 *
 * <ul>
 *   <li>응답 대본({@link Reply})을 순서대로 쓰고, 다 쓰면 마지막 응답을 되풀이한다.
 *   <li>요청 검사 모드(기본 켜짐): 본문에 {@code temperature}가 있거나 {@code store}가 {@code false}가 아니거나, {@code text.format}의
 *       요청용 스키마에 공급자가 받지 않는 키워드({@code minLength} · {@code pattern} · {@code $id} 등)가 있거나, {@code strict=true}인데
 *       닫히지 않은 객체 · required 밖 속성이 있으면 대본을 쓰지 않고 400으로 답한다(검토 R-02).
 *   <li>받은 요청(경로 · 헤더 · 본문 · 검사 위반 · 답한 상태)을 보관해 시험이 금지 키 · 이름 유출을 확인한다.
 * </ul>
 *
 * <p>시험 전용이며 운영 코드는 이 모듈에 의존하지 않는다(R-F8 · ArchUnit ③). 실제 공급자를 부르지 않는다(R-F1).
 */
public final class LunaResponsesStub implements AutoCloseable {

    /** 대역 경로. */
    public static final String PATH = "/v1/responses";

    /** 공급자 구조화 출력이 받는 스키마 키워드(BR4.5 허용 목록). */
    public static final Set<String> SUPPORTED_KEYWORDS = Set.of(
            "type", "properties", "required", "additionalProperties", "items", "enum", "const", "anyOf", "$defs", "$ref",
            "description");

    private static final JsonCodec JSON = JsonCodec.shared();
    private static final int CHUNK_BYTES = 64 * 1024;

    private final HttpServer server;
    private final ExecutorService executor;
    private final List<Reply> script = new ArrayList<>();
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final CountDownLatch closed = new CountDownLatch(1);
    private volatile boolean requestCheck = true;
    private int position;

    private LunaResponsesStub() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    /** 대역을 띄운다. 대본이 없으면 정상 빈 객체 응답({@code {}})을 준다. */
    public static LunaResponsesStub start() {
        try {
            return new LunaResponsesStub();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 호출부 설정 {@code mujoong.luna.base-url}로 쓸 주소(예: {@code http://127.0.0.1:41234/v1}). */
    public URI baseUrl() {
        return URI.create("http://" + server.getAddress().getAddress().getHostAddress() + ":" + server.getAddress().getPort()
                + "/v1");
    }

    /** 응답 대본을 바꾼다(순서대로 쓰고, 다 쓰면 마지막 응답을 되풀이). */
    public synchronized LunaResponsesStub respond(Reply... replies) {
        if (replies.length == 0) {
            throw new IllegalArgumentException("응답이 하나 이상 있어야 한다");
        }
        script.clear();
        script.addAll(List.of(replies));
        position = 0;
        return this;
    }

    /** 요청 검사 모드를 끈다(검사 자체를 시험할 때만). */
    public LunaResponsesStub withoutRequestCheck() {
        this.requestCheck = false;
        return this;
    }

    /** 받은 요청 전부(도착 순서). */
    public List<RecordedRequest> requests() {
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

    // ------------------------------------------------------------------ 처리

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
                    violations.addAll(RequestCheck.violations(body));
                }
                reply = violations.isEmpty() ? next() : Reply.status(400);
            }
            requests.add(new RecordedRequest(path, Collections.unmodifiableMap(headers), body, List.copyOf(violations),
                    reply.status()));
            if (!reply.delay().isZero() && closed.await(reply.delay().toNanos(), TimeUnit.NANOSECONDS)) {
                return;
            }
            write(exchange, reply);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // 호출부가 상한 · 시한으로 연결을 끊으면 쓰기가 실패한다(대본의 의도된 결과). 시험 대역이라 조용히 끝낸다.
        }
    }

    private synchronized Reply next() {
        if (script.isEmpty()) {
            return Reply.ok("gpt-6-luna", "{}");
        }
        Reply reply = script.get(Math.min(position, script.size() - 1));
        position++;
        return reply;
    }

    private void write(HttpExchange exchange, Reply reply) throws IOException, InterruptedException {
        reply.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        if (reply.paddingBytes() > 0) {
            exchange.sendResponseHeaders(reply.status(), 0);
            try (OutputStream out = exchange.getResponseBody()) {
                byte[] chunk = new byte[CHUNK_BYTES];
                java.util.Arrays.fill(chunk, (byte) ' ');
                for (int written = 0; written < reply.paddingBytes(); written += CHUNK_BYTES) {
                    out.write(chunk, 0, Math.min(CHUNK_BYTES, reply.paddingBytes() - written));
                }
                out.write(bytes);
            }
            return;
        }
        if (!reply.bodyDrip().isZero()) {
            exchange.sendResponseHeaders(reply.status(), 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (byte b : bytes) {
                    out.write(b);
                    out.flush();
                    if (closed.await(reply.bodyDrip().toNanos(), TimeUnit.NANOSECONDS)) {
                        return;
                    }
                }
            }
            return;
        }
        exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    // ------------------------------------------------------------------ 요청 검사

    /** 요청 검사 모드의 규칙(공급자가 거절하는 요청 모양). */
    static final class RequestCheck {

        private RequestCheck() {
        }

        @SuppressWarnings("unchecked")
        static List<String> violations(String body) {
            List<String> problems = new ArrayList<>();
            Map<String, Object> request;
            try {
                request = JSON.read(body, Map.class);
            } catch (RuntimeException e) {
                return List.of("body: JSON이 아니다");
            }
            if (request == null) {
                return List.of("body: 비었다");
            }
            if (request.containsKey("temperature")) {
                problems.add("temperature: 추론 모델이 받지 않는 매개변수");
            }
            if (!Boolean.FALSE.equals(request.get("store"))) {
                problems.add("store: false가 아니다");
            }
            for (String required : List.of("model", "instructions", "input")) {
                if (!(request.get(required) instanceof String text) || text.isBlank()) {
                    problems.add(required + ": 문자열이 아니다");
                }
            }
            if (request.get("text") instanceof Map<?, ?> text && text.get("format") instanceof Map<?, ?> format
                    && "json_schema".equals(format.get("type"))) {
                Object schema = format.get("schema");
                if (!(schema instanceof Map<?, ?>)) {
                    problems.add("text.format.schema: 객체가 아니다");
                } else {
                    walkSchema((Map<String, Object>) schema, "", problems);
                    if (Boolean.TRUE.equals(format.get("strict"))) {
                        strictShape((Map<String, Object>) schema, "", problems);
                    }
                }
            }
            return problems;
        }

        @SuppressWarnings("unchecked")
        private static void walkSchema(Map<String, Object> node, String pointer, List<String> problems) {
            for (Map.Entry<String, Object> entry : node.entrySet()) {
                String key = entry.getKey();
                if (!SUPPORTED_KEYWORDS.contains(key)) {
                    problems.add(pointer + "/" + key + ": 비지원 키워드");
                    continue;
                }
                Object value = entry.getValue();
                if ((key.equals("properties") || key.equals("$defs")) && value instanceof Map<?, ?> named) {
                    named.forEach((name, child) -> {
                        if (child instanceof Map<?, ?> childNode) {
                            walkSchema((Map<String, Object>) childNode, pointer + "/" + key + "/" + name, problems);
                        }
                    });
                } else if (key.equals("items") && value instanceof Map<?, ?> items) {
                    walkSchema((Map<String, Object>) items, pointer + "/items", problems);
                } else if (key.equals("anyOf") && value instanceof List<?> options) {
                    for (int i = 0; i < options.size(); i++) {
                        if (options.get(i) instanceof Map<?, ?> option) {
                            walkSchema((Map<String, Object>) option, pointer + "/anyOf/" + i, problems);
                        }
                    }
                }
            }
        }

        @SuppressWarnings("unchecked")
        private static void strictShape(Map<String, Object> node, String pointer, List<String> problems) {
            if (node.get("properties") instanceof Map<?, ?> properties) {
                if (!Boolean.FALSE.equals(node.get("additionalProperties"))) {
                    problems.add(pointer + ": strict인데 additionalProperties가 false가 아니다");
                }
                List<?> required = node.get("required") instanceof List<?> list ? list : List.of();
                if (!required.containsAll(properties.keySet())) {
                    problems.add(pointer + ": strict인데 required 밖 속성이 있다");
                }
                properties.forEach((name, child) -> {
                    if (child instanceof Map<?, ?> childNode) {
                        strictShape((Map<String, Object>) childNode, pointer + "/properties/" + name, problems);
                    }
                });
            }
            if (node.get("$defs") instanceof Map<?, ?> defs) {
                defs.forEach((name, child) -> {
                    if (child instanceof Map<?, ?> childNode) {
                        strictShape((Map<String, Object>) childNode, pointer + "/$defs/" + name, problems);
                    }
                });
            }
            if (node.get("items") instanceof Map<?, ?> items) {
                strictShape((Map<String, Object>) items, pointer + "/items", problems);
            }
            if (node.get("anyOf") instanceof List<?> options) {
                for (Object option : options) {
                    if (option instanceof Map<?, ?> optionNode) {
                        strictShape((Map<String, Object>) optionNode, pointer + "/anyOf", problems);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ 기록 · 응답

    /**
     * 받은 요청 하나.
     *
     * @param violations 요청 검사 위반(비었으면 대본 응답을 썼다)
     * @param status 대역이 답한 상태 코드
     */
    public record RecordedRequest(String path, Map<String, List<String>> headers, String body, List<String> violations,
            int status) {

        /** 본문을 JSON 객체로 읽는다. */
        @SuppressWarnings("unchecked")
        public Map<String, Object> json() {
            return JSON.read(body, Map.class);
        }

        /** 헤더 값 하나(없으면 null). */
        public String header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }

    /**
     * 대본 응답 하나.
     *
     * @param delay 응답 머리를 보내기 전 대기
     * @param bodyDrip 본문을 1바이트씩 흘릴 때 바이트 사이 대기(0이면 한 번에)
     * @param paddingBytes 본문 앞에 붙일 공백 바이트 수(큰 본문 대본)
     */
    public record Reply(int status, String body, Map<String, String> headers, Duration delay, Duration bodyDrip,
            int paddingBytes) {

        public Reply {
            Objects.requireNonNull(body, "body");
            headers = Map.copyOf(headers);
            Objects.requireNonNull(delay, "delay");
            Objects.requireNonNull(bodyDrip, "bodyDrip");
        }

        /** 완료(completed) 응답: 모델 이름과 출력 텍스트 하나. */
        public static Reply ok(String model, String outputText) {
            return json(200, response(model, "completed", null, List.of(outputText(outputText))));
        }

        /** 미완료(출력 한도 도달) 응답. */
        public static Reply incomplete(String model) {
            return json(200, response(model, "incomplete", Map.of("reason", "max_output_tokens"),
                    List.of(outputText("{\"partial\":"))));
        }

        /** 완료지만 출력 텍스트가 빈 응답. */
        public static Reply emptyOutput(String model) {
            return json(200, response(model, "completed", null, List.of(outputText(""))));
        }

        /** 거절(refusal) 응답. */
        public static Reply refusal(String model) {
            Map<String, Object> refusal = new LinkedHashMap<>();
            refusal.put("type", "refusal");
            refusal.put("refusal", "대역 거절");
            return json(200, response(model, "completed", null, List.of(refusal)));
        }

        /** model 값이 없는 완료 응답. */
        public static Reply noModel(String outputText) {
            return json(200, response(null, "completed", null, List.of(outputText(outputText))));
        }

        /** 상태 코드만 있는 오류 응답(본문은 공급자 오류 모양). */
        public static Reply status(int status) {
            return json(status, "{\"error\":{\"type\":\"stub_error\",\"code\":\"" + status + "\"}}");
        }

        /** 429와 {@code Retry-After}(초). */
        public static Reply rateLimited(long retryAfterSeconds) {
            return status(429).withHeader("Retry-After", Long.toString(retryAfterSeconds));
        }

        /** 다른 호스트로 보내는 302. */
        public static Reply redirect(String location) {
            return status(302).withHeader("Location", location);
        }

        /** 본문 앞에 공백 {@code bytes}바이트를 붙인 200 완료 응답(본문 크기 상한 대본). */
        public static Reply oversized(String model, int bytes) {
            Reply ok = ok(model, "{}");
            return new Reply(ok.status, ok.body, ok.headers, ok.delay, ok.bodyDrip, bytes);
        }

        /** 임의 본문 응답. */
        public static Reply json(int status, String body) {
            return new Reply(status, body, Map.of(), Duration.ZERO, Duration.ZERO, 0);
        }

        /** 응답 머리를 {@code delayBy} 뒤에 보낸다. */
        public Reply delayed(Duration delayBy) {
            return new Reply(status, body, headers, delayBy, bodyDrip, paddingBytes);
        }

        /** 본문을 1바이트씩 {@code perByte} 간격으로 흘린다(느린 본문 대본). */
        public Reply dripping(Duration perByte) {
            return new Reply(status, body, headers, delay, perByte, paddingBytes);
        }

        /** 응답 머리를 더한다. */
        public Reply withHeader(String name, String value) {
            Map<String, String> merged = new LinkedHashMap<>(headers);
            merged.put(name, value);
            return new Reply(status, body, merged, delay, bodyDrip, paddingBytes);
        }

        private static Map<String, Object> outputText(String text) {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("type", "output_text");
            content.put("text", text);
            return content;
        }

        private static String response(String model, String status, Map<String, Object> incomplete,
                List<Map<String, Object>> content) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("type", "message");
            message.put("role", "assistant");
            message.put("content", content);
            Map<String, Object> reasoning = new LinkedHashMap<>();
            reasoning.put("type", "reasoning");
            reasoning.put("summary", List.of());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("id", "resp_stub");
            response.put("object", "response");
            response.put("status", status);
            if (incomplete != null) {
                response.put("incomplete_details", incomplete);
            }
            if (model != null) {
                response.put("model", model);
            }
            response.put("output", List.of(reasoning, message));
            return JSON.write(response);
        }
    }
}
