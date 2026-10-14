package kr.mujoong.core.notification.transport;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.security.GuardianPrincipal;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.UUID;

@Tag("u7")
class PushTransportConfigurationTest {
    @Test
    void absentSecretsCreateDisabledSenderAndPublicResponseHasExplicitNull() {
        CoreProperties props = mock(CoreProperties.class, RETURNS_DEEP_STUBS);
        when(props.timing().push().sendTimeout()).thenReturn(Duration.ofSeconds(5));
        new ApplicationContextRunner()
                .withUserConfiguration(PushTransportConfiguration.class)
                .withBean(CoreProperties.class, () -> props)
                .withBean(JsonCodec.class, JsonCodec::shared)
                .withPropertyValues(
                        "MUJOONG_PUSH_VAPID_PUBLIC_KEY=",
                        "MUJOONG_PUSH_VAPID_PRIVATE_KEY=",
                        "MUJOONG_PUSH_VAPID_SUBJECT=")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean(PushDeliverySender.class).enabled())
                                    .isFalse();
                            var controller =
                                    new VapidPublicKeyController(
                                            context.getBean(PushVapidSettings.class));
                            var response = controller.get(new GuardianPrincipal(UUID.randomUUID()));
                            assertThat(JsonCodec.shared().write(response.getBody()))
                                    .isEqualTo("{\"enabled\":false,\"publicKey\":null}");
                            assertThat(response.getHeaders().getCacheControl())
                                    .contains("no-store");
                            assertThatThrownBy(() -> controller.get(null))
                                    .isInstanceOf(CoreApiException.class);
                        });
    }

    @Test
    void enabledPublicResponseContainsOnlyPublicKey() {
        PushTestKeys keys = new PushTestKeys();
        var controller =
                new VapidPublicKeyController(
                        PushVapidSettings.load(PushVapidSettingsTest.environment(keys)));
        String response =
                JsonCodec.shared()
                        .write(controller.get(new GuardianPrincipal(UUID.randomUUID())).getBody());
        assertThat(response).contains(keys.publicKey).doesNotContain(keys.privateKey, "mailto:");
        var disabled =
                new VapidPushSender(
                        PushVapidSettings.load(new MockEnvironment()),
                        (e, h, b) -> {
                            throw new AssertionError();
                        },
                        JsonCodec.shared());
        assertThat(disabled.send(null, null, null).status())
                .isEqualTo(DeliveryOutcome.Status.DISABLED);
    }
}
