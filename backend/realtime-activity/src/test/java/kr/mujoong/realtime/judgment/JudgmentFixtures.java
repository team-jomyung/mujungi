package kr.mujoong.realtime.judgment;

import java.time.Instant;
import java.util.UUID;

/** 판정 기록 시험 공통 값(합성 값만, R-F3). */
final class JudgmentFixtures {

    static final Instant T0 = Instant.parse("2026-10-17T09:00:00Z");

    private JudgmentFixtures() {
    }

    /** 확정(J6) 기록 한 건. */
    static JudgmentLiveRecord confirmed(UUID id, UUID guardian, UUID session) {
        return new JudgmentLiveRecord(id, guardian, session, JudgmentPoint.J6, JudgmentTrigger.STOP_CONFIRM_CANDIDATE, null, null, 3,
                "judgment-v1", "prompt-v1", "luna-test", JudgmentOutcomeKind.CONFIRMED, null, true, "END_CLEAR", 0.8, "REST", 0.1, 1_200L,
                NextAction.CLOSE, null, T0);
    }

    /** 보류(J1) 기록 한 건. */
    static JudgmentLiveRecord abstained(UUID id, UUID guardian, UUID session, AbstainReason reason) {
        return new JudgmentLiveRecord(id, guardian, session, JudgmentPoint.J1, JudgmentTrigger.JOIN_UNCLEAR, null, null, 1,
                "judgment-v1", "prompt-v1", null, JudgmentOutcomeKind.ABSTAINED, reason, true, null, null, null, null, 4_000L,
                NextAction.REPROMPT_JOIN, "모은 출력 합성 전사", T0);
    }
}
