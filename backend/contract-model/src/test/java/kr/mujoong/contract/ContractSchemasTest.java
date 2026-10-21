package kr.mujoong.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * U8 계약 파일 시험(U8 functional-spec 7절 · BR5.1 · BR5.4 · BR5.5 · BR6.1 · BR7.1 · NFR3.1 ~ NFR3.3 · SD1 · SD2 · SD7).
 *
 * <p>U8이 더한 Luna · 제브 구조화 입출력 스키마와 새 연습 기록 형식이 스스로 든 정상 예시를 통과하고, 금지 필드(식별자 · 키 · 원음)를 넣은
 * 예시를 거절하며, 아이 문자열 필드에 이름 치환 문구가 있는지 본다. ai-io.md 10절의 R-F15 표식 문장과 deviations.md U8 절 표시도 확인한다.
 * 스키마 메타 검사 · $ref · 스펙 예시는 기존 {@link ContractFilesTest}가 계속 맡는다.
 */
@Tag("live-content")
class ContractSchemasTest {

    private static final String ID_PREFIX = "https://mujoong.invalid/contracts/schemas/";
    private static final String DRAFT_2020_12 = "https://json-schema.org/draft/2020-12/schema";
    private static final String NAME_SUBSTITUTION = "아이 이름은 '아이'로 치환된 값";

    /** U8이 더한 스키마 파일(BR5.1 · BR6.1). */
    private static final List<String> U8_SCHEMAS = List.of(
            "ai-judgment-request.schema.json",
            "ai-judgment-output.schema.json",
            "ai-scenario-output.schema.json",
            "ai-scenario-review-output.schema.json",
            "ai-goal-candidate-output.schema.json",
            "ai-rejudgment-output.schema.json",
            "ai-seed-output.schema.json",
            "guardrail-request.schema.json",
            "guardrail-result.schema.json",
            "practice-record-v2.schema.json");

    /** 외부 AI로 가면 안 되는 이름(SD1 · NFR3.1). */
    private static final List<String> FORBIDDEN_FIELDS =
            List.of("childId", "guardianId", "sessionId", "deviceId", "apiKey", "audio");

    /** 스키마별 아이 문자열 필드의 JSON 포인터(SD1 · NFR3.2). */
    private static final Map<String, List<String>> CHILD_STRING_FIELDS = Map.of(
            "ai-judgment-request.schema.json",
            List.of("/properties/childUtterance", "/properties/recentTurns/items/properties/text"),
            "guardrail-request.schema.json",
            List.of("/properties/candidate", "/properties/recentContext/items/properties/text"),
            "ai-scenario-output.schema.json",
            List.of("/properties/scenes/items/properties/sceneDescription",
                    "/properties/scenes/items/properties/opportunitySentence",
                    "/properties/scenes/items/properties/reexplanation"),
            "ai-goal-candidate-output.schema.json",
            List.of("/properties/candidates/items/properties/evidence/items/properties/quote"));

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private static Path contracts;
    private static Path schemasDir;
    private static SchemaRegistry registry;

