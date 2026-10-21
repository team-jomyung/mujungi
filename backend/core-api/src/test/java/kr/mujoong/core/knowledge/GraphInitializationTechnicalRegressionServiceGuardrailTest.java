package kr.mujoong.core.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** U16 전체 검증에서 찾은 기술 회귀. 로컬 HTTP만 사용하며 일반 업무2초·원자성은 유지한다. */
@Tag("service-guardrail")
class GraphInitializationTechnicalRegressionServiceGuardrailTest {
    @Test void startupOnlyExplainsTheExactReplacementQueriesAndPreparesOnce() throws Exception {
        try (var wire = new Wire(200, reply("a".repeat(64)))) {
            wire.graph.prepareSchema();
            var prepared = wire.allStatements().stream().filter(value ->
                    ((String) value.get("statement")).startsWith("EXPLAIN ")).toList();
            assertThat(prepared).hasSize(3);
            assertThat(wire.allStatements()).allSatisfy(value -> assertThat((String) value.get("statement"))
                    .satisfiesAnyOf(text -> assertThat(text).startsWith("CREATE CONSTRAINT "),
                            text -> assertThat(text).startsWith("EXPLAIN ")));
            int requestsBefore = wire.requests.size();
            wire.graph.prepareSchema();
            assertThat(wire.requests).hasSize(requestsBefore);
            wire.graph.replaceBase("17", UUID.randomUUID(), GraphModelTest.catalog());
            var actual = (List<?>) wire.requests.getLast().get("statements");
            assertThat(prepared.stream().map(value -> ((String) value.get("statement")).substring(8)).toList())
                    .containsExactlyElementsOf(actual.stream().map(value -> (String) ((Map<?, ?>) value).get("statement")).toList());
        }
    }

    @Test void failedPlanPreparationDoesNotOpenReadyOrRetryAutomatically() throws Exception {
        try (var wire = new Wire(200, reply("a".repeat(64)))) {
            wire.rejectPlans = true;
            assertThatThrownBy(wire.graph::prepareSchema).isInstanceOf(IllegalStateException.class);
            assertThat(wire.allStatements().stream().filter(value ->
                    ((String) value.get("statement")).startsWith("EXPLAIN "))).hasSize(1);
            wire.rejectPlans = false;
            wire.graph.prepareSchema();
            assertThat(wire.allStatements().stream().filter(value ->
                    ((String) value.get("statement")).startsWith("EXPLAIN "))).hasSize(4);
        }
    }

    @Test void catalogAndChildReplacementUseOneExplicitTransactionRequest() throws Exception {
        try (var wire = new Wire(200, reply("a".repeat(64)))) {
            wire.graph.replaceBase("17", UUID.randomUUID(), GraphModelTest.catalog());
            assertThat(wire.paths).containsExactly("/db/neo4j/tx/17");
            var statement = wire.statement();
            String cypher = (String) statement.get("statement");
            assertThat(cypher).isEqualTo("MERGE (v:U15GraphVersion {version:$version}) ON CREATE SET v.digest=$digest RETURN v.digest");
            var statements = wire.statements();
            assertThat((String) ((Map<?, ?>) statements.get(1)).get("statement"))
                    .startsWith("MATCH (v:U15GraphVersion {version:$version,digest:$digest})")
                    .contains("childId:$child,origin:'POC'", "DETACH DELETE s");
            assertThat((String) ((Map<?, ?>) statements.get(2)).get("statement"))
                    .startsWith("MATCH (v:U15GraphVersion {version:$version,digest:$digest})")
                    .contains("UNWIND $seeds", "childId:$child,seedId:item.id");
            assertThat(statements).allSatisfy(value -> assertThat(((Map<?, ?>) value).get("parameters")).isEqualTo(wire.parameters()));
            assertThat(wire.parameters()).containsOnlyKeys("version", "digest", "child", "seeds");
        }
    }

    @Test void allFourteenSeedsAndEmptyCatalogStayInParameters() throws Exception {
        var seeds = java.util.stream.IntStream.rangeClosed(1, 14)
                .mapToObj(n -> GraphModelTest.seed("seed:S" + n, KnowledgeSeed.Origin.POC)).toList();
        try (var wire = new Wire(200, reply("a".repeat(64)))) {
            wire.graph.replaceBase("17", UUID.randomUUID(), new SeedCatalog("v1", "a".repeat(64), seeds));
            assertThat((List<?>) wire.parameters().get("seeds")).hasSize(14);
        }
        try (var wire = new Wire(200, reply("a".repeat(64)))) {
            wire.graph.replaceBase("17", UUID.randomUUID(), new SeedCatalog("v1", "a".repeat(64), List.of()));
            assertThat((List<?>) wire.parameters().get("seeds")).isEmpty();
            assertThat(wire.paths).hasSize(1);
        }
    }

