package kr.mujoong.testsupport.secret;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 시험용 비밀을 실행마다 SecureRandom으로 만든다(J127 · NFR9.10 · SD-12). 저장소 · 픽스처에 비밀 값을 두지 않는다.
 * 모든 값은 base64url(패딩 없음)이라 환경 변수 · 파일 · 쿠키에 그대로 쓸 수 있다.
 */
public final class TestSecrets {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private TestSecrets() {
    }

    /** {@code bytes}바이트 난수의 base64url. */
    public static String base64Url(int bytes) {
        if (bytes < 1) {
            throw new IllegalArgumentException("바이트 수는 1 이상이어야 한다");
        }
        byte[] random = new byte[bytes];
        RANDOM.nextBytes(random);
        return BASE64URL.encodeToString(random);
    }

    /** DB 역할 비밀번호(192비트). */
    public static String dbPassword() {
        return base64Url(24);
    }

    /** 내부 토큰 INTERNAL_SERVICE_TOKEN(256비트, 43자, J124 하한). */
    public static String internalServiceToken() {
        return base64Url(32);
    }

    /** ownerHash 비밀(32바이트 난수 → 43자, J124 하한 32바이트 이상). */
    public static String ownerHashSecret() {
        return base64Url(32);
    }

    /** 기기 토큰(256비트, BR3.1). */
    public static String deviceToken() {
        return base64Url(32);
    }

    /** bootstrap 비밀(128비트, BR1.5). */
    public static String bootstrapSecret() {
        return base64Url(16);
    }

    /**
     * DB 비밀번호 다섯 개(POSTGRES_PASSWORD · DB_OWNER_PASSWORD · DB_CORE_PASSWORD · DB_REALTIME_PASSWORD · DB_BACKUP_PASSWORD).
     * {@link #toString()}은 값을 가린다.
     */
    public record DatabasePasswords(String superuser, String owner, String core, String realtime, String backup) {

        /** 새 비밀번호 묶음. */
        public static DatabasePasswords generate() {
            return new DatabasePasswords(dbPassword(), dbPassword(), dbPassword(), dbPassword(), dbPassword());
        }

        @Override
        public String toString() {
            return "DatabasePasswords[***]";
        }
    }
}