    @BeforeAll
    static void load() {
        contracts = locateRepoRoot().resolve("contracts");
        schemasDir = contracts.resolve("schemas");
        registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, builder -> builder
                .schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
                .schemas(iri -> iri.startsWith(ID_PREFIX) ? readSchemaText(iri.substring(ID_PREFIX.length())) : null));
    }

    // ------------------------------------------------------------------ 정상 예시 · 닫힌 객체

    @Test
    void everyU8SchemaHasItsOwnIdAndItsExamplesValidate() {
        List<String> failures = new ArrayList<>();
        for (String file : U8_SCHEMAS) {
            JsonNode document = readSchema(file);
            assertThat(document.path("$schema").stringValue()).as(file).isEqualTo(DRAFT_2020_12);
            assertThat(document.path("$id").stringValue()).as(file).isEqualTo(ID_PREFIX + file);
            JsonNode examples = document.path("examples");
            assertThat(examples.isArray() && !examples.isEmpty()).as(file + " 정상 예시").isTrue();
            for (JsonNode example : examples) {
                List<?> errors = schema(file).validate(example);
                if (!errors.isEmpty()) {
                    failures.add(file + " " + errors);
                }
            }
        }

        assertThat(failures).isEmpty();
    }

    @Test
    void everyObjectInTheU8SchemasIsClosed() {
        List<String> open = new ArrayList<>();
        for (String file : U8_SCHEMAS) {
            walk(readSchema(file), "", (pointer, node) -> {
                JsonNode type = node.path("type");
                boolean objectShape = node.has("properties") || (type.isString() && "object".equals(type.stringValue()));
                if (objectShape && !pointer.startsWith("/examples") && !node.has("additionalProperties")) {
                    open.add(file + pointer);
                }
                if (node.has("properties") && node.path("additionalProperties").isBoolean()
                        && node.path("additionalProperties").booleanValue()) {
                    open.add(file + pointer + " (true)");
                }
            });
        }

        assertThat(open).as("additionalProperties가 없는 객체(SD1: 루트 · 하위 객체 모두 닫음)").isEmpty();
    }

    // ------------------------------------------------------------------ 금지 필드

    @Test
    void everyForbiddenFieldIsRejectedAtTheRootOfEverySchema() {
        List<String> accepted = new ArrayList<>();
        for (String file : U8_SCHEMAS) {
            JsonNode example = readSchema(file).path("examples").get(0);
            for (String forbidden : FORBIDDEN_FIELDS) {
                ObjectNode polluted = (ObjectNode) example.deepCopy();
                polluted.put(forbidden, "00000000-0000-4000-8000-000000000001");
                if (schema(file).validate(polluted).isEmpty()) {
                    accepted.add(file + " ← " + forbidden);
                }
            }
        }

        assertThat(accepted).as("금지 필드를 받아들인 스키마").isEmpty();
    }

    @Test
    void forbiddenFieldsAreAlsoRejectedInsideNestedRequestObjects() {
        ObjectNode judgment = (ObjectNode) readSchema("ai-judgment-request.schema.json").path("examples").get(0).deepCopy();
        ((ObjectNode) judgment.path("recentTurns").get(0)).put("childId", "00000000-0000-4000-8000-000000000002");
        ObjectNode criteria = (ObjectNode) judgment.path("criteria");
        ObjectNode withAudio = (ObjectNode) readSchema("ai-judgment-request.schema.json").path("examples").get(0).deepCopy();
        ((ObjectNode) withAudio.path("criteria")).put("audio", "AAAA");
        ObjectNode guardrail = (ObjectNode) readSchema("guardrail-request.schema.json").path("examples").get(0).deepCopy();
        ((ObjectNode) guardrail.path("recentContext").get(0)).put("sessionId", "00000000-0000-4000-8000-000000000003");

        assertThat(criteria.isObject()).isTrue();
        assertThat(schema("ai-judgment-request.schema.json").validate(judgment)).isNotEmpty();
        assertThat(schema("ai-judgment-request.schema.json").validate(withAudio)).isNotEmpty();
        assertThat(schema("guardrail-request.schema.json").validate(guardrail)).isNotEmpty();
    }

    @Test
    void requestSchemasNeverDeclareAForbiddenPropertyName() {
        List<String> declared = new ArrayList<>();
        for (String file : U8_SCHEMAS) {
            walk(readSchema(file), "", (pointer, node) -> {
                for (String forbidden : FORBIDDEN_FIELDS) {
                    if (node.path("properties").has(forbidden)) {
                        declared.add(file + pointer + "/properties/" + forbidden);
                    }
                }
            });
        }

        assertThat(declared).isEmpty();
    }

    // ------------------------------------------------------------------ 이름 치환 문구

    @Test
    void childStringFieldsSayTheChildNameIsSubstituted() {
        List<String> missing = new ArrayList<>();
        CHILD_STRING_FIELDS.forEach((file, pointers) -> {
            JsonNode document = readSchema(file);
            for (String pointer : pointers) {
                JsonNode field = document.at(pointer);
                JsonNode description = field.path("description");
                if (!description.isString() || !description.stringValue().contains(NAME_SUBSTITUTION)) {
                    missing.add(file + pointer);
                }
            }
        });

        assertThat(missing).as("치환 문구가 없는 아이 문자열 필드").isEmpty();
    }

    // ------------------------------------------------------------------ 거절 예시 · 경계

    @Test
    void practiceRecordV2RejectsARecordWithoutOrWithAnotherFlowVersion() {
        ObjectNode record = (ObjectNode) readSchema("practice-record-v2.schema.json").path("examples").get(0).deepCopy();
        ObjectNode withoutFlow = record.deepCopy();
        withoutFlow.remove("flowVersion");
        ObjectNode otherFlow = record.deepCopy();
        otherFlow.put("flowVersion", "practice-conversation-v2");
        ObjectNode withRawLabel = record.deepCopy();
        ((ObjectNode) withRawLabel.path("scenes").get(0).path("liveOutcome")).put("probability", 0.7);

        assertThat(schema("practice-record-v2.schema.json").validate(record)).isEmpty();
        assertThat(schema("practice-record-v2.schema.json").validate(withoutFlow)).as("flowVersion 없음 = U5b 형식").isNotEmpty();
        assertThat(schema("practice-record-v2.schema.json").validate(otherFlow)).isNotEmpty();
        assertThat(schema("practice-record-v2.schema.json").validate(withRawLabel)).as("확률 원값은 싣지 않는다").isNotEmpty();
    }

    // ------------------------------------------------------------------ 공유 고정 자료(core ↔ realtime 교차 계약, ai-io.md 10.15)

    @Test
    void theSharedStartPackageFixtureCarriesAFirstScenarioThatPassesThePracticeScenarioSchema() {
        JsonNode pack = JSON.readTree(read(contracts.resolve("fixtures/practice-start-package.json")));
        JsonNode first = pack.path("goals").get(0).path("firstScenario");

        assertThat(schema("practice-scenario.schema.json").validate(first)).isEmpty();
        assertThat(first.path("scriptVersion").asString()).isEqualTo("scenario-script.v1");
        assertThat(first.path("scenes").size()).isEqualTo(2);
        assertThat(first.path("scenes").get(0).path("sceneKind").asString()).isEqualTo("BASE");
        assertThat(first.path("scenes").get(1).path("sceneKind").asString()).isEqualTo("VARIANT");
        assertThat(pack.path("goals").get(1).path("firstScenario").isNull()).as("둘째 목표는 대본을 나중에 받는다").isTrue();
    }

    @Test
    void theSharedRecordFixturePassesThePracticeRecordV2Schema() {
        JsonNode record = JSON.readTree(read(contracts.resolve("fixtures/practice-record-v2.json")));

        assertThat(schema("practice-record-v2.schema.json").validate(record)).isEmpty();
        assertThat(record.path("scenes").size()).isEqualTo(3);
    }

    @Test
    void outputSchemasRejectBrokenModelAnswers() {
        assertThat(schema("ai-judgment-output.schema.json").validate(JSON.readTree(
                "{\"first\":{\"option\":\"FULL\",\"probability\":1.2},\"second\":{\"option\":\"PARTIAL\",\"probability\":0.1}}")))
                .as("확률은 0 ~ 1").isNotEmpty();
        assertThat(schema("ai-judgment-output.schema.json").validate(JSON.readTree(
                "{\"first\":{\"option\":\"FULL\",\"probability\":0.9}}"))).as("2순위 필수").isNotEmpty();
        assertThat(schema("ai-scenario-review-output.schema.json").validate(JSON.readTree(
                "{\"verdict\":\"MAYBE\",\"reasons\":[]}"))).isNotEmpty();
        assertThat(schema("guardrail-result.schema.json").validate(JSON.readTree(
                "{\"decision\":\"ALLOW\",\"categories\":[\"RUDENESS\"],\"reasonCode\":\"OK\",\"policyVersion\":\"guardrail-v1\"}")))
                .as("C §3.1 7개 범주 밖").isNotEmpty();
        assertThat(schema("guardrail-result.schema.json").validate(JSON.readTree(
                "{\"decision\":\"BLOCK\",\"categories\":[],\"reasonCode\":\"모델이 쓴 긴 설명\",\"policyVersion\":\"guardrail-v1\"}")))
                .as("reasonCode는 코드만").isNotEmpty();
        assertThat(schema("ai-goal-candidate-output.schema.json").validate(JSON.readTree("{\"candidates\":[{},{},{},{}]}")))
                .as("후보 최대 3개").isNotEmpty();
        assertThat(schema("ai-seed-output.schema.json").validate(JSON.readTree("{\"skillName\":\"x\"}"))).isNotEmpty();
    }

    @Test
    void scenarioOutputDoesNotAcceptAPartnerRoleFromTheModel() {
        ObjectNode output = (ObjectNode) readSchema("ai-scenario-output.schema.json").path("examples").get(0).deepCopy();
        ((ObjectNode) output.path("scenes").get(0)).put("partnerRole", "친구");

        assertThat(schema("ai-scenario-output.schema.json").validate(output)).as("ai-io.md 7절 U5a-J22").isNotEmpty();
    }

    @Test
    void judgmentRequestIsLimitedToJ1ToJ6AndThreeRecentTurns() {
        ObjectNode request = (ObjectNode) readSchema("ai-judgment-request.schema.json").path("examples").get(0).deepCopy();
        ObjectNode j7 = request.deepCopy();
        j7.put("judgmentPoint", "J7");
        ObjectNode tooManyTurns = request.deepCopy();
        ArrayNode turns = (ArrayNode) tooManyTurns.path("recentTurns");
        turns.add(turns.get(0).deepCopy());
        turns.add(turns.get(0).deepCopy());
        ObjectNode nullCriteria = request.deepCopy();
        nullCriteria.put("judgmentPoint", "J1");
        nullCriteria.putNull("criteria");

        assertThat(schema("ai-judgment-request.schema.json").validate(j7)).isNotEmpty();
        assertThat(schema("ai-judgment-request.schema.json").validate(tooManyTurns)).isNotEmpty();
        assertThat(schema("ai-judgment-request.schema.json").validate(nullCriteria)).as("J1 · J4 · J6은 null").isEmpty();
    }

    @Test
    void sessionStartRequestTakesApprovedSpeechAsAnOptionalAddition() {
        String old = """
                {"sessionId":"00000000-0000-4000-8000-000000000020","idempotencyKey":"00000000-0000-4000-8000-000000000010",
                 "guardianId":"00000000-0000-4000-8000-000000000001","childId":"00000000-0000-4000-8000-000000000002",
                 "deviceId":"00000000-0000-4000-8000-000000000003","activityType":"FREE_TALK",
                 "consentSnapshot":{"policyVersion":"1.0","voiceAndText":true,"guardianView":true,"reportSharing":true,"rawAudio":true},
                 "childContext":{"displayName":"하늘","age":8,"interests":[]},
                 "stopExpressions":[{"text":"무중아 그만할래","isDefault":true}],
                 "approvedContent":{
                   "CLOSING":{"contentVersionId":"00000000-0000-4000-8000-000000000041","text":"안녕!","audioUrl":"/media/approved/closing-v1.wav"},
                   "WAITING_FILLER":{"contentVersionId":"00000000-0000-4000-8000-000000000042","text":"음.","audioUrl":"/media/approved/waiting-filler-v1.wav"}},
                 "practice":null}
                """;
        ObjectNode withSpeech = (ObjectNode) JSON.readTree(old);
        withSpeech.putObject("approvedSpeech").putObject("D_REST_ACK")
                .put("contentVersionId", "00000000-0000-4000-8000-0000000008b3").put("text", "잠깐 쉬고 싶구나. 천천히 해도 괜찮아.");
        ObjectNode speechWithAudio = withSpeech.deepCopy();
        ((ObjectNode) speechWithAudio.path("approvedSpeech").path("D_REST_ACK")).put("audioUrl", "/media/approved/x.wav");
        ObjectNode lowerCaseCode = withSpeech.deepCopy();
        lowerCaseCode.putObject("approvedSpeech").putObject("d_rest_ack")
                .put("contentVersionId", "00000000-0000-4000-8000-0000000008b3").put("text", "x");

        Schema start = schema("internal-session-start-request.schema.json");
        assertThat(start.validate(JSON.readTree(old))).as("approvedSpeech 없는 옛 패키지").isEmpty();
        assertThat(start.validate(withSpeech)).isEmpty();
        assertThat(start.validate(speechWithAudio)).as("approvedSpeech 항목은 음성 없이 두 필드뿐").isNotEmpty();
        assertThat(start.validate(lowerCaseCode)).isNotEmpty();
    }

    // ------------------------------------------------------------------ ai-io.md 10절 · 편차 표시

    @Test
    void aiIoSection10KeepsTheRf15GuardSentence() {
        String section = section(read(contracts.resolve("ai-io.md")), "## 10. U8 공통 규칙");

        assertThat(section).as("ai-io.md 10절").isNotEmpty();
        assertThat(section).contains("**R-F15** 공급자 약관 · 보호자 동의 확인 전에는 실제 아동 데이터를 외부 모델로 보내지 않는다. "
                + "실호출은 합성 자료로만 한다.");
        // U9 SD10 · NFR3.7(R-M10): 'U9가 정한다' 자리는 U9의 결정 문장으로 바뀌었다(편차 U9-b). 옛 위임 문장이 남지 않는다
        assertThat(section).contains("U9는 코드로 판정하지 않고 RUNBOOK 확인 기록으로 지킨다(편차 U9-b)");
        assertThat(section).doesNotContain("'확인됨' 상태를 코드에서 판정하는 방법은 U9가 정한다");
        assertThat(section).contains("`MUJOONG_TEXT_AI_API_KEY`는 쓰지 않는다");
        assertThat(section).contains("`UNKNOWN` · `NOT_MATCHED`는 제브 생략 근거가 아니며");
        for (String file : U8_SCHEMAS) {
            assertThat(section).as("10.1 스키마 목록").contains("`" + file + "`");
        }
        for (String forbidden : FORBIDDEN_FIELDS.subList(0, 5)) {
            assertThat(section).as("10.2 금지 필드").contains(forbidden);
        }
    }

    @Test
    void sectionReaderStopsAtTheNextTopLevelHeading() {
        String markdown = "## 9. 앞\n가\n## 10. U8 공통 규칙 (덧붙임)\n나\n### 10.1 안\n다\n## 11. 뒤\n라\n";

        assertThat(section(markdown, "## 10. U8 공통 규칙")).contains("나", "다").doesNotContain("가", "라");
        assertThat(section(markdown, "## 12. 없음")).isEmpty();
    }

    @Test
    void everyU8DeviationHasOneRowAndAMarkerInAContractFile() {
        String deviations = read(contracts.resolve("deviations.md"));
        Pattern row = Pattern.compile("^\\| \\(U8-([a-z])\\) \\|", Pattern.MULTILINE);
        List<String> symbols = new ArrayList<>();
        Matcher matcher = row.matcher(deviations);
        while (matcher.find()) {
            symbols.add(matcher.group(1));
        }
        String others = read(contracts.resolve("ai-io.md")) + read(contracts.resolve("timing.md"))
                + read(contracts.resolve("internal-api.yaml")) + read(contracts.resolve("events.asyncapi.yaml"));

        assertThat(symbols).containsExactly("a", "b", "c", "d", "e");
        for (String symbol : symbols) {
            assertThat(others.contains("[U8 편차 (U8-" + symbol + ")]") || others.contains("\"(U8-" + symbol + ")\""))
                    .as("(U8-" + symbol + ") 표시").isTrue();
        }
    }

    // ------------------------------------------------------------------ 도구

    private static Schema schema(String file) {
        ObjectNode wrapper = JSON.createObjectNode();
        wrapper.put("$schema", DRAFT_2020_12);
        wrapper.put("$ref", ID_PREFIX + file);
        return registry.getSchema(wrapper);
    }

    private static JsonNode readSchema(String file) {
        return JSON.readTree(read(schemasDir.resolve(file)));
    }

    /** {@code heading}으로 시작하는 줄부터 다음 같은 수준(## ) 머리 앞까지. 없으면 빈 문자열. */
    static String section(String markdown, String heading) {
        Matcher head = Pattern.compile("^" + Pattern.quote(heading) + ".*$", Pattern.MULTILINE).matcher(markdown);
        if (!head.find()) {
            return "";
        }
        int start = head.start();
        int bodyStart = head.end();
        Matcher next = Pattern.compile("^## ", Pattern.MULTILINE).matcher(markdown);
        int end = next.find(bodyStart) ? next.start() : markdown.length();
        return markdown.substring(start, end);
    }

    private static void walk(JsonNode node, String pointer, BiConsumer<String, JsonNode> visitor) {
        if (node.isObject()) {
            visitor.accept(pointer, node);
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                walk(property.getValue(), pointer + "/" + property.getKey(), visitor);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walk(node.get(i), pointer + "/" + i, visitor);
            }
        }
    }

    private static Path locateRepoRoot() {
        String property = System.getProperty("mujoong.repo.root");
        if (property != null && !property.isBlank()) {
            return Path.of(property);
        }
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("contracts/schemas"))) {
                return dir;
            }
        }
        throw new IllegalStateException("contracts/를 찾지 못했다(mujoong.repo.root 미설정)");
    }

    private static String readSchemaText(String fileName) {
        Path file = schemasDir.resolve(fileName.contains("#") ? fileName.substring(0, fileName.indexOf('#')) : fileName);
        return Files.isRegularFile(file) ? read(file) : null;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
