package kr.mujoong.realtime.testing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import kr.mujoong.realtime.guardrail.GuardrailClient;
import kr.mujoong.realtime.guardrail.GuardrailMode;
import kr.mujoong.realtime.guardrail.GuardrailRequest;
import kr.mujoong.realtime.guardrail.GuardrailVerdict;

/**
 * 시험용 제브 대역(U13): 요청마다 약속을 하나 만들고 시험이 손으로 푼다. 실제 제브를 부르지 않는다(R-F1). 요청 본문은 시험에서만 읽는다.
 * 실제 검사가 아니라 테스트 대역이다 — 이 대역이 낸 ALLOW는 실제 제브 성공이 아니다(R-M11).
 */
public final class FakeGuardrailClient implements GuardrailClient {

    /** 시험이 쓰는 정책 판. */
    public static final String POLICY = "jev-guardrail-v1";

    /** 받은 요청과 그 약속. */
    public record Call(GuardrailRequest request, Instant deadline, CompletableFuture<GuardrailVerdict> future) {
    }

    private final List<Call> calls = new ArrayList<>();
    private boolean available = true;
    private RuntimeException throwOnCheck;

    @Override
    public GuardrailMode mode() {
        return GuardrailMode.REAL;
    }

    @Override
    public synchronized boolean available() {
        return available;
    }

    public synchronized void available(boolean value) {
        this.available = value;
    }

    /** 다음 호출부터 {@link #check}가 이 예외를 던진다(포트 규약 위반 — begin 예외 시험용). */
    public synchronized void throwOnCheck(RuntimeException error) {
        this.throwOnCheck = error;
    }

    @Override
    public synchronized CompletionStage<GuardrailVerdict> check(GuardrailRequest request, Instant deadline) {
        if (throwOnCheck != null) {
            throw throwOnCheck;
        }
        Call call = new Call(request, deadline, new CompletableFuture<>());
        calls.add(call);
        return call.future();
    }

    public synchronized List<Call> calls() {
        return List.copyOf(calls);
    }

    public synchronized Call last() {
        return calls.getLast();
    }

    public synchronized int count() {
        return calls.size();
    }

    /** 마지막 요청을 ALLOW로 푼다. */
    public void allow() {
        allow(last());
    }

    public void allow(Call call) {
        call.future().complete(GuardrailVerdict.allow("OK", POLICY, 310L, List.of(), List.of()));
    }

    /** 마지막 요청을 BLOCK으로 푼다. */
    public void block() {
        last().future().complete(GuardrailVerdict.block("UNSAFE", POLICY, 420L, List.of(), List.of()));
    }

    /** 마지막 요청을 REVIEW로 푼다. */
    public void review() {
        last().future().complete(GuardrailVerdict.review("NEEDS_REVIEW", POLICY, 420L, List.of(), List.of()));
    }

    /** 마지막 요청을 실패로 푼다. */
    public void fail(GuardrailVerdict.Failure failure) {
        last().future().complete(GuardrailVerdict.failed(failure, POLICY, 800L));
    }
}
