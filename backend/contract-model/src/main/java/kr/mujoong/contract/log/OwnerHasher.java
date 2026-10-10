package kr.mujoong.contract.log;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * ownerHash(OD-4 · NFR9.15): {@code HMAC-SHA-256(비밀, 소유 id의 UTF-8)}의 16진 앞 12자(48비트). 되돌릴 수 없고 두 앱이 같은 비밀을 쓰므로
 * 같은 소유자는 두 앱에서 같은 값이다. 소유 id가 없으면 null이다.
 *
 * <p>비밀은 환경 변수 {@code MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET}로만 받고 32바이트 이상이어야 한다(J124). demo · prod에서 비면
 * 기동 실패이고(S1, 앱의 검사), dev는 {@link #ephemeral()}로 프로세스마다 임시 무작위 값을 쓴다. 비밀 값은 예외 메시지 · 로그에 넣지 않는다.
 */
public final class OwnerHasher {

    /** 비밀 최소 길이(UTF-8 바이트, J124). */
    public static final int MIN_SECRET_BYTES = 32;
    /** 결과 길이(16진 문자). */
    public static final int HASH_HEX_LENGTH = 12;

    private static final String ALGORITHM = "HmacSHA256";
    private static final HexFormat HEX = HexFormat.of();

    private final SecretKeySpec key;

    /**
     * @param secret 32바이트 이상(UTF-8)의 비밀
     * @throws IllegalArgumentException 비었거나 짧을 때(값은 메시지에 넣지 않는다)
     */
    public OwnerHasher(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("ownerHash 비밀이 비어 있다(MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET)");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "ownerHash 비밀이 " + MIN_SECRET_BYTES + "바이트보다 짧다(MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET)");
        }
        this.key = new SecretKeySpec(bytes, ALGORITHM);
    }

    /** 프로세스마다 다른 임시 비밀로 만든다(dev 프로필 · 시험, OD-4). */
    public static OwnerHasher ephemeral() {
        byte[] random = new byte[MIN_SECRET_BYTES];
        new SecureRandom().nextBytes(random);
        return new OwnerHasher(Base64.getUrlEncoder().withoutPadding().encodeToString(random));
    }

    /** 소유 id의 ownerHash. null이면 null. */
    public String hash(String ownerId) {
        if (ownerId == null) {
            return null;
        }
        byte[] digest = newMac().doFinal(ownerId.getBytes(StandardCharsets.UTF_8));
        return HEX.formatHex(digest).substring(0, HASH_HEX_LENGTH);
    }

    /** UUID 소유 id의 ownerHash(문자열 표기 기준). null이면 null. */
    public String hash(UUID ownerId) {
        return ownerId == null ? null : hash(ownerId.toString());
    }

    private Mac newMac() {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac;
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA-256을 초기화하지 못했다", e);
        }
    }

    @Override
    public String toString() {
        return "OwnerHasher[secret=***]";
    }
}
