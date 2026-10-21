package kr.mujoong.core.luna;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import kr.mujoong.contract.json.JsonCodec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 출력 스키마 짝 (U9 BR4.2 · BR4.5 · SD2 · SD4 · Q3-A · 검토 R-02). */
@Tag("luna")
class LunaSchemasTest {

    private static final JsonCodec JSON = JsonCodec.shared();

    private static String contractFile(String name) {
        try {
            Path file = LunaTestFixtures.REPO_ROOT.resolve("contracts/schemas/" + name);
            return Files.isRegularFile(file) ? Files.readString(file) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void everyRequestCopyKeepsOnlyProviderKeywordsAndInlinesForeignRefs() {
        LunaSchemas schemas = LunaSchemas.loadDefault(JSON);

        for (LunaPurpose purpose : LunaPurpose.values()) {
            if (purpose.outputSchema() == null) {
                assertThat(schemas.pair(purpose)).as(purpose.name()).isEmpty();
                continue;
            }
            LunaSchemas.OutputSchemaPair pair = schemas.pair(purpose).orElseThrow();
            List<String> keywords = new ArrayList<>();
            collectKeywords(pair.requestSchema(), keywords);
            assertThat(keywords).as(purpose + " 요청용 사본 키워드").isNotEmpty().allMatch(LunaSchemas.REQUEST_KEYWORDS::contains);
            assertThat(JSON.write(pair.requestSchema())).as(purpose.name())
                    .doesNotContain("common.schema.json", "\"examples\"", "\"title\"", "\"$id\"", "\"$schema\"");
        }
        String rejudgment = JSON.write(schemas.pair(LunaPurpose.REJUDGMENT).orElseThrow().requestSchema());
        assertThat(rejudgment).as("common Uuid 정의를 끼워 넣음").contains("\"sceneRunId\":{\"type\":\"string\"}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void goalRequestRequiresExplicitStructureDecisionButKeepsHistoricalContractCompatible() {
        var schemas = LunaSchemas.loadDefault(JSON);
        var pair = schemas.pair(LunaPurpose.GOAL_CANDIDATES).orElseThrow();
        var properties = (Map<String, Object>) pair.requestSchema().get("properties");
        var candidates = (Map<String, Object>) properties.get("candidates");
        var item = (Map<String, Object>) candidates.get("items");
        assertThat((List<String>) item.get("required")).contains("structureSignature");
        assertThat(pair.strict()).isTrue();
        // The unchanged contract still reads earlier outputs lacking this optional field.
        String historical = LunaTestFixtures.example("ai-goal-candidate-output.schema.json");
        assertThat(schemas.isValidOutput(LunaPurpose.GOAL_CANDIDATES, historical)).isTrue();
        var output = JSON.read(historical, Map.class);
        var candidate = (Map<String, Object>) ((List<?>) output.get("candidates")).getFirst();
        candidate.put("structureSignature", null);
        assertThat(schemas.isValidOutput(LunaPurpose.GOAL_CANDIDATES, JSON.write(output))).isTrue();
        candidate.put("structureSignature", Map.of("targetSkill", "PRODUCE", "requiredInference", "타인의 선호를 확인할 질문을 고르기",
                "perspectiveStructure", "상대의 의도 1층", "decidableByEvidence", "NO", "directCheckAllowed", "YES"));
        assertThat(schemas.isValidOutput(LunaPurpose.GOAL_CANDIDATES, JSON.write(output))).isTrue();
        var invalid = new java.util.HashMap<>((Map<String, Object>) candidate.get("structureSignature"));
        invalid.put("targetSkill", "INVENTED");
        candidate.put("structureSignature", invalid);
        assertThat(schemas.isValidOutput(LunaPurpose.GOAL_CANDIDATES, JSON.write(output))).isFalse();
    }

    @Test
    void reviewRequestHasProviderCompatibleRootWhileServerKeepsConditionalSafetyChecks() {
        var schemas=LunaSchemas.loadDefault(JSON);
        var pair=schemas.pair(LunaPurpose.SCENARIO_REVIEW).orElseThrow();
        assertThat(pair.requestSchema()).containsEntry("type","object").doesNotContainKey("anyOf");
        assertThat(pair.strict()).isTrue();
        assertThat(schemas.isValidOutput(LunaPurpose.SCENARIO_REVIEW,"{\"verdict\":\"APPROVE\",\"reasons\":[]}")).isTrue();
        assertThat(schemas.isValidOutput(LunaPurpose.SCENARIO_REVIEW,"{\"verdict\":\"APPROVE\",\"reasons\":[\"PRESSURE\"]}")).isFalse();
        assertThat(schemas.isValidOutput(LunaPurpose.SCENARIO_REVIEW,"{\"verdict\":\"REJECT\",\"reasons\":[\"UNKNOWN\"]}")).isFalse();
    }

    @Test
    void strictIsTrueOnlyWhenEveryObjectIsClosedAndFullyRequired() {
        Map<String, Object> closed = Map.of("type", "object", "additionalProperties", false, "required", List.of("a"),
                "properties", Map.of("a", Map.of("type", "array", "items", Map.of("type", "object",
                        "additionalProperties", false, "required", List.of("b"), "properties", Map.of("b", Map.of("type", "string"))))));
        Map<String, Object> optional = Map.of("type", "object", "additionalProperties", false, "required", List.of(),
                "properties", Map.of("a", Map.of("type", "string")));
        Map<String, Object> openNested = Map.of("type", "object", "additionalProperties", false, "required", List.of("a"),
                "properties", Map.of("a", Map.of("type", "object", "properties", Map.of("b", Map.of("type", "string")),
                        "required", List.of("b"))));

        assertThat(LunaSchemas.isStrict(closed)).isTrue();
        assertThat(LunaSchemas.isStrict(optional)).isFalse();
        assertThat(LunaSchemas.isStrict(openNested)).isFalse();
        assertThat(LunaSchemas.loadDefault(JSON).pair(LunaPurpose.REJUDGMENT).orElseThrow().strict())
                .as("재판정 출력은 모든 객체가 닫히고 모든 속성이 required다").isTrue();
    }

    @Test
    void contractValidationCatchesTheConstraintsMissingFromTheRequestCopy() {
        LunaSchemas schemas = LunaSchemas.loadDefault(JSON);
        String valid = LunaTestFixtures.example("ai-rejudgment-output.schema.json");
        String badUuid = valid.replace("00000000-0000-4000-8000-000000000901", "not-a-uuid");
        String badEnum = valid.replace("\"functional\"", "\"great\"");
        String extraKey = valid.replaceFirst("\\{\"scenes\"", "{\"extra\":1,\"scenes\"");

        assertThat(schemas.isValidOutput(LunaPurpose.REJUDGMENT, valid)).isTrue();
        assertThat(schemas.isValidOutput(LunaPurpose.REJUDGMENT, badUuid)).as("pattern · format(요청 사본엔 없음)").isFalse();
        assertThat(schemas.isValidOutput(LunaPurpose.REJUDGMENT, badEnum)).isFalse();
        assertThat(schemas.isValidOutput(LunaPurpose.REJUDGMENT, extraKey)).isFalse();
        assertThat(schemas.isValidOutput(LunaPurpose.REJUDGMENT, "not json")).isFalse();
        assertThat(schemas.isValidOutput(LunaPurpose.REJUDGMENT, "[1]")).isFalse();
        assertThatThrownBy(() -> schemas.isValidOutput(LunaPurpose.RESULT_TEXT, "{}")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lengthAndRangeViolationsAreCaughtForEveryContractSchema() {
        LunaSchemas schemas = LunaSchemas.loadDefault(JSON);
        String judgmentLike = LunaTestFixtures.example("ai-scenario-review-output.schema.json");

        assertThat(schemas.isValidOutput(LunaPurpose.SCENARIO_REVIEW, judgmentLike)).isTrue();
        for (LunaPurpose purpose : List.of(LunaPurpose.SCENARIO_GENERATION, LunaPurpose.SCENARIO_REVIEW,
                LunaPurpose.SEED_GENERATION, LunaPurpose.GOAL_CANDIDATES, LunaPurpose.REJUDGMENT)) {
            assertThat(schemas.isValidOutput(purpose, LunaTestFixtures.example(purpose.outputSchema()))).as(purpose.name()).isTrue();
            assertThat(schemas.isValidOutput(purpose, "{}")).as(purpose + " 필수 속성 누락").isFalse();
        }
        String longText = LunaTestFixtures.example("ai-seed-output.schema.json").replaceFirst(":\"([^\"]*)\"",
                ":\"" + "가".repeat(5000) + "\"");
        assertThat(schemas.isValidOutput(LunaPurpose.SEED_GENERATION, longText)).as("maxLength").isFalse();
    }

    @Test
    void aMissingUnreadableOrSelfContradictingSchemaRefusesToLoad() {
        Function<String, String> missingSeed = name -> name.equals("ai-seed-output.schema.json") ? null : contractFile(name);
        Function<String, String> unreadable = name -> name.equals("ai-seed-output.schema.json") ? "{not json" : contractFile(name);
        Function<String, String> badExample = name -> name.equals("ai-seed-output.schema.json")
                ? contractFile(name).replace("\"examples\": [", "\"examples\": [{\"unexpected\": true},") : contractFile(name);
        Function<String, String> missingCommon = name -> name.equals("common.schema.json") ? null : contractFile(name);

        assertThatThrownBy(() -> LunaSchemas.load(JSON, missingSeed)).isInstanceOf(LunaSchemas.LoadException.class)
                .hasMessage("SCHEMA_MISSING: ai-seed-output.schema.json");
        assertThatThrownBy(() -> LunaSchemas.load(JSON, unreadable)).hasMessage("SCHEMA_UNREADABLE: ai-seed-output.schema.json");
        assertThatThrownBy(() -> LunaSchemas.load(JSON, badExample))
                .hasMessage("SCHEMA_EXAMPLE_INVALID: ai-seed-output.schema.json");
        assertThatThrownBy(() -> LunaSchemas.load(JSON, missingCommon)).isInstanceOf(LunaSchemas.LoadException.class)
                .hasMessageContaining("common.schema.json");
    }

    @Test
    void jsonObjectCheckForTheLegacyPathAcceptsOnlyObjects() {
        LunaSchemas schemas = LunaSchemas.loadDefault(JSON);

        assertThat(schemas.isJsonObject("{\"sentences\":[]}")).isTrue();
        assertThat(schemas.isJsonObject("[]")).isFalse();
        assertThat(schemas.isJsonObject("\"text\"")).isFalse();
        assertThat(schemas.isJsonObject(" ")).isFalse();
        assertThat(schemas.isJsonObject(null)).isFalse();
        assertThat(schemas.isJsonObject("{broken")).isFalse();
    }

    private static void collectKeywords(Object node, List<String> keywords) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                keywords.add(key);
                if ((key.equals("properties") || key.equals("$defs")) && entry.getValue() instanceof Map<?, ?> named) {
                    named.values().forEach(child -> collectKeywords(child, keywords));
                } else if (key.equals("items") || key.equals("anyOf")) {
                    collectKeywords(entry.getValue(), keywords);
                }
            }
        } else if (node instanceof Collection<?> list) {
            list.forEach(child -> collectKeywords(child, keywords));
        }
    }
}
