package kr.mujoong.core.practice;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** U4 recorded snapshot is the only source of actual exposure, not scenario issuance. */
public interface ScenarioRecordPort {
    RecordedExposures require(UUID sessionId);
    record Exposure(UUID scenarioId, Instant exposedAt) {}
    record RecordedExposures(UUID sessionId, UUID guardianId, UUID childId, List<Exposure> exposures) {
        public RecordedExposures { exposures = List.copyOf(exposures); }
    }
}
