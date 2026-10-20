package kr.mujoong.core.knowledge;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;

/** BR2·BR5: Neo4j 5 명시적 HTTP 트랜잭션. 재전송/자동 커밋은 사용하지 않는다. */
public final class Neo4jChildKnowledgeGraph implements ChildKnowledgeGraphPort {
    private final URI transactions;
    private final String authorization;
    private final Duration budget;
    private final HttpClient client;
    private final JsonCodec json = JsonCodec.shared();
    private volatile boolean schemaReady;

    @Override public synchronized void prepareSchema() {
        if (schemaReady) return;
        String tx = begin();
        try {
            statement(tx, "CREATE CONSTRAINT u15_version_unique IF NOT EXISTS FOR (v:U15GraphVersion) REQUIRE v.version IS UNIQUE", Map.of());
            statement(tx, "CREATE CONSTRAINT u15_child_seed_unique IF NOT EXISTS FOR (s:U15Seed) REQUIRE (s.childId,s.seedId) IS UNIQUE", Map.of());
            commit(tx);
            schemaReady = true;
        } finally {
            terminate(tx);
        }
    }

    public Neo4jChildKnowledgeGraph(URI endpoint, String auth, Duration budget) {
        if (!List.of("http", "https").contains(endpoint.getScheme()) || endpoint.getUserInfo() != null
                || endpoint.getHost() == null || auth == null || !auth.contains("/") || budget.isZero() || budget.isNegative()) {
            throw new IllegalArgumentException("Invalid graph connection");
        }
        transactions = endpoint.resolve("/db/neo4j/tx");
        authorization = "Basic " + Base64.getEncoder().encodeToString(auth.replaceFirst("/", ":").getBytes(StandardCharsets.UTF_8));
        this.budget = budget;
        client = HttpClient.newBuilder().connectTimeout(budget).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public String begin() {
        Map<?, ?> reply = request("POST", transactions, List.of(), false);
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

    @Override public void replaceBase(String tx, UUID child, SeedCatalog catalog) {
        var rows = statement(tx, "MERGE (v:U15GraphVersion {version:$version}) ON CREATE SET v.digest=$digest RETURN v.digest",
                Map.of("version", catalog.version(), "digest", catalog.digest()));
        if (rows.isEmpty() || !catalog.digest().equals(rows.get(0).get(0))) {
            throw new IllegalArgumentException("Catalog version conflict");
        }
        statement(tx, "MATCH (s:U15Seed {childId:$child,origin:'POC'}) DETACH DELETE s", Map.of("child", child.toString()));
        // One round trip for the base catalog keeps cold initialization inside its existing budget.
        var seeds = catalog.seeds().stream().map(seed -> Map.of("id", seed.seedId(),
                "origin", seed.origin().name(), "enabled", seed.enabled(), "payload", json.write(seed))).toList();
        statement(tx, "UNWIND $seeds AS item MERGE (s:U15Seed {childId:$child,seedId:item.id}) "
                + "SET s.origin=item.origin,s.enabled=item.enabled,s.payload=item.payload",
                Map.of("child", child.toString(), "seeds", seeds));
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

    @SuppressWarnings("unchecked")
    private List<List<Object>> statement(String tx, String cypher, Map<String, ?> parameters) {
        Map<?, ?> reply = request("POST", uri(tx, ""), List.of(Map.of("statement", cypher, "parameters", parameters)), false);
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
        var builder = HttpRequest.newBuilder(uri).timeout(budget).header("Authorization", authorization)
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
