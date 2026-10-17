package kr.mujoong.realtime.guardrail;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 제브 공급자 주소 규칙 (U13 BR2.2 · Q4 답 A). 허용 호스트는 코드 상수라 설정으로 바꿀 수 없고, {@code contracts/ai-io.md} 10.12의 목록과 시험이
 * 대조한다(Luna {@code LunaHosts} 선례, 코드 공유 없음). 기동 확인과 보내기 직전 확인이 같은 {@link #check(URI, Mode)}를 쓴다.
 *
 * <ul>
 *   <li>{@link Mode#STRICT}: https이고 호스트가 허용 목록 안(demo · prod · 프로필 없음 · 모르는 프로필).
 *   <li>{@link Mode#DEV}: STRICT 규칙 또는 루프백 호스트(dev 프로필, 로컬 가짜 제브).
 *   <li>{@link Mode#TEST}: 루프백 호스트만(test 프로필, 실공급자로 가지 않게 R-F1).
 * </ul>
 *
 * <p>사용자 정보가 붙은 주소 · 질의 · 조각이 있는 주소는 모드와 관계없이 거부한다. 이름 해석 없이 글자로만 판단한다.
 */
public final class JevHosts {

    /** 허용 호스트(contracts/ai-io.md 10.12, 시험이 문서 값과 대조한다). 외부 공급자 호스트라 R-F3의 서비스 도메인이 아니다(요구사항 Q4 답 A). */
    public static final Set<String> ALLOWED_HOSTS = Set.of("api.typesafe.ai");

    private static final Set<String> PUBLIC_PROFILES = Set.of("demo", "prod");

    /** 주소 규칙의 엄격도. */
    public enum Mode {
        STRICT,
        DEV,
        TEST
    }

    /** 거부 사유(값은 담지 않는다). */
    public enum Problem {
        MISSING,
        INVALID,
        HOST_NOT_ALLOWED,
        NOT_LOOPBACK
    }

    private JevHosts() {
    }

    /**
     * 활성 프로필로 모드를 고른다. demo · prod가 있으면 STRICT, 없고 dev가 있으면 DEV, test가 있으면 TEST, 그 밖(프로필 없음 · 모르는
     * 프로필)은 STRICT다.
     */
    public static Mode modeFor(String... activeProfiles) {
        Set<String> profiles = Set.copyOf(Arrays.asList(activeProfiles));
        if (profiles.stream().anyMatch(PUBLIC_PROFILES::contains)) {
            return Mode.STRICT;
        }
        if (profiles.contains("dev")) {
            return Mode.DEV;
        }
        if (profiles.contains("test")) {
            return Mode.TEST;
        }
        return Mode.STRICT;
    }

    /** 설정 문자열 주소를 확인한다. 비었으면 MISSING, 주소 모양이 아니면 INVALID. */
    public static Optional<Problem> check(String url, Mode mode) {
        if (url == null || url.isBlank()) {
            return Optional.of(Problem.MISSING);
        }
        URI parsed;
        try {
            parsed = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            return Optional.of(Problem.INVALID);
        }
        return check(parsed, mode);
    }

    /** 문제가 없으면 빈 값. */
    public static Optional<Problem> check(URI url, Mode mode) {
        if (url == null || url.toString().isBlank()) {
            return Optional.of(Problem.MISSING);
        }
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        String host = url.getHost();
        if (!(scheme.equals("https") || scheme.equals("http")) || host == null || host.isBlank()
                || url.getRawUserInfo() != null || url.getRawQuery() != null || url.getRawFragment() != null) {
            return Optional.of(Problem.INVALID);
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        boolean strictOk = scheme.equals("https") && ALLOWED_HOSTS.contains(normalizedHost);
        return switch (mode) {
            case STRICT -> strictOk ? Optional.empty() : Optional.of(Problem.HOST_NOT_ALLOWED);
            case DEV -> strictOk || isLoopback(normalizedHost) ? Optional.empty() : Optional.of(Problem.HOST_NOT_ALLOWED);
            case TEST -> isLoopback(normalizedHost) ? Optional.empty() : Optional.of(Problem.NOT_LOOPBACK);
        };
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
