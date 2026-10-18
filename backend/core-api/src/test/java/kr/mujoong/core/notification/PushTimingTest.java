package kr.mujoong.core.notification;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.config.CorePropertiesConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("web-push")
class PushTimingTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(CorePropertiesConfig.class).withPropertyValues("spring.profiles.active=test");

    @Test
    void bindsPushDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var push = context.getBean(CoreProperties.class).timing().push();
            assertThat(push.dispatchPoll()).isEqualTo(Duration.ofSeconds(1));
            assertThat(push.retryBackoff()).containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2));
            assertThat(push.sendTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(push.deviceStatusCheck()).isEqualTo(Duration.ofSeconds(10));
        });
    }

    @Test
    void rejectsZeroTimeout() {
        runner.withPropertyValues("mujoong.timing.push.send-timeout=0s")
                .run(context -> assertThat(context).hasFailed());
    }
}
