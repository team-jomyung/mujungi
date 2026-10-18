package kr.mujoong.core.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import kr.mujoong.contract.profile.ProfileGuardRules;
import kr.mujoong.contract.profile.ProfileGuardRules.Combination;
import kr.mujoong.contract.profile.ProfileGuardRules.Startup;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Core 프로필 조합 가드 (SD-14 표 1 ~ 13 · A2 · A6 · NFR9.11 · BR8.3). 표 데이터는 {@link ProfileGuardRules#TABLE}(두 앱 공통)이고 실제
 * {@link StandardEnvironment}에 프로필 · 스위치를 넣어 후처리기를 돌린다. 거부 줄에는 프로필 · 키 이름만 있고 값은 없다.
 */
@Tag("skeleton")
class ProfileGuardEnvironmentPostProcessorTest {

    private final ProfileGuardEnvironmentPostProcessor processor = new ProfileGuardEnvironmentPostProcessor();

    static Stream<Combination> table() {
        return ProfileGuardRules.TABLE.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("table")
    void everyTableRowIsEnforcedOnARealEnvironment(Combination combination) {
        StandardEnvironment environment = environment(combination.profiles(), combination.devLoginEnabled(),
                combination.loadTestEnabled());
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream original = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            if (combination.startup() == Startup.ALLOW) {
                assertThatCode(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                        .doesNotThrowAnyException();
                assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
            } else {
                assertThatThrownBy(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                        .isInstanceOf(ProfileGuardEnvironmentPostProcessor.ProfileGuardRejectedException.class)
                        .hasMessageContaining("표 " + combination.row());
                String printed = err.toString(StandardCharsets.UTF_8);
                assertThat(printed).contains("ProfileGuardRejected").contains(StartupFailureLine.PREFIX)
                        .doesNotContain("=true").doesNotContain("true");
            }
        } finally {
            System.setErr(original);
        }
    }

    @Test
    void rejectionNamesConflictingProfilesAndKeysOnly() {
        StandardEnvironment environment = environment(Set.of("demo"), true, false);

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                .hasMessageContaining("demo").hasMessageContaining(ProfileGuardRules.DEV_LOGIN_ENABLED_KEY)
                .hasMessageNotContaining("true");
    }

    @Test
    void switchesAreReadFromEnvironmentValuesCaseInsensitively() {
        StandardEnvironment environment = environment(Set.of("prod"), false, false);
        environment.getPropertySources().addFirst(new MapPropertySource("loose",
                Map.of(ProfileGuardRules.LOAD_TEST_ENABLED_KEY, " TRUE ")));

        assertThat(ProfileGuardEnvironmentPostProcessor.evaluate(environment).allowed()).isFalse();
        assertThat(ProfileGuardEnvironmentPostProcessor.evaluate(environment).row()).isEqualTo(9);
        environment.getPropertySources().addFirst(new MapPropertySource("off",
                Map.of(ProfileGuardRules.LOAD_TEST_ENABLED_KEY, "yes")));
        assertThat(ProfileGuardEnvironmentPostProcessor.evaluate(environment).allowed()).isTrue();
    }

    @Test
    void runsRightAfterConfigDataSoProfileFilesAreVisible() {
        assertThat(processor.getOrder())
                .isGreaterThan(org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor.ORDER);
    }

    @Test
    void requestTimeDoubleCheckNeverOpensDevLoginOutsideDev() {
        assertThat(ProfileGuardRules.isDevLoginAllowedAtRequest(Set.of("dev"), true)).isTrue();
        assertThat(ProfileGuardRules.isDevLoginAllowedAtRequest(Set.of("dev"), false)).isFalse();
        assertThat(ProfileGuardRules.isDevLoginAllowedAtRequest(Set.of("dev", "demo"), true)).isFalse();
        assertThat(ProfileGuardRules.isDevLoginAllowedAtRequest(Set.of("prod", "dev"), true)).isFalse();
        assertThat(ProfileGuardRules.isDevLoginAllowedAtRequest(Set.of("test"), true)).isFalse();
    }

    @Test
    void noEnvironmentProfileIsAllowedWithoutDevLogin() {
        StandardEnvironment environment = environment(Set.of("test"), false, false);

        assertThatCode(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                .doesNotThrowAnyException();
        assertThat(ProfileGuardEnvironmentPostProcessor.evaluate(environment).devLoginIssuable()).isFalse();
    }

    private static StandardEnvironment environment(Set<String> profiles, boolean devLogin, boolean loadTest) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles(profiles.toArray(String[]::new));
        Map<String, Object> values = new HashMap<>();
        values.put(ProfileGuardRules.DEV_LOGIN_ENABLED_KEY, Boolean.toString(devLogin));
        values.put(ProfileGuardRules.LOAD_TEST_ENABLED_KEY, Boolean.toString(loadTest));
        environment.getPropertySources().addFirst(new MapPropertySource("test-switches", values));
        return environment;
    }
}
