package kr.mujoong.core.knowledge;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.MujoongClock;

/** BR2·BR5: Neo4j 5 명시적 HTTP 트랜잭션. 재전송/자동 커밋은 사용하지 않는다. */
public final class Neo4jChildKnowledgeGraph implements ChildKnowledgeGraphPort {
    private static final String BASE_VERSION = "MERGE (v:U15GraphVersion {version:$version}) ON CREATE SET v.digest=$digest RETURN v.digest";
    // 새 카탈로그에 없는 기본 씨앗만 지운다. 남는 씨앗은 LOAD_BASE의 MERGE가 내용만 바꾸므로 관찰 · 참고 기록이 재초기화에도 유지된다.
    private static final String REPLACE_BASE = "MATCH (v:U15GraphVersion {version:$version,digest:$digest}) "
            + "WITH v MATCH (s:U15Seed {childId:$child,origin:'POC'}) WHERE NOT s.seedId IN [item IN $seeds | item.id] DETACH DELETE s";
    private static final String LOAD_BASE = "MATCH (v:U15GraphVersion {version:$version,digest:$digest}) "
            + "WITH v UNWIND $seeds AS item MERGE (s:U15Seed {childId:$child,seedId:item.id}) "
            + "SET s.origin=item.origin,s.enabled=item.enabled,s.payload=item.payload";
    private final URI transactions;
    private final String authorization;
    private final Duration budget;
    private final Duration initializationBudget;
    private final MujoongClock clock;
    private final HttpClient client;
    private final JsonCodec json = JsonCodec.shared();
    private volatile boolean schemaReady;

    @Override public synchronized void prepareSchema() {
        if (schemaReady) return;
        var deadline = new InitializationDeadline(clock, clock.now().plus(initializationBudget));
        String tx = null;
        try {
            tx = begin(deadline.remaining());
            initializationStatement(tx, "CREATE CONSTRAINT u15_version_unique IF NOT EXISTS FOR (v:U15GraphVersion) REQUIRE v.version IS UNIQUE", Map.of(), deadline);
            initializationStatement(tx, "CREATE CONSTRAINT u15_child_seed_unique IF NOT EXISTS FOR (s:U15Seed) REQUIRE (s.childId,s.seedId) IS UNIQUE", Map.of(), deadline);
            request("POST", uri(tx, "/commit"), List.of(), false, deadline.remaining());
            tx = null;
            tx = begin(deadline.remaining());
            prepareReplacementPlan(tx, deadline);
            request("DELETE", uri(tx, ""), List.of(), true, deadline.remaining());
            tx = null;
            deadline.remaining();
            schemaReady = true;
        } catch (RuntimeException failure) {
            if (tx != null) cleanUpInitialization(tx, failure);
            throw failure;
        }
    }

    private void prepareReplacementPlan(String tx, InitializationDeadline deadline) {
        // 운영 시작 경로에서 계획만 준비한다. EXPLAIN은 합성 파라미터를 실행하거나 사용자 데이터를 쓰지 않는다.
        // 준비 실패는 그대로 전파하며, 성공 전에 schemaReady를 열거나 자동 재시도하지 않는다.
        var parameters = Map.of("version", "__schema_preparation__", "digest", "0".repeat(64),
                "child", "00000000-0000-0000-0000-000000000000", "seeds",
                List.of(Map.of("id", "__schema_preparation__", "origin", "POC", "enabled", true, "payload", "{}")));
        initializationStatement(tx, "EXPLAIN " + BASE_VERSION, parameters, deadline);
        initializationStatement(tx, "EXPLAIN " + REPLACE_BASE, parameters, deadline);
        initializationStatement(tx, "EXPLAIN " + LOAD_BASE, parameters, deadline);
    }

    private void initializationStatement(String tx, String cypher, Map<String, ?> parameters,
            InitializationDeadline deadline) {
        request("POST", uri(tx, ""), List.of(Map.of("statement", cypher, "parameters", parameters)),
                false, deadline.remaining());
    }

