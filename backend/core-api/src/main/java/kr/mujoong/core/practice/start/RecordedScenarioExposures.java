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
        if(raw instanceof Map<?,?> flow && flow.containsKey("flowVersion")) return flowExposures(flow);
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
    /**
     * 새 연습 흐름 기록(flowVersion, contracts/schemas/practice-record-v2.schema.json)은 장면마다 scenarioRef와 시작 시각을 남긴다.
     * 실제로 시작한 장면만 노출로 보고, 같은 장면 행(BASE · VARIANT)이 여러 번이면 처음 시작한 시각을 쓴다. 이 형식을 읽지 못해
     * 노출 정리가 계속 실패했다(2026-10-30, FT-1030-g).
     */
    @SuppressWarnings("unchecked")
    private static List<Exposure> flowExposures(Map<?,?> flow) {
        List<kr.mujoong.core.result.PracticeRecordV2.Scene> scenes;
        try { scenes=kr.mujoong.core.result.PracticeRecordV2.read((Map<String,Object>)flow); }
        catch(RuntimeException invalid) { throw new IllegalArgumentException("연습 기록 형식 오류"); }
        Map<UUID,Instant> first=new java.util.LinkedHashMap<>();
        for(var scene:scenes) {
            if(scene.scenarioRef()==null) continue;
            first.merge(scene.scenarioRef(),scene.startedAt(),(a,b)->a.isBefore(b)?a:b);
        }
        return first.entrySet().stream().map(e->new Exposure(e.getKey(),e.getValue())).toList();
    }
}
