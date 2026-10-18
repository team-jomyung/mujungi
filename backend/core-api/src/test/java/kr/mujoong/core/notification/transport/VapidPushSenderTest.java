package kr.mujoong.core.notification.transport;

import static org.assertj.core.api.Assertions.*;

import com.the13haven.push2u.PushDeliveryException;
import com.the13haven.push2u.PushHttpClient;
import com.the13haven.push2u.PushResponse;

import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.core.notification.PushEventType;
import kr.mujoong.testsupport.http.ContractStubServer;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@Tag("web-push")
class VapidPushSenderTest {
    private final JsonCodec json = JsonCodec.shared();
    private final PushTestKeys vapid = new PushTestKeys();
    private final PushTestKeys browser = new PushTestKeys();
    private final PushPayload payload =
            new PushPayload(
                    PushEventType.VOICE_PROBLEM,
                    ActivityType.FREE_TALK,
                    UUID.randomUUID(),
                    Instant.parse("2026-10-14T00:00:00Z"));

    @Test
    void generatedKeysProduceVerifiableVapidAndDecryptableFourFieldPayload() throws Exception {
        AtomicReference<byte[]> encrypted = new AtomicReference<>();
        AtomicReference<Map<String, String>> headers = new AtomicReference<>();
        var sender =
                sender(
                        (endpoint, h, bytes) -> {
                            encrypted.set(bytes);
                            headers.set(h);
                            return PushResponse.of(201);
                        });
        assertThat(sender.send(target(), payload, "event_topic").status())
                .isEqualTo(DeliveryOutcome.Status.ACCEPTED);
        assertThat(headers.get())
                .containsEntry("Content-Encoding", "aes128gcm")
                .containsEntry("TTL", "0")
                .containsEntry("Topic", "event_topic");
        String authorization = headers.get().get("Authorization");
        String jwt = authorization.substring(authorization.indexOf("t=") + 2).split(",")[0].trim();
        String[] parts = jwt.split("\\.");
        Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
        signature.initVerify(vapid.pair.getPublic());
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(signature.verify(decode(parts[2]))).isTrue();
        Map<?, ?> claims =
                json.read(new String(decode(parts[1]), StandardCharsets.UTF_8), Map.class);
        assertThat(claims.get("aud")).isEqualTo("https://push.example.invalid");
        assertThat(claims.get("sub")).isEqualTo("mailto:push@example.invalid");
        assertThat(((Number) claims.get("exp")).longValue())
                .isBetween(
                        Instant.now().getEpochSecond(),
                        Instant.now().plus(Duration.ofHours(24)).getEpochSecond());
        Map<?, ?> decoded =
                json.read(new String(decrypt(encrypted.get()), StandardCharsets.UTF_8), Map.class);
        assertThat(decoded.keySet().stream().map(Object::toString))
                .containsExactlyInAnyOrder("type", "activityType", "sessionId", "sentAt");
        assertThat(decoded.get("type")).isEqualTo("VOICE_PROBLEM");
        assertThat(decoded.get("sessionId")).isEqualTo(payload.sessionId().toString());
    }

    @Test
    void fakeHttpStatusesAreClassifiedWithoutHiddenRetries() throws Exception {
        try (ContractStubServer stub = ContractStubServer.start();
                HttpClient http = HttpClient.newHttpClient()) {
            stub.route("POST", "/push")
                    .respond(
                            ContractStubServer.StubResponse.json(201, ""),
                            ContractStubServer.StubResponse.json(404, ""),
                            ContractStubServer.StubResponse.json(410, ""),
                            ContractStubServer.StubResponse.json(503, ""),
                            ContractStubServer.StubResponse.json(302, ""));
            var sender =
                    sender(
                            (endpoint, headers, bytes) -> {
                                try {
                                    var request =
                                            HttpRequest.newBuilder(stub.baseUrl().resolve("/push"))
                                                    .POST(
                                                            HttpRequest.BodyPublishers.ofByteArray(
                                                                    bytes))
                                                    .timeout(Duration.ofSeconds(2));
                                    headers.forEach(request::header);
                                    return PushResponse.of(
                                            http.send(
                                                            request.build(),
                                                            HttpResponse.BodyHandlers.discarding())
                                                    .statusCode());
                                } catch (Exception e) {
                                    throw new PushDeliveryException("Test transport failure", e);
                                }
                            });
            assertThat(sender.send(target(), payload, "event_topic").status())
                    .isEqualTo(DeliveryOutcome.Status.ACCEPTED);
            assertThat(sender.send(target(), payload, "event_topic").status())
                    .isEqualTo(DeliveryOutcome.Status.EXPIRED);
            assertThat(sender.send(target(), payload, "event_topic").status())
                    .isEqualTo(DeliveryOutcome.Status.EXPIRED);
            assertThat(sender.send(target(), payload, "event_topic").status())
                    .isEqualTo(DeliveryOutcome.Status.RETRYABLE_FAILURE);
            assertThat(sender.send(target(), payload, "event_topic").status())
                    .isEqualTo(DeliveryOutcome.Status.RETRYABLE_FAILURE);
            assertThat(stub.requests()).hasSize(5);
        }
    }

