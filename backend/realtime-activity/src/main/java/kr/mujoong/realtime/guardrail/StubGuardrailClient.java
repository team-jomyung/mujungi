package kr.mujoong.realtime.guardrail;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 제브 대역 (U13 BR1.4). 제브를 부르지 않고 즉시 {@code ALLOW}(reasonCode {@value GuardrailVerdict#STUB_ALLOW})로 완료한다. 요청 구성 · 검사표 ·
 * 지연 · {@code guardrail_event} · 지표 등 제브 단계는 실제와 똑같이 지나고, 모든 기록 · 지표 · 로그에 모드 {@link GuardrailMode#STUB}(대역)가
 * 표시된다. 이 통과는 실제 검사 성공이 아니다(R-M11). 공개 프로필(demo · prod) 밖에서만 켜진다(기동 점검). 시험 · CI · 검증 스택이 쓰는 가짜
 * 제브는 이 대역이나 시험이 손으로 푸는 대역이다(R-F1).
 */
public final class StubGuardrailClient implements GuardrailClient {

    @Override
    public GuardrailMode mode() {
        return GuardrailMode.STUB;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public CompletionStage<GuardrailVerdict> check(GuardrailRequest request, Instant deadline) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(deadline, "deadline");
        return CompletableFuture.completedFuture(GuardrailVerdict.stubAllow(request.policyVersion()));
    }
}
