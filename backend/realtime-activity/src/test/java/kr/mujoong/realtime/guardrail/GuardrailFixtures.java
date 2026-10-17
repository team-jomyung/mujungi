package kr.mujoong.realtime.guardrail;

import java.time.Instant;
import java.util.UUID;

/** 제브 검사 기록 시험 공통 값(합성 값만, R-F3). */
final class GuardrailFixtures {

    static final Instant T0 = Instant.parse("2026-10-17T09:00:00Z");
    static final String POLICY = "jev-guardrail-v1";

    private GuardrailFixtures() {
    }

    /** 새 발화가 ALLOW로 끝난 기록 한 건(실제 모드). */
    static GuardrailEventRecord allow(UUID id, UUID guardian, UUID session) {
        return new GuardrailEventRecord(id, guardian, session, GuardrailMode.REAL, GuardrailEventRecord.SourceKind.NEW_UTTERANCE,
                GuardrailEventRecord.Decision.ALLOW, "OK", POLICY, 310, T0);
    }

    /** 막힘 한 건. */
    static GuardrailEventRecord blocked(UUID id, UUID guardian, UUID session, GuardrailEventRecord.Decision decision, String reason) {
        return new GuardrailEventRecord(id, guardian, session, GuardrailMode.REAL, GuardrailEventRecord.SourceKind.NEW_UTTERANCE,
                decision, reason, POLICY, 800, T0);
    }

    /** 승인 원문 재생 생략(제브를 부르지 않음, 지연 0) 한 건. */
    static GuardrailEventRecord skipped(UUID id, UUID guardian, UUID session) {
        return new GuardrailEventRecord(id, guardian, session, GuardrailMode.REAL,
                GuardrailEventRecord.SourceKind.APPROVED_VERBATIM_SKIPPED, GuardrailEventRecord.Decision.ALLOW,
                "APPROVED_VERBATIM", POLICY, 0, T0);
    }
}
