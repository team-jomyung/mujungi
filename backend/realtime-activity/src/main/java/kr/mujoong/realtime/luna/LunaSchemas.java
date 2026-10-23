package kr.mujoong.realtime.luna;

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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import kr.mujoong.contract.json.JsonCodec;

/**
 * 요청 스키마 — realtime (U9 BR2.4 · SD4 · Q3-A). core에 같은 모양이 따로 있다(R-F5). 기동 때 실행 자원 {@code luna/schemas/}에서 목적마다
 * 요청 스키마(보내기 전 입력 검증)를 만든다. 진행 중 판정은 Decisions API로 묻고 응답은 호출부가 보낸 선택지로 검증하므로 출력 스키마는 쓰지
 * 않는다(FT-1023-d).
 *
 * <p>하나라도 읽거나 만들지 못하거나 정상 예시가 검증을 통과하지 못하면 {@link LoadException}으로 기동을 거부한다. 검증기는 문자열 입력으로만
 * 부른다(J139). 예외 메시지에는 사유와 파일 이름만 둔다.
 */
public final class LunaSchemas {

    /** 실행 자원 폴더. */
    public static final String RESOURCE_DIR = "luna/schemas/";

    static final String ID_PREFIX = "https://mujoong.invalid/contracts/schemas/";

    private final JsonCodec json;
    private final Map<LunaPurpose, Schema> requestSchemas;

    private LunaSchemas(JsonCodec json, Map<LunaPurpose, Schema> requestSchemas) {
        this.json = json;
        this.requestSchemas = requestSchemas;
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
        Map<LunaPurpose, Schema> requestSchemas = new EnumMap<>(LunaPurpose.class);
        for (LunaPurpose purpose : LunaPurpose.values()) {
            String requestFile = purpose.requestSchema();
            Map<String, Object> request = readDocument(json, reader, requestFile);
            Schema requestSchema = compile(registry, requestFile);
            requireExamplesPass(json, requestSchema, request, requestFile);
            requestSchemas.put(purpose, requestSchema);
        }
        return new LunaSchemas(json, Map.copyOf(requestSchemas));
    }

    /** 정리된 입력이 목적의 요청 스키마를 통과하는가(BR2.4). JSON 객체가 아니어도 거짓이다. */
    public boolean isValidRequest(LunaPurpose purpose, String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            return false;
        }
        try {
            if (!(json.read(inputJson, Object.class) instanceof Map<?, ?>)) {
                return false;
            }
            return requestSchemas.get(purpose).validate(inputJson, InputFormat.JSON).isEmpty();
        } catch (RuntimeException e) {
            // JSON이 아니거나 검증기가 읽지 못한 값은 위반으로 본다(원인 메시지는 값 일부를 담을 수 있어 버린다, BR6.2)
            return false;
        }
    }

    // ------------------------------------------------------------------ 읽기

    private static Schema compile(SchemaRegistry registry, String file) {
        try {
            return registry.getSchema(SchemaLocation.of(ID_PREFIX + file));
        } catch (RuntimeException e) {
            throw new LoadException("SCHEMA_COMPILE_FAILED", file);
        }
    }

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

    private static void requireExamplesPass(JsonCodec json, Schema schema, Map<String, Object> document, String file) {
        Object examples = document.get("examples");
        if (!(examples instanceof List<?> list) || list.isEmpty()) {
            throw new LoadException("SCHEMA_EXAMPLE_MISSING", file);
        }
        for (Object example : list) {
            boolean valid;
            try {
                valid = schema.validate(json.write(example), InputFormat.JSON).isEmpty();
            } catch (RuntimeException e) {
                // 참조를 풀지 못한 스키마 등은 예시 검증에서 드러난다(원인 메시지 버림)
                valid = false;
            }
            if (!valid) {
                throw new LoadException("SCHEMA_EXAMPLE_INVALID", file);
            }
        }
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

    /** 스키마 적재 실패(기동 거부). 메시지는 사유 코드와 파일 이름뿐이다. */
    public static final class LoadException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public LoadException(String reason, String file) {
            super(reason + ": " + file);
        }
    }
}
