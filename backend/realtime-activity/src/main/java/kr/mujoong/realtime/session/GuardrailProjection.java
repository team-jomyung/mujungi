package kr.mujoong.realtime.session;

import java.time.Instant;
import java.util.UUID;
import kr.mujoong.contract.status.SessionStatus;

/** 실행기만 발행하고 조회는 한 번 읽는 불변 스냅숏. */
public record GuardrailProjection(SessionGuardrailFacts facts, SessionStatus status, UUID connectionId, Instant goodbyeDeadline) { }
