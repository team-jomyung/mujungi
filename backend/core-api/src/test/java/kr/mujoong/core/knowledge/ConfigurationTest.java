package kr.mujoong.core.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.textai.ForbiddenExpressionPolicy;
import kr.mujoong.core.textai.TextAiProperties;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;

@Tag("knowledge-graph")
class ConfigurationTest {
    final KnowledgeConfiguration config = new KnowledgeConfiguration();
    CoreProperties core() {
        var value = mock(CoreProperties.class, RETURNS_DEEP_STUBS);
        when(value.timing().knowledge().graphBudget()).thenReturn(Duration.ofSeconds(2));
        return value;
    }
    @Test void packagedOriginalLoadsFourteenSeeds() throws Exception {
        assertThat(config.knowledgeSeedCatalog().seeds()).hasSize(14);
    }
    @Test void keylessEnabledFlagStillUsesUnavailableGraph() {
        assertThat(config.childKnowledgeGraph(new KnowledgeProperties(true), core(), new MockEnvironment()))
                .isInstanceOf(MemoryChildKnowledgeGraph.class);
    }
    @Test void disabledFlagNeverConstructsRemoteGraph() {
        assertThat(config.childKnowledgeGraph(new KnowledgeProperties(false), core(), new MockEnvironment().withProperty("NEO4J_AUTH", "neo4j/test-only")))
                .isInstanceOf(MemoryChildKnowledgeGraph.class);
    }
    @Test void configuredGraphUsesRemoteAdapterWithoutConnectingAtStartup() {
        assertThat(config.childKnowledgeGraph(new KnowledgeProperties(true, "http://127.0.0.1:1"), core(),
                new MockEnvironment().withProperty("NEO4J_AUTH", "neo4j/test-only"))).isInstanceOf(Neo4jChildKnowledgeGraph.class);
    }
    @Test void keylessServiceAndSeedProviderStartWithoutExternalCall() throws Exception {
        var graph = mock(ChildKnowledgeGraphPort.class);
        try (var service = config.childKnowledgeService(mock(KnowledgeLedgerRepository.class), mock(KnowledgeAccessPolicy.class),
                graph, mock(PlatformTransactionManager.class), new MutableClock(), GraphModelTest.catalog(),
                new KnowledgeProperties(true), core(), new MockEnvironment())) {
            assertThat(service.enabled()).isFalse();
            assertThat(service.seedSummaries(UUID.randomUUID(), UUID.randomUUID())).isEmpty();
            var timing = new TextAiProperties(Duration.ofSeconds(2), new TextAiProperties.Retry(1, Duration.ofMillis(20)),
                    new TextAiProperties.Result(Duration.ofSeconds(3), 0, 0.2, "v1"));
            var generator = config.seedGenerationService(service, Optional.empty(),
                    config.seedContentValidator(new ForbiddenExpressionPolicy()), timing, new SeedGenerationProperties(true));
            assertThat(generator.generateSeed(new SeedGenerationService.Request(UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), "candidate", "NONE", GraphModelTest.SIGNATURE)).seedId()).isEqualTo("NONE");
            verifyNoInteractions(graph);
        }
    }
    @Test void publicLookupRemainsCompatibleSingleMethod() {
        assertThat(config.publicKnowledgeGraphLookup().findRelatedBehaviorKeys("unknown")).isEmpty();
    }
}
