package kr.mujoong.core.textai;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import kr.mujoong.core.knowledge.KnowledgeProperties;
import kr.mujoong.core.luna.LunaKeyAbsentCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/** 텍스트 AI 설정(값은 application.yml {@code mujoong.text-ai.*} · {@code mujoong.knowledge.*}, U5a 리뷰 5). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({TextAiProperties.class, KnowledgeProperties.class})
public class TextAiConfiguration {

    /**
     * No invented successful response in production; a real adapter must explicitly replace this bean. U9: env
     * {@code OPENAI_API_KEY}가 없을 때만 등록되고(LunaKeyAbsentCondition), 있으면 Luna 어댑터가 대신한다(BR1.1 · BR1.2 · SD11).
     */
    @Bean
    @Conditional(LunaKeyAbsentCondition.class)
    @ConditionalOnMissingBean(TextAiProvider.class)
    TextAiProvider unavailableTextAiProvider() {
        return (prompt, timeout) -> {
            throw new TextAiException(TextAiException.Reason.UNAVAILABLE);
        };
    }

    @Bean(name = "textAiExecutor", destroyMethod = "shutdownNow")
    ExecutorService textAiExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
