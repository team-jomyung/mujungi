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

@Tag("knowledge-graph") @Tag("db") @Tag("service-guardrail")
class Neo4jIT {
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
                "neo4j/" + PASSWORD, Duration.ofSeconds(2), Duration.ofSeconds(10),
                new kr.mujoong.contract.time.SystemMujoongClock());
    }
    @AfterAll static void stop() { NEO4J.stop(); }
    @Test void configuredStartupAuthenticatesAndPreparesSchema() {
        var core = org.mockito.Mockito.mock(kr.mujoong.core.config.CoreProperties.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        org.mockito.Mockito.when(core.timing().knowledge().graphBudget()).thenReturn(Duration.ofSeconds(2));
        org.mockito.Mockito.when(core.timing().knowledge().graphInitializationBudget()).thenReturn(Duration.ofSeconds(10));
        var properties = new KnowledgeProperties(true, "http://"+NEO4J.getHost()+":"+NEO4J.getMappedPort(7474));
        var env = new org.springframework.mock.env.MockEnvironment().withProperty("NEO4J_AUTH","neo4j/"+PASSWORD);
        var clock = new kr.mujoong.contract.time.SystemMujoongClock();
        assertThat(new KnowledgeConfiguration().childKnowledgeGraph(properties,core,env,clock)).isInstanceOf(Neo4jChildKnowledgeGraph.class);
        assertThatThrownBy(()->new KnowledgeConfiguration().childKnowledgeGraph(properties,core,
                new org.springframework.mock.env.MockEnvironment().withProperty("NEO4J_AUTH","neo4j/wrong-password"),clock))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void schemaPreparationDoesNotCreateSyntheticChildOrPinSyntheticVersion() {
        assertThat(graph.seeds(new UUID(0, 0))).isEmpty();
        String tx = graph.begin();
        try {
            graph.replaceBase(tx, UUID.randomUUID(), new SeedCatalog("__schema_preparation__", "a".repeat(64), List.of()));
        } finally {
            graph.terminate(tx);
        }
        assertThat(graph.seeds(new UUID(0, 0))).isEmpty();
    }
    @Test void commitsBaseAndIsolatesChildren() {
        UUID child = UUID.randomUUID();
        String tx = graph.begin();
        graph.replaceBase(tx, child, GraphModelTest.catalog());
        graph.commit(tx);
        assertThat(graph.seeds(child)).hasSize(1);
        assertThat(graph.seeds(UUID.randomUUID())).isEmpty();
    }
    @Test void rollbackRemovesUncommittedWrites() {
        UUID child = UUID.randomUUID();
        String tx = graph.begin();
        graph.replaceBase(tx, child, GraphModelTest.catalog());
        graph.writeGeneratedSeed(tx, child, GraphModelTest.seed("seed:G0001", KnowledgeSeed.Origin.GENERATED));
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
        graph.replaceBase(tx, child, GraphModelTest.catalog());
        graph.commit(tx);
        assertThat(connection().seeds(child)).hasSize(1);
    }
    @Test void reloadPreservesGeneratedAndRejectsConflict() {
        UUID child = UUID.randomUUID();
        String tx = graph.begin();
        graph.writeGeneratedSeed(tx, child, GraphModelTest.seed("seed:G0001", KnowledgeSeed.Origin.GENERATED));
        graph.replaceBase(tx, child, GraphModelTest.catalog());
        graph.commit(tx);
        tx = graph.begin();
        graph.replaceBase(tx, child, GraphModelTest.catalog());
        graph.commit(tx);
        assertThat(graph.seeds(child)).hasSize(2);
        String conflict = graph.begin();
        assertThatThrownBy(() -> graph.replaceBase(conflict, child, new SeedCatalog("v1", "b".repeat(64), GraphModelTest.catalog().seeds())))
                .isInstanceOf(IllegalArgumentException.class);
        graph.terminate(conflict);
        String committedConflict = graph.begin();
        assertThatThrownBy(() -> graph.replaceBase(committedConflict, child,
                new SeedCatalog("v1", "b".repeat(64), List.of(GraphModelTest.seed("seed:S99", KnowledgeSeed.Origin.POC)))))
                .isInstanceOf(IllegalArgumentException.class);
        graph.commit(committedConflict);
        assertThat(graph.seeds(child)).extracting(KnowledgeSeed::seedId)
                .containsExactly("seed:G0001", "seed:S1");
    }
    @Test void deleteIdsPreservesOthers() {
        UUID child = UUID.randomUUID();
        String tx = graph.begin();
        graph.replaceBase(tx, child, GraphModelTest.catalog());
        graph.writeGeneratedSeed(tx, child, GraphModelTest.seed("seed:G0001", KnowledgeSeed.Origin.GENERATED));
        graph.commit(tx);
        tx = graph.begin();
        graph.deleteSeeds(tx, child, List.of("seed:G0001"));
        graph.commit(tx);
        assertThat(graph.seeds(child)).extracting(KnowledgeSeed::seedId).containsExactly("seed:S1");
    }
    @Test void childDeleteIsScoped() {
        UUID child = UUID.randomUUID(), other = UUID.randomUUID();
        String tx = graph.begin();
        graph.replaceBase(tx, child, GraphModelTest.catalog());
        graph.replaceBase(tx, other, GraphModelTest.catalog());
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
        graph.writeGeneratedSeed(tx, child, GraphModelTest.seed("seed:G0001", KnowledgeSeed.Origin.GENERATED));
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
