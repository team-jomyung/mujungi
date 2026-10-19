package kr.mujoong.core.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.core.common.error.CoreApiException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("web-push")
class PushSubscriptionValidationTest {
    @Test
    void acceptsFreshP256ReceiverKeysAndDoesNotDependOnVapidConfiguration() {
        assertThatCode(() -> PushSubscriptionValidator.requireValid(PushSubscriptionFixtures.request()))
                .doesNotThrowAnyException();
        assertThatCode(() -> PushSubscriptionValidator.requireEndpoint("https://push.example.test:443/path?q=value"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "http://push.example.test/p", "//push.example.test/p", "https:///p",
            "https://push.example.test/p#secret",
            "https://push.example.test:8443/p", "https://push.example.test/p\n", "https://push.example.test/한글",
            "https://push.example.test/%zz"})
    void rejectsEndpointsThatAreNotAnUnambiguousHttpsPushAddress(String endpoint) {
        invalid(() -> PushSubscriptionValidator.requireEndpoint(endpoint));
    }

    @Test
    void rejectsUrlCredentialsGeneratedAtRuntime() {
        String endpoint = "https://" + UUID.randomUUID() + ":" + UUID.randomUUID() + "@push.example.test/p";
        invalid(() -> PushSubscriptionValidator.requireEndpoint(endpoint));
    }

    @Test
    void rejectsMissingFieldsInvalidBase64LengthsAndPointsOutsideTheCurve() {
        var valid = PushSubscriptionFixtures.request();
        invalid(() -> PushSubscriptionValidator.requireValid(null));
        invalid(() -> PushSubscriptionValidator.requireValid(new PushSubscriptionRequest(valid.endpoint(), null, "WEB")));
        invalid(() -> PushSubscriptionValidator.requireEndpoint("https://push.example.test/" + "a".repeat(2048)));
        for (String platform : java.util.Arrays.asList(null, "", " ", "p".repeat(41))) {
            invalid(() -> PushSubscriptionValidator.requireValid(new PushSubscriptionRequest(valid.endpoint(), valid.keys(), platform)));
        }
        for (String invalidKey : java.util.Arrays.asList(null, "", "A", "not*base64", encoded(64), encoded(65), encoded(66))) {
            invalid(() -> PushSubscriptionValidator.requireValid(new PushSubscriptionRequest(valid.endpoint(),
                    new PushSubscriptionRequest.Keys(invalidKey, valid.keys().auth()), valid.platform())));
        }
        byte[] outside = new byte[65];
        outside[0] = 4;
        invalid(() -> PushSubscriptionValidator.requireValid(new PushSubscriptionRequest(valid.endpoint(),
                new PushSubscriptionRequest.Keys(Base64.getUrlEncoder().withoutPadding().encodeToString(outside),
                        valid.keys().auth()), valid.platform())));
        for (String invalidAuth : java.util.Arrays.asList(null, "", "not*base64", encoded(15), encoded(17))) {
            invalid(() -> PushSubscriptionValidator.requireValid(new PushSubscriptionRequest(valid.endpoint(),
                    new PushSubscriptionRequest.Keys(valid.keys().p256dh(), invalidAuth), valid.platform())));
        }
    }

    @Test
    void requestAndStoredRecordStringsNeverExposeEndpointOrReceiverSecrets() {
        var request = PushSubscriptionFixtures.request();
        var subscription = new PushSubscription(UUID.randomUUID(), UUID.randomUUID(), request.endpoint(),
                request.keys().p256dh(), request.keys().auth(), request.platform(),
                java.time.Instant.EPOCH, null, null);
        for (String text : List.of(request.toString(), request.keys().toString(), subscription.toString())) {
            assertThat(text).doesNotContain(request.endpoint(), request.keys().p256dh(), request.keys().auth());
        }
    }

    private static String encoded(int length) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[length]);
    }

    private static void invalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CoreApiException.class,
                error -> assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
