package kr.mujoong.core.luna;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import kr.mujoong.contract.json.JsonCodec;

/**
 * 출력 스키마 짝 (U9 BR4.2 · BR4.5 · SD2 · SD4 · Q3-A). 기동 때 실행 자원 {@code luna/schemas/}(빌드가 contracts/schemas에서 복사)의 계약
 * 스키마를 모두 읽어, 출력 스키마가 있는 목적마다 다음 셋을 만든다.
 *
 * <ul>
 *   <li>검증용 원본: 계약 스키마 전체(JSON Schema 2020-12, format 단언 켬). 응답은 언제나 이것으로 검증한다.
 *   <li>요청용 사본: 공급자 구조화 출력이 받는 키워드({@link #REQUEST_KEYWORDS})만 남긴 사본. 다른 파일을 가리키는 {@code $ref}는 그 정의를
 *       끼워 넣는다(공급자는 바깥 파일을 읽지 못한다).
 *   <li>strict 판정: 사본의 모든 객체가 {@code additionalProperties: false}이고 모든 속성이 required일 때만 true.
 * </ul>
 *
 * <p>하나라도 읽거나 만들지 못하거나 스키마의 정상 예시가 검증을 통과하지 못하면 {@link LoadException}으로 기동을 거부한다(스키마 없는 호출을
 * 막음, functional-spec W1 4번). 검증기는 문자열 입력으로만 부른다(Jackson 타입을 호출부에 들이지 않음, J139). 예외 메시지에는 사유와
 * 파일 이름만 둔다.
 */
public final class LunaSchemas {

    /** 실행 자원 폴더. */
    public static final String RESOURCE_DIR = "luna/schemas/";
    /** 공급자 구조화 출력이 받는 스키마 키워드(BR4.5). */
    public static final Set<String> REQUEST_KEYWORDS = Set.of(
            "type", "properties", "required", "additionalProperties", "items", "enum", "const", "anyOf", "$defs", "$ref",
            "description");

    static final String ID_PREFIX = "https://mujoong.invalid/contracts/schemas/";

    private final JsonCodec json;
    private final Map<LunaPurpose, OutputSchemaPair> pairs;
    private final Map<LunaPurpose, Schema> contractSchemas;

    private LunaSchemas(JsonCodec json, Map<LunaPurpose, OutputSchemaPair> pairs, Map<LunaPurpose, Schema> contractSchemas) {
        this.json = json;
        this.pairs = pairs;
        this.contractSchemas = contractSchemas;
    }

    /** 실행 자원에서 읽는다. */
    public static LunaSchemas loadDefault(JsonCodec json) {
        return load(json, LunaSchemas::readResource);
    }

