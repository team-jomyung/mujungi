package kr.mujoong.core.knowledge;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.luna.CoreLunaClient;
import kr.mujoong.core.textai.ForbiddenExpressionPolicy;
import kr.mujoong.core.textai.TextAiProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.PlatformTransactionManager;

/** 서비스 시작은 인증된 그래프 준비 성공을 요구한다. 격리 시험은 명시적으로 비활성화한다. */
@Configuration(proxyBeanMethods = false)
@org.springframework.boot.context.properties.EnableConfigurationProperties(SeedGenerationProperties.class)
public class KnowledgeConfiguration {
    @Bean SeedCatalog knowledgeSeedCatalog() throws IOException {
        try (var input = new ClassPathResource("knowledge/social-comm-poc.zip").getInputStream()) {
            return new SeedArchiveLoader().load(input, "poc-v1");
        }
    }
    @Bean ChildKnowledgeGraphPort childKnowledgeGraph(KnowledgeProperties properties, CoreProperties core,
            Environment environment, MujoongClock clock) {
        String auth = environment.getProperty("NEO4J_AUTH", "");
        if (!properties.enabled()) return new MemoryChildKnowledgeGraph();
        if (auth.isBlank()) throw new IllegalStateException("Neo4j enabled but NEO4J_AUTH is missing");
        var graph = new Neo4jChildKnowledgeGraph(URI.create(properties.endpoint()),
                auth, core.timing().knowledge().graphBudget(),
                core.timing().knowledge().graphInitializationBudget(), clock);
        // 초기화 전용 마감 안에서 성공하기 전에는 서비스 준비 상태를 광고하지 않는다.
        graph.prepareSchema();
        return graph;
    }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(KnowledgeGraphLookup.class)
    KnowledgeGraphLookup publicKnowledgeGraphLookup() { return key -> List.of(); }
    @Bean(destroyMethod = "close") ChildKnowledgeService childKnowledgeService(KnowledgeLedgerRepository ledger,
            KnowledgeAccessPolicy access, ChildKnowledgeGraphPort graph, PlatformTransactionManager manager,
            MujoongClock clock, SeedCatalog catalog, KnowledgeProperties properties, CoreProperties core, Environment environment) {
        boolean available = properties.enabled() && !environment.getProperty("NEO4J_AUTH", "").isBlank();
        return new ChildKnowledgeService(ledger, access, graph, manager, clock, catalog, available, core.timing().knowledge().graphBudget());
    }
    @Bean SeedContentValidator seedContentValidator(ForbiddenExpressionPolicy forbidden) { return new SeedContentValidator(forbidden); }
    @Bean SeedGenerationService seedGenerationService(ChildKnowledgeService knowledge, Optional<CoreLunaClient> luna,
            SeedContentValidator validator, TextAiProperties timing, SeedGenerationProperties properties) throws IOException {
        String prompt = new ClassPathResource("knowledge/seed-generation.v2.txt").getContentAsString(StandardCharsets.UTF_8);
        return new SeedGenerationService(knowledge, luna, validator, properties.enabled(), timing.requestTimeout(), prompt);
    }
}
