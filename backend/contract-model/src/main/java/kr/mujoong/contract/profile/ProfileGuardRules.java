package kr.mujoong.contract.profile;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 프로필 조합 가드 규칙(SD-14 표 1 ~ 13 · A2 · A6 · NFR9.11 · BR8.3). 두 앱이 같은 규칙을 쓰도록 규칙 표 상수만 여기 둔다 — 가드 구현
 * (환경 후처리기)은 각 앱에 따로 있고 Boot 설정은 공유하지 않는다(tech-stack · SD-14 '두 앱').
 *
 * <p>환경 프로필은 dev · demo · prod 중 하나다. 부가 프로필은 load-test(A6)와 test(CI = 시험 프로필, J94)다.
 * load-test는 프로필 또는 키 {@code mujoong.load-test.enabled=true}로 켜진 것으로 본다(SD-14 표 9 · reliability-design S1).
 * 거부하면 앱은 웹 서버가 뜨기 전에 비0으로 끝나고, 거부 표현은 충돌한 프로필 이름과 키 이름만 담는다(값 없음).
 *
 * <p>표에 없는 조합: 환경 프로필이 없는 경우(예: Gradle 시험의 {@code test} 단독, 프로필 없음)는 허용하되 시험 로그인은 열리지 않는다
 * (dev가 아니므로). 이 해석은 J 후보로 code-summary에 남긴다.
 */
public final class ProfileGuardRules {

    public static final String DEV = "dev";
    public static final String DEMO = "demo";
    public static final String PROD = "prod";
    public static final String LOAD_TEST = "load-test";
    public static final String TEST = "test";

    /** 시험 로그인 스위치 키(기본 false, 프로필 파일에 true를 쓰지 않는다, SD-14). */
    public static final String DEV_LOGIN_ENABLED_KEY = "mujoong.dev-login.enabled";
    /** 부하 시험 스위치 키(A6). */
    public static final String LOAD_TEST_ENABLED_KEY = "mujoong.load-test.enabled";

    private static final Set<String> ENVIRONMENT_PROFILES = Set.of(DEV, DEMO, PROD);

    private ProfileGuardRules() {
    }

    /** 가드 입력. 프로필 이름은 대소문자를 가리지 않고 앞뒤 공백을 무시한다. */
    public record Input(Set<String> activeProfiles, boolean devLoginEnabled, boolean loadTestEnabled) {

        public Input {
            Set<String> normalized = new TreeSet<>();
            if (activeProfiles != null) {
                for (String profile : activeProfiles) {
                    if (profile != null && !profile.isBlank()) {
                        normalized.add(profile.trim().toLowerCase(java.util.Locale.ROOT));
                    }
                }
            }
            activeProfiles = Set.copyOf(normalized);
        }

        /** 편의 생성. */
        public static Input of(Collection<String> profiles, boolean devLoginEnabled, boolean loadTestEnabled) {
            return new Input(profiles == null ? Set.of() : Set.copyOf(profiles), devLoginEnabled, loadTestEnabled);
        }

        boolean has(String profile) {
            return activeProfiles.contains(profile);
        }

        boolean publicEnvironment() {
            return has(DEMO) || has(PROD);
        }

        boolean loadTest() {
            return has(LOAD_TEST) || loadTestEnabled;
        }
    }

    /**
     * 판정.
     *
     * @param allowed 기동 허용 여부
     * @param row SD-14 표의 행 번호(1 ~ 13). 표에 없는 허용 조합은 0
     * @param conflicts 거부 사유가 된 프로필 이름 · 키 이름(값 없음). 허용이면 빈 목록
     * @param devLoginIssuable {@code POST /api/dev/login}이 시드 세션을 발급하는가(행 2 · 13의 enabled)
     */
    public record Verdict(boolean allowed, int row, List<String> conflicts, boolean devLoginIssuable) {

        public Verdict {
            conflicts = List.copyOf(conflicts);
        }