    /**
     * 파일 읽기 함수로 읽는다(시험이 빠진 파일 · 틀린 파일을 넣는다).
     *
     * @param reader 파일 이름 → 내용(없으면 null)
     */
    public static LunaSchemas load(JsonCodec json, Function<String, String> reader) {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(reader, "reader");
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, builder -> builder
                .schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
                .schemas(iri -> iri.startsWith(ID_PREFIX) ? reader.apply(withoutFragment(iri.substring(ID_PREFIX.length())))
                        : null));
        Map<LunaPurpose, OutputSchemaPair> pairs = new EnumMap<>(LunaPurpose.class);
        Map<LunaPurpose, Schema> contractSchemas = new EnumMap<>(LunaPurpose.class);
        for (LunaPurpose purpose : LunaPurpose.values()) {
            String file = purpose.outputSchema();
            if (file == null) {
                continue;
            }
            Map<String, Object> document = readDocument(json, reader, file);
            Map<String, Object> requestSchema = requestNode(document, json, reader, file);
            // 공급자는 최상위 anyOf를 받지 않는다. 공통 객체 모양은 전송하고,
            // APPROVE/reasons 같은 조건부 제약은 아래 원본 계약으로 반드시 재검사한다.
            if ("object".equals(requestSchema.get("type")) && requestSchema.containsKey("properties")) {
                requestSchema.remove("anyOf");
            }
            Schema contract;
            try {
                contract = registry.getSchema(SchemaLocation.of(ID_PREFIX + file));
            } catch (RuntimeException e) {
                throw new LoadException("SCHEMA_COMPILE_FAILED", file);
            }
            requireExamplesPass(json, contract, document, file);
            pairs.put(purpose, new OutputSchemaPair(file, requestSchema, isStrict(requestSchema)));
            contractSchemas.put(purpose, contract);
        }
        return new LunaSchemas(json, Map.copyOf(pairs), Map.copyOf(contractSchemas));
    }

    /** 목적의 스키마 짝(출력 스키마가 없는 목적은 빈 값). */
    public Optional<OutputSchemaPair> pair(LunaPurpose purpose) {
        return Optional.ofNullable(pairs.get(purpose));
    }

    /**
     * 출력 텍스트가 목적의 원본 계약 스키마를 통과하는가(BR4.2). JSON이 아니어도 거짓이다.
     *
     * @throws IllegalArgumentException 출력 스키마가 없는 목적
     */
    public boolean isValidOutput(LunaPurpose purpose, String outputJson) {
        Schema schema = contractSchemas.get(purpose);
        if (schema == null) {
            throw new IllegalArgumentException("출력 스키마가 없는 목적: " + purpose);
        }
        if (!isJsonObject(outputJson)) {
            return false;
        }
        try {
            return schema.validate(outputJson, InputFormat.JSON).isEmpty();
        } catch (RuntimeException e) {
            // 검증기가 읽지 못한 출력은 스키마 위반으로 본다(원인 메시지는 출력 일부를 담을 수 있어 버린다, BR6.2)
            return false;
        }
    }

    /** 텍스트가 JSON 객체인가(출력 스키마가 없는 기존 인터페이스 경로의 확인, BR4.2). */
    public boolean isJsonObject(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        try {
            return json.read(text, Object.class) instanceof Map<?, ?>;
        } catch (RuntimeException e) {
            // JSON이 아니면 객체가 아니다(원인 메시지 버림, BR6.2)
            return false;
        }
    }

    // ------------------------------------------------------------------ 요청용 사본

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requestNode(Map<String, Object> node, JsonCodec json, Function<String, String> reader,
            String file) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (!REQUEST_KEYWORDS.contains(key)) {
                continue;
            }
            switch (key) {
                case "properties", "$defs" -> {
                    Map<String, Object> named = new LinkedHashMap<>();
                    asMap(value, file).forEach((name, child) ->
                            named.put(name, requestNode(asMap(child, file), json, reader, file)));
                    copy.put(key, named);
                }
                case "items" -> copy.put(key, requestNode(asMap(value, file), json, reader, file));
                case "anyOf" -> {
                    List<Object> options = new ArrayList<>();
                    for (Object option : asList(value, file)) {
                        options.add(requestNode(asMap(option, file), json, reader, file));
                    }
                    copy.put(key, options);
                }
                case "$ref" -> {
                    String ref = String.valueOf(value);
                    if (ref.startsWith("#")) {
                        copy.put(key, ref);
                    } else {
                        copy.putAll(requestNode(externalDefinition(ref, json, reader, file), json, reader, file));
                    }
                }
                default -> copy.put(key, value);
            }
        }
        return copy;
    }

    /** 다른 파일의 {@code <파일>#/$defs/<이름>} 정의. 그 정의 안에 다시 $ref가 있으면 끼워 넣을 수 없어 거부한다. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> externalDefinition(String ref, JsonCodec json, Function<String, String> reader,
            String file) {
        int hash = ref.indexOf("#/$defs/");
        if (hash <= 0) {
            throw new LoadException("UNSUPPORTED_REF", file);
        }
        Map<String, Object> other = readDocument(json, reader, ref.substring(0, hash));
        Object defs = other.get("$defs");
        Object definition = defs instanceof Map<?, ?> map ? map.get(ref.substring(hash + "#/$defs/".length())) : null;
        if (!(definition instanceof Map<?, ?> found) || containsRef(found)) {
            throw new LoadException("UNSUPPORTED_REF", file);
        }
        return (Map<String, Object>) found;
    }

    private static boolean containsRef(Object node) {
        if (node instanceof Map<?, ?> map) {
            return map.containsKey("$ref") || map.values().stream().anyMatch(LunaSchemas::containsRef);
        }
        if (node instanceof Collection<?> list) {
            return list.stream().anyMatch(LunaSchemas::containsRef);
        }
        return false;
    }

    /** 사본의 모든 객체가 닫히고 모든 속성이 required인가(BR4.5 strict 판정). */
    static boolean isStrict(Object node) {
        if (node instanceof Map<?, ?> map) {
            if (map.get("properties") instanceof Map<?, ?> properties) {
                List<?> required = map.get("required") instanceof List<?> list ? list : List.of();
                if (!Boolean.FALSE.equals(map.get("additionalProperties")) || !required.containsAll(properties.keySet())) {
                    return false;
                }
            }
            return map.values().stream().allMatch(LunaSchemas::isStrict);
        }
        if (node instanceof Collection<?> list) {
            return list.stream().allMatch(LunaSchemas::isStrict);
        }
        return true;
    }

    // ------------------------------------------------------------------ 읽기

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readDocument(JsonCodec json, Function<String, String> reader, String file) {
        String text = reader.apply(file);
        if (text == null) {
            throw new LoadException("SCHEMA_MISSING", file);
        }
        try {
            Map<String, Object> document = json.read(text, Map.class);
            if (document == null) {
                throw new LoadException("SCHEMA_UNREADABLE", file);
            }
            return document;
        } catch (LoadException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LoadException("SCHEMA_UNREADABLE", file);
        }
    }

    private static void requireExamplesPass(JsonCodec json, Schema contract, Map<String, Object> document, String file) {
        Object examples = document.get("examples");
        if (!(examples instanceof List<?> list) || list.isEmpty()) {
            throw new LoadException("SCHEMA_EXAMPLE_MISSING", file);
        }
        for (Object example : list) {
            boolean valid;
            try {
                valid = contract.validate(json.write(example), InputFormat.JSON).isEmpty();
            } catch (RuntimeException e) {
                // 참조를 풀지 못한 스키마 등은 예시 검증에서 드러난다(원인 메시지 버림)
                valid = false;
            }
            if (!valid) {
                throw new LoadException("SCHEMA_EXAMPLE_INVALID", file);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value, String file) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new LoadException("SCHEMA_SHAPE_INVALID", file);
    }

    private static List<?> asList(Object value, String file) {
        if (value instanceof List<?> list) {
            return list;
        }
        throw new LoadException("SCHEMA_SHAPE_INVALID", file);
    }

    private static String withoutFragment(String name) {
        int hash = name.indexOf('#');
        return hash < 0 ? name : name.substring(0, hash);
    }

    private static String readResource(String name) {
        try (InputStream in = LunaSchemas.class.getClassLoader().getResourceAsStream(RESOURCE_DIR + name)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 목적 하나의 스키마 짝(BR4.5).
     *
     * @param file 계약 스키마 파일 이름
     * @param requestSchema 요청 {@code text.format.schema}로 보내는 사본(허용 키워드만)
     * @param strict 요청 {@code text.format.strict} 값
     */
    public record OutputSchemaPair(String file, Map<String, Object> requestSchema, boolean strict) {

        public OutputSchemaPair {
            Objects.requireNonNull(file, "file");
            requestSchema = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(requestSchema));
        }
    }

    /** 스키마 적재 실패(기동 거부). 메시지는 사유 코드와 파일 이름뿐이다. */
    public static final class LoadException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public LoadException(String reason, String file) {
            super(reason + ": " + file);
        }
    }
}
