package kr.mujoong.core.startup;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import kr.mujoong.contract.log.OwnerHasher;
import kr.mujoong.core.config.CoreProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Core 필수 비밀 기동 검사 (U1 3.5 Step 21 · RD-12 S1 · SD-12 · J124 · NFR9.10).
 *
 * <p>코드에 기본 비밀이 없고, 필수 비밀이 없거나 형식이 틀리면 기동을 거부한다(빈 생성 단계 = 웹 서버가 포트를 열기 전, 종료 코드 1).
 *
 * <ul>
 *   <li>{@code INTERNAL_SERVICE_TOKEN}: 모든 프로필에서 필수. base64url 43자 이상(256비트 하한, J124).
 *   <li>{@code MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET}: demo · prod에서 필수, 값이 있으면 어느 프로필이든 32바이트 이상(OD-4). dev · test는 비면
 *       프로세스마다 임시 값(OwnerHashConfig).
 *   <li>{@code GOOGLE_OAUTH_CLIENT_SECRET}: U2 전에는 비워 둔다(검사하지 않는다).
 * </ul>
 *
 * <p>거부 문구에는 환경 변수 이름만 쓰고 값은 쓰지 않는다. 로그 변환(APP_ERROR)이 메시지를 버리므로 기동 실패 사유는 표준 오류에 한 줄로도
 * 남긴다(컨테이너 로그가 증거).
 */
@Component
public class RequiredSecretsCheck {

    /** 내부 토큰 환경 변수 이름. */
    public static final String INTERNAL_TOKEN = "INTERNAL_SERVICE_TOKEN";
    /** ownerHash 비밀 환경 변수 이름. */
    public static final String OWNER_HASH_SECRET = "MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET";
    /** 내부 토큰 최소 길이(base64url 43자 = 256비트). */
    public static final int MIN_INTERNAL_TOKEN_CHARS = 43;

    private static final Pattern BASE64URL = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final Set<String> PUBLIC_PROFILES = Set.of("demo", "prod");

    public RequiredSecretsCheck(Environment environment, CoreProperties properties) {
        List<String> problems = problems(environment.getProperty(INTERNAL_TOKEN),
                properties.observability().ownerHashSecret(), environment.getActiveProfiles());
        if (!problems.isEmpty()) {
            String line = "필수 비밀이 없거나 형식이 틀렸다(값 미출력): " + String.join(", ", problems);
            StartupFailureLine.print("RequiredSecretsMissing", line);
            throw new IllegalStateException(line);
        }
    }

    /**
     * 문제가 있는 환경 변수 이름 목록(값 없음). 비어 있으면 통과.
     */
    public static List<String> problems(String internalToken, String ownerHashSecret, String... activeProfiles) {
        List<String> problems = new ArrayList<>();
        if (!isValidInternalToken(internalToken)) {
            problems.add(INTERNAL_TOKEN);
        }
        boolean publicEnvironment = Arrays.stream(activeProfiles).anyMatch(PUBLIC_PROFILES::contains);
        boolean ownerSecretBlank = ownerHashSecret == null || ownerHashSecret.isBlank();
        if ((ownerSecretBlank && publicEnvironment)
                || (!ownerSecretBlank
                        && ownerHashSecret.getBytes(StandardCharsets.UTF_8).length < OwnerHasher.MIN_SECRET_BYTES)) {
            problems.add(OWNER_HASH_SECRET);
        }
        return problems;
    }

    /** 내부 토큰 형식(base64url 43자 이상)인가. */
    public static boolean isValidInternalToken(String token) {
        return token != null && token.length() >= MIN_INTERNAL_TOKEN_CHARS && BASE64URL.matcher(token).matches();
    }
}
