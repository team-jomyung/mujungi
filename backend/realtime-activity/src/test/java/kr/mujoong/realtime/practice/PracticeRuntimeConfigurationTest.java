package kr.mujoong.realtime.practice;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.Map;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

@Tag("u5b")
class PracticeRuntimeConfigurationTest {
    @Test void runtimeDefaultsAreBoundAndTheCommitReserveIsExplicit() throws Exception {
        var config = new PracticeRuntimeConfiguration();
        var settings = config.practiceProperties(new StandardEnvironment());
        assertThat(settings.firstResponseWait()).isEqualTo(Duration.ofSeconds(20));
        assertThat(settings.helpConsentWait()).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.cueBudget()).isEqualTo(Duration.ofSeconds(5));
        assertThat(settings.judgeTotalBudget()).isEqualTo(Duration.ofSeconds(12));
        assertThat(settings.judgeFinalizationReserve()).isEqualTo(Duration.ofSeconds(2));
        assertThat(settings.evaluationBudget()).isPresent();
    }
    @Test void environmentOverridesModuleDefaultsAndInvalidBudgetFailsBinding() throws Exception {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "mujoong.timing.practice.judge-finalization-reserve", "3s")));
        assertThat(new PracticeRuntimeConfiguration().practiceProperties(environment).judgeFinalizationReserve()).isEqualTo(Duration.ofSeconds(3));
        environment.getPropertySources().addFirst(new MapPropertySource("invalid", Map.of(
                "mujoong.timing.practice.judge-total-budget", "13s")));
        assertThatThrownBy(() -> new PracticeRuntimeConfiguration().practiceProperties(environment)).isInstanceOf(RuntimeException.class);
    }
    @Test void realProvidersRemainUnavailableInsteadOfReturningFabricatedSuccess() {
        var config = new PracticeRuntimeConfiguration();
        assertThatThrownBy(() -> config.cueProvider().generate(new CueBrief("상황", "발화", "도움"))
                .toCompletableFuture().join()).hasCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> config.evaluationProvider().evaluate(null).toCompletableFuture().join())
                .hasCauseInstanceOf(IllegalStateException.class);
        try (var resources = new PracticeRuntimeConfiguration.Resources(new MutableClock())) {
            assertThat(resources.clock).isInstanceOf(EvaluationClock.class);
        }
    }
}
