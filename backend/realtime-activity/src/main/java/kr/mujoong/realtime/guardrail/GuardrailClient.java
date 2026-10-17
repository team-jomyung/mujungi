package kr.mujoong.realtime.guardrail;

import java.time.Instant;
import java.util.concurrent.CompletionStage;

/**
 * 제브 검사 포트 (U13 BR1.1 · BR1.2 · BR1.6). 제브는 이 포트로만 부르고 {@code EvaluationProvider} · {@code GateDecision} · {@code JudgeClient}와
 * 섞지 않는다(ADR-006). {@link #check}는 예외를 던지지 않고 모든 실패를 {@link GuardrailVerdict.Decision#FAILED} 결과로 완료한다 — 실패가
 * 자동 {@code ALLOW}가 되는 일은 없다. 결과는 다른 스레드에서 끝나므로 호출자는 실행기 편지함으로만 넘긴다(BR3.1).
 */
public interface GuardrailClient {

    /** 이 클라이언트의 모드. {@code REAL}(실제 어댑터 또는 그 자리를 지키는 비활성 구현) · {@code STUB}(대역) · {@code OFF}(검사 없음 표시). */
    GuardrailMode mode();

    /** 제브를 부를 수 있는가. 거짓이면 {@link #check}는 {@code UNAVAILABLE} 실패로 완료한다. */
    boolean available();

    /**
     * 검사 한 번. 공급자 호출은 최대 한 번이고 재시도하지 않는다(BR2.1).
     *
     * @param deadline 이 시각에 닿으면 시간 초과 실패로 끝낸다(검사표의 {@code requestedAt + jev-budget})
     */
    CompletionStage<GuardrailVerdict> check(GuardrailRequest request, Instant deadline);
}
