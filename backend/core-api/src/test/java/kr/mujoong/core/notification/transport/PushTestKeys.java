package kr.mujoong.core.notification.transport;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/** Fresh cryptographic material per test, never persisted or printed. */
final class PushTestKeys {
    final KeyPair pair;
    final String publicKey;
    final String privateKey;
    final String auth;

    PushTestKeys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            pair = generator.generateKeyPair();
            ECPublicKey key = (ECPublicKey) pair.getPublic();
            byte[] raw = new byte[65];
            raw[0] = 4;
            System.arraycopy(unsigned(key.getW().getAffineX()), 0, raw, 1, 32);
            System.arraycopy(unsigned(key.getW().getAffineY()), 0, raw, 33, 32);
            publicKey = encode(raw);
            privateKey = encode(unsigned(((ECPrivateKey) pair.getPrivate()).getS()));
            byte[] secret = new byte[16];
            new SecureRandom().nextBytes(secret);
            auth = encode(secret);
        } catch (Exception failure) {
            throw new IllegalStateException("Test key generation failed");
        }
    }

    static byte[] unsigned(BigInteger value) {
        byte[] encoded = value.toByteArray();
        byte[] result = new byte[32];
        int size = Math.min(encoded.length, result.length);
        System.arraycopy(encoded, encoded.length - size, result, result.length - size, size);
        return result;
    }

    static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