    @Test void hostileCatalogAndSeedTextNeverBecomeCypher() throws Exception {
        String hostile = "v' }) DETACH DELETE n // 한글\\\"";
        var original = GraphModelTest.seed("seed:S1", KnowledgeSeed.Origin.POC);
        var seed = new KnowledgeSeed(original.seedId(), original.origin(), hostile, original.signature(),
                original.invariantSentence(), original.family(), original.preservationRule(), original.relationship(),
                original.situation(), true);
        try (var wire = new Wire(200, reply("a".repeat(64)))) {
            wire.graph.replaceBase("17", UUID.randomUUID(), new SeedCatalog(hostile, "a".repeat(64), List.of(seed)));
            assertThat(wire.statements()).allSatisfy(value ->
                    assertThat((String) ((Map<?, ?>) value).get("statement")).doesNotContain(hostile));
            assertThat(wire.parameters().get("version")).isEqualTo(hostile);
            var payload = (String) ((Map<?, ?>) ((List<?>) wire.parameters().get("seeds")).getFirst()).get("payload");
            assertThat(JsonCodec.shared().read(payload, KnowledgeSeed.class)).isEqualTo(seed);
        }
    }

    @Test void digestConflictRejectsWithoutRetryOrCommit() throws Exception {
        try (var wire = new Wire(200, reply("b".repeat(64)))) {
            assertThatThrownBy(() -> wire.graph.replaceBase("17", UUID.randomUUID(), GraphModelTest.catalog()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Catalog version conflict");
            assertThat(wire.paths).containsExactly("/db/neo4j/tx/17");
        }
    }

    @Test void missingDigestRowIsNotSuccess() throws Exception {
        try (var wire = new Wire(200, Map.of("results", List.of(), "errors", List.of()))) {
            assertThatThrownBy(() -> wire.graph.replaceBase("17", UUID.randomUUID(), GraphModelTest.catalog()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(wire.paths).hasSize(1);
        }
    }

    @Test void neo4jRejectionDoesNotBecomeSuccessOrAutomaticRetry() throws Exception {
        try (var wire = new Wire(200, Map.of("errors", List.of(Map.of("code", "Neo.ClientError.Statement.SyntaxError"))))) {
            assertThatThrownBy(() -> wire.graph.replaceBase("17", UUID.randomUUID(), GraphModelTest.catalog()))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Graph operation rejected");
            assertThat(wire.paths).hasSize(1);
        }
    }

    @Test void httpFailureDoesNotCommitOrRetry() throws Exception {
        try (var wire = new Wire(503, Map.of("errors", List.of()))) {
            assertThatThrownBy(() -> wire.graph.replaceBase("17", UUID.randomUUID(), GraphModelTest.catalog()))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Graph unavailable");
            assertThat(wire.paths).containsExactly("/db/neo4j/tx/17");
        }
    }

    @Test void maliciousTransactionHandleOpensNoRequest() throws Exception {
        try (var wire = new Wire(200, reply("a".repeat(64)))) {
            assertThatThrownBy(() -> wire.graph.replaceBase("../../commit", UUID.randomUUID(), GraphModelTest.catalog()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid graph transaction");
            assertThat(wire.paths).isEmpty();
        }
    }

    private static Map<String, Object> reply(String digest) {
        return Map.of("results", List.of(Map.of("data", List.of(Map.of("row", List.of(digest))))), "errors", List.of());
    }

    private static final class Wire implements AutoCloseable {
        private final HttpServer server;
        private final List<Map<?, ?>> requests = new ArrayList<>();
        private final List<String> paths = new ArrayList<>();
        private final Neo4jChildKnowledgeGraph graph;
        private boolean rejectPlans;

        Wire(int status, Map<String, Object> response) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String input = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<?, ?> request = input.isEmpty() ? Map.of("statements", List.of()) : JsonCodec.shared().read(input, Map.class);
                requests.add(request);
                paths.add(exchange.getRequestURI().getPath());
                Object outputBody = response;
                if (exchange.getRequestURI().getPath().equals("/db/neo4j/tx")) {
                    outputBody = Map.of("commit", "http://127.0.0.1/db/neo4j/tx/17/commit", "errors", List.of());
                } else if (rejectPlans && input.contains("EXPLAIN ")) {
                    outputBody = Map.of("errors", List.of(Map.of("code", "Neo.ClientError.Statement.SyntaxError")));
                }
                byte[] body = JsonCodec.shared().write(outputBody).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            server.start();
            graph = new Neo4jChildKnowledgeGraph(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "synthetic/credential", Duration.ofSeconds(2), Duration.ofSeconds(10),
                    new kr.mujoong.contract.time.SystemMujoongClock());
        }

        private List<?> statements() {
            assertThat(requests).hasSize(1);
            var statements = (List<?>) requests.getFirst().get("statements");
            assertThat(statements).hasSize(3);
            return statements;
        }
        private Map<?, ?> statement() { return (Map<?, ?>) statements().getFirst(); }
        private List<Map<?, ?>> allStatements() {
            return requests.stream().flatMap(request -> ((List<?>) request.get("statements")).stream())
                    .<Map<?, ?>>map(value -> (Map<?, ?>) value).toList();
        }
        @SuppressWarnings("unchecked")
        private Map<String, Object> parameters() { return (Map<String, Object>) statement().get("parameters"); }
        @Override public void close() { server.stop(0); }
    }
}
