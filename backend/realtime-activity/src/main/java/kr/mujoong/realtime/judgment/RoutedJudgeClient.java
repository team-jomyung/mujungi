package kr.mujoong.realtime.judgment;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * 판정 지점마다 다른 공급자로 보내는 판정기. {@code jevPoints}에 든 지점은 제브 구현으로, 나머지는 기본(Luna) 구현으로 보낸다. 제브 구현을 쓸 수 없으면
 * (키 없음 · 주소 · 모델 문제) 그 지점도 기본 구현으로 간다. 둘 다 못 쓰면 {@link #available()}이 거짓이라 실행부는 판정 턴을 열지 않는다.
 */
public final class RoutedJudgeClient implements JudgeClient {

    private final JudgeClient jev;
    private final JudgeClient fallback;
    private final Set<JudgmentPoint> jevPoints;

    public RoutedJudgeClient(JudgeClient jev, JudgeClient fallback, Set<JudgmentPoint> jevPoints) {
        this.jev = Objects.requireNonNull(jev, "jev");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.jevPoints = Set.copyOf(jevPoints);
    }

    @Override
    public boolean available() {
        return fallback.available() || jev.available();
    }

    @Override
    public CompletionStage<JudgeOutcome> judge(JudgeRequest request, UUID turnId) {
        return (jevPoints.contains(request.point()) && jev.available() ? jev : fallback).judge(request, turnId);
    }
}
