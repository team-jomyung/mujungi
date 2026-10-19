package kr.mujoong.core.notification;

import static org.assertj.core.api.Assertions.assertThat;

import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.web.support.CoreWebApp;
import kr.mujoong.core.web.support.OpenApiContracts;
import kr.mujoong.core.web.support.WebFixtures;
import kr.mujoong.testsupport.http.ContractStubServer;
import kr.mujoong.testsupport.secret.TestSecrets;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag("web-push")
@Tag("db")
class PushSubscriptionApiIT {
    private static final String PATH = "/api/push-subscription";
    private static final List<UUID> OWNERS = new ArrayList<>();
    private static DbTestSupport db;
    private static CoreWebApp app;
    private static ContractStubServer realtime;

    @BeforeAll
    static void start() {
        db = DbTestSupport.coreApp();
        String token = TestSecrets.internalServiceToken();
        realtime = WebFixtures.realtime(token);
        app =
                CoreWebApp.builder(db.postgres())
                        .internalToken(token)
                        .realtime(realtime.baseUrl())
                        .start();
    }

    @AfterAll
    static void stop() {
        try {
            if (app != null) {
                for (UUID owner : OWNERS) {
                    app.bean(PushDeletionParticipant.class)
                            .deleteByGuardian(owner, UUID.randomUUID());
                }
            }
        } finally {
            try {
                if (app != null) app.close();
            } finally {
                if (realtime != null) realtime.close();
                if (db != null) {
                    try {
                        WebFixtures.deleteSessions(db, OWNERS.toArray(UUID[]::new));
                    } finally {
                        db.close();
                    }
                }
            }
        }
    }

    @Test
    void bothMutationsRequireGuardianSessionAndCsrf() {
        var owner = guardian();
        var request = PushSubscriptionFixtures.request();
        for (String method : List.of("PUT", "DELETE")) {
            String body =
                    method.equals("PUT") ? JsonCodec.shared().write(request) : endpoint(request);
            assertThat(app.call(method, PATH).json(body).send().status()).isEqualTo(401);
            assertThat(app.call(method, PATH).session(owner.session()).json(body).send().status())
                    .isEqualTo(403);
        }
    }

    @Test
    void savesWithoutVapidAndDeletesOnlyTheCurrentDeviceWithoutEchoingSecrets() {
        var owner = guardian();
        var other = guardian();
        var one = PushSubscriptionFixtures.request();
        var two = PushSubscriptionFixtures.request();
        for (var request : List.of(one, two)) {
            var response =
                    app.put(PATH)
                            .session(owner.session())
                            .csrf(owner.csrf())
                            .json(JsonCodec.shared().write(request))
                            .send();
            assertThat(response.status()).isEqualTo(204);
            assertThat(response.body()).isEmpty();
            assertThat(OpenApiContracts.load().violations("PUT /api/push-subscription", response))
                    .isEmpty();
        }
        var conflict =
                app.put(PATH)
                        .session(other.session())
                        .csrf(other.csrf())
                        .json(JsonCodec.shared().write(one))
                        .send();
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.code()).isEqualTo("PUSH_SUBSCRIPTION_CONFLICT");
        assertThat(conflict.body())
                .doesNotContain(one.endpoint(), one.keys().p256dh(), one.keys().auth());
        assertThat(OpenApiContracts.load().violations("PUT /api/push-subscription", conflict))
                .isEmpty();
        assertThat(
                        app.call("DELETE", PATH)
                                .session(other.session())
                                .csrf(other.csrf())
                                .json(endpoint(one))
                                .send()
                                .status())
                .isEqualTo(204);
        assertThat(app.bean(PushSubscriptionRepository.class).findActiveByGuardian(owner.id()))
                .hasSize(2);
        var removed =
                app.call("DELETE", PATH)
                        .session(owner.session())
                        .csrf(owner.csrf())
                        .json(endpoint(one))
                        .send();
        assertThat(removed.status()).isEqualTo(204);
        assertThat(OpenApiContracts.load().violations("DELETE /api/push-subscription", removed))
                .isEmpty();
        assertThat(app.bean(PushSubscriptionRepository.class).findActiveByGuardian(owner.id()))
                .extracting(PushSubscription::endpoint)
                .containsExactly(two.endpoint());
    }

    @Test
    void malformedBodyAndDeletingAccountAreRejectedWithFixedErrorEnvelope() {
        var owner = guardian();
        for (String method : List.of("PUT", "DELETE")) {
            for (String body :
                    List.of("{", "[]", "{}", "{\"endpoint\":\"http://push.example.test/x\"}")) {
                var response =
                        app.call(method, PATH)
                                .session(owner.session())
                                .csrf(owner.csrf())
                                .json(body)
                                .send();
                assertThat(response.status()).isEqualTo(400);
                assertThat(response.json()).containsOnlyKeys("code", "message", "traceId");
                assertThat(response.code()).isEqualTo("VALIDATION_FAILED");
            }
        }
        db.jdbc()
                .update(
                        "update core.core_guardian set status='DELETION_REQUESTED' where id=:id",
                        Map.of("id", owner.id()));
        var response =
                app.put(PATH)
                        .session(owner.session())
                        .csrf(owner.csrf())
                        .json(JsonCodec.shared().write(PushSubscriptionFixtures.request()))
                        .send();
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.code()).isEqualTo("ACCOUNT_DELETION_IN_PROGRESS");
    }

    private static WebFixtures.Guardian guardian() {
        UUID id = db.insertGuardian("ACTIVE");
        OWNERS.add(id);
        return WebFixtures.guardianFor(app, id);
    }

    @Test
    void vapidPublicEndpointRequiresSessionAndReturnsDisabledStateWithoutSecrets() {
        String path = PATH + "/vapid-public-key";
        assertThat(app.get(path).send().status()).isEqualTo(401);
        var owner = guardian();
        var response = app.get(path).session(owner.session()).send();
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json())
                .containsOnlyKeys("enabled", "publicKey")
                .containsEntry("enabled", false)
                .containsEntry("publicKey", null);
        assertThat(
                        OpenApiContracts.load()
                                .violations(
                                        "GET /api/push-subscription/vapid-public-key", response))
                .isEmpty();
        assertThat(response.body())
                .doesNotContain("privateKey", "subject", "MUJOONG_PUSH_VAPID_PRIVATE_KEY");
    }

    private static String endpoint(PushSubscriptionRequest request) {
        return JsonCodec.shared().write(Map.of("endpoint", request.endpoint()));
    }
}
