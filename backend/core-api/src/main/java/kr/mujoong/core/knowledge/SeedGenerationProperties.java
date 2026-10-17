package kr.mujoong.core.knowledge;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties(prefix = "mujoong.knowledge.seed-generation")
public record SeedGenerationProperties(boolean enabled) {}
