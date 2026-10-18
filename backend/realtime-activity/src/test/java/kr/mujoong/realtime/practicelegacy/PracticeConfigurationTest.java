package kr.mujoong.realtime.practicelegacy;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.HashMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.ClassPathResource;

@Tag("u5b")
class PracticeConfigurationTest {
    PracticeProperties bind(String key, String value) {
        var yaml = new YamlPropertiesFactoryBean(); yaml.setResources(new ClassPathResource("practice/u5b-independent.yml"));
        var values = new HashMap<String, Object>(); yaml.getObject().forEach((k, v) -> values.put(k.toString(), v));
        if (key != null) values.put("mujoong.timing.practice." + key, value);
        return new Binder(new MapConfigurationPropertySource(values)).bind("mujoong.timing.practice", Bindable.of(PracticeProperties.class)).get();
    }
    @Test void defaultSettingsKeepExistingWaitsWithoutActivatingEvaluation() {
        var settings = bind(null, null);
        assertThat(settings.timing().firstResponse()).isEqualTo(Duration.ofSeconds(20));
        assertThat(settings.timing().helpConsent()).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.timing().cueStart()).isEqualTo(Duration.ofSeconds(5));
        assertThat(settings.judgeTotalBudget()).isEqualTo(Duration.ofSeconds(12));
        assertThat(settings.evaluationBudget()).isEmpty();
    }
    @Test void explicitTestReserveProducesVersionedWholeBudget() {
        var budget = bind("judge-finalization-reserve", "1s").evaluationBudget().orElseThrow();
        assertThat(budget.begin(PracticeModelTest.T).deadlineAt()).isEqualTo(PracticeModelTest.T.plusSeconds(12));
        assertThat(budget.reserve()).isEqualTo(Duration.ofSeconds(1)); assertThat(budget.version()).isEqualTo("u5b-q6");
    }
    @Test void rejectsNonPositiveStageWait() {
        assertThatThrownBy(() -> bind("first-response-wait", "0s")).hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsReserveThatConsumesWholeBudget() {
        assertThatThrownBy(() -> bind("judge-finalization-reserve", "12s")).hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsCallOrWholeLimitBeyondApprovedValues() {
        assertThatThrownBy(() -> bind("judge-budget", "6s")).hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bind("judge-total-budget", "13s")).hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
