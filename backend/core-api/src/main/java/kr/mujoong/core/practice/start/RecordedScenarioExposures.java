package kr.mujoong.core.practice.start;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.practice.ScenarioRecordPort.Exposure;

public final class RecordedScenarioExposures {
    private RecordedScenarioExposures() {}
    public static List<Exposure> parse(String value, JsonCodec json) {
        if(value==null || "null".equals(value)) return List.of();
        Object raw=json.read(value,Object.class);
        if(!(raw instanceof Map<?,?> practice) || !(practice.get("goals") instanceof List<?> goals))
            throw new IllegalArgumentException("연습 기록 형식 오류");
        var exposures=new ArrayList<Exposure>();
        for(Object goal:goals) {
            if(!(goal instanceof Map<?,?> g) || !(g.get("scenarios") instanceof List<?> scenarios))
                throw new IllegalArgumentException("장면 기록 형식 오류");
            for(Object item:scenarios) {
                if(!(item instanceof Map<?,?> scenario)) throw new IllegalArgumentException("장면 기록 형식 오류");
                Object at=scenario.get("exposedAt");
                if(at==null) continue;
                if(!(at instanceof String timestamp) || !(scenario.get("scenarioId") instanceof String id))
                    throw new IllegalArgumentException("장면 노출 형식 오류");
                exposures.add(new Exposure(UUID.fromString(id),Instant.parse(timestamp)));
            }
        }
        return List.copyOf(exposures);
    }
}
