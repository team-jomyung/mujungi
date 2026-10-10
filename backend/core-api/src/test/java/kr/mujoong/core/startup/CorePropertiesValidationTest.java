package kr.mujoong.core.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.config.CorePropertiesConfig;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

/**
 * Core 설정 기동 검증 (RD-24 · I-6 · I-7 · SD-5 · J124 · NFR9.10). 키 누락 · 형식 오류 · 불변식 위반은 기동 실패이고 오류에는 키 이름만
 * 있다. 필수 비밀이 없거나 짧으면 값을 출력하지 않고 기동을 거부한다. I-3 · I-10은 Realtime 키(verify-token · hello · provider ·
 * device-ack)라 Realtime 시험(Step 29)이 본다.
 */
@Tag("u1")
class CorePropertiesValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(CorePropertiesConfig.class)
            .withPropertyValues("spring.profiles.active=test");

    @Test
    void repositoryDefaultsBindAndPassValidation() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            CoreProperties properties = context.getBean(CoreProperties.class);
            assertThat(properties.timing().core().deviceStatusStale()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.internalHttp().sessionStartRetries()).isEqualTo(1);
            assertThat(properties.outbox().retryBackoff()).hasSize(6);
            assertThat(properties.security().allowedOrigin()).isEqualTo("https://mujoong.invalid");
        });
    }

    @Test
    void staleShorterThanThreeReportIntervalsFailsStartupNamingKeys() {
        runner.withPropertyValues("mujoong.timing.core.device-status-stale=2s").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure()))
                    .contains("mujoong.timing.core.device-status-stale")
                    .contains("mujoong.timing.device.status-report-interval");
        });
    }

    @Test
    void cookieLongerThanSessionIdleFails() {
        runner.withPropertyValues("mujoong.security.session-cookie-max-age=31d").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("mujoong.security.session-cookie-max-age");
        });
    }

    @Test
    void poolAboveTenPerAppFails() {
        runner.withPropertyValues("spring.datasource.hikari.maximum-pool-size=11").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("spring.datasource.hikari.maximum-pool-size");
        });
    }

    @Test
    void nonPositiveDurationOrMissingKeyFails() {
        runner.withPropertyValues("mujoong.outbox.poll-interval=0s").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("mujoong.outbox.poll-interval");
        });
        runner.withPropertyValues("mujoong.internal-http.realtime-base-url=").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void requiredSecretsAreCheckedByNameAndNeverPrinted() {
        String token = TestSecrets.internalServiceToken();
        String ownerSecret = TestSecrets.ownerHashSecret();

        assertThat(RequiredSecretsCheck.problems(token, null, "test")).isEmpty();
        assertThat(RequiredSecretsCheck.problems(null, null, "dev")).containsExactly(RequiredSecretsCheck.INTERNAL_TOKEN);
        assertThat(RequiredSecretsCheck.problems(token.substring(0, 42), ownerSecret, "prod"))
                .containsExactly(RequiredSecretsCheck.INTERNAL_TOKEN);
        assertThat(RequiredSecretsCheck.problems(token + "+/", ownerSecret, "prod"))
                .containsExactly(RequiredSecretsCheck.INTERNAL_TOKEN);
        assertThat(RequiredSecretsCheck.problems(token, "", "demo")).containsExactly(RequiredSecretsCheck.OWNER_HASH_SECRET);
        assertThat(RequiredSecretsCheck.problems(token, "too-short", "dev"))
                .containsExactly(RequiredSecretsCheck.OWNER_HASH_SECRET);
    }

    @Test
    void missingSecretRefusesStartupWithNamesOnly() {
        String shortSecret = "short-owner-secret-value";
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment.setProperty(RequiredSecretsCheck.INTERNAL_TOKEN, "tiny");
        CoreProperties.Observability observability = new CoreProperties.Observability(shortSecret, Duration.ofSeconds(60),
                Duration.ofSeconds(10));
        CoreProperties properties = org.mockito.Mockito.mock(CoreProperties.class);
        org.mockito.Mockito.when(properties.observability()).thenReturn(observability);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream original = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            assertThatThrownBy(() -> new RequiredSecretsCheck(environment, properties))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(RequiredSecretsCheck.INTERNAL_TOKEN)
                    .hasMessageContaining(RequiredSecretsCheck.OWNER_HASH_SECRET)
                    .hasMessageNotContaining("tiny")
                    .hasMessageNotContaining(shortSecret);
        } finally {
            System.setErr(original);
        }
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("RequiredSecretsMissing").doesNotContain("tiny")
                .doesNotContain(shortSecret);
    }

    private static String rootMessage(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            messages.append(t.getMessage()).append('\n');
        }
        return messages.toString();
    }
}
