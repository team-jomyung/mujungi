package kr.mujoong.core.result;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
@Tag("goal-result")
class TimingContractTest {
    Path root=Path.of(System.getProperty("mujoong.repo.root"));
    String read(String file)throws java.io.IOException{return Files.readString(root.resolve(file));}
    @Test void coreDefaultMatchesFourAttempts()throws Exception{assertThat(read("backend/core-api/src/main/resources/application.yml")).contains("job-attempts: 4","job-retry-backoff: [5s, 15s, 30s]");}
    @Test void timingContractMatchesCoreOwnership()throws Exception{assertThat(read("contracts/timing.md")).contains("`mujoong.timing.result.job-attempts` | `4` | core-api","[5s, 15s, 30s]");}
    @Test void nonOwningRealtimeConfigurationDoesNotDuplicateCoreJobKeys()throws Exception{assertThat(read("backend/realtime-activity/src/main/resources/application.yml")).doesNotContain("job-attempts:","job-retry-backoff:");}
    @Test void nonOwningWebTimingConfigurationDoesNotDuplicateServerJobKeys()throws Exception{assertThat(read("frontend/src/shared/timing/timing-defaults.json")).doesNotContain("result.job-attempts","result.job-retry-backoff");}
    @Test void aiContractDoesNotClaimOldThreeAttempts()throws Exception{assertThat(read("contracts/ai-io.md")).contains("총 4회 시도","GOAL_CANDIDATES·REJUDGMENT·RESULT_TEXT").doesNotContain("작업당 총 3회");}
    @Test void typedConfigurationAcceptsFourAndRejectsMismatchedSchedule(){assertThat(GoalResultResultRetryIT.timing().jobAttempts()).isEqualTo(4);assertThatThrownBy(()->new kr.mujoong.core.config.CoreTimingProperties.ResultTiming(4,java.util.List.of(java.time.Duration.ofSeconds(5)),java.time.Duration.ofMillis(10),java.time.Duration.ofSeconds(30),java.time.Duration.ofMinutes(5),java.time.Duration.ofHours(1))).isInstanceOf(IllegalArgumentException.class);}
}
