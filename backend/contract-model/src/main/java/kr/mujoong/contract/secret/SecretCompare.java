package kr.mujoong.contract.secret;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 비밀 비교 · 해시(SD-8 · BR1.5 · BR3.3 · NFR9.4).
 *
 * <p>서버는 bootstrap 비밀 · 기기 토큰의 SHA-256 소문자 16진수만 저장한다(entities.md core_device). 내부 토큰은 받은 값과
 * 기대 값 각각의 SHA-256을 {@link MessageDigest#isEqual}로 비교해 길이 · 내용 차이가 시간에 드러나지 않게 한다.
 * 이 클래스는 입력 값을 예외 메시지 · 로그에 넣지 않는다.
 */
public final class SecretCompare {

    private static final HexFormat HEX = HexFormat.of();
    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    private SecretCompare() {
    }

    /** SHA-256 바이트. */
    public static byte[] sha256(byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("해시할 값이 없다");
        }
        return newDigest().digest(value);
    }

    /** UTF-8 문자열의 SHA-256 바이트. */
    public static byte[] sha256(String value) {
        if (value == null) {
            throw new IllegalArgumentException("해시할 값이 없다");
        }
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    /** UTF-8 문자열의 SHA-256 소문자 16진수 64자(저장 형식). */
    public static String sha256Hex(String value) {
        return HEX.formatHex(sha256(value));
    }

    /** 바이트의 SHA-256 소문자 16진수 64자. */
    public static String sha256Hex(byte[] value) {
        return HEX.formatHex(sha256(value));
    }

    /**
     * 두 비밀이 같은가(상수 시간). 각각의 SHA-256을 비교하므로 길이 차이도 드러나지 않는다. 어느 쪽이든 null이면 거짓.
     */
    public static boolean constantTimeEquals(String received, String expected) {
        if (received == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(sha256(received), sha256(expected));
    }

    /**
     * 원문 비밀의 SHA-256이 저장된 16진수 해시와 같은가(상수 시간). 저장값 형식이 틀리면 거짓.
     */
    public static boolean matchesHash(String rawSecret, String storedSha256Hex) {
        if (rawSecret == null || storedSha256Hex == null || !SHA256_HEX.matcher(storedSha256Hex).matches()) {
            return false;
        }
        return MessageDigest.isEqual(sha256(rawSecret), HEX.parseHex(storedSha256Hex));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK에 SHA-256이 없다", e);
        }
    }
}
