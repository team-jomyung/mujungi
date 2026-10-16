package kr.mujoong.core.knowledge;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
@ConfigurationProperties(prefix = "mujoong.knowledge.neo4j")
public record KnowledgeProperties(boolean enabled, String endpoint) {
    @ConstructorBinding
    public KnowledgeProperties {}
    public KnowledgeProperties(boolean enabled) { this(enabled, "http://neo4j:7474"); }
}
