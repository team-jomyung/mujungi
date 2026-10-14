package kr.mujoong.core.practice;

import java.time.Duration;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.config.CoreProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** C10 상황 생성의 포트 기본값과 시간 한도. 시간 값은 application.yml을 묶은 {@link CoreProperties}에서 읽는다(U5a 리뷰 5). */
@Configuration(proxyBeanMethods = false)
public class ScenarioConfiguration {
    @Bean @ConditionalOnMissingBean(PracticeSessionContextPort.class)
    PracticeSessionContextPort practiceSessionContextPort(
            kr.mujoong.core.practice.start.PracticePreparationRepository preparations,
            kr.mujoong.core.activity.StartActivityService activities) {
        return id -> {
            var p=preparations.findSession(id).filter(value->"READY".equals(value.status()))
                    .orElseThrow(()->CoreApiException.of(ErrorCode.SERVICE_NOT_READY));
            return kr.mujoong.core.practice.start.PracticeStartService.context(p,
                    activities.isActivePractice(p.guardianId(),p.childId(),id));
        };
    }
    @Bean @ConditionalOnMissingBean(ScenarioRecordPort.class)
    ScenarioRecordPort scenarioRecordPort(
            kr.mujoong.core.practice.start.PracticePreparationRepository preparations,
            kr.mujoong.core.record.RecordQueryService records,
            kr.mujoong.contract.json.JsonCodec json) {
        return id -> {
            var p=preparations.findSession(id).orElseThrow(()->CoreApiException.of(ErrorCode.SERVICE_NOT_READY));
            String raw=records.practiceSnapshot(p.guardianId(),id).orElseThrow(()->CoreApiException.of(ErrorCode.NOT_FOUND));
            return new ScenarioRecordPort.RecordedExposures(id,p.guardianId(),p.childId(),
                    kr.mujoong.core.practice.start.RecordedScenarioExposures.parse(raw,json));
        };
    }
    @Bean
    ScenarioLimits scenarioLimits(CoreProperties properties) {
        return new ScenarioLimits(properties.timing().practice().scenarioRequestTimeout());
    }
    public record ScenarioLimits(Duration requestTimeout) {
        public ScenarioLimits {
            if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
                throw new IllegalArgumentException("scenario request timeout must be positive");
            }
        }
    }
}
