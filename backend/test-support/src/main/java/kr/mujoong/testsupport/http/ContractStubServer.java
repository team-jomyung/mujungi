package kr.mujoong.testsupport.http;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.trace.TraceIds;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 상대 앱 계약 대역 서버 (U1 3.5 Step 28 · RD-23 · J129 · 시험 전략 '앱 사이 HTTP' 경계).
 *
 * <p>JDK {@link HttpServer}로 루프백 무작위 포트에 뜬다. 경로마다
 *
 * <ul>
 *   <li>요청 본문을 {@code contracts/schemas}의 스키마(JSON Schema 2020-12, {@code https://mujoong.invalid/contracts/schemas/} 아래 $id를
 *       저장소 파일로 바꿔 읽음)로 검사한다. 위반이면 응답 스크립트를 쓰지 않고 400 VALIDATION_FAILED 봉투로 답하고 위반 목록을 기록한다.
 *   <li>응답 스크립트(상태 · 본문 · 지연)를 순서대로 쓴다. 다 쓰면 마지막 응답을 되풀이한다.
 *   <li>받은 요청(메서드 · 경로 · 질의 · 헤더 · 본문 · 위반 · 답한 상태)을 기록한다.
 * </ul>
 *
 * <p>{@link #requireInternalToken}을 걸면 {@code X-Internal-Token}이 정확히 한 개이고 같을 때만 통과하고 아니면 401 UNAUTHENTICATED다. 지연
 * 응답은 서버를 닫으면 바로 풀린다. 시험 전용이며 운영 코드는 이 모듈에 의존하지 않는다(ArchUnit ③).
 */
public final class ContractStubServer implements AutoCloseable {

    /** 스키마 $id 접두. */
    public static final String SCHEMA_ID_PREFIX = "https://mujoong.invalid/contracts/schemas/";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String DRAFT_2020_12 = "https://json-schema.org/draft/2020-12/schema";

    private final HttpServer server;
    private final ExecutorService executor;
    private final SchemaRegistry registry;
    private final List<Route> routes = new CopyOnWriteArrayList<>();
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final CountDownLatch closed = new CountDownLatch(1);
    private final Object arrivals = new Object();
    private volatile String internalToken;

    private ContractStubServer(Path schemasDir) throws IOException {
        this.registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, builder -> builder
                .schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
                .schemas(iri -> iri.startsWith(SCHEMA_ID_PREFIX)
                        ? readSchema(schemasDir, iri.substring(SCHEMA_ID_PREFIX.length()))
                        : null));
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    /** 저장소의 {@code contracts/schemas}로 검사하는 대역을 띄운다. */
    public static ContractStubServer start() {
        try {
            return new ContractStubServer(locateSchemas());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 대역 기준 주소(예: {@code http://127.0.0.1:41234}). */
    public URI baseUrl() {
        return URI.create("http://" + server.getAddress().getAddress().getHostAddress() + ":" + server.getAddress().getPort());
    }

    /** 내부 토큰 확인을 켠다(값은 시험이 실행마다 만든다). */
    public ContractStubServer requireInternalToken(String token) {
        this.internalToken = Objects.requireNonNull(token, "token");
        return this;
    }

    /**
     * 경로를 등록한다.
     *
     * @param pathTemplate {@code {이름}} 자리표시가 있는 경로(예: {@code /internal/sessions/{sessionId}/end})
     */
    public Route route(String method, String pathTemplate) {
        Route route = new Route(method.toUpperCase(Locale.ROOT), pathTemplate);
        routes.removeIf(existing -> existing.method.equals(route.method) && existing.template.equals(route.template));
        routes.add(route);
        return route;
    }

    /** 받은 요청 전부(도착 순서). */
    public List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    /** 이 경로로 받은 요청(도착 순서). */
    public List<RecordedRequest> requests(String method, String pathTemplate) {
        Pattern pattern = compile(pathTemplate);
        String upper = method.toUpperCase(Locale.ROOT);
        return requests.stream().filter(r -> r.method().equals(upper) && pattern.matcher(r.path()).matches()).toList();
    }

    /**
     * 이 경로 요청이 {@code count}건 이상 올 때까지 기다린다(상한 {@code timeout}).
     *
     * @return 받은 요청(제한 안에 다 오지 않으면 그때까지 받은 것)
     */
    public List<RecordedRequest> awaitRequests(String method, String pathTemplate, int count, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (arrivals) {
            while (true) {
                List<RecordedRequest> matched = requests(method, pathTemplate);
                long remaining = deadline - System.nanoTime();
                if (matched.size() >= count || remaining <= 0) {
                    return matched;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(arrivals, remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return requests(method, pathTemplate);
                }
            }
        }
    }

    /** 기록을 비운다(스크립트는 둔다). */
    public void clearRequests() {
        requests.clear();
    }

    /** 스키마 위반이 있었던 요청. */
    public List<RecordedRequest> violations() {
        return requests.stream().filter(r -> !r.schemaErrors().isEmpty()).toList();
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
            URI uri = exchange.getRequestURI();
            String path = uri.getRawPath();
            String body = readBody(exchange.getRequestBody());
            Map<String, List<String>> headers = new TreeMap<>();
            exchange.getRequestHeaders().forEach((name, values) ->
                    headers.put(name.toLowerCase(Locale.ROOT), List.copyOf(values)));
            String traceId = TraceIds.orNew(first(headers.get("x-trace-id")));

            StubResponse response;
            List<String> schemaErrors = List.of();
            Route route = find(method, path);
            if (internalToken != null && !tokenMatches(headers.get("x-internal-token"))) {
                response = StubResponse.error(401, ErrorCode.UNAUTHENTICATED);
            } else if (route == null) {
                response = StubResponse.error(404, ErrorCode.NOT_FOUND);
            } else {
                schemaErrors = route.validate(body);
                response = schemaErrors.isEmpty() ? route.next() : StubResponse.error(400, ErrorCode.VALIDATION_FAILED);
            }
            RecordedRequest recorded = new RecordedRequest(method, path, uri.getRawQuery(), Collections.unmodifiableMap(headers),
                    body, schemaErrors, response.status());
            requests.add(recorded);
            synchronized (arrivals) {
                arrivals.notifyAll();
            }
            if (!response.delay().isZero() && closed.await(response.delay().toMillis(), TimeUnit.MILLISECONDS)) {
                return;
            }
            byte[] bytes = response.bodyFor(traceId).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set(TraceIds.HEADER, traceId);
            exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean tokenMatches(List<String> presented) {
        return presented != null && presented.size() == 1 && internalToken.equals(presented.get(0));
    }

    private Route find(String method, String path) {
        for (Route route : routes) {
            if (route.method.equals(method) && route.pattern.matcher(path).matches()) {
                return route;
            }
        }
        return null;
    }

    private static String first(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static String readBody(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static Pattern compile(String template) {
        StringBuilder regex = new StringBuilder("^");
        for (String part : template.split("(?=\\{)|(?<=\\})")) {
            if (part.startsWith("{") && part.endsWith("}")) {
                regex.append("[^/]+");
            } else {
                regex.append(Pattern.quote(part));
            }
        }
        return Pattern.compile(regex.append('$').toString());
    }

    private static Path locateSchemas() {
        String property = System.getProperty("mujoong.repo.root");
        if (property != null && !property.isBlank()) {
            return Path.of(property).resolve("contracts/schemas");
        }
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("contracts/schemas"))) {
                return dir.resolve("contracts/schemas");
            }
        }
        throw new IllegalStateException("contracts/schemas를 찾지 못했다(mujoong.repo.root 미설정)");
    }

    private static String readSchema(Path schemasDir, String fileName) {
        String name = fileName.contains("#") ? fileName.substring(0, fileName.indexOf('#')) : fileName;
        Path file = schemasDir.resolve(name);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ 경로 · 응답

    /** 한 경로의 검사 스키마와 응답 스크립트. */
    public final class Route {

        private final String method;
        private final String template;
        private final Pattern pattern;
        private final List<StubResponse> script = new ArrayList<>();
        private Schema requestSchema;
        private int position;

        private Route(String method, String template) {
            this.method = method;
            this.template = template;
            this.pattern = compile(template);
        }

        /** 요청 본문 스키마(파일 이름, 예: {@code internal-session-start-request.schema.json}). */
        public Route requestSchema(String schemaFile) {
            ObjectNode wrapper = JSON.createObjectNode();
            wrapper.put("$schema", DRAFT_2020_12);
            wrapper.put("$ref", SCHEMA_ID_PREFIX + schemaFile);
            this.requestSchema = registry.getSchema(wrapper);
            return this;
        }

        /** 응답 스크립트를 바꾼다(순서대로 쓰고, 다 쓰면 마지막 응답을 되풀이). */
        public synchronized Route respond(StubResponse... responses) {
            if (responses.length == 0) {
                throw new IllegalArgumentException("응답이 하나 이상 있어야 한다");
            }
            script.clear();
            script.addAll(List.of(responses));
            position = 0;
            return this;
        }

        private synchronized StubResponse next() {
            if (script.isEmpty()) {
                return StubResponse.error(500, ErrorCode.INTERNAL_ERROR);
            }
            StubResponse response = script.get(Math.min(position, script.size() - 1));
            position++;
            return response;
        }

        private List<String> validate(String body) {
            if (requestSchema == null) {
                return List.of();
            }
            JsonNode node;
            try {
                node = JSON.readTree(body);
            } catch (JacksonException e) {
                return List.of("body: JSON이 아니다");
            }
            if (node == null || node.isMissingNode()) {
                return List.of("body: 비었다");
            }
            return requestSchema.validate(node).stream().map(com.networknt.schema.Error::toString).toList();
        }
    }

    /** 스크립트 응답 하나. */
    public record StubResponse(int status, String body, Duration delay, ErrorCode errorCode) {

        public StubResponse {
            Objects.requireNonNull(delay, "delay");
        }

        /** JSON 본문 응답. */
        public static StubResponse json(int status, String body) {
            return new StubResponse(status, body, Duration.ZERO, null);
        }

        /** 계약 오류 봉투 응답({code, message, traceId}, message는 코드별 고정 문장). */
        public static StubResponse error(int status, ErrorCode code) {
            return new StubResponse(status, null, Duration.ZERO, code);
        }

        /** 같은 응답을 {@code delay} 뒤에 보낸다(호출 쪽 시간 초과 재현). */
        public StubResponse delayed(Duration delayBy) {
            return new StubResponse(status, body, delayBy, errorCode);
        }

        String bodyFor(String traceId) {
            if (errorCode == null) {
                return body == null ? "" : body;
            }
            ObjectNode node = JSON.createObjectNode();
            node.put("code", errorCode.name());
            node.put("message", errorCode.message());
            node.put("traceId", traceId);
            return node.toString();
        }
    }

    /** 받은 요청 하나. 헤더 이름은 소문자다. */
    public record RecordedRequest(
            String method,
            String path,
            String query,
            Map<String, List<String>> headers,
            String body,
            List<String> schemaErrors,
            int respondedStatus) {

        /** 헤더 첫 값(없으면 null). */
        public String header(String name) {
            return first(headers.get(name.toLowerCase(Locale.ROOT)));
        }

        /** 본문 JSON. */
        public JsonNode json() {
            return JSON.readTree(body);
        }

        /** 내부 토큰 값은 문자열 표현에 내지 않는다. */
        @Override
        public String toString() {
            return "RecordedRequest[" + method + " " + path + ", status=" + respondedStatus + ", schemaErrors="
                    + schemaErrors.size() + "]";
        }
    }
}
