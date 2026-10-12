package kr.mujoong.core.practice;
import kr.mujoong.contract.json.JsonCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
/** Only temporary adapters; U2/U4/U6 can replace each consumer-owned port with a bean. */
@Configuration(proxyBeanMethods=false)
public class PracticePortConfiguration {
    @Bean @ConditionalOnMissingBean(PracticeAccountPort.class)
    PracticeAccountPort practiceAccountPort(NamedParameterJdbcTemplate jdbc, JsonCodec json) {
        return new JdbcPracticeAccountAdapter(jdbc,json);
    }
    @Bean @ConditionalOnMissingBean(CandidateEvidencePort.class)
    CandidateEvidencePort candidateEvidencePort() { return (child,session,evidence) -> false; }
    @Bean @ConditionalOnMissingBean(GoalMetadataPort.class)
    GoalMetadataPort goalMetadataPort() { return (child,goal,session) -> GoalMetadataPort.Metadata.unavailable(); }
}
