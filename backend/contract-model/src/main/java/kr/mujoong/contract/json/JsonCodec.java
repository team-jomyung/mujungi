package kr.mujoong.contract.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorEnvelope;
import kr.mujoong.contract.event.EventEnvelope;
import kr.mujoong.contract.event.EventPayload;
import kr.mujoong.contract.secret.SecretCompare;
import kr.mujoong.contract.trace.TraceIds;
import kr.mujoong.contract.ws.WsEnvelope;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.WsPayload;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.DeserializationConfig;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.deser.ValueDeserializerModifier;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.node.ObjectNode;

/**
 * 앱 코드의 Jackson 직접 사용이 모이는 한 곳(J139: Boot 3.5.x로 바꿀 때 Jackson 좌표 {@code tools.jackson} → {@code com.fasterxml}를 여기서만
 * 고친다). 계약 JSON의 읽기 · 쓰기 규칙을 한 번만 정한다(계약 10절 · BR5.4 · BR5.9 · BR8.10).
 *
 * <ul>
 *   <li>모르는 필드는 무시한다. 모르는 enum 값은 그 enum의 {@code UNRECOGNIZED} 상수로 읽는다('알 수 없음', 오류로 끊지 않음).
 *   <li>시각은 ISO 8601 UTC 문자열(밀리초까지, 끝 Z)로 쓴다. null 필드는 쓰지 않는다.
 *   <li>예외 메시지에 입력 본문을 넣지 않는다(SD-11 · BR5.13).
 * </ul>
 */
public final class JsonCodec {

    private static final JsonCodec SHARED = new JsonCodec();

    private final JsonMapper mapper;

    public JsonCodec() {
        this.mapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(DateTimeFeature.TRUNCATE_TO_MSECS_ON_WRITE)
                .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
                .addModule(new SimpleModule("mujoong-contract").setDeserializerModifier(new UnrecognizedEnumModifier()))
                .build();
    }

    /** 공유 인스턴스(스레드 안전). */
    public static JsonCodec shared() {
        return SHARED;
    }

