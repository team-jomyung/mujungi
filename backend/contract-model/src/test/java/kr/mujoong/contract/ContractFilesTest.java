package kr.mujoong.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.event.EventPayload;
import kr.mujoong.contract.event.EventType;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.ws.CloseCode;
import kr.mujoong.contract.ws.WsEnvelope;
import kr.mujoong.contract.ws.WsMessageType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * 계약 정본 파일 시험(V-5 · J20 · J59 · J112 · BR8.6). {@code contracts/}의 YAML · JSON을 파싱하고, 스키마를 JSON Schema 2020-12 메타
 * 스키마로 검사하고, 모든 $ref가 풀리는지, 스펙의 예시가 가리키는 스키마를 통과하는지, (a) ~ (z) 편차 표시가 있는지, errors.md 표와
 * {@link ErrorCode}가 같은지 본다. 스펙 예시는 Java 계약 모델({@link JsonCodec})로도 읽혀야 한다(두 정본이 어긋나지 않게).
 */
@Tag("u1")
class ContractFilesTest {

    private static final String ID_PREFIX = "https://mujoong.invalid/contracts/schemas/";
    private static final String DRAFT_2020_12 = "https://json-schema.org/draft/2020-12/schema";
    private static final Pattern MD_MARKER = Pattern.compile("\\[편차 \\(([a-z])\\)\\]");
    private static final Pattern DEVIATION_ROW = Pattern.compile("^\\| \\(([a-z])\\) \\|.*$", Pattern.MULTILINE);
    private static final List<String> LETTERS =
            IntStream.rangeClosed('a', 'z').mapToObj(c -> String.valueOf((char) c)).toList();
    private static final List<String> SPEC_FILES = List.of(
            "parent-api.yaml", "device-api.yaml", "internal-api.yaml", "events.asyncapi.yaml", "device-ws.asyncapi.yaml");
    private static final List<String> MARKDOWN_FILES =
            List.of("README.md", "errors.md", "timing.md", "ports.md", "ai-io.md", "deviations.md");

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final ObjectMapper YAML = YAMLMapper.builder().build();

    private static Path repoRoot;
    private static Path contracts;
    private static Path schemasDir;
    private static Map<String, JsonNode> schemas;
    private static Map<String, JsonNode> specs;
    private static SchemaRegistry registry;

