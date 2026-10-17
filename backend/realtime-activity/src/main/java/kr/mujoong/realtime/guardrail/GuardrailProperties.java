package kr.mujoong.realtime.guardrail;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 제브 설정 {@code mujoong.guardrail.jev.*} (U13 BR1.3 · BR2.2 · BR2.3). 값은 모두 환경 변수 자리표시자로 받고 저장소에는 이름만 둔다(R-F3).
 * 시간 값이 아니다 — 검사 시한은 {@code mujoong.timing.guardrail.jev-budget}(R-F7). Luna 설정({@code LunaProperties})과 같이 별도 레코드다.
 *
 * <p>값은 가공하지 않는다(빈 문자열도 그대로). 해석은 사용처가 한다: 모드는 {@link GuardrailMode#parse}, 주소는 {@code JevHosts.check},
 * 모델은 {@code JevModels.problem}.
 *
 * @param mode {@code REAL | STUB | OFF}. 비면 프로필 기본값(공개 프로필 REAL, 그 밖 STUB)
 * @param baseUrl 제브 주소(env {@code JEV_BASE_URL}). 호스트는 {@code JevHosts.ALLOWED_HOSTS} 안이어야 한다
 * @param model 고정 모델 버전(env {@code JEV_MODEL}). 이동 별칭({@code -latest})은 거부한다
 */
@ConfigurationProperties(prefix = "mujoong.guardrail.jev")
public record GuardrailProperties(String mode, String baseUrl, String model) {

    public GuardrailProperties {
        mode = mode == null ? "" : mode;
        baseUrl = baseUrl == null ? "" : baseUrl;
        model = model == null ? "" : model;
    }
}