    /** 값을 JSON 문자열로 쓴다. */
    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new JsonCodecException("JSON으로 쓰지 못했다: " + typeName(value), e);
        }
    }

    /** JSON 문자열을 타입으로 읽는다. 실패 메시지에 본문을 넣지 않는다. */
    public <T> T read(String json, Class<T> type) {
        Objects.requireNonNull(type, "type");
        if (json == null) {
            throw new JsonCodecException("JSON 본문이 없다: " + type.getSimpleName(), null);
        }
        try {
            return mapper.readValue(json, type);
        } catch (JacksonException e) {
            throw new JsonCodecException("JSON을 읽지 못했다: " + type.getSimpleName(), e);
        }
    }

    /** JSON 글의 최상위 모양. 공급자 출력이 왜 객체로 읽히지 않았는지 가르는 진단용이며 본문은 담지 않는다. */
    public enum Shape {
        /** JSON 객체 하나. */
        OBJECT,
        /** 읽히는 JSON이지만 최상위가 객체가 아니다(배열 · 문자열 · 숫자 등). */
        NOT_OBJECT,
        /** 앞부분은 JSON 객체인데 뒤에 다른 글이 붙어 있다(객체 두 번 · 설명 덧붙임 등). */
        TRAILING_CONTENT,
        /** JSON으로 읽히지 않는다(잘린 출력 · 마크다운 울타리 등). */
        NOT_JSON
    }

    /** 글의 최상위 JSON 모양. OBJECT는 {@link #read}로 읽어 지도가 나오는 경우와 같다. */
    public Shape shape(String text) {
        if (text == null) {
            return Shape.NOT_JSON;
        }
        try {
            if (mapper.readValue(text, Object.class) instanceof Map<?, ?>) {
                return Shape.OBJECT;
            }
        } catch (JacksonException e) {
            // 엄격하게 읽히지 않으면 앞 값만 따로 읽어 어디서 어긋났는지 가른다
        }
        JsonNode first;
        try {
            first = mapper.readerFor(JsonNode.class).without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(text);
        } catch (JacksonException e) {
            return Shape.NOT_JSON;
        }
        return first != null && first.isObject() ? Shape.TRAILING_CONTENT : Shape.NOT_OBJECT;
    }

    /**
     * 정규화 JSON: 객체 키를 사전순으로 정렬하고 공백 없이 쓴다. payload 해시(payload_hash · request_hash)의 입력이다(BR5.5).
     */
    public String canonicalize(String json) {
        return writeNode(sortKeys(readTree(json)));
    }

    /** 값을 정규화 JSON으로 쓴다. */
    public String canonicalJson(Object value) {
        return canonicalize(write(value));
    }

    /** 정규화 JSON의 SHA-256 소문자 16진수(payload_hash, BR5.1 · BR5.5). */
    public String sha256OfCanonical(String json) {
        return SecretCompare.sha256Hex(canonicalize(json));
    }

    /** 오류 봉투를 쓴다. */
    public String writeError(ErrorEnvelope envelope) {
        return write(envelope);
    }

    // ---- 영속 이벤트 봉투 (계약 6절 · BR5.4) ----

    /** 보내는 쪽 봉투(payload를 정규화해 싣는다). */
    public EventEnvelope envelopeFor(UUID eventId, EventPayload payload, Instant occurredAt, String traceId) {
        Objects.requireNonNull(payload, "payload");
        return EventEnvelope.create(eventId, payload, canonicalJson(payload), occurredAt, traceId);
    }

    /** 봉투를 전송 JSON으로 쓴다(payload는 객체로 싣는다). */
    public String encodeEventEnvelope(EventEnvelope envelope) {
        ObjectNode node = mapper.createObjectNode();
        node.put("eventId", envelope.eventId().toString());
        node.put("eventType", envelope.eventType());
        node.put("schemaVersion", envelope.schemaVersion());
        node.set("occurredAt", mapper.valueToTree(envelope.occurredAt()));
        node.put("aggregateType", envelope.aggregateType());
        node.put("aggregateId", envelope.aggregateId().toString());
        if (envelope.traceId() != null) {
            node.put("traceId", envelope.traceId());
        }
        node.set("payload", readTree(envelope.payloadJson()));
        return writeNode(node);
    }

    /**
     * 받은 본문을 봉투로 읽는다(BR5.4). 필수 7필드가 없거나 형식이 틀리면 {@link EventEnvelope.InvalidEnvelopeException}
     * (400 INVALID_ENVELOPE). 모르는 필드는 무시하고 형식이 틀린 traceId는 없는 것으로 본다(OD-7). eventType · schemaVersion이
     * 모르는 값이어도 봉투로는 유효하다(BR5.9 IGNORED는 받는 쪽이 정한다).
     */
    public EventEnvelope decodeEventEnvelope(String body) {
        JsonNode root;
        try {
            root = body == null ? null : mapper.readTree(body);
        } catch (JacksonException e) {
            root = null;
        }
        if (root == null || !root.isObject()) {
            throw new EventEnvelope.InvalidEnvelopeException(List.of("body:notJsonObject"));
        }
        List<String> problems = new ArrayList<>();
        UUID eventId = uuidField(root, "eventId", problems);
        String eventType = textField(root, "eventType", problems);
        if (eventType != null && eventType.length() > 100) {
            problems.add("eventType:tooLong");
        }
        Integer schemaVersion = null;
        JsonNode version = root.get("schemaVersion");
        if (version == null || version.isNull()) {
            problems.add("schemaVersion:missing");
        } else if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() < 1) {
            problems.add("schemaVersion:invalid");
        } else {
            schemaVersion = version.intValue();
        }
        Instant occurredAt = instantField(root, "occurredAt", problems);
        String aggregateType = textField(root, "aggregateType", problems);
        UUID aggregateId = uuidField(root, "aggregateId", problems);
        JsonNode payload = root.get("payload");
        if (payload == null || payload.isNull()) {
            problems.add("payload:missing");
        } else if (!payload.isObject()) {
            problems.add("payload:notObject");
        }
        if (!problems.isEmpty()) {
            throw new EventEnvelope.InvalidEnvelopeException(problems);
        }
        JsonNode trace = root.get("traceId");
        String traceId = trace != null && trace.isString() && TraceIds.isValid(trace.stringValue()) ? trace.stringValue() : null;
        return new EventEnvelope(eventId, eventType, schemaVersion, occurredAt, aggregateType, aggregateId, traceId,
                writeNode(sortKeys(payload)));
    }

    /**
     * 봉투의 payload를 타입으로 읽고 형식을 확인한다. 위반이면 {@link EventEnvelope.InvalidEnvelopeException}(필드 이름만).
     */
    public <T extends EventPayload> T readPayload(EventEnvelope envelope, Class<T> type) {
        T payload;
        try {
            payload = mapper.readValue(envelope.payloadJson(), type);
        } catch (JacksonException e) {
            throw new EventEnvelope.InvalidEnvelopeException(List.of("payload:invalid"));
        }
        List<String> problems = payload.problems();
        if (!problems.isEmpty()) {
            throw new EventEnvelope.InvalidEnvelopeException(problems.stream().map(p -> "payload." + p).toList());
        }
        return payload;
    }

    // ---- 기기 WSS 제어 봉투 (계약 4.2 · J123) ----

    /** 보낼 봉투를 텍스트 프레임 JSON으로 쓴다. 형식 위반이면 {@link IllegalArgumentException}(필드 이름만). */
    public String encodeWs(WsEnvelope envelope) {
        List<String> problems = envelope.problems();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("WSS 봉투 형식 위반: " + String.join(", ", problems));
        }
        ObjectNode node = mapper.createObjectNode();
        node.put("type", envelope.type());
        node.put("seq", envelope.seq());
        node.set("sentAt", mapper.valueToTree(envelope.sentAt()));
        if (envelope.opId() != null) {
            node.put("opId", envelope.opId().toString());
        }
        if (envelope.sessionId() != null) {
            node.put("sessionId", envelope.sessionId().toString());
        }
        node.set("payload", mapper.valueToTree(envelope.payload()));
        return writeNode(node);
    }

    /**
     * 받은 텍스트 프레임을 해석한다. JSON이 아니거나 봉투 필수 필드가 틀리면 MALFORMED, 모르는 type은 UNKNOWN_TYPE, payload · opId ·
     * sessionId 규칙 위반은 BAD_PAYLOAD다. 크기 검사(8KB)와 방향 검사는 받는 쪽이 한다(J123).
     */
    public WsEnvelope.Decoded decodeWs(String text) {
        JsonNode root;
        try {
            root = text == null ? null : mapper.readTree(text);
        } catch (JacksonException e) {
            root = null;
        }
        if (root == null || !root.isObject()) {
            return WsEnvelope.Decoded.failure(WsEnvelope.Outcome.MALFORMED, null, null, List.of("body:notJsonObject"));
        }
        List<String> problems = new ArrayList<>();
        JsonNode typeNode = root.get("type");
        String type = typeNode != null && typeNode.isString() && !typeNode.stringValue().isBlank() ? typeNode.stringValue() : null;
        if (type == null) {
            problems.add("type:missing");
        }
        Long seq = null;
        JsonNode seqNode = root.get("seq");
        if (seqNode == null || !seqNode.isIntegralNumber() || !seqNode.canConvertToLong()
                || seqNode.longValue() < 1 || seqNode.longValue() > WsEnvelope.MAX_SEQ) {
            problems.add("seq:invalid");
        } else {
            seq = seqNode.longValue();
        }
        Instant sentAt = instantField(root, "sentAt", problems);
        JsonNode payloadNode = root.get("payload");
        if (payloadNode == null || !payloadNode.isObject()) {
            problems.add("payload:missing");
        }
        if (!problems.isEmpty()) {
            return WsEnvelope.Decoded.failure(WsEnvelope.Outcome.MALFORMED, type, seq, problems);
        }
        WsMessageType messageType = WsMessageType.fromWire(type);
        if (messageType == WsMessageType.UNRECOGNIZED) {
            return WsEnvelope.Decoded.failure(WsEnvelope.Outcome.UNKNOWN_TYPE, type, seq, List.of("type:unknown"));
        }
        UUID opId = optionalUuid(root, "opId", problems);
        UUID sessionId = optionalUuid(root, "sessionId", problems);
        WsPayload payload = null;
        try {
            payload = mapper.treeToValue(payloadNode, messageType.payloadType());
        } catch (JacksonException e) {
            problems.add("payload:invalid");
        }
        if (!problems.isEmpty()) {
            return WsEnvelope.Decoded.failure(WsEnvelope.Outcome.BAD_PAYLOAD, type, seq, problems);
        }
        WsEnvelope envelope = new WsEnvelope(type, seq, sentAt, opId, sessionId, payload);
        List<String> envelopeProblems = envelope.problems();
        if (!envelopeProblems.isEmpty()) {
            return WsEnvelope.Decoded.failure(WsEnvelope.Outcome.BAD_PAYLOAD, type, seq, envelopeProblems);
        }
        return WsEnvelope.Decoded.ok(envelope);
    }

    // ---- 내부 도구 ----

    private JsonNode readTree(String json) {
        if (json == null) {
            throw new JsonCodecException("JSON 본문이 없다", null);
        }
        try {
            return mapper.readTree(json);
        } catch (JacksonException e) {
            throw new JsonCodecException("JSON을 읽지 못했다", e);
        }
    }

    private String writeNode(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JacksonException e) {
            throw new JsonCodecException("JSON으로 쓰지 못했다", e);
        }
    }

    private JsonNode sortKeys(JsonNode node) {
        if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                sorted.put(property.getKey(), sortKeys(property.getValue()));
            }
            ObjectNode copy = mapper.createObjectNode();
            sorted.forEach(copy::set);
            return copy;
        }
        if (node.isArray()) {
            var array = mapper.createArrayNode();
            for (JsonNode element : node) {
                array.add(sortKeys(element));
            }
            return array;
        }
        return node;
    }

    private static String textField(JsonNode root, String name, List<String> problems) {
        JsonNode node = root.get(name);
        if (node == null || node.isNull()) {
            problems.add(name + ":missing");
            return null;
        }
        if (!node.isString() || node.stringValue().isBlank()) {
            problems.add(name + ":invalid");
            return null;
        }
        return node.stringValue();
    }

    private static UUID uuidField(JsonNode root, String name, List<String> problems) {
        String text = textField(root, name, problems);
        if (text == null) {
            return null;
        }
        try {
            return parseUuid(text);
        } catch (IllegalArgumentException e) {
            problems.add(name + ":invalid");
            return null;
        }
    }

    private static UUID optionalUuid(JsonNode root, String name, List<String> problems) {
        JsonNode node = root.get(name);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            problems.add(name + ":invalid");
            return null;
        }
        try {
            return parseUuid(node.stringValue());
        } catch (IllegalArgumentException e) {
            problems.add(name + ":invalid");
            return null;
        }
    }

    private static Instant instantField(JsonNode root, String name, List<String> problems) {
        String text = textField(root, name, problems);
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            problems.add(name + ":invalid");
            return null;
        }
    }

    /** 36자 정규 형식 UUID만 받는다({@link UUID#fromString}의 느슨한 형식은 거절). */
    private static UUID parseUuid(String text) {
        if (text.length() != 36) {
            throw new IllegalArgumentException("UUID 형식이 아니다");
        }
        return UUID.fromString(text);
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    /** JSON 읽기 · 쓰기 실패. 메시지에 본문을 넣지 않는다. */
    public static final class JsonCodecException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        JsonCodecException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 모르는 enum 값을 그 enum의 {@code UNRECOGNIZED} 상수로 읽게 하는 수정자(계약 10절 '알 수 없음'). */
    private static final class UnrecognizedEnumModifier extends ValueDeserializerModifier {

        private static final long serialVersionUID = 1L;

        @Override
        public ValueDeserializer<?> modifyEnumDeserializer(
                DeserializationConfig config, JavaType type, BeanDescription.Supplier beanDescRef,
                ValueDeserializer<?> deserializer) {
            Object[] constants = type.getRawClass().getEnumConstants();
            if (constants == null) {
                return deserializer;
            }
            for (Object constant : constants) {
                if ("UNRECOGNIZED".equals(((Enum<?>) constant).name())) {
                    return new TolerantEnumDeserializer(constants, constant);
                }
            }
            return deserializer;
        }
    }

    /** 이름이 맞으면 그 상수, 아니면 UNRECOGNIZED. */
    private static final class TolerantEnumDeserializer extends ValueDeserializer<Object> {

        private final Object[] constants;
        private final Object fallback;

        TolerantEnumDeserializer(Object[] constants, Object fallback) {
            this.constants = constants.clone();
            this.fallback = fallback;
        }

        @Override
        public Object deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() == JsonToken.VALUE_STRING) {
                String name = parser.getString();
                for (Object constant : constants) {
                    if (constant != fallback && ((Enum<?>) constant).name().equals(name)) {
                        return constant;
                    }
                }
            } else {
                parser.skipChildren();
            }
            return fallback;
        }
    }
}
