package kr.mujoong.realtime.luna;

import java.net.URI;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Realtime Luna 호출 설정 {@code mujoong.luna.*} (U9 BR1.5 · SD3 · SD12 · NFR3.4 · NFR3.10). 값은 application.yml에만 있고 코드 기본값은
 * 모델 이름 하나뿐이다(env가 빈 값일 때 BR1.5). 시간 값이 아니다 — 시도 시한은 {@code mujoong.timing.judgment.live-budget}(BR5.1).
 * 진행 중 판정은 Decisions API 한 길뿐이라 끝점 선택 · 출력 한도 · 추론 강도 설정은 없다(FT-1023-d).
 *
 * @param model 요청 모델 이름(env {@code OPENAI_JUDGE_MODEL}, 비면 {@value #DEFAULT_MODEL})
 * @param baseUrl OpenAI API 기준 주소({@code {baseUrl}/decisions}). 프로필 규칙은 {@link LunaHosts}
 * @param maxResponseBytes 응답 본문 바이트 상한(넘으면 LENGTH_EXCEEDED, NFR3.10)
 * @param enabled realtime Luna 스위치(U11 BR12.3, 기본 {@code false}). 시간 값이 아니라 R-M5 대상이 아니다. 키가 있어도 이 값이 false면 꺼진다
 */
@ConfigurationProperties(prefix = "mujoong.luna")
public record LunaProperties(String model, URI baseUrl, int maxResponseBytes, boolean enabled) {

    /** env 모델 이름이 비었을 때 쓰는 이름(BR1.5). */
    public static final String DEFAULT_MODEL = "gpt-6-luna";

    private static final Pattern MODEL_FORMAT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    public LunaProperties {
        model = model == null || model.isBlank() ? DEFAULT_MODEL : model.strip();
        if (!MODEL_FORMAT.matcher(model).matches()) {
            throw new IllegalArgumentException("mujoong.luna.model 형식이 올바르지 않다");
        }
        Objects.requireNonNull(baseUrl, "mujoong.luna.base-url");
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("mujoong.luna.max-response-bytes는 양수여야 한다");
        }
    }
}
