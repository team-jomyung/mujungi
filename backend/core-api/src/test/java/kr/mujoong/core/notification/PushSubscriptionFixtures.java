package kr.mujoong.core.notification;

import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.UUID;

/** 저장된 비밀 없이 매번 새로운 수신 키와 auth를 만드는 시험 도우미. */
public final class PushSubscriptionFixtures {
    private PushSubscriptionFixtures() {
    }

    public static PushSubscriptionRequest request() {
        return request("https://push.example.test/" + UUID.randomUUID());
    }

    public static PushSubscriptionRequest request(String endpoint) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            ECPublicKey key = (ECPublicKey) generator.generateKeyPair().getPublic();
            byte[] point = new byte[65];
            point[0] = 4;
            coordinate(key.getW().getAffineX().toByteArray(), point, 1);
            coordinate(key.getW().getAffineY().toByteArray(), point, 33);
            byte[] auth = new byte[16];
            new SecureRandom().nextBytes(auth);
            var encoder = Base64.getUrlEncoder().withoutPadding();
            return new PushSubscriptionRequest(endpoint,
                    new PushSubscriptionRequest.Keys(encoder.encodeToString(point), encoder.encodeToString(auth)),
                    "ANDROID_CHROME");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("시험 수신 키를 만들지 못했다");
        }
    }

    private static void coordinate(byte[] source, byte[] target, int offset) {
        int length = Math.min(32, source.length);
        System.arraycopy(source, source.length - length, target, offset + 32 - length, length);
    }
}
