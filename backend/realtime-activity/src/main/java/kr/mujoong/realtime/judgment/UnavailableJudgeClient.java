package kr.mujoong.realtime.judgment;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 공급자를 부르지 않는 보류 판정기 (U11 BR1.6). 키가 없거나 스위치 {@code mujoong.luna.enabled}가 꺼져 Luna 호출부 빈이 없을 때 쓴다.
 * {@link #available()}이 거짓이라 실행부는 턴을 열지 않는다. 그래도 {@link #judge}가 불리면 NO_PROVIDER 보류로 완료한다.
 */
public final class UnavailableJudgeClient implements JudgeClient {

    private final String promptVersion;

    public UnavailableJudgeClient(String promptVersion) {
        this.promptVersion = Objects.requireNonNull(promptVersion, "promptVersion");
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public CompletionStage<JudgeOutcome> judge(JudgeRequest request, UUID turnId) {
        return CompletableFuture.completedFuture(JudgeOutcome.abstained(AbstainReason.NO_PROVIDER, Duration.ZERO, null, promptVersion));
    }
}
