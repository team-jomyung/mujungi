package kr.mujoong.core.knowledge;

import java.time.Instant;
import java.util.Objects;

/** 자유대화 목표 후보에 이 씨앗 태그가 붙은 횟수와 마지막 시각. 원문 · 세션 출처는 담지 않는다(BR2). */
public record SeedObservation(int count, Instant lastObservedAt) {
    public SeedObservation {
        Objects.requireNonNull(lastObservedAt);
        if (count < 1) throw new IllegalArgumentException("Observation count must be positive");
    }
}
