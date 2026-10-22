package kr.mujoong.core.luna;

import java.util.List;
import java.util.Map;

/** 리포트 출력의 자료형을 공급자에게도 전달한다. 인용·출처·안전 검증의 대체가 아니다. */
public final class ReportResponseSchema {
    private ReportResponseSchema() {}

    public static LunaSchemas.OutputSchemaPair pair() {
        var citation = object(Map.of(
                "activityNo", Map.of("type", "integer"),
                "utteranceSeq", Map.of("type", List.of("integer", "null")),
                "quote", Map.of("type", List.of("string", "null")),
                "source", Map.of("type", "string", "enum", List.of("TRANSCRIPT", "CHILD_RECALL", "GUARDIAN_INPUT"))));
        var section = object(Map.of(
                "sectionNo", Map.of("type", "integer", "enum", List.of(2, 5, 6, 10)),
                "text", Map.of("type", "string"),
                "missing", Map.of("type", "boolean"),
                "citations", Map.of("type", "array", "items", citation)));
        return new LunaSchemas.OutputSchemaPair("result-report", object(Map.of(
                "sections", Map.of("type", "array", "items", section))), true);
    }

    private static Map<String, Object> object(Map<String, Object> properties) {
        return Map.of("type", "object", "properties", properties, "required", properties.keySet().stream().sorted().toList(),
                "additionalProperties", false);
    }
}
