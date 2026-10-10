package kr.mujoong.core.startup;

import java.util.Arrays;
import java.util.Locale;
import kr.mujoong.contract.profile.ProfileGuardRules;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

/**
 * Core 프로필 조합 가드 (U1 3.5 Step 21 · SD-14 표 1 ~ 13 · A2 · A6 · NFR9.11 · BR8.3 · RD-12 S1).
 *
 * <p>설정 파일 · 환경 변수 적재(ConfigData) 바로 뒤, 앱 문맥 · 웹 서버를 만들기 전에 활성 프로필 · {@code mujoong.dev-login.enabled} ·
 * {@code mujoong.load-test.enabled}를 {@link ProfileGuardRules}(두 앱 공통 표)로 판정한다. 거부면 표준 오류에 거부 줄(충돌한 프로필 ·
 * 키 이름만, 값 없음)을 쓰고 예외로 기동을 끝낸다(main 스레드 예외 → 종료 코드 1). 등록은 {@code META-INF/spring.factories}의
 * {@code org.springframework.boot.EnvironmentPostProcessor} 키다(Boot 4 판 맞춤).
 */
public class ProfileGuardEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** ConfigData 적재 바로 뒤(프로필 파일 · 환경 변수가 반영된 값을 본다). */
    public static final int ORDER = ConfigDataEnvironmentPostProcessor.ORDER + 1;

    /** 거부 줄의 errorKind. */
    static final String ERROR_KIND = "ProfileGuardRejected";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        ProfileGuardRules.Verdict verdict = evaluate(environment);
        if (!verdict.allowed()) {
            String reason = verdict.describe();
            StartupFailureLine.print(ERROR_KIND, reason);
            throw new ProfileGuardRejectedException(reason);
        }
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    /** 지금 환경의 판정(요청 때 이중 확인 · 시험도 같은 입력을 쓴다). */
    public static ProfileGuardRules.Verdict evaluate(Environment environment) {
        return ProfileGuardRules.evaluate(input(environment));
    }

    /** 가드 입력: 활성 프로필과 두 스위치(문자열 'true'만 켜짐, 대소문자 무시). */
    public static ProfileGuardRules.Input input(Environment environment) {
        return ProfileGuardRules.Input.of(
                Arrays.asList(environment.getActiveProfiles()),
                isTrue(environment.getProperty(ProfileGuardRules.DEV_LOGIN_ENABLED_KEY)),
                isTrue(environment.getProperty(ProfileGuardRules.LOAD_TEST_ENABLED_KEY)));
    }

    private static boolean isTrue(String value) {
        return value != null && value.trim().toLowerCase(Locale.ROOT).equals("true");
    }

    /** 프로필 조합 거부. 메시지는 표 행 번호와 프로필 · 키 이름뿐이다. */
    public static final class ProfileGuardRejectedException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        public ProfileGuardRejectedException(String reason) {
            super(reason);
        }
    }
}
