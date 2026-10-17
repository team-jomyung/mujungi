package kr.mujoong.realtime.guardrail;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 제브를 부르지 않는 비활성 구현 (U13 BR1.2 · BR1.6). {@code REAL}인데 실제 어댑터가 없을 때, 또는 {@code OFF}(검사 없음) 모드의 자리 채우개로
 * 쓴다. {@link #available()}이 거짓이고 그래도 {@link #check}가 불리면 {@code UNAVAILABLE} 실패로 완료한다 — <b>자동 {@code ALLOW}가 아니라
 * 막힘</b>이다(판정 포트의 NO_PROVIDER 보류와 다르다, 게이트에서는 기본값이 재생 안 함).
 */
public final class UnavailableGuardrailClient implements GuardrailClient {

    private final GuardrailMode mode;

    /**
     * @param mode {@link GuardrailMode#REAL}(어댑터 없음) 또는 {@link GuardrailMode#OFF}(검사 없음). 대역은 {@link StubGuardrailClient}를 쓴다
     */
    public UnavailableGuardrailClient(GuardrailMode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
        if (mode == GuardrailMode.STUB) {
            throw new IllegalArgumentException("STUB은 대역 구현을 쓴다");
        }
    }

    @Override
    public GuardrailMode mode() {
        return mode;
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public CompletionStage<GuardrailVerdict> check(GuardrailRequest request, Instant deadline) {
        Objects.requireNonNull(request, "request");
        return CompletableFuture.completedFuture(
                GuardrailVerdict.failed(GuardrailVerdict.Failure.UNAVAILABLE, request.policyVersion(), 0L));
    }
}
