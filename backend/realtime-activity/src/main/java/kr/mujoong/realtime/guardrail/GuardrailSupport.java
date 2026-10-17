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
 * @param approved 승인 원문 일치를 묻는 좁은 포트(BR7.4). 기동 설정은 콘텐츠 레지스트리를 {@link #withApprovedVerbatim}으로 연결한다. 기본은 {@link ApprovedVerbatimPort#NONE}
 */
public record GuardrailSupport(GuardrailMode mode, GuardrailClient client, GuardrailEventRecorder recorder, Duration budget,
        String policyVersion, ApprovedVerbatimPort approved) {

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
        Objects.requireNonNull(approved, "approved");
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

    /** 승인 원문 포트는 없다({@link ApprovedVerbatimPort#NONE}). */
    public GuardrailSupport(GuardrailMode mode, GuardrailClient client, GuardrailEventRecorder recorder, Duration budget,
            String policyVersion) {
        this(mode, client, recorder, budget, policyVersion, ApprovedVerbatimPort.NONE);
    }

    /** 정책 판은 기본값({@value #DEFAULT_POLICY_VERSION})이다. */
    public GuardrailSupport(GuardrailMode mode, GuardrailClient client, GuardrailEventRecorder recorder, Duration budget) {
        this(mode, client, recorder, budget, DEFAULT_POLICY_VERSION);
    }

    /** 정책 판만 바꾼 사본. */
    public GuardrailSupport withPolicyVersion(String version) {
        return new GuardrailSupport(mode, client, recorder, budget, version, approved);
    }

    /** 승인 원문 포트만 바꾼 사본(BR7.4). */
    public GuardrailSupport withApprovedVerbatim(ApprovedVerbatimPort port) {
        return new GuardrailSupport(mode, client, recorder, budget, policyVersion, port);
    }

    /**
     * 검사 안전 타이머의 지연(검사 상한의 두 배). 포트 규약은 모든 호출이 상한 안에 끝나는 것이라 이 타이머는 규약을 어긴 클라이언트가 협력 객체를
     * 검사 중에 영영 가두는 일을 막는 마지막 장치일 뿐이다(시간 값은 {@code jev-budget} 설정에서만 얻는다, R-F7).
     */
    public Duration safetyDelay() {
        return budget.multipliedBy(SAFETY_FACTOR);
    }

    private static final int SAFETY_FACTOR = 2;

    /** 제브 단계를 지나는가(OFF가 아니다). */
    public boolean enabled() {
        return mode != GuardrailMode.OFF;
    }
}