    @Test
    void failureNeverDisclosesEndpointOrKeys() {
        var sender =
                sender(
                        (endpoint, headers, bytes) -> {
                            throw new PushDeliveryException("sensitive " + endpoint);
                        });
        var outcome = sender.send(target(), payload, "event_topic");
        assertThat(outcome.status()).isEqualTo(DeliveryOutcome.Status.RETRYABLE_FAILURE);
        assertThat(outcome.toString()).doesNotContain("capability", browser.auth, vapid.privateKey);
        assertThat(target().toString())
                .doesNotContain("capability", browser.publicKey, browser.auth);
    }

    @Test
    void allFourEventTypesUseZeroTtlForImmediateDeliveryOnly() {
        AtomicReference<String> ttl = new AtomicReference<>();
        var sender =
                sender(
                        (endpoint, headers, bytes) -> {
                            ttl.set(headers.get("TTL"));
                            return PushResponse.of(201);
                        });
        for (PushEventType type : PushEventType.values()) {
            var message =
                    new PushPayload(
                            type, ActivityType.PRACTICE, UUID.randomUUID(), payload.sentAt());
            assertThat(sender.send(target(), message, "event_topic").status())
                    .isEqualTo(DeliveryOutcome.Status.ACCEPTED);
            assertThat(ttl.get()).isEqualTo("0");
        }
    }

    private VapidPushSender sender(PushHttpClient transport) {
        return new VapidPushSender(
                PushVapidSettings.load(PushVapidSettingsTest.environment(vapid)), transport, json);
    }

    private PushTarget target() {
        return PushTarget.validated(
                "https://push.example.invalid/capability", browser.publicKey, browser.auth);
    }

    private static byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private byte[] decrypt(byte[] body) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(body);
        byte[] salt = new byte[16];
        buffer.get(salt);
        buffer.getInt();
        byte[] serverPublic = new byte[Byte.toUnsignedInt(buffer.get())];
        buffer.get(serverPublic);
        byte[] encrypted = new byte[buffer.remaining()];
        buffer.get(encrypted);
        var params = ((ECPublicKey) browser.pair.getPublic()).getParams();
        var point =
                new ECPoint(
                        new BigInteger(1, Arrays.copyOfRange(serverPublic, 1, 33)),
                        new BigInteger(1, Arrays.copyOfRange(serverPublic, 33, 65)));
        var serverKey =
                KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, params));
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(browser.pair.getPrivate());
        agreement.doPhase(serverKey, true);
        byte[] authPrk = hmac(decode(browser.auth), agreement.generateSecret());
        byte[] info =
                concat(
                        "WebPush: info\0".getBytes(StandardCharsets.US_ASCII),
                        decode(browser.publicKey),
                        serverPublic);
        byte[] ikm = expand(authPrk, info, 32);
        byte[] prk = hmac(salt, ikm);
        byte[] key =
                expand(
                        prk,
                        "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII),
                        16);
        byte[] nonce =
                expand(prk, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, nonce));
        byte[] plaintext = cipher.doFinal(encrypted);
        assertThat(plaintext[plaintext.length - 1]).isEqualTo((byte) 2);
        return Arrays.copyOf(plaintext, plaintext.length - 1);
    }

    private static byte[] hmac(byte[] key, byte[] value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value);
    }

    private static byte[] expand(byte[] key, byte[] info, int size) throws Exception {
        return Arrays.copyOf(hmac(key, concat(info, new byte[] {1})), size);
    }

    private static byte[] concat(byte[]... pieces) {
        int length = Arrays.stream(pieces).mapToInt(x -> x.length).sum();
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] piece : pieces) {
            System.arraycopy(piece, 0, out, offset, piece.length);
            offset += piece.length;
        }
        return out;
    }
}
