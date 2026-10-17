package kr.mujoong.realtime.guardrail;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 제브 기동 점검 규칙: 모드 × 프로필 × 키 (U13 BR9.1 · BR2.2 · BR2.3, 순수 함수). {@code RealtimeStartupChecks.problems}가 부른다. 돌려주는 것은
 * 문제가 있는 설정 키 · 환경 변수 이름과 사유 열거값뿐이다(값 없음, R-F3).
 *
 * <table>
 *   <caption>모드 × 프로필 × 키</caption>
 *   <tr><th>프로필</th><th>REAL + 키</th><th>REAL, 키 없음</th><th>STUB</th><th>OFF</th></tr>
 *   <tr><td>demo · prod(공개)</td><td>허용</td><td>기동 거부</td><td>기동 거부</td><td>기동 거부</td></tr>
 *   <tr><td>dev · test · 프로필 없음</td><td>허용</td><td>기동 거부</td><td>허용(대역 표시)</td><td>허용(비활성 표시)</td></tr>
 * </table>
 *
 * <p>기본 모드는 공개 프로필 REAL, 그 밖 STUB이다. REAL이면 어느 프로필이든 허용 호스트 안의 주소와 고정 버전 모델이 있어야 한다(주소 규칙의
 * 엄격도는 프로필이 정한다, {@link JevHosts#modeFor}).
 */
public final class GuardrailStartupRules {

    /** 제브 키 환경 변수 이름(R-M9 비밀 이름 목록에 이미 있다). 값은 호출 때마다 환경에서 읽고 보관하지 않는다(BR2.8). */
    public static final String API_KEY_ENV = "JEV_API_KEY";

    /**
     * 점검 입력. 문자열 표현에 키 값을 내지 않는다(키는 있는지 여부만 받는다).
     *
     * @param mode 설정된 모드 문자열(비면 프로필 기본값)
     * @param keyPresent {@value #API_KEY_ENV}가 비어 있지 않은가
     * @param baseUrl 설정된 제브 주소
     * @param model 설정된 모델 이름
     */
    public record Inputs(String mode, boolean keyPresent, String baseUrl, String model) {

        @Override
        public String toString() {
            return "Inputs[mode=" + mode + ", keyPresent=" + keyPresent + "]";
        }
    }

    private GuardrailStartupRules() {
    }

    /** 이 점검 입력이 정하는 모드(모르는 값은 프로필 기본값, 모르는 값 자체는 {@link #problems}가 따로 거부한다). */
    public static GuardrailMode modeOf(Inputs inputs, boolean publicProfile) {
        return GuardrailMode.resolve(inputs.mode(), publicProfile);
    }

    /**
     * 문제가 있는 키 · 변수 이름 목록(값 없음). 비어 있으면 통과.
     *
     * @param publicProfile demo · prod가 활성인가(강제 프로필)
     * @param activeProfiles 주소 규칙의 엄격도를 정하는 활성 프로필
     */
    public static List<String> problems(Inputs inputs, boolean publicProfile, String... activeProfiles) {
        List<String> problems = new ArrayList<>();
        boolean configured = inputs.mode() != null && !inputs.mode().isBlank();
        if (configured && GuardrailMode.parse(inputs.mode()).isEmpty()) {
            problems.add(GuardrailMode.KEY + "(INVALID)");
        }
        GuardrailMode mode = modeOf(inputs, publicProfile);
        if (publicProfile && mode != GuardrailMode.REAL) {
            // 공개 프로필에서 대역 · 비활성은 자동 통과와 같아 앱 기동을 막는다(AC2.3.1)
            problems.add(GuardrailMode.KEY + "(PUBLIC_REQUIRES_REAL)");
            return problems;
        }
        if (mode != GuardrailMode.REAL) {
            return problems;
        }
        if (!inputs.keyPresent()) {
            problems.add(API_KEY_ENV);
        }
        Optional<JevHosts.Problem> host = JevHosts.check(inputs.baseUrl(), JevHosts.modeFor(activeProfiles));
        host.ifPresent(problem -> problems.add(GuardrailProperties.BASE_URL_KEY + "(" + problem + ")"));
        Optional<JevModels.Problem> model = JevModels.problem(inputs.model());
        model.ifPresent(problem -> problems.add(GuardrailProperties.MODEL_KEY + "(" + problem + ")"));
        return problems;
    }
}
