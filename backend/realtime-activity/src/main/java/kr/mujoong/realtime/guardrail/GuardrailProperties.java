package kr.mujoong.realtime.guardrail;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 제브 설정 {@code mujoong.guardrail.jev.*} (U13 BR1.3 · BR2.2 · BR2.3, 선 형식 정정 FR3). 값은 모두 환경 변수 자리표시자로 받고 저장소에는 이름만 둔다(R-F3).
 * 시간 값이 아니다 — 검사 시한은 {@code mujoong.timing.guardrail.jev-budget}(R-F7). Luna 설정({@code LunaProperties})과 같이 별도 레코드다.
 *
 * <p>문자열 값은 가공하지 않는다(빈 문자열도 그대로). 해석은 사용처가 한다: 모드는 {@link GuardrailMode#parse}, 주소는 {@code JevHosts.check},
 * 모델은 {@code JevModels.problem}, 문턱은 {@link #validThreshold}.
 *
 * @param mode {@code REAL | STUB | OFF}. 비면 프로필 기본값(공개 프로필 REAL, 그 밖 STUB)
 * @param baseUrl 제브 주소(env {@code JEV_BASE_URL}). 호스트는 {@code JevHosts.ALLOWED_HOSTS} 안이어야 한다
 * @param model 고정 모델 버전(env {@code JEV_MODEL}). 이동 별칭({@code -latest})은 거부한다
 * @param passThreshold 통과 문턱(env {@code JEV_PASS_THRESHOLD}, 허용 {@code 0 < x <= 1}). {@code PASS} 확률이 이 값 이상이고 1순위일 때만 자동
 *     통과한다. <b>임시 시험값이다 — AI 성능 시험으로 확정한다</b>(선 형식 정정 D6, 값은 {@code application.yml}에만 있다)
 * @param sendChildName 제브 요청 후보 문장 · 맥락의 아이 이름을 '아이'로 바꾸지 않고 원문으로 보내는가(env {@code JEV_SEND_CHILD_NAME}, 기본
 *     {@code false}). 욕설 이름을 제브가 보게 하려는 스위치다(JIRA-266). <b>R-F15 확인 기록(제브의 아동 이름 전달 허용 범위) 전에는 켜지 않는다</b>
 *     (contracts/ai-io.md 10.2 · infra/RUNBOOK.md). 다른 외부 AI 경로(Luna 등)는 이 값과 관계없이 이름을 바꾼다
 */
@ConfigurationProperties(prefix = "mujoong.guardrail.jev")
public record GuardrailProperties(String mode, String baseUrl, String model, double passThreshold, boolean sendChildName) {

    /** 주소 설정 키. */
    public static final String BASE_URL_KEY = "mujoong.guardrail.jev.base-url";
    /** 모델 설정 키. */
    public static final String MODEL_KEY = "mujoong.guardrail.jev.model";
    /** 통과 문턱 설정 키. */
    public static final String PASS_THRESHOLD_KEY = "mujoong.guardrail.jev.pass-threshold";
    /** 아이 이름 원문 전달 스위치 키(JIRA-266). */
    public static final String SEND_CHILD_NAME_KEY = "mujoong.guardrail.jev.send-child-name";

    public GuardrailProperties {
        mode = mode == null ? "" : mode;
        baseUrl = baseUrl == null ? "" : baseUrl;
        model = model == null ? "" : model;
    }

    /** 문턱이 허용 범위({@code 0 < x <= 1}, 유한한 수)인가. NaN · 무한대 · 0 이하 · 1 초과는 거짓이다. */
    public static boolean validThreshold(double value) {
        return Double.isFinite(value) && value > 0 && value <= 1;
    }
}
