package kr.mujoong.realtime.guardrail;

import java.time.Duration;
import java.util.Objects;

/**
 * 세션 실행기가 제브 단계를 지날 때 쓰는 장치 묶음 (U13 BR1.6 · BR7.4). {@link #DISABLED}는 '검사 없음'이다: 제브 단계를 건너뛰고 지금 흐름이 그대로
 * 돈다(U1 ~ U11 시험이 쓰는 기본값). 의미는 모드 {@code OFF}와 같지만 <b>자동 ALLOW인 REAL이 아니라</b> 모드가 {@link GuardrailMode#OFF}로 표시된
 * 비실검사다(편차 U13-D7). {@code SessionExecutor.Services} 생성자 오버로드를 늘리지 않으려고 장치를 이 기록 하나로 묶는다(JudgmentSupport 선례).
 *
 * @param mode 앱 기동 때 정해진 모드
 * @param client 제브 포트({@code mode}와 같은 모드여야 한다)
 * @param recorder 검사 기록기(없으면 기록하지 않는다. {@code OFF}는 언제나 없다)
 * @param budget 검사 시한({@code mujoong.timing.guardrail.jev-budget}, 800ms). {@code OFF}에서는 쓰이지 않아 0이다(시간 값은 설정 키에서만 읽는다, R-F7)
 * @param policyVersion 세션에 고정되는 정책 판(요청의 {@code policyVersion}). 기동 설정 빈은 정책 리소스의 판으로 바꿔 넣는다({@link #withPolicyVersion})
 */
public record GuardrailSupport(GuardrailMode mode, GuardrailClient client, GuardrailEventRecorder recorder, Duration budget,
        String policyVersion) {

    /** U8 정책 리소스({@code guardrail/policy-v1.json})의 판. 장치를 직접 만드는 시험의 기본값이다. */
    public static final String DEFAULT_POLICY_VERSION = "jev-guardrail-v1";

    /** 검사 없음(= OFF). 기존 생성 경로의 기본값. */
    public static final GuardrailSupport DISABLED = new GuardrailSupport(GuardrailMode.OFF,
            new UnavailableGuardrailClient(GuardrailMode.OFF), null, Duration.ZERO);

    public GuardrailSupport {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(policyVersion, "policyVersion");
        if (client.mode() != mode) {
            throw new IllegalArgumentException("클라이언트의 모드가 장치 모드와 다르다");
        }
        if (mode == GuardrailMode.OFF && recorder != null) {
            throw new IllegalArgumentException("OFF 모드는 검사 기록을 남기지 않는다");
        }
        if (mode != GuardrailMode.OFF && (budget.isNegative() || budget.isZero())) {
            throw new IllegalArgumentException("검사 시한은 양수여야 한다");
        }
        if (policyVersion.isBlank() || policyVersion.length() > GuardrailEventRecord.POLICY_VERSION_MAX) {
            throw new IllegalArgumentException("정책 판 길이가 올바르지 않다");
        }
    }

    /** 정책 판은 기본값({@value #DEFAULT_POLICY_VERSION})이다. */
    public GuardrailSupport(GuardrailMode mode, GuardrailClient client, GuardrailEventRecorder recorder, Duration budget) {
        this(mode, client, recorder, budget, DEFAULT_POLICY_VERSION);
    }

    /** 정책 판만 바꾼 사본. */
    public GuardrailSupport withPolicyVersion(String version) {
        return new GuardrailSupport(mode, client, recorder, budget, version);
    }

    /** 제브 단계를 지나는가(OFF가 아니다). */
    public boolean enabled() {
        return mode != GuardrailMode.OFF;
    }
}
