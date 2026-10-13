package kr.mujoong.core.knowledge;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties(prefix = "mujoong.knowledge.neo4j")
public record KnowledgeProperties(boolean enabled) {}
