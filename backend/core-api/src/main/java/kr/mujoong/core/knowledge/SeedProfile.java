package kr.mujoong.core.knowledge;

import java.time.Instant;
import java.util.Objects;

/** 아이 그래프의 씨앗 하나와 그 씨앗에 쌓인 관찰 · 참고 기록. 시각이 없으면 아직 관찰 · 참고되지 않았다. */
public record SeedProfile(KnowledgeSeed seed, int observedCount, Instant lastObservedAt, Instant lastReferencedAt) {
    public SeedProfile {
        Objects.requireNonNull(seed);
        if (observedCount < 0) throw new IllegalArgumentException("Observation count must not be negative");
    }
}
