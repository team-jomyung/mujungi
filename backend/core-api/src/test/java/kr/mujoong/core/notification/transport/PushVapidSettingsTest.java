package kr.mujoong.core.notification.transport;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

@Tag("u7")
class PushVapidSettingsTest {
    @Test
    void allAbsentDisablesOnlyPush() {
        var settings = PushVapidSettings.load(new MockEnvironment());
        assertThat(settings.enabled()).isFalse();
        assertThat(settings.publicKey()).isNull();
    }

    @Test
    void partialConfigurationFailsWithoutDisclosingInput() {
        String secret = new PushTestKeys().privateKey;
        assertThatThrownBy(
                        () ->
                                PushVapidSettings.load(
                                        new MockEnvironment()
                                                .withProperty(
                                                        "MUJOONG_PUSH_VAPID_PRIVATE_KEY", secret)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Web Push VAPID 설정 오류: MUJOONG_PUSH_VAPID_PUBLIC_KEY,"
                            + " MUJOONG_PUSH_VAPID_PRIVATE_KEY, MUJOONG_PUSH_VAPID_SUBJECT")
                .hasNoCause();
    }

    @Test
    void matchingGeneratedKeysAreAcceptedAndSecretsAreRedacted() {
        PushTestKeys keys = new PushTestKeys();
        var settings = PushVapidSettings.load(environment(keys));
        assertThat(settings.enabled()).isTrue();
        assertThat(settings.publicKey()).isEqualTo(keys.publicKey);
        assertThat(settings.toString()).doesNotContain(keys.privateKey, keys.publicKey, "mailto:");
    }

    @Test
    void mismatchedKeysAndInvalidSubjectFailAtStartup() {
        PushTestKeys keys = new PushTestKeys();
        assertThatThrownBy(
                        () ->
                                PushVapidSettings.load(
                                        environment(keys)
                                                .withProperty(
                                                        "MUJOONG_PUSH_VAPID_PRIVATE_KEY",
                                                        new PushTestKeys().privateKey)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasNoCause();
        assertThatThrownBy(
                        () ->
                                PushVapidSettings.load(
                                        environment(keys)
                                                .withProperty(
                                                        "MUJOONG_PUSH_VAPID_SUBJECT",
                                                        "https://contact.invalid")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasNoCause();
    }

    static MockEnvironment environment(PushTestKeys keys) {
        return new MockEnvironment()
                .withProperty("MUJOONG_PUSH_VAPID_PUBLIC_KEY", keys.publicKey)
                .withProperty("MUJOONG_PUSH_VAPID_PRIVATE_KEY", keys.privateKey)
                .withProperty("MUJOONG_PUSH_VAPID_SUBJECT", "mailto:push@example.invalid");
    }
}
