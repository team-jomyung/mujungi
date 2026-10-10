package kr.mujoong.core.web.support;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import kr.mujoong.core.web.support.CoreWebApp.Result;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * 계약 OpenAPI 파일({@code contracts/parent-api.yaml} · {@code device-api.yaml} · {@code internal-api.yaml})의 연산표(시험 소스 전용).
 *
 * <p>연산 키는 {@code "METHOD /전체/경로/{변수}"}다(보호자 · 기기 API는 서버 경로 {@code /api}를 붙인다). 응답 하나를 연산의 문서화된 상태
 * 코드 · 본문 스키마 · {@code x-error-codes}와 대조한다.
 */
public final class OpenApiContracts {

    private static final ObjectMapper YAML = YAMLMapper.builder().build();
    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete");

    private final Map<String, Operation> operations;

    private OpenApiContracts(Map<String, Operation> operations) {
        this.operations = operations;
    }

    /** 저장소의 세 계약 파일을 읽는다. */
    public static OpenApiContracts load() {
        Path dir = WebFixtures.repoRoot().resolve("contracts");
        Map<String, Operation> operations = new LinkedHashMap<>();
        read(dir.resolve("parent-api.yaml"), "/api", operations);
        read(dir.resolve("device-api.yaml"), "/api", operations);
        read(dir.resolve("internal-api.yaml"), "", operations);
        return new OpenApiContracts(operations);
    }

    private static void read(Path file, String prefix, Map<String, Operation> into) {
        JsonNode root = YAML.readTree(file.toFile());
        JsonNode components = root.path("components").path("responses");
        for (Map.Entry<String, JsonNode> path : root.path("paths").properties()) {
            for (Map.Entry<String, JsonNode> op : path.getValue().properties()) {
                if (!METHODS.contains(op.getKey())) {
                    continue;
                }
                String key = op.getKey().toUpperCase(Locale.ROOT) + " " + prefix + path.getKey();
                Map<String, JsonNode> responses = new LinkedHashMap<>();
                for (Map.Entry<String, JsonNode> response : op.getValue().path("responses").properties()) {
                    JsonNode node = response.getValue();
                    String ref = node.path("$ref").asString("");
                    if (ref.startsWith("#/components/responses/")) {
                        node = components.path(ref.substring("#/components/responses/".length()));
                    }
                    responses.put(response.getKey(), node);
                }
                into.put(key, new Operation(key, responses));
            }
        }
    }

    /** 모든 연산 키. */
    public Set<String> keys() {
        return new TreeSet<>(operations.keySet());
    }

    public Optional<Operation> operation(String key) {
        return Optional.ofNullable(operations.get(key));
    }

    /**
     * 응답이 연산 계약에 맞는지(위반 목록, 맞으면 빈 목록).
     *
     * @param key 연산 키(예: {@code "POST /api/device/register"})
     */
    public List<String> violations(String key, Result result) {
        List<String> problems = new ArrayList<>();
        Operation operation = operations.get(key);
        if (operation == null) {
            problems.add(key + ": 계약에 없는 연산");
            return problems;
        }
        JsonNode response = operation.responses().get(Integer.toString(result.status()));
        if (response == null) {
            problems.add(key + ": 문서에 없는 상태 " + result.status());
            return problems;
        }
        JsonNode schemaRef = response.path("content").path("application/json").path("schema").path("$ref");
        if (schemaRef.isMissingNode()) {
            if (!result.body().isEmpty()) {
                problems.add(key + " " + result.status() + ": 본문이 없어야 한다");
            }
        } else {
            String schemaFile = schemaRef.asString().substring(schemaRef.asString().lastIndexOf('/') + 1);
            WebFixtures.schemaErrors(schemaFile, result.body())
                    .forEach(error -> problems.add(key + " " + result.status() + " " + schemaFile + ": " + error));
        }
        JsonNode codes = response.path("x-error-codes");
        if (codes.isArray() && result.status() >= 400) {
            List<String> allowed = new ArrayList<>();
            codes.forEach(code -> allowed.add(code.asString()));
            String code = result.code();
            if (!allowed.contains(code)) {
                problems.add(key + " " + result.status() + ": 문서에 없는 code " + code + " (허용 " + allowed + ")");
            }
        }
        return problems;
    }

    /** 연산 하나(상태 코드 → 응답 노드, 참조는 풀어 둠). */
    public record Operation(String key, Map<String, JsonNode> responses) {
    }
}
