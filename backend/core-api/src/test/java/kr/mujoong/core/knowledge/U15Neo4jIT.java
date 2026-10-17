package kr.mujoong.core.knowledge;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

@Tag("u15") @Tag("db")
class U15Neo4jIT {
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final GenericContainer<?> NEO4J = new GenericContainer<>(DockerImageName.parse("neo4j:5.26.31-community"))
            .withEnv("NEO4J_AUTH", "neo4j/" + PASSWORD).withExposedPorts(7474)
            .waitingFor(Wait.forHttp("/").forPort(7474).forStatusCode(200));
    private static Neo4jChildKnowledgeGraph graph;
    @BeforeAll static void start() {
        NEO4J.start();
        graph = connection();
        graph.prepareSchema();
    }
    private static Neo4jChildKnowledgeGraph connection() {
        return new Neo4jChildKnowledgeGraph(URI.create("http://" + NEO4J.getHost() + ":" + NEO4J.getMappedPort(7474)),
                "neo4j/" + PASSWORD, Duration.ofSeconds(2));
    }
    @AfterAll static void stop() { NEO4J.stop(); }
    @Test void commitsBaseAndIsolatesChildren() {
        UUID child = UUID.randomUUID();
        String tx = graph.begin();
        graph.replaceBase(tx, child, U15GraphModelTest.catalog());
        graph.commit(tx);
        assertThat(graph.seeds(child)).hasSize(1);
        assertThat(graph.seeds(UUID.randomUUID())).isEmpty();
    }
    @Test void rollbackRemovesUncommittedWrites() {
        UUID child = UUID.randomUUID();
        String tx = graph.begin();
        graph.writeGeneratedSeed(tx, child, U15GraphModelTest.seed("seed:G0001", KnowledgeSeed.Origin.GENERATED));
        assertThat(graph.terminate(tx)).isTrue();
        assertThat(graph.seeds(child)).isEmpty();
        assertThatThrownBy(() -> graph.commit(tx)).isInstanceOf(IllegalStateException.class);
    }
    @Test void closedHandleTerminationIsIdempotent() {
        String tx = graph.begin();
        graph.commit(tx);
        assertThat(graph.terminate(tx)).isTrue();
        assertThat(graph.terminate(tx)).isTrue();
    }
    @Test void reconnectUsesDurableCommittedData() {
        UUID child = UUID.randomUUID();
        String tx = graph.begin();
        graph.replaceBase(tx, child, U15GraphModelTest.catalog());
        graph.commit(tx);
        assertThat(connection().seeds(child)).hasSize(1);
    }
    @Test void reloadPreservesGeneratedAndRejectsConflict() {
        UUID child = UUID.randomUUID();
        String tx = graph.begin();
        graph.writeGeneratedSeed(tx, child, U15GraphModelTest.seed("seed:G0001", KnowledgeSeed.Origin.GENERATED));
        graph.replaceBase(tx, child, U15GraphModelTest.catalog());
        graph.commit(tx);
        tx = graph.begin();
        graph.replaceBase(tx, child, U15GraphModelTest.catalog());
        graph.commit(tx);
        assertThat(graph.seeds(child)).hasSize(2);
        String conflict = graph.begin();
        assertThatThrownBy(() -> graph.replaceBase(conflict, child, new SeedCatalog("v1", "b".repeat(64), U15GraphModelTest.catalog().seeds())))
                .isInstanceOf(IllegalArgumentException.class);
        graph.terminate(conflict);
    }
    @Test void deleteIdsPreservesOthers() {
        UUID child = UUID.randomUUID();
        String tx = graph.begin();
        graph.replaceBase(tx, child, U15GraphModelTest.catalog());
        graph.writeGeneratedSeed(tx, child, U15GraphModelTest.seed("seed:G0001", KnowledgeSeed.Origin.GENERATED));
        graph.commit(tx);
        tx = graph.begin();
        graph.deleteSeeds(tx, child, List.of("seed:G0001"));
        graph.commit(tx);
        assertThat(graph.seeds(child)).extracting(KnowledgeSeed::seedId).containsExactly("seed:S1");
    }
    @Test void childDeleteIsScoped() {
        UUID child = UUID.randomUUID(), other = UUID.randomUUID();
        String tx = graph.begin();
        graph.replaceBase(tx, child, U15GraphModelTest.catalog());
        graph.replaceBase(tx, other, U15GraphModelTest.catalog());
        graph.commit(tx);
        tx = graph.begin();
        graph.deleteChild(tx, child);
        graph.commit(tx);
        assertThat(graph.seeds(child)).isEmpty();
        assertThat(graph.seeds(other)).hasSize(1);
    }
    @Test void concurrentCommitAndTerminationCannotResurrectAfterCleanup() throws Exception {
        UUID child = UUID.randomUUID();
        String tx = graph.begin();
        graph.writeGeneratedSeed(tx, child, U15GraphModelTest.seed("seed:G0001", KnowledgeSeed.Origin.GENERATED));
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var commit = workers.submit(() -> {
                start.await();
                try { graph.commit(tx); } catch (IllegalStateException alreadyClosed) { /* rollback won */ }
                return true;
            });
            var rollback = workers.submit(() -> {
                start.await();
                try { return graph.terminate(tx); } catch (IllegalStateException concurrentCommit) { return false; }
            });
            start.countDown();
            commit.get(5, java.util.concurrent.TimeUnit.SECONDS);
            rollback.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(graph.terminate(tx)).isTrue();
            String cleanup = graph.begin();
            graph.deleteChild(cleanup, child);
            graph.commit(cleanup);
            assertThat(connection().seeds(child)).isEmpty();
            assertThatThrownBy(() -> graph.commit(tx)).isInstanceOf(IllegalStateException.class);
            assertThat(connection().seeds(child)).isEmpty();
        }
    }
    @Test void refusesHandleInjection() {
        assertThatThrownBy(() -> graph.terminate("../../commit")).isInstanceOf(IllegalArgumentException.class);
    }
}
