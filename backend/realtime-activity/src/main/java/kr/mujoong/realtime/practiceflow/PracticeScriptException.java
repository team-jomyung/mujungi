package kr.mujoong.realtime.practiceflow;

import java.util.Objects;

/**
 * 연습 대본 · 시작 패키지 practice 조각을 읽지 못했다 (U12 BR1.3). 메시지 · 원인에는 사유 코드만 담고 대본 문장 · 목표 이름 · 판정 기준은 넣지
 * 않는다(R-F14). 거절된 대본의 목표는 상태 기계가 '미실시'로 두고 다음으로 간다.
 */
public final class PracticeScriptException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 거절 사유(코드만). */
    public enum Reason {
        /** JSON 모양이 아니거나 필드 형식이 틀렸다. */
        UNPARSABLE,
        /** scriptVersion이 없다 — 새 흐름 대상이 아니다(BR1.4). */
        NO_SCRIPT_VERSION,
        /** 읽을 수 없는 scriptVersion. */
        UNSUPPORTED_VERSION,
        /** scenarioId가 없거나 UUID가 아니다. */
        BAD_SCENARIO_ID,
        /** scenes[]가 없거나 비었다. */
        NO_SCENES,
        /** 장면 필수 6필드(sceneKey · sceneKind · sceneDescription · opportunitySentence · reexplanation · partnerRole) 중 하나가 없다. */
        MISSING_SCENE_FIELD,
        /** sceneKind가 BASE · VARIANT가 아니다. */
        BAD_SCENE_KIND,
        /** 쓸 수 있는 원 장면(BASE)이 없다. */
        NO_BASE_SCENE,
        /** 대본의 validation이 없거나 통과가 아니다(goalIncluded · triggerIncluded · forbiddenFound). */
        VALIDATION_FAILED,
        /** 시작 패키지 practice 조각이 계약 모양이 아니다(목표 없음 · 목표 필수 값 없음). */
        BAD_PACKAGE,
        /** 대본을 받는 내부 호출이 실패했다. */
        REQUEST_FAILED
    }

    private final transient Reason reason;

    public PracticeScriptException(Reason reason) {
        super("연습 대본을 읽지 못했다: " + Objects.requireNonNull(reason, "reason"));
        this.reason = reason;
    }

    public PracticeScriptException(Reason reason, Throwable cause) {
        super("연습 대본을 읽지 못했다: " + Objects.requireNonNull(reason, "reason"), cause);
        this.reason = reason;
    }

    /** 거절 사유 코드. */
    public Reason reason() {
        return reason;
    }
}
