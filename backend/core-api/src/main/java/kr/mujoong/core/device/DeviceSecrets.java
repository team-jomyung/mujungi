package kr.mujoong.core.device;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;
import kr.mujoong.contract.secret.SecretCompare;
import org.springframework.stereotype.Component;

/**
 * bootstrap 비밀 · 기기 토큰 만들기와 해시 (U1 3.5 Step 19 · BR1.5 · BR3.1 · BR3.3 · NFR9.4 · SD-12).
 *
 * <p>bootstrap 비밀은 128비트, 기기 토큰은 256비트 SecureRandom 값의 base64url(패딩 없음)이다. 서버는 SHA-256 소문자 16진수만 저장하고
 * 원문은 Set-Cookie 응답 한 번에만 있다. 원문은 {@link Secret}으로만 다뤄 문자열 표현 · 로그에 나가지 않는다.
 */
@Component
public class DeviceSecrets {

    /** bootstrap 비밀 바이트 수(128비트). */
    public static final int BOOTSTRAP_SECRET_BYTES = 16;
    /** 기기 토큰 바이트 수(256비트). */
    public static final int DEVICE_TOKEN_BYTES = 32;
    /** 쿠키로 받은 값이 비밀 꼴인가(base64url, 길이 상한). 꼴이 아니면 조회하지 않는다. */
    private static final Pattern SECRET_FORM = Pattern.compile("^[A-Za-z0-9_-]{16,128}$");
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private final RandomGenerator random;

    public DeviceSecrets() {
        this(new SecureRandom());
    }

    /** 시험용 난수원. */
    public DeviceSecrets(RandomGenerator random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    /** 새 bootstrap 비밀(BR1.5). */
    public Secret newBootstrapSecret() {
        return new Secret(randomBase64Url(BOOTSTRAP_SECRET_BYTES));
    }

    /** 새 기기 토큰(BR3.1). */
    public Secret newDeviceToken() {
        return new Secret(randomBase64Url(DEVICE_TOKEN_BYTES));
    }

    /** 저장 형식 해시(SHA-256 소문자 16진수 64자). */
    public static String hash(String rawSecret) {
        return SecretCompare.sha256Hex(Objects.requireNonNull(rawSecret, "rawSecret"));
    }

    /** 쿠키 값이 비밀 꼴인가(아니면 모르는 값으로 본다). */
    public static boolean looksLikeSecret(String cookieValue) {
        return cookieValue != null && SECRET_FORM.matcher(cookieValue).matches();
    }

    private String randomBase64Url(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return BASE64URL.encodeToString(value);
    }

    /**
     * 비밀 원문. 쿠키에 실을 때만 {@link #value()}를 꺼낸다. 문자열 표현은 가린다(BR8.7 · SD-15 2번).
     */
    public record Secret(String value) {

        public Secret {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("비밀이 비어 있다");
            }
        }

        /** 저장 형식 해시. */
        public String hash() {
            return DeviceSecrets.hash(value);
        }

        @Override
        public String toString() {
            return "Secret[***]";
        }
    }
}
