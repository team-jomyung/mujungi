package kr.mujoong.core.knowledge;

import java.util.List;
import java.util.HashSet;

/** BR2: 검증 완료된 원천 전체를 한 단위로 전달한다. */
public record SeedCatalog(String version, String digest, List<KnowledgeSeed> seeds) {
    public SeedCatalog {
        seeds = List.copyOf(seeds);
        if (version == null || version.isBlank() || digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid catalog version");
        }
        var ids = new HashSet<String>();
        for (KnowledgeSeed seed : seeds) {
            if (seed.origin() != KnowledgeSeed.Origin.POC || !ids.add(seed.seedId())) {
                throw new IllegalArgumentException("Invalid catalog seed");
            }
        }
    }
}