    @BeforeAll
    static void loadContracts() throws IOException {
        repoRoot = locateRepoRoot();
        contracts = repoRoot.resolve("contracts");
        schemasDir = contracts.resolve("schemas");
        schemas = new TreeMap<>();
        try (Stream<Path> files = Files.list(schemasDir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".schema.json")).sorted().toList()) {
                schemas.put(file.getFileName().toString(), JSON.readTree(Files.readString(file, StandardCharsets.UTF_8)));
            }
        }
        specs = new LinkedHashMap<>();
        for (String name : SPEC_FILES) {
            specs.put(name, YAML.readTree(Files.readString(contracts.resolve(name), StandardCharsets.UTF_8)));
        }
        // 스키마 $id(https://mujoong.invalid/...)는 네트워크로 받지 않고 저장소 파일로 바꿔 읽는다
        registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, builder -> builder
                .schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
                .schemas(iri -> iri.startsWith(ID_PREFIX) ? readSchemaText(iri.substring(ID_PREFIX.length())) : null));
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
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ 파싱 · 메타 검사

    @Test
    void everyContractFileIsPresentAndParses() {
        assertThat(schemas).hasSizeGreaterThanOrEqualTo(30);
        assertThat(schemas.values()).allMatch(JsonNode::isObject);
        for (String name : MARKDOWN_FILES) {
            assertThat(contracts.resolve(name)).as(name).isRegularFile();
        }
        for (Map.Entry<String, JsonNode> spec : specs.entrySet()) {
            JsonNode doc = spec.getValue();
            assertThat(doc.isObject()).as(spec.getKey()).isTrue();
            if (spec.getKey().contains("asyncapi")) {
                assertThat(doc.path("asyncapi").stringValue()).as(spec.getKey()).isEqualTo("2.6.0");
            } else {
                assertThat(doc.path("openapi").stringValue()).as(spec.getKey()).isEqualTo("3.0.3");
                assertThat(doc.path("paths").isObject()).as(spec.getKey()).isTrue();
            }
            assertThat(doc.path("info").path("title").stringValue()).as(spec.getKey()).isNotBlank();
            assertThat(doc.path("x-unit").stringValue()).as(spec.getKey()).isEqualTo("U1");
        }
    }

    @Test
    void schemasDeclareDraft202012AndTheirOwnId() {
        for (Map.Entry<String, JsonNode> schema : schemas.entrySet()) {
            assertThat(schema.getValue().path("$schema").stringValue()).as(schema.getKey()).isEqualTo(DRAFT_2020_12);
            assertThat(schema.getValue().path("$id").stringValue()).as(schema.getKey()).isEqualTo(ID_PREFIX + schema.getKey());
        }
    }

    @Test
    void schemasPassTheDraft202012MetaSchema() {
        Schema metaSchema = registry.getSchema(SchemaLocation.of(DRAFT_2020_12));
        Map<String, List<String>> failures = new TreeMap<>();

        for (Map.Entry<String, JsonNode> schema : schemas.entrySet()) {
            List<String> errors = messages(metaSchema.validate(schema.getValue()));
            if (!errors.isEmpty()) {
                failures.put(schema.getKey(), errors);
            }
        }

        assertThat(failures).isEmpty();
    }

    @Test
    void metaSchemaCatchesABrokenSchema() {
        Schema metaSchema = registry.getSchema(SchemaLocation.of(DRAFT_2020_12));
        JsonNode broken = JSON.readTree("{\"type\":\"objekt\",\"required\":\"code\",\"minLength\":-1}");

        assertThat(metaSchema.validate(broken)).isNotEmpty();
    }

    // ------------------------------------------------------------------ $ref

    @Test
    void everyRefResolvesToAnExistingFileAndPointer() {
        List<String> unresolved = new ArrayList<>();
        int[] checked = {0};

        for (Map.Entry<String, JsonNode> schema : schemas.entrySet()) {
            walk(schema.getValue(), "", (pointer, node) -> {
                JsonNode ref = node.get("$ref");
                if (ref != null && ref.isString()) {
                    checked[0]++;
                    resolveSchemaRef(schema.getKey(), ref.stringValue())
                            .ifPresent(problem -> unresolved.add(schema.getKey() + pointer + ": " + problem));
                }
            });
        }
        for (Map.Entry<String, JsonNode> spec : specs.entrySet()) {
            walk(spec.getValue(), "", (pointer, node) -> {
                for (String key : List.of("$ref", "x-payload-schema")) {
                    JsonNode ref = node.get(key);
                    if (ref != null && ref.isString()) {
                        checked[0]++;
                        resolveSpecRef(spec.getValue(), ref.stringValue())
                                .ifPresent(problem -> unresolved.add(spec.getKey() + pointer + ": " + problem));
                    }
                }
            });
        }

        assertThat(unresolved).isEmpty();
        assertThat(checked[0]).isGreaterThan(200);
    }

    private static java.util.Optional<String> resolveSchemaRef(String fileName, String ref) {
        String target = ref.startsWith(ID_PREFIX) ? ref.substring(ID_PREFIX.length()) : ref;
        if (target.startsWith("http:") || target.startsWith("https:")) {
            return java.util.Optional.of("바깥 $ref " + ref);
        }
        int hash = target.indexOf('#');
        String file = hash < 0 ? target : target.substring(0, hash);
        String pointer = hash < 0 ? "" : target.substring(hash + 1);
        JsonNode doc = file.isEmpty() ? schemas.get(fileName) : schemas.get(file);
        if (doc == null) {
            return java.util.Optional.of("없는 파일 " + ref);
        }
        return pointer.isEmpty() || !doc.at(pointer).isMissingNode()
                ? java.util.Optional.empty()
                : java.util.Optional.of("없는 위치 " + ref);
    }

    private static java.util.Optional<String> resolveSpecRef(JsonNode doc, String ref) {
        if (ref.startsWith("#")) {
            return doc.at(ref.substring(1)).isMissingNode()
                    ? java.util.Optional.of("없는 위치 " + ref)
                    : java.util.Optional.empty();
        }
        if (!ref.startsWith("schemas/")) {
            return java.util.Optional.of("schemas/ 밖 $ref " + ref);
        }
        return resolveSchemaRef("", ref.substring("schemas/".length()));
    }

    // ------------------------------------------------------------------ 예시

    @Test
    void specExamplesValidateAgainstTheSchemasTheyPointTo() {
        List<String> failures = new ArrayList<>();
        int[] validated = {0};

        for (Map.Entry<String, JsonNode> spec : specs.entrySet()) {
            forEachExample(spec.getValue(), (location, example) -> {
                validated[0]++;
                List<String> errors = messages(example.schema().validate(example.value()));
                if (!errors.isEmpty()) {
                    failures.add(spec.getKey() + location + " " + errors);
                }
            });
        }

        assertThat(failures).isEmpty();
        assertThat(validated[0]).isGreaterThanOrEqualTo(50);
    }

    @Test
    void validatorRejectsBrokenInstances() {
        Schema envelope = schemaFor("schemas/error-envelope.schema.json");
        Schema ws = schemaFor("schemas/ws-envelope.schema.json");

        assertThat(envelope.validate(JSON.readTree("{\"code\":\"NOT_A_CODE\",\"message\":\"x\"}"))).isNotEmpty();
        assertThat(envelope.validate(JSON.readTree(
                "{\"code\":\"NOT_FOUND\",\"message\":\"x\",\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\",\"stack\":\"x\"}")))
                .isNotEmpty();
        assertThat(ws.validate(JSON.readTree("""
                {"type":"HEARTBEAT","seq":1,"sentAt":"2026-10-15T09:02:00Z","opId":"00000000-0000-4000-8000-000000000070",
                 "payload":{"hbSeq":1}}
                """))).as("opId는 서버 명령 4종에만").isNotEmpty();
        assertThat(ws.validate(JSON.readTree("""
                {"type":"STOP_OUTPUT","seq":1,"sentAt":"2026-10-15T09:02:00Z","sessionId":"00000000-0000-4000-8000-000000000020",
                 "payload":{"reason":"SILENCE"}}
                """))).as("STOP_OUTPUT은 opId 필수").isNotEmpty();
        assertThat(ws.validate(JSON.readTree("""
                {"type":"HEARTBEAT","seq":0,"sentAt":"2026-10-15T09:02:00Z","payload":{"hbSeq":1}}
                """))).as("seq는 1부터").isNotEmpty();
    }

    @Test
    void deviceWsExamplesDecodeWithTheJavaCodec() {
        JsonNode messages = specs.get("device-ws.asyncapi.yaml").path("components").path("messages");
        Set<String> seen = new TreeSet<>();

        for (Map.Entry<String, JsonNode> message : messages.properties()) {
            for (JsonNode example : message.getValue().path("examples")) {
                WsEnvelope.Decoded decoded = JsonCodec.shared().decodeWs(JSON.writeValueAsString(example.path("payload")));
                assertThat(decoded.outcome()).as(message.getKey() + " " + decoded.problems()).isEqualTo(WsEnvelope.Outcome.OK);
                assertThat(decoded.envelope().messageType().name()).isEqualTo(message.getKey());
                seen.add(message.getKey());
            }
        }

        Set<String> expected = Arrays.stream(WsMessageType.values())
                .filter(type -> type != WsMessageType.UNRECOGNIZED)
                .map(Enum::name)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(seen).isEqualTo(expected);
        assertThat(messages.has("INPUT_AUDIO")).isTrue();
        assertThat(messages.has("OUTPUT_AUDIO")).isTrue();
    }

    @Test
    void eventExamplesReadWithTheJavaPayloadTypes() {
        JsonNode messages = specs.get("events.asyncapi.yaml").path("components").path("messages");
        Set<String> names = new TreeSet<>();

        for (Map.Entry<String, JsonNode> message : messages.properties()) {
            EventType type = EventType.fromWire(message.getKey());
            assertThat(type).as(message.getKey()).isNotEqualTo(EventType.UNRECOGNIZED);
            assertThat(message.getValue().path("x-aggregate-type").stringValue()).isEqualTo(type.aggregateType().wire());
            assertThat(message.getValue().path("x-control-event").booleanValue()).isEqualTo(type.isControlEvent());
            assertThat(message.getValue().path("x-schema-version").intValue()).isEqualTo(EventType.CURRENT_SCHEMA_VERSION);
            for (JsonNode example : message.getValue().path("examples")) {
                EventPayload payload = JsonCodec.shared().read(JSON.writeValueAsString(example.path("payload")), type.payloadType());
                assertThat(payload.problems()).as(message.getKey()).isEmpty();
            }
            names.add(message.getKey());
        }

        assertThat(names).containsExactlyInAnyOrder(Arrays.stream(EventType.values())
                .filter(type -> type != EventType.UNRECOGNIZED)
                .map(EventType::wire)
                .toArray(String[]::new));
    }

    @Test
    void closeCodeTableMatchesTheJavaEnum() {
        Map<Integer, String> fromSpec = new TreeMap<>();
        for (JsonNode row : specs.get("device-ws.asyncapi.yaml").path("x-close-codes")) {
            fromSpec.put(row.path("code").intValue(), row.path("name").stringValue() + "/" + row.path("deviceAction").stringValue());
        }
        Map<Integer, String> fromEnum = Arrays.stream(CloseCode.values())
                .collect(Collectors.toMap(CloseCode::code, code -> code.name() + "/" + code.deviceAction(), (a, b) -> a, TreeMap::new));

        assertThat(fromSpec).isEqualTo(fromEnum);
    }

    // ------------------------------------------------------------------ 오류 code

    @Test
    void errorResponsesUseKnownCodesAndFixedMessages() {
        List<String> failures = new ArrayList<>();
        int[] examples = {0};

        for (Map.Entry<String, JsonNode> spec : specs.entrySet()) {
            walk(spec.getValue(), "", (pointer, node) -> {
                JsonNode codes = node.get("x-error-codes");
                if (codes == null) {
                    return;
                }
                Set<String> allowed = new LinkedHashSet<>();
                for (JsonNode code : codes) {
                    allowed.add(code.stringValue());
                    if (ErrorCode.fromWire(code.stringValue()).isEmpty()) {
                        failures.add(spec.getKey() + pointer + ": 모르는 code " + code.stringValue());
                    }
                }
                walk(node.path("content"), pointer + "/content", (inner, media) -> {
                    JsonNode example = media.get("example");
                    if (example == null || !example.has("code")) {
                        return;
                    }
                    examples[0]++;
                    String code = example.path("code").stringValue();
                    if (!allowed.contains(code)) {
                        failures.add(spec.getKey() + inner + ": 예시 code " + code + "가 x-error-codes 밖");
                    } else if (!ErrorCode.valueOf(code).message().equals(example.path("message").stringValue())) {
                        failures.add(spec.getKey() + inner + ": " + code + " message가 고정 문장과 다르다");
                    }
                });
            });
        }

        assertThat(failures).isEmpty();
        assertThat(examples[0]).isGreaterThanOrEqualTo(5);
    }

    @Test
    void errorsMdTableMatchesTheErrorCodeEnum() throws IOException {
        String markdown = Files.readString(contracts.resolve("errors.md"), StandardCharsets.UTF_8);
        Pattern row = Pattern.compile("^\\| `([A-Z_]+)` \\| ([0-9, ]+) \\| ([^|]+) \\| ([^|]+) \\|", Pattern.MULTILINE);
        Map<String, String> fromTable = new TreeMap<>();
        Matcher matcher = row.matcher(markdown);
        while (matcher.find()) {
            List<Integer> http = Arrays.stream(matcher.group(2).split(","))
                    .map(String::trim)
                    .map(Integer::valueOf)
                    .toList();
            Set<ErrorCode.Audience> audiences = EnumSet.noneOf(ErrorCode.Audience.class);
            for (String audience : matcher.group(4).trim().split(" · ")) {
                audiences.add(switch (audience.trim()) {
                    case "보호자" -> ErrorCode.Audience.GUARDIAN;
                    case "기기" -> ErrorCode.Audience.DEVICE;
                    case "내부" -> ErrorCode.Audience.INTERNAL;
                    case "Nginx" -> ErrorCode.Audience.NGINX;
                    default -> throw new IllegalStateException("errors.md 노출 값: " + audience);
                });
            }
            fromTable.put(matcher.group(1), http + " | " + matcher.group(3).trim() + " | " + new TreeSet<>(audiences));
        }
        Map<String, String> fromEnum = Arrays.stream(ErrorCode.values())
                .collect(Collectors.toMap(Enum::name,
                        code -> code.httpStatuses() + " | " + code.message() + " | " + new TreeSet<>(code.audiences()),
                        (a, b) -> a, TreeMap::new));

        assertThat(fromTable).isEqualTo(fromEnum);
    }

    // ------------------------------------------------------------------ 편차 (a) ~ (z)

    @Test
    void everyDeviationHasOneRowAndAMarkerInEachListedFile() throws IOException {
        String deviations = Files.readString(contracts.resolve("deviations.md"), StandardCharsets.UTF_8);
        Map<String, List<String>> rows = new TreeMap<>();
        Matcher matcher = DEVIATION_ROW.matcher(deviations);
        while (matcher.find()) {
            rows.computeIfAbsent(matcher.group(1), letter -> new ArrayList<>()).add(matcher.group());
        }

        assertThat(rows.keySet()).containsExactlyElementsOf(LETTERS);
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : rows.entrySet()) {
            String letter = entry.getKey();
            if (entry.getValue().size() != 1) {
                problems.add("(" + letter + ") 행이 " + entry.getValue().size() + "개");
                continue;
            }
            String[] cells = entry.getValue().getFirst().split("\\|");
            String locations = cells[cells.length - 1].isBlank() ? cells[cells.length - 2] : cells[cells.length - 1];
            for (String location : locations.trim().split(" · ")) {
                if (!markersIn(location.trim()).contains(letter)) {
                    problems.add("(" + letter + ") 표시가 " + location.trim() + "에 없다");
                }
            }
        }

        assertThat(problems).isEmpty();
    }

    @Test
    void everyDeviationIsMarkedOutsideTheDeviationTableAndNoUnknownMarkerExists() {
        Set<String> marked = new TreeSet<>();
        for (String spec : SPEC_FILES) {
            marked.addAll(markersIn(spec));
        }
        for (String markdown : MARKDOWN_FILES) {
            if (!markdown.equals("deviations.md")) {
                marked.addAll(markersIn(markdown));
            }
        }
        marked.addAll(markersIn("infra/postgres/init/01-roles.sql"));

        assertThat(marked).containsExactlyElementsOf(LETTERS);
    }

    /** 파일 하나의 편차 표시 글자. YAML은 x-deviation 배열, 그 밖(Markdown · SQL)은 [편차 (x)]. */
    private static Set<String> markersIn(String location) {
        Path file = location.contains("/") ? repoRoot.resolve(location) : contracts.resolve(location);
        Set<String> letters = new TreeSet<>();
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (location.endsWith(".yaml")) {
                walk(YAML.readTree(text), "", (pointer, node) -> {
                    for (JsonNode value : node.path("x-deviation")) {
                        Matcher letter = Pattern.compile("^\\(([a-z])\\)$").matcher(value.stringValue());
                        letters.add(letter.matches() ? letter.group(1) : "?" + value.stringValue());
                    }
                });
            } else {
                Matcher matcher = MD_MARKER.matcher(text);
                while (matcher.find()) {
                    letters.add(matcher.group(1));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(location, e);
        }
        return letters;
    }

    // ------------------------------------------------------------------ 도구

    /** 스펙 예시 하나와 그 스키마. */
    private record Example(Schema schema, JsonNode value) {
    }

    /**
     * 스펙의 예시를 모두 찾는다: OpenAPI 미디어 타입 객체의 {@code schema} + {@code example} · {@code examples.*.value}, AsyncAPI 메시지의
     * {@code payload} + {@code examples[].payload}.
     */
    private static void forEachExample(JsonNode doc, BiConsumer<String, Example> consumer) {
        walk(doc, "", (pointer, node) -> {
            if (node.has("schema") && (node.has("example") || node.has("examples"))) {
                Schema schema = schemaOf(node.get("schema"));
                if (node.has("example")) {
                    consumer.accept(pointer + "/example", new Example(schema, node.get("example")));
                }
                for (Map.Entry<String, JsonNode> named : node.path("examples").properties()) {
                    consumer.accept(pointer + "/examples/" + named.getKey(), new Example(schema, named.getValue().path("value")));
                }
            }
            if (node.has("payload") && node.path("examples").isArray()) {
                Schema schema = schemaOf(node.get("payload"));
                int index = 0;
                for (JsonNode example : node.path("examples")) {
                    consumer.accept(pointer + "/examples/" + index++, new Example(schema, example.path("payload")));
                }
            }
        });
    }

    private static Schema schemaOf(JsonNode specSchema) {
        JsonNode ref = specSchema.get("$ref");
        if (ref != null && ref.isString()) {
            return schemaFor(ref.stringValue());
        }
        ObjectNode inline = (ObjectNode) specSchema.deepCopy();
        inline.put("$schema", DRAFT_2020_12);
        return registry.getSchema(inline);
    }

    private static Schema schemaFor(String specRef) {
        if (!specRef.startsWith("schemas/")) {
            throw new IllegalArgumentException("schemas/ 밖 $ref: " + specRef);
        }
        ObjectNode wrapper = JSON.createObjectNode();
        wrapper.put("$schema", DRAFT_2020_12);
        wrapper.put("$ref", ID_PREFIX + specRef.substring("schemas/".length()));
        return registry.getSchema(wrapper);
    }

    /** 모든 객체 노드를 JSON 포인터와 함께 방문한다. */
    private static void walk(JsonNode node, String pointer, BiConsumer<String, JsonNode> visitor) {
        if (node.isObject()) {
            visitor.accept(pointer, node);
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                walk(property.getValue(), pointer + "/" + property.getKey().replace("~", "~0").replace("/", "~1"), visitor);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walk(node.get(i), pointer + "/" + i, visitor);
            }
        }
    }

    private static List<String> messages(List<com.networknt.schema.Error> errors) {
        return errors.stream().map(com.networknt.schema.Error::toString).toList();
    }
}
