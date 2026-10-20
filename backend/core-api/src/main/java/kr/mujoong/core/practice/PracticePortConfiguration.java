package kr.mujoong.core.practice;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
/** 아직 붙지 않은 뒤 유닛 포트의 기본값(닫힌 쪽으로 실패). U4 · U6이 같은 포트의 빈을 두면 그쪽을 쓴다. 계정 포트는 {@link PracticeAccountAdapter}다. */
@Configuration(proxyBeanMethods=false)
public class PracticePortConfiguration {
    @Bean @ConditionalOnMissingBean(CandidateEvidencePort.class)
    CandidateEvidencePort candidateEvidencePort() { return (child,session,evidence) -> false; }
    @Bean @ConditionalOnMissingBean(GoalMetadataPort.class)
    GoalMetadataPort goalMetadataPort() { return (child,goal,session) -> GoalMetadataPort.Metadata.unavailable(); }
}
