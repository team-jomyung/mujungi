package kr.mujoong.core.result;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
@Configuration(proxyBeanMethods=false)
public class ResultTextConfiguration {
    @Bean @ConditionalOnMissingBean(ResultTextProvider.class)
    ResultTextProvider unavailableResultProvider(){return (request,timeout)->{throw new ResultAiException("PROVIDER_UNAVAILABLE");};}
}
