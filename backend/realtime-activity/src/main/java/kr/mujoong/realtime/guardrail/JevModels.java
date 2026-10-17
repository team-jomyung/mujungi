package kr.mujoong.realtime.guardrail;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 제브 모델 이름 규칙 (U13 BR2.3). 모델은 설정의 고정 버전이어야 하고 이동 별칭({@code jev-latest} 등 {@code latest}로 끝나는 이름)이면 거부한다.
 * 요청의 모델 이름은 언제나 설정값 그대로다. 사유 열거값만 돌려주고 이름 값은 담지 않는다.
 */
public final class JevModels {

    private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final Pattern MOVING_ALIAS = Pattern.compile("(^|[-_.])latest$");

    /** 거부 사유. */
    public enum Problem {
        MISSING,
        INVALID_FORMAT,
        MOVING_ALIAS
    }

    private JevModels() {
    }

    /** 이름이 모델 이름 형식인가(이동 별칭 여부는 보지 않는다). 응답의 {@code model}을 보고서에 옮기기 전 위생 점검에 쓴다. */
    static boolean wellFormed(String model) {
        return model != null && FORMAT.matcher(model).matches();
    }

    /** 문제가 없으면 빈 값. */
    public static Optional<Problem> problem(String model) {
        if (model == null || model.isBlank()) {
            return Optional.of(Problem.MISSING);
        }
        String name = model.strip();
        if (!FORMAT.matcher(name).matches()) {
            return Optional.of(Problem.INVALID_FORMAT);
        }
        if (MOVING_ALIAS.matcher(name.toLowerCase(Locale.ROOT)).find()) {
            return Optional.of(Problem.MOVING_ALIAS);
        }
        return Optional.empty();
    }
}
