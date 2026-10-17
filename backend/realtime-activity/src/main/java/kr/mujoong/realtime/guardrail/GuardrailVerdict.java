package kr.mujoong.realtime.guardrail;

import java.util.List;
import java.util.Objects;

/**
 * 제브 검사 결과의 내부 형식 (U13 FR1.2 · BR1.2 · BR2.6). 제브의 실제 응답은 어댑터 한 곳에서 이 형식으로 바뀐다. 모든 실패(시간 초과 · 전송 오류 ·
 * 잘못된 JSON · 정책 판 불일치 · 어댑터 없음)는 예외가 아니라 {@link Decision#FAILED} 결과로 완료되고, 자동 {@code ALLOW}로 바뀌지 않는다.
 * {@code ALLOW}는 어댑터가 응답을 읽어 decision이 ALLOW이고 판이 같을 때만 만든다. 대역({@code StubGuardrailClient})은 대역 표시가 붙은
 * {@code ALLOW}(reasonCode {@value #STUB_ALLOW})를 만든다(BR1.4).
 *
 * @param decision 판정
 * @param failure {@code decision}이 {@code FAILED}일 때만 있다
 * @param categories 걸린 분류(guardrail-result.schema.json categories 이름). 없으면 빈 목록
 * @param policyFlags 정책 표지. 없으면 빈 목록
 * @param reasonCode {@code ^[A-Z][A-Z0-9_]{0,79}$}. 막힘 사유 코드로 쓴다
 * @param policyVersion 응답이 말한 판(실패면 요청의 판)
 * @param latencyMs 요청을 보낸 뒤 결과를 받기까지(또는 시간 초과로 끊기까지)
 */
public record GuardrailVerdict(Decision decision, Failure failure, List<String> categories, List<String> policyFlags,
        String reasonCode, String policyVersion, long latencyMs) {

    /** 대역이 돌려주는 사유 코드. */
    public static final String STUB_ALLOW = "STUB_ALLOW";

    /** 판정. */
    public enum Decision {
        ALLOW,
        BLOCK,
        REVIEW,
        /** 실패(아래 {@link Failure}가 채워진다). */
        FAILED
    }

    /** 실패 종류. 모두 '막힘'이다(BR3.3). */
    public enum Failure {
        /** 시간 상한({@code jev-budget}) 초과. */
        TIMEOUT,
        /** 전송 오류(연결 · 읽기 · 허용 밖 호스트 · 비정상 상태 코드). */
        TRANSPORT_ERROR,
        /** 응답이 JSON이 아니거나 필수 필드가 없다. */
        INVALID_JSON,
        /** 응답의 정책 판이 요청의 판과 다르다. */
        POLICY_MISMATCH,
        /** REAL인데 어댑터가 없거나 키가 비었다(기동 점검을 통과했어도 실행 중 방어). */
        UNAVAILABLE,
        /** 요청을 만들 수 없었다(후보 문장 길이 초과 등, 부르지 않음). */
        INVALID_REQUEST
    }

    public GuardrailVerdict {
        Objects.requireNonNull(decision, "decision");
        if ((decision == Decision.FAILED) != (failure != null)) {
            throw new IllegalArgumentException("failure는 decision이 FAILED일 때만 있다");
        }
        categories = categories == null ? List.of() : List.copyOf(categories);
        policyFlags = policyFlags == null ? List.of() : List.copyOf(policyFlags);
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (!GuardrailEventRecord.REASON_CODE.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException("reasonCode 형식이 올바르지 않다");
        }
        Objects.requireNonNull(policyVersion, "policyVersion");
        if (latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs는 0 이상이어야 한다");
        }
    }

    /** 허용. */
    public static GuardrailVerdict allow(String reasonCode, String policyVersion, long latencyMs, List<String> categories,
            List<String> policyFlags) {
        return new GuardrailVerdict(Decision.ALLOW, null, categories, policyFlags, reasonCode, policyVersion, latencyMs);
    }

    /** 대역의 허용(대역 표시 사유 코드). */
    public static GuardrailVerdict stubAllow(String policyVersion) {
        return allow(STUB_ALLOW, policyVersion, 0L, List.of(), List.of());
    }

    /** 막음. */
    public static GuardrailVerdict block(String reasonCode, String policyVersion, long latencyMs, List<String> categories,
            List<String> policyFlags) {
        return new GuardrailVerdict(Decision.BLOCK, null, categories, policyFlags, reasonCode, policyVersion, latencyMs);
    }

    /** 사람 검토 필요(재생하지 않는다). */
    public static GuardrailVerdict review(String reasonCode, String policyVersion, long latencyMs, List<String> categories,
            List<String> policyFlags) {
        return new GuardrailVerdict(Decision.REVIEW, null, categories, policyFlags, reasonCode, policyVersion, latencyMs);
    }

    /** 실패. 사유 코드는 실패 종류 이름이다. */
    public static GuardrailVerdict failed(Failure failure, String policyVersion, long latencyMs) {
        Objects.requireNonNull(failure, "failure");
        return new GuardrailVerdict(Decision.FAILED, failure, List.of(), List.of(), failure.name(), policyVersion, latencyMs);
    }

    /** 재생해도 되는 결과인가(유효성 검사표는 별개다, BR4.2). */
    public boolean isAllow() {
        return decision == Decision.ALLOW;
    }

    /** 이 결과를 {@code guardrail_event}에 남길 때의 판정(BR8.1). 시간 초과는 TIMEOUT, 그 밖의 실패는 ERROR다. */
    public GuardrailEventRecord.Decision eventDecision() {
        return switch (decision) {
            case ALLOW -> GuardrailEventRecord.Decision.ALLOW;
            case BLOCK -> GuardrailEventRecord.Decision.BLOCK;
            case REVIEW -> GuardrailEventRecord.Decision.REVIEW;
            case FAILED -> failure == Failure.TIMEOUT ? GuardrailEventRecord.Decision.TIMEOUT : GuardrailEventRecord.Decision.ERROR;
        };
    }
}
