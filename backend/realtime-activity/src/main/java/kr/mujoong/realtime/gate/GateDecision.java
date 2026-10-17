package kr.mujoong.realtime.gate;

import java.util.Objects;

/**
 * 출력 게이트 판정 (U1 3.5 Step 25 · BR7.4 · SD-17 · PD-3 표 2.3-B · 2.3-C).
 *
 * <p>통과 또는 폐기 사유 하나. 한 구간이 둘 이상에 해당하면 판정 순서(SEGMENT_OVERSIZE → GATE_EMPTY → GATE_PATTERN → NOT_ACTIVE →
 * RECORDER_DEGRADED → OUTBOUND_FULL)에서 처음 걸린 것이 사유다. 지표 · 로그에는 사유 열거값만 쓴다(전사 · 걸린 패턴 없음).
 */
public record GateDecision(boolean passed, DiscardReason reason) {

    private static final GateDecision PASS = new GateDecision(true, null);

    /** 폐기 사유. */
    public enum DiscardReason {
        SEGMENT_OVERSIZE,
        GATE_EMPTY,
        GATE_PATTERN,
        NOT_ACTIVE,
        RECORDER_DEGRADED,
        OUTBOUND_FULL,
        /** 제브가 막음(BLOCK) (U13 BR3.3). */
        GUARDRAIL_BLOCK,
        /** 제브가 사람 검토 필요로 판정(REVIEW) (U13 BR3.3). */
        GUARDRAIL_REVIEW,
        /** 제브 검사 시간 초과 (U13 BR3.3). */
        GUARDRAIL_TIMEOUT,
        /** 제브 전송 오류 · 잘못된 JSON · 정책 판 불일치 · 어댑터 없음 · 요청 거절 (U13 BR3.3). */
        GUARDRAIL_ERROR,
        /** 제브 대기열이 차 새 구간을 폐기 (U13 BR5.3). */
        GUARDRAIL_QUEUE_FULL
    }

    public GateDecision {
        if (passed == (reason != null)) {
            throw new IllegalArgumentException("통과면 사유가 없고 폐기면 사유가 있어야 한다");
        }
    }

    public static GateDecision pass() {
        return PASS;
    }

    public static GateDecision discard(DiscardReason reason) {
        return new GateDecision(false, Objects.requireNonNull(reason, "reason"));
    }
}
