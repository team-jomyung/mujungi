package kr.mujoong.realtime.voice;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 공급자 호스트 허용 목록 (U1 3.5 Step 25 · J109 · J141 · SD-25 2.12 · RD-12 S1b ②).
 *
 * <p>목록은 코드 상수라 설정으로 바꿀 수 없다. 규칙은 기동 점검(S1b)과 연결 스레드({@link ProviderConnector})가 같이 쓴다:
 *
 * <ul>
 *   <li>주소가 없으면 거부(J141 — 실제 공급자 기본값은 demo · prod 설정 파일에만 있다).
 *   <li>ws · wss가 아니거나 호스트가 없으면 거부.
 *   <li>demo · prod: wss이고 호스트가 허용 목록 안이어야 한다(대역 서버로 향한 구성이 운영에 새지 않게).
 *   <li>test: 루프백 호스트만(실공급자로 가지 않게). 이름 해석 없이 글자로만 판단한다.
 *   <li>그 밖(dev): 형식만 본다.
 * </ul>
 */
public final class ProviderHosts {

    /** 공급자 호스트 허용 목록(BR7.2 · ai-io 2절). */
    public static final Set<String> ALLOWED_HOSTS = Set.of("api.openai.com");

    private static final Set<String> PUBLIC_PROFILES = Set.of("demo", "prod");
    private static final String TEST_PROFILE = "test";

    /** 거부 사유(값은 담지 않는다). */
    public enum Problem {
        MISSING,
        INVALID,
        HOST_NOT_ALLOWED,
        NOT_LOOPBACK
    }

    private ProviderHosts() {
    }

    /** 문제가 없으면 빈 값. */
    public static Optional<Problem> check(URI url, String... activeProfiles) {
        if (url == null || url.toString().isBlank()) {
            return Optional.of(Problem.MISSING);
        }
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        String host = url.getHost();
        if (!(scheme.equals("ws") || scheme.equals("wss")) || host == null || host.isBlank()) {
            return Optional.of(Problem.INVALID);
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        Set<String> profiles = Set.copyOf(Arrays.asList(activeProfiles));
        if (profiles.stream().anyMatch(PUBLIC_PROFILES::contains)) {
            if (!scheme.equals("wss") || !ALLOWED_HOSTS.contains(normalizedHost)) {
                return Optional.of(Problem.HOST_NOT_ALLOWED);
            }
            return Optional.empty();
        }
        if (profiles.contains(TEST_PROFILE) && !isLoopback(normalizedHost)) {
            return Optional.of(Problem.NOT_LOOPBACK);
        }
        return Optional.empty();
    }

    /** 이 주소로 연결해도 되는가. */
    public static boolean isConnectable(URI url, String... activeProfiles) {
        return check(url, activeProfiles).isEmpty();
    }

    /** 루프백 호스트인가(localhost · 127.0.0.0/8 · ::1). 이름 해석을 하지 않는다. */
    static boolean isLoopback(String host) {
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (bare.equals("localhost") || bare.equals("::1") || bare.equals("0:0:0:0:0:0:0:1")) {
            return true;
        }
        String[] parts = bare.split("\\.", -1);
        if (parts.length != 4 || !parts[0].equals("127")) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)
                    || Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }
}
