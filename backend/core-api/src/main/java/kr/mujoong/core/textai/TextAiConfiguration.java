package kr.mujoong.core.textai;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import kr.mujoong.core.knowledge.KnowledgeProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

@Configuration(proxyBeanMethods = false)
@PropertySource("classpath:u5a-ai.properties")
@EnableConfigurationProperties({TextAiProperties.class, KnowledgeProperties.class})
public class TextAiConfiguration {
    /** No invented successful response in production; a real adapter must explicitly replace this bean. */
    @Bean
    @ConditionalOnMissingBean(TextAiProvider.class)
    TextAiProvider unavailableTextAiProvider() {
        return (prompt, timeout) -> { throw new TextAiException(TextAiException.Reason.UNAVAILABLE); };
    }
    @Bean(name = "textAiExecutor", destroyMethod = "shutdownNow")
    ExecutorService textAiExecutor() { return Executors.newVirtualThreadPerTaskExecutor(); }
}
