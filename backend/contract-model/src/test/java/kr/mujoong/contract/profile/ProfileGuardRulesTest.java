package kr.mujoong.contract.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import kr.mujoong.contract.profile.ProfileGuardRules.Combination;
import kr.mujoong.contract.profile.ProfileGuardRules.DevLogin;
import kr.mujoong.contract.profile.ProfileGuardRules.Input;
import kr.mujoong.contract.profile.ProfileGuardRules.Startup;
import kr.mujoong.contract.profile.ProfileGuardRules.Verdict;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 프로필 조합 가드(SD-14 표 1 ~ 13 · A2 · A6 · NFR9.11 · BR8.3). 두 앱 가드가 같은 표를 쓰는지는 Step 27 · 29가 본다. */
@Tag("u1")
class ProfileGuardRulesTest {

    static Stream<Combination> table() {
        return ProfileGuardRules.TABLE.stream();
    }

    @ParameterizedTest
    @MethodSource("table")
    void everySd14RowGivesItsExpectedStartupAndDevLoginOutcome(Combination combination) {
        Verdict verdict = ProfileGuardRules.evaluate(combination.input());

        assertThat(verdict.row()).as(combination.toString()).isEqualTo(combination.row());
        assertThat(verdict.allowed()).as(combination.toString()).isEqualTo(combination.startup() == Startup.ALLOW);
        assertThat(verdict.devLoginIssuable()).as(combination.toString()).isEqualTo(combination.devLogin() == DevLogin.ISSUE);
        if (verdict.allowed()) {
            assertThat(verdict.conflicts()).isEmpty();
            assertThat(verdict.describe()).isEmpty();
        } else {
            assertThat(verdict.conflicts()).isNotEmpty();
            assertThat(verdict.describe()).startsWith("프로필 조합 거부(SD-14 표 " + combination.row() + "): ");
        }
    }

    @Test
    void tableCoversAllThirteenRows() {
        Set<Integer> rows = ProfileGuardRules.TABLE.stream().map(Combination::row).collect(Collectors.toSet());

        assertThat(rows).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(new Integer[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13}).toList());
    }

    @Test
    void rejectionNamesOnlyProfilesAndKeysNeverValues() {
        Verdict demoWithDevLogin = ProfileGuardRules.evaluate(Input.of(List.of("demo"), true, false));
        Verdict prodWithLoadTestKey = ProfileGuardRules.evaluate(Input.of(List.of("prod"), false, true));
        Verdict loadTestAlone = ProfileGuardRules.evaluate(Input.of(List.of("load-test"), false, true));

        assertThat(demoWithDevLogin.conflicts()).containsExactly("demo", ProfileGuardRules.DEV_LOGIN_ENABLED_KEY);
        assertThat(prodWithLoadTestKey.conflicts()).containsExactly("prod", ProfileGuardRules.LOAD_TEST_ENABLED_KEY);
        assertThat(loadTestAlone.conflicts()).containsExactly("load-test", ProfileGuardRules.LOAD_TEST_ENABLED_KEY);
        assertThat(demoWithDevLogin.describe()).doesNotContain("true", "false");
    }

    @Test
    void profileNamesAreCaseInsensitiveAndBlankEntriesIgnored() {
        Verdict verdict = ProfileGuardRules.evaluate(new Input(Set.of(" DEMO ", "Dev", ""), false, false));

        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.row()).isEqualTo(5);
        assertThat(new Input(null, false, false).activeProfiles()).isEmpty();
        assertThat(Input.of(null, false, false).activeProfiles()).isEmpty();
    }

    @Test
    void noEnvironmentProfileIsAllowedButNeverIssuesDevLogin() {
        Verdict testOnly = ProfileGuardRules.evaluate(Input.of(List.of("test"), true, false));
        Verdict none = ProfileGuardRules.evaluate(Input.of(List.of(), false, false));

        assertThat(testOnly.allowed()).isTrue();
        assertThat(testOnly.row()).isZero();
        assertThat(testOnly.devLoginIssuable()).isFalse();
        assertThat(none.allowed()).isTrue();
    }

    @Test
    void requestTimeDoubleCheckOpensDevLoginOnlyOnDevWithoutPublicProfiles() {
        assertThat(ProfileGuardRules.isDevLoginAllowedAtRequest(Set.of("dev"), true)).isTrue();
        assertThat(ProfileGuardRules.isDevLoginAllowedAtRequest(Set.of("dev"), false)).isFalse();
        assertThat(ProfileGuardRules.isDevLoginAllowedAtRequest(Set.of("dev", "demo"), true)).isFalse();
        assertThat(ProfileGuardRules.isDevLoginAllowedAtRequest(Set.of("dev", "prod"), true)).isFalse();
        assertThat(ProfileGuardRules.isDevLoginAllowedAtRequest(Set.of("test"), true)).isFalse();
    }

    @Test
    void blockedEventProfileAttrIsTheUppercaseEnvironmentProfile() {
        assertThat(ProfileGuardRules.environmentProfile(Set.of("demo", "test"))).contains("DEMO");
        assertThat(ProfileGuardRules.environmentProfile(Set.of("prod"))).contains("PROD");
        assertThat(ProfileGuardRules.environmentProfile(Set.of("test"))).isEmpty();
    }

    @Test
    void combinationDescribesItselfWithoutValuesOfSecrets() {
        Combination row2 = ProfileGuardRules.TABLE.get(1);

        assertThat(row2.toString()).isEqualTo("row 2 [dev] dev-login=true load-test.enabled=false");
        assertThat(row2.input().devLoginEnabled()).isTrue();
    }
}
