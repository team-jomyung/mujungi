package kr.mujoong.realtime.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import kr.mujoong.realtime.gate.SeedDictionary;
import kr.mujoong.realtime.startup.RealtimeStartupChecks.Inputs;
import kr.mujoong.realtime.startup.RealtimeStartupChecks.StartupRejectedException;
import kr.mujoong.realtime.voice.ProviderHosts;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Realtime 기동 점검 S1b · S2b 시험 (U1 3.5 Step 29 · RD-12 · RD-24 · I-10 · SD-24 · SD-25 · J124 · J141).
 *
 * <p>필수 비밀 · 공급자 주소 · 사전 로딩 거부와 데이터 등급 대조를 본다. 거부는 예외(기동 실패 → 프로세스 종료 코드 1)이고 거부 줄에는 키
 * 이름만 있고 값은 없다. 비밀 값은 실행 때 만든다.
 */
@Tag("u1")
class RealtimeStartupChecksTest {

    private static final URI LOOPBACK = URI.create("ws://127.0.0.1:9/fake-provider");

    private final String token = TestSecrets.internalServiceToken();
    private final String providerKey = TestSecrets.base64Url(24);
    private final String ownerSecret = TestSecrets.ownerHashSecret();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream original;

    @BeforeEach
    void captureStderr() {
        original = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStderr() {
        System.setErr(original);
    }

    @Test
    void validSecretsALoopbackProviderAndTheShippedDictionaryPass() {
        RealtimeStartupChecks checks = new RealtimeStartupChecks(
                new Inputs(token, providerKey, ownerSecret, LOOPBACK, List.of("test")), SeedDictionary::loadDefault);

        assertThat(checks.dictionary().entries()).isNotEmpty();
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(new Inputs(token, providerKey, ownerSecret, LOOPBACK, null).toString()).doesNotContain(token);
    }

    @Test
    void aMissingOrShortInternalTokenAndABlankProviderKeyInDemoAreRejectedByNameOnly() {
        String shortToken = token.substring(0, RealtimeStartupChecks.MIN_INTERNAL_TOKEN_CHARS - 1);

        assertThatThrownBy(() -> check(new Inputs(null, providerKey, ownerSecret, LOOPBACK, List.of("test"))))
                .isInstanceOf(StartupRejectedException.class).hasMessageContaining(RealtimeStartupChecks.INTERNAL_TOKEN);
        assertThatThrownBy(() -> check(new Inputs(shortToken, " ", ownerSecret, LOOPBACK, List.of("demo"))))
                .hasMessageContaining(RealtimeStartupChecks.INTERNAL_TOKEN)
                .hasMessageContaining(RealtimeStartupChecks.OPENAI_API_KEY).hasMessageNotContaining(shortToken);
        assertThat(RealtimeStartupChecks.isValidInternalToken(token + "+")).isFalse();
        String printed = err.toString(StandardCharsets.UTF_8);
        assertThat(printed).contains(StartupFailureLine.PREFIX).contains("RealtimeStartupRejected")
                .doesNotContain(shortToken).doesNotContain(providerKey);
    }

    @Test
    void theOwnerHashSecretIsRequiredInPublicEnvironmentsAndMustBeLongEnough() {
        assertThatThrownBy(() -> check(new Inputs(token, providerKey, null, LOOPBACK, List.of("demo"))))
                .hasMessageContaining(RealtimeStartupChecks.OWNER_HASH_SECRET);
        assertThatThrownBy(() -> check(new Inputs(token, providerKey, "짧음", LOOPBACK, List.of("test"))))
                .hasMessageContaining(RealtimeStartupChecks.OWNER_HASH_SECRET);
        assertThatCode(() -> check(new Inputs(token, providerKey, null, LOOPBACK, List.of("test"))))
                .doesNotThrowAnyException();
    }

    @Test
    void anEmptyProviderUrlIsRejectedAndTheTestProfileAllowsOnlyLoopbackHosts() {
        assertThatThrownBy(() -> check(new Inputs(token, providerKey, ownerSecret, null, List.of("test"))))
                .hasMessageContaining(RealtimeStartupChecks.PROVIDER_URL_KEY + "(MISSING)");
        assertThatThrownBy(() -> check(new Inputs(token, providerKey, ownerSecret,
                URI.create("wss://provider.invalid/v1"), List.of("test"))))
                .hasMessageContaining(RealtimeStartupChecks.PROVIDER_URL_KEY + "(NOT_LOOPBACK)");
    }

    @Test
    void publicEnvironmentsAcceptOnlyAllowListedProviderHosts() {
        String allowedHost = ProviderHosts.ALLOWED_HOSTS.iterator().next();
        URI allowed = URI.create("wss://" + allowedHost + "/v1/live/sessions");

        assertThatCode(() -> check(new Inputs(token, providerKey, ownerSecret, allowed, List.of("prod"))))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> check(new Inputs(token, providerKey, ownerSecret, LOOPBACK, List.of("prod"))))
                .hasMessageContaining(RealtimeStartupChecks.PROVIDER_URL_KEY);
        assertThatThrownBy(() -> check(new Inputs(token, providerKey, ownerSecret,
                URI.create("wss://provider.invalid/v1"), List.of("demo"))))
                .hasMessageContaining("HOST_NOT_ALLOWED");
    }

    @Test
    void aBrokenSeedDictionaryStopsTheStartupWithoutPrintingPatterns() {
        assertThatThrownBy(() -> new RealtimeStartupChecks(
                new Inputs(token, providerKey, ownerSecret, LOOPBACK, List.of("test")),
                () -> SeedDictionary.parse("{\"version\":\"v\",\"level\":\"SEED\",\"entries\":[]}")))
                .isInstanceOf(StartupRejectedException.class).hasMessageContaining("EMPTY_CATEGORY");
    }

    @ParameterizedTest
    @ValueSource(strings = {"synthetic", "SYNTHETIC"})
    void syntheticDataIsAllowedWithTheSeedDictionary(String raw) {
        assertThat(DataClassCheck.classify(raw)).isEqualTo(DataClassCheck.DataClass.SYNTHETIC);
        assertThat(DataClassCheck.isAllowed(DataClassCheck.classify(raw), SeedDictionary.loadDefault())).isTrue();
    }

    @Test
    void realOrMissingDataClassWithTheSeedDictionaryIsRejected() {
        SeedDictionary seed = SeedDictionary.loadDefault();

        assertThat(DataClassCheck.classify("real")).isEqualTo(DataClassCheck.DataClass.REAL);
        assertThat(DataClassCheck.classify(null)).isEqualTo(DataClassCheck.DataClass.REAL);
        assertThat(DataClassCheck.classify("")).isEqualTo(DataClassCheck.DataClass.REAL);
        assertThat(DataClassCheck.isAllowed(DataClassCheck.DataClass.REAL, seed)).isFalse();
    }

    private static RealtimeStartupChecks check(Inputs inputs) {
        return new RealtimeStartupChecks(inputs, SeedDictionary::loadDefault);
    }
}
