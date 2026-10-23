package kr.mujoong.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import kr.mujoong.core.config.CoreTimingProperties.KnowledgeTiming;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * U8 시간 키 바인딩(timing.md 13절 · BR3.1 · functional-spec 8.2). Core가 소유하는 새 키 {@code mujoong.timing.knowledge.graph-budget}이
 * {@link CoreTimingProperties#knowledge()}에 계약 기본값으로 묶이고, 0 · 빈 값은 기동을 거부하는지 본다. 키 이름 · 기본값의 timing.md
 * 대조는 {@link ConfigKeyContractTest}가 계속 맡는다.
 */
@Tag("live-content")
@Tag("service-guardrail")
class TimingPropertiesTest {

    private static final Path APPLICATION_YML = Path.of(System.getProperty("mujoong.repo.root", ".."))
            .toAbsolutePath().normalize().resolve("backend/core-api/src/main/resources/application.yml");

    @Test
    void applicationYmlBindsTheKnowledgeGraphBudget() {
        CoreProperties bound = ConfigKeyContractTest.bind(APPLICATION_YML);

        assertThat(bound.timing().knowledge().graphBudget()).isEqualTo(Duration.parse("PT2S"));
    }

    @Test
    void zeroNegativeOrMissingGraphBudgetIsRejectedWithTheKeyName() {
        assertThatThrownBy(() -> new KnowledgeTiming(Duration.ZERO, Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mujoong.timing.knowledge.graph-budget");
        assertThatThrownBy(() -> new KnowledgeTiming(Duration.parse("PT-1S"), Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mujoong.timing.knowledge.graph-budget");
        assertThatThrownBy(() -> new KnowledgeTiming(null, Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mujoong.timing.knowledge.graph-budget");
    }

    @Test
    void timingTreeRequiresTheKnowledgeGroup() {
        CoreTimingProperties t =ConfigKeyContractTest.bind(APPLICATION_YML).timing();

        assertThatThrownBy(() -> new CoreTimingProperties(t.session(), t.core(), t.bootstrap(), t.device(), t.login(),
                t.cache(), t.account(), t.practice(), t.goal(), t.result(), t.report(), t.push(), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mujoong.timing.knowledge");
    }

    @Test
    void initializationDefaultBindsSeparatelyFromTheOrdinaryBudget() {
        KnowledgeTiming timing = ConfigKeyContractTest.bind(APPLICATION_YML).timing().knowledge();
        assertThat(timing.graphInitializationBudget()).isEqualTo(Duration.ofSeconds(10));
        assertThat(timing.graphBudget()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void initializationOverrideBindsWithoutChangingTheOrdinaryBudget() {
        var source = new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
                java.util.Map.of("graph-budget", "2s", "graph-initialization-budget", "15s"));
        KnowledgeTiming timing = new org.springframework.boot.context.properties.bind.Binder(source)
                .bind("", org.springframework.boot.context.properties.bind.Bindable.of(KnowledgeTiming.class)).get();
        assertThat(timing.graphInitializationBudget()).isEqualTo(Duration.ofSeconds(15));
        assertThat(timing.graphBudget()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void missingInitializationBudgetIsRejected() {
        assertThatThrownBy(() -> new KnowledgeTiming(Duration.ofSeconds(2), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mujoong.timing.knowledge.graph-initialization-budget");
    }

    @Test
    void zeroInitializationBudgetIsRejected() {
        assertThatThrownBy(() -> new KnowledgeTiming(Duration.ofSeconds(2), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mujoong.timing.knowledge.graph-initialization-budget");
    }

    @Test
    void negativeInitializationBudgetIsRejected() {
        assertThatThrownBy(() -> new KnowledgeTiming(Duration.ofSeconds(2), Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mujoong.timing.knowledge.graph-initialization-budget");
    }
}