        /** 거부 한 줄(프로필 · 키 이름만). 허용이면 빈 문자열. */
        public String describe() {
            if (allowed) {
                return "";
            }
            return "프로필 조합 거부(SD-14 표 " + row + "): " + String.join(", ", conflicts);
        }
    }

    /** SD-14 표를 적용한다. 첫 해당 행이 이긴다. */
    public static Verdict evaluate(Input input) {
        List<String> environments = input.activeProfiles().stream().filter(ENVIRONMENT_PROFILES::contains).sorted().toList();
        if (environments.size() > 2) {
            return reject(11, environments);
        }
        if (input.has(DEMO) && input.has(DEV)) {
            return reject(5, List.of(DEMO, DEV));
        }
        if (input.has(PROD) && input.has(DEV)) {
            return reject(6, List.of(PROD, DEV));
        }
        if (environments.size() > 1) {
            return reject(11, environments);
        }
        if (input.publicEnvironment() && input.devLoginEnabled()) {
            return reject(input.has(DEMO) ? 7 : 8, List.of(environments.getFirst(), DEV_LOGIN_ENABLED_KEY));
        }
        if (input.publicEnvironment() && input.loadTest()) {
            return reject(9, withLoadTestCause(environments.getFirst(), input));
        }
        if (input.publicEnvironment() && input.has(TEST)) {
            return reject(10, List.of(environments.getFirst(), TEST));
        }
        if (input.loadTest() && !input.has(DEV)) {
            return reject(12, withLoadTestCause(null, input));
        }
        if (input.has(DEV)) {
            int row = (input.loadTest() || input.has(TEST)) ? 13 : (input.devLoginEnabled() ? 2 : 1);
            return new Verdict(true, row, List.of(), input.devLoginEnabled());
        }
        if (input.has(DEMO)) {
            return new Verdict(true, 3, List.of(), false);
        }
        if (input.has(PROD)) {
            return new Verdict(true, 4, List.of(), false);
        }
        return new Verdict(true, 0, List.of(), false);
    }

    /**
     * 요청 때 이중 확인(SD-14 '요청 때 이중 확인'): demo 또는 prod가 활성이면 dev 로그인을 열지 않는다. 가드가 우회된 경우의 마지막 선이다.
     */
    public static boolean isDevLoginAllowedAtRequest(Set<String> activeProfiles, boolean devLoginEnabled) {
        Input input = new Input(activeProfiles, devLoginEnabled, false);
        return input.has(DEV) && devLoginEnabled && !input.publicEnvironment();
    }

    /**
     * 차단 사건(SECURITY_DEV_LOGIN_BLOCKED)의 profile attrs 값: 활성 환경 프로필의 대문자 이름(DEV · DEMO · PROD). 없으면 빈 값.
     */
    public static Optional<String> environmentProfile(Set<String> activeProfiles) {
        Input input = new Input(activeProfiles, false, false);
        return input.activeProfiles().stream()
                .filter(ENVIRONMENT_PROFILES::contains)
                .sorted()
                .findFirst()
                .map(profile -> profile.toUpperCase(java.util.Locale.ROOT));
    }

    private static List<String> withLoadTestCause(String environment, Input input) {
        Set<String> causes = new LinkedHashSet<>();
        if (environment != null) {
            causes.add(environment);
        }
        if (input.has(LOAD_TEST)) {
            causes.add(LOAD_TEST);
        }
        if (input.loadTestEnabled()) {
            causes.add(LOAD_TEST_ENABLED_KEY);
        }
        return new ArrayList<>(causes);
    }

    private static Verdict reject(int row, List<String> conflicts) {
        return new Verdict(false, row, conflicts, false);
    }

    /** 기동 기대. */
    public enum Startup {
        ALLOW,
        REJECT
    }