    private void cleanUpInitialization(String tx, RuntimeException failure) {
        // 중단 플래그는 유한 정리 요청 동안만 해제하고 반드시 복구한다. 정리 실패가 원래 시작 실패를 덮지 않는다.
        boolean interrupted = Thread.interrupted();
        var cleanup = new InitializationDeadline(clock, clock.now().plus(budget));
        try {
            request("DELETE", uri(tx, ""), List.of(), true, cleanup.remaining());
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private record InitializationDeadline(MujoongClock source, Instant expiresAt) {
        private Duration remaining() {
            Duration remaining = Duration.between(source.now(), expiresAt);
            if (remaining.isZero() || remaining.isNegative()) {
                throw new IllegalStateException("Graph initialization deadline expired");
            }
            return remaining;
        }
    }

    public Neo4jChildKnowledgeGraph(URI endpoint, String auth, Duration budget,
            Duration initializationBudget, MujoongClock clock) {
        if (!List.of("http", "https").contains(endpoint.getScheme()) || endpoint.getUserInfo() != null
                || endpoint.getHost() == null || auth == null || !auth.contains("/") || budget == null
                || budget.isZero() || budget.isNegative() || initializationBudget == null
                || initializationBudget.isZero() || initializationBudget.isNegative() || clock == null) {
            throw new IllegalArgumentException("Invalid graph connection");
        }
        transactions = endpoint.resolve("/db/neo4j/tx");
        authorization = "Basic " + Base64.getEncoder().encodeToString(auth.replaceFirst("/", ":").getBytes(StandardCharsets.UTF_8));
        this.budget = budget;
        this.initializationBudget = initializationBudget;
        this.clock = clock;
        client = HttpClient.newBuilder().connectTimeout(budget).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public String begin() {
        return begin(budget);
    }

    private String begin(Duration timeout) {
        Map<?, ?> reply = request("POST", transactions, List.of(), false, timeout);
        String commit = String.valueOf(reply.get("commit"));
        URI uri = URI.create(commit);
        String[] parts = uri.getPath().split("/");
        String id = parts[parts.length - 2];
        validateHandle(id);
        return id;
    }

    @Override public List<KnowledgeSeed> seeds(UUID child) {
        String tx = begin();
        try {
            var rows = statement(tx, "MATCH (s:U15Seed {childId:$child}) WHERE s.enabled=true RETURN s.payload ORDER BY s.seedId",
                    Map.of("child", child.toString()));
            return rows.stream().map(row -> json.read(String.valueOf(row.get(0)), KnowledgeSeed.class)).toList();
        } finally {
            if (!terminate(tx)) throw new IllegalStateException("Graph read transaction unresolved");
        }
    }

    @Override public List<SeedProfile> profiles(UUID child) {
        String tx = begin();
        try {
            var rows = statement(tx, "MATCH (s:U15Seed {childId:$child}) WHERE s.enabled=true RETURN s.payload,"
                    + "coalesce(s.observedCount,0),s.lastObservedAt,s.lastReferencedAt ORDER BY s.seedId",
                    Map.of("child", child.toString()));
            return rows.stream().map(row -> new SeedProfile(json.read(String.valueOf(row.get(0)), KnowledgeSeed.class),
                    ((Number) row.get(1)).intValue(), instant(row.get(2)), instant(row.get(3)))).toList();
        } finally {
            if (!terminate(tx)) throw new IllegalStateException("Graph read transaction unresolved");
        }
    }

    private static Instant instant(Object value) { return value == null ? null : Instant.parse(String.valueOf(value)); }

    @Override public void writeObservations(String tx, UUID child, Map<String, SeedObservation> observations) {
        var counts = new java.util.HashMap<String, Object>();
        var last = new java.util.HashMap<String, Object>();
        observations.forEach((seedId, observation) -> {
            counts.put(seedId, observation.count());
            last.put(seedId, observation.lastObservedAt().toString());
        });
        // MATCH만 쓰므로 삭제와 겹쳐도 지워진 씨앗을 다시 만들지 않는다. 목록에 없는 씨앗은 0회 · 시각 없음으로 돌아간다.
        statement(tx, "MATCH (s:U15Seed {childId:$child}) SET s.observedCount=coalesce($counts[s.seedId],0),"
                + "s.lastObservedAt=$last[s.seedId]", Map.of("child", child.toString(), "counts", counts, "last", last));
    }

    @Override public void markReferenced(String tx, UUID child, String seedId, Instant referencedAt) {
        statement(tx, "MATCH (s:U15Seed {childId:$child,seedId:$id}) SET s.lastReferencedAt=$at",
                Map.of("child", child.toString(), "id", seedId, "at", referencedAt.toString()));
    }

    @Override public void replaceBase(String tx, UUID child, SeedCatalog catalog) {
        var seeds = catalog.seeds().stream().map(seed -> Map.of("id", seed.seedId(),
                "origin", seed.origin().name(), "enabled", seed.enabled(), "payload", json.write(seed))).toList();
        var parameters = Map.of("version", catalog.version(), "digest", catalog.digest(), "child", child.toString(), "seeds", seeds);
        // 기존 2초 예산 안에서 단순한 세 문장을 한 HTTP 요청으로 실행한다. 명시적 트랜잭션은 유지한다.
        // 두 쓰기 모두 digest를 확인하므로 충돌 뒤 호출자가 커밋해도 기존 씨앗은 바뀌지 않는다.
        var rows = rows(request("POST", uri(tx, ""), List.of(
                Map.of("statement", BASE_VERSION, "parameters", parameters),
                Map.of("statement", REPLACE_BASE, "parameters", parameters),
                Map.of("statement", LOAD_BASE, "parameters", parameters)), false));
        if (rows.isEmpty() || !catalog.digest().equals(rows.get(0).get(0))) {
            throw new IllegalArgumentException("Catalog version conflict");
        }
    }

    @Override public void writeGeneratedSeed(String tx, UUID child, KnowledgeSeed seed) {
        if (seed.origin() != KnowledgeSeed.Origin.GENERATED) throw new IllegalArgumentException("Expected generated seed");
        write(tx, child, seed);
    }

    private void write(String tx, UUID child, KnowledgeSeed seed) {
        statement(tx, "MERGE (s:U15Seed {childId:$child,seedId:$id}) SET s.origin=$origin,s.enabled=$enabled,s.payload=$payload",
                Map.of("child", child.toString(), "id", seed.seedId(), "origin", seed.origin().name(),
                        "enabled", seed.enabled(), "payload", json.write(seed)));
    }

    @Override public void deleteSeeds(String tx, UUID child, List<String> ids) {
        statement(tx, "MATCH (s:U15Seed {childId:$child}) WHERE s.seedId IN $ids DETACH DELETE s",
                Map.of("child", child.toString(), "ids", ids));
    }

    @Override public void deleteChild(String tx, UUID child) {
        statement(tx, "MATCH (s:U15Seed {childId:$child}) DETACH DELETE s", Map.of("child", child.toString()));
    }

    @Override public void commit(String tx) { request("POST", uri(tx, "/commit"), List.of(), false); }

    @Override public boolean terminate(String tx) {
        request("DELETE", uri(tx, ""), List.of(), true);
        return true;
    }

    private List<List<Object>> statement(String tx, String cypher, Map<String, ?> parameters) {
        return rows(request("POST", uri(tx, ""), List.of(Map.of("statement", cypher, "parameters", parameters)), false));
    }

    @SuppressWarnings("unchecked")
    private List<List<Object>> rows(Map<?, ?> reply) {
        List<?> results = (List<?>) reply.get("results");
        if (results == null || results.isEmpty()) return List.of();
        List<?> data = (List<?>) ((Map<?, ?>) results.get(0)).get("data");
        return data.stream().map(row -> (List<Object>) ((Map<?, ?>) row).get("row")).toList();
    }

    private URI uri(String tx, String suffix) {
        validateHandle(tx);
        return URI.create(transactions.toString() + "/" + tx + suffix);
    }

    private static void validateHandle(String tx) {
        if (tx == null || !tx.matches("[0-9]+")) throw new IllegalArgumentException("Invalid graph transaction");
    }

    private Map<?, ?> request(String method, URI uri, List<?> statements, boolean allowClosed) {
        return request(method, uri, statements, allowClosed, budget);
    }

    private Map<?, ?> request(String method, URI uri, List<?> statements, boolean allowClosed, Duration timeout) {
        var builder = HttpRequest.newBuilder(uri).timeout(timeout).header("Authorization", authorization)
                .header("Content-Type", "application/json");
        builder.method(method, method.equals("DELETE") ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.write(Map.of("statements", statements))));
        try {
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Map<?, ?> body = json.read(response.body(), Map.class);
            List<?> errors = (List<?>) body.get("errors");
            if (errors != null && !errors.isEmpty()) {
                boolean closed = errors.stream().allMatch(error -> "Neo.ClientError.Transaction.TransactionNotFound"
                        .equals(((Map<?, ?>) error).get("code")));
                if (allowClosed && closed) return body;
                throw new IllegalStateException("Graph operation rejected");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("Graph unavailable");
            return body;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Graph operation interrupted");
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Graph operation unresolved");
        }
    }
}