    /** {@code POST /api/dev/login} 기대. */
    public enum DevLogin {
        /** 시드 보호자 세션 발급. */
        ISSUE,
        /** 404 + SECURITY_DEV_LOGIN_BLOCKED. */
        NOT_FOUND,
        /** 기동하지 않으므로 해당 없음. */
        NONE
    }

    /**
     * SD-14 표 한 칸(매개변수화 시험의 공통 데이터 소스, Step 27 · 29). '무엇이든' 칸은 대표 값 둘로 펼쳤다.
     *
     * @param row 표 행 번호
     */
    public record Combination(
            int row, Set<String> profiles, boolean devLoginEnabled, boolean loadTestEnabled, Startup startup, DevLogin devLogin) {

        public Combination {
            profiles = Set.copyOf(profiles);
        }

        /** 이 칸의 가드 입력. */
        public Input input() {
            return new Input(profiles, devLoginEnabled, loadTestEnabled);
        }

        @Override
        public String toString() {
            return "row " + row + " " + new TreeSet<>(profiles) + " dev-login=" + devLoginEnabled + " load-test.enabled="
                    + loadTestEnabled;
        }
    }

    /** SD-14 표 1 ~ 13(두 앱이 같은 조합을 거부하는지 보는 계약 시험의 데이터). */
    public static final List<Combination> TABLE = List.of(
            new Combination(1, Set.of(DEV), false, false, Startup.ALLOW, DevLogin.NOT_FOUND),
            new Combination(2, Set.of(DEV), true, false, Startup.ALLOW, DevLogin.ISSUE),
            new Combination(3, Set.of(DEMO), false, false, Startup.ALLOW, DevLogin.NOT_FOUND),
            new Combination(4, Set.of(PROD), false, false, Startup.ALLOW, DevLogin.NOT_FOUND),
            new Combination(5, Set.of(DEMO, DEV), false, false, Startup.REJECT, DevLogin.NONE),
            new Combination(5, Set.of(DEMO, DEV), true, false, Startup.REJECT, DevLogin.NONE),
            new Combination(6, Set.of(PROD, DEV), false, false, Startup.REJECT, DevLogin.NONE),
            new Combination(6, Set.of(PROD, DEV), true, false, Startup.REJECT, DevLogin.NONE),
            new Combination(7, Set.of(DEMO), true, false, Startup.REJECT, DevLogin.NONE),
            new Combination(8, Set.of(PROD), true, false, Startup.REJECT, DevLogin.NONE),
            new Combination(9, Set.of(DEMO, LOAD_TEST), false, false, Startup.REJECT, DevLogin.NONE),
            new Combination(9, Set.of(PROD, LOAD_TEST), false, false, Startup.REJECT, DevLogin.NONE),
            new Combination(9, Set.of(DEMO), false, true, Startup.REJECT, DevLogin.NONE),
            new Combination(9, Set.of(PROD), false, true, Startup.REJECT, DevLogin.NONE),
            new Combination(10, Set.of(DEMO, TEST), false, false, Startup.REJECT, DevLogin.NONE),
            new Combination(10, Set.of(PROD, TEST), false, false, Startup.REJECT, DevLogin.NONE),
            new Combination(11, Set.of(DEMO, PROD), false, false, Startup.REJECT, DevLogin.NONE),
            new Combination(11, Set.of(DEV, DEMO, PROD), false, false, Startup.REJECT, DevLogin.NONE),
            new Combination(12, Set.of(LOAD_TEST), false, false, Startup.REJECT, DevLogin.NONE),
            new Combination(12, Set.of(LOAD_TEST), true, true, Startup.REJECT, DevLogin.NONE),
            new Combination(13, Set.of(DEV, LOAD_TEST), false, true, Startup.ALLOW, DevLogin.NOT_FOUND),
            new Combination(13, Set.of(DEV, TEST), false, false, Startup.ALLOW, DevLogin.NOT_FOUND),
            new Combination(13, Set.of(DEV, TEST), true, false, Startup.ALLOW, DevLogin.ISSUE));
}
