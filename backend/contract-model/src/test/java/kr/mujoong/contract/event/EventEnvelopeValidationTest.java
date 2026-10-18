package kr.mujoong.contract.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import kr.mujoong.contract.event.EventEnvelope.InvalidEnvelopeException;
import kr.mujoong.contract.event.payload.DeletionCompleted;
import kr.mujoong.contract.event.payload.DeletionRequested;
import kr.mujoong.contract.event.payload.DeviceReleased;
import kr.mujoong.contract.event.payload.DeviceStatusChanged;
import kr.mujoong.contract.event.payload.SessionRecordCaptured;
import kr.mujoong.contract.event.payload.SessionStateChanged;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.FailureCode;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.trace.TraceIds;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 영속 이벤트 봉투 검사(BR5.4 · BR5.9 · 편차 (p) · OD-7 · 계약 6절). */
@Tag("skeleton")
class EventEnvelopeValidationTest {

    private static final JsonCodec CODEC = JsonCodec.shared();
    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID GUARDIAN_ID = UUID.fromString("00000000-0000-4000-8000-000000000003");
    private static final UUID DEVICE_ID = UUID.fromString("00000000-0000-4000-8000-000000000004");
    private static final Instant AT = Instant.parse("2026-10-15T09:00:00.123Z");

    private static String validBody() {
        return """
                {"eventId":"00000000-0000-4000-8000-000000000001","eventType":"DeviceReleased","schemaVersion":1,
                 "occurredAt":"2026-10-15T09:00:00.123Z","aggregateType":"Device",
                 "aggregateId":"00000000-0000-4000-8000-000000000004",
                 "payload":{"reason":"REREGISTERED","deviceId":"00000000-0000-4000-8000-000000000004"}}
                """;
    }

    @Test
    void validEnvelopeDecodesAllSevenRequiredFieldsAndNormalizesPayload() {
        EventEnvelope envelope = CODEC.decodeEventEnvelope(validBody());

        assertThat(envelope.eventId()).isEqualTo(EVENT_ID);
        assertThat(envelope.type()).isEqualTo(EventType.DEVICE_RELEASED);
        assertThat(envelope.schemaVersion()).isEqualTo(1);
        assertThat(envelope.occurredAt()).isEqualTo(AT);
        assertThat(envelope.aggregate()).isEqualTo(AggregateType.DEVICE);
        assertThat(envelope.aggregateId()).isEqualTo(DEVICE_ID);
        assertThat(envelope.traceId()).isNull();
        assertThat(envelope.payloadJson())
                .isEqualTo("{\"deviceId\":\"00000000-0000-4000-8000-000000000004\",\"reason\":\"REREGISTERED\"}");
        assertThat(envelope.isHandleable()).isTrue();
        assertThat(CODEC.readPayload(envelope, DeviceReleased.class).reason()).isEqualTo(DeviceReleased.Reason.REREGISTERED);
    }

    static Stream<Arguments> missingRequiredFields() {
        return Stream.of(
                Arguments.of("eventId", "eventId:missing"),
                Arguments.of("eventType", "eventType:missing"),
                Arguments.of("schemaVersion", "schemaVersion:missing"),
                Arguments.of("occurredAt", "occurredAt:missing"),
                Arguments.of("aggregateType", "aggregateType:missing"),
                Arguments.of("aggregateId", "aggregateId:missing"),
                Arguments.of("payload", "payload:missing"));
    }

    @ParameterizedTest
    @MethodSource("missingRequiredFields")
    void eachMissingRequiredFieldIsReportedByNameOnly(String field, String problem) {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = CODEC.read(validBody(), java.util.LinkedHashMap.class);
        body.remove(field);

        assertThatThrownBy(() -> CODEC.decodeEventEnvelope(CODEC.write(body)))
                .isInstanceOfSatisfying(InvalidEnvelopeException.class, e -> assertThat(e.problems()).containsExactly(problem));
    }

    @Test
    void malformedValuesAreReportedWithoutEchoingThem() {
        String body = """
                {"eventId":"not-a-uuid-secret-value","eventType":"","schemaVersion":"1",
                 "occurredAt":"yesterday","aggregateType":7,"aggregateId":"00000000000040008000000000000004",
                 "payload":[1,2]}
                """;

        assertThatThrownBy(() -> CODEC.decodeEventEnvelope(body))
                .isInstanceOfSatisfying(InvalidEnvelopeException.class, e -> {
                    assertThat(e.problems()).containsExactly(
                            "eventId:invalid", "eventType:invalid", "schemaVersion:invalid", "occurredAt:invalid",
                            "aggregateType:invalid", "aggregateId:invalid", "payload:notObject");
                    assertThat(e.getMessage()).doesNotContain("secret-value", "yesterday");
                });
        assertThatThrownBy(() -> CODEC.decodeEventEnvelope("not json"))
                .isInstanceOfSatisfying(InvalidEnvelopeException.class,
                        e -> assertThat(e.problems()).containsExactly("body:notJsonObject"));
        assertThatThrownBy(() -> CODEC.decodeEventEnvelope("[]"))
                .isInstanceOfSatisfying(InvalidEnvelopeException.class,
                        e -> assertThat(e.problems()).containsExactly("body:notJsonObject"));
        assertThatThrownBy(() -> CODEC.decodeEventEnvelope(null)).isInstanceOf(InvalidEnvelopeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.5", "2147483648"})
    void schemaVersionMustBeAnIntegerOfAtLeastOne(String version) {
        String body = validBody().replace("\"schemaVersion\":1", "\"schemaVersion\":" + version);

        assertThatThrownBy(() -> CODEC.decodeEventEnvelope(body))
                .isInstanceOfSatisfying(InvalidEnvelopeException.class,
                        e -> assertThat(e.problems()).containsExactly("schemaVersion:invalid"));
    }

    @Test
    void eventTypeLongerThan100CharactersIsInvalid() {
        String body = validBody().replace("\"DeviceReleased\"", "\"" + "X".repeat(101) + "\"");

        assertThatThrownBy(() -> CODEC.decodeEventEnvelope(body))
                .isInstanceOfSatisfying(InvalidEnvelopeException.class,
                        e -> assertThat(e.problems()).containsExactly("eventType:tooLong"));
    }

    @Test
    void unknownFieldsAreIgnored() {
        String body = validBody().replace("\"schemaVersion\":1,", "\"schemaVersion\":1,\"futureField\":{\"x\":1},");

        EventEnvelope envelope = CODEC.decodeEventEnvelope(body);

        assertThat(envelope.type()).isEqualTo(EventType.DEVICE_RELEASED);
    }

    @Test
    void traceIdIsOptionalAndKeptOnlyWhenWellFormed() {
        String traceId = TraceIds.newTraceId();
        String withTrace = validBody().replace("\"schemaVersion\":1,", "\"schemaVersion\":1,\"traceId\":\"" + traceId + "\",");
        String withBadTrace = validBody().replace("\"schemaVersion\":1,", "\"schemaVersion\":1,\"traceId\":\"bad\\nline\",");

        assertThat(CODEC.decodeEventEnvelope(withTrace).traceId()).isEqualTo(traceId);
        assertThat(CODEC.decodeEventEnvelope(withBadTrace).traceId()).isNull();
        assertThat(CODEC.decodeEventEnvelope(validBody()).traceId()).isNull();
    }

    @Test
    void unknownEventTypeOrNewerSchemaVersionIsStillAValidEnvelopeButNotHandleable() {
        EventEnvelope unknownType = CODEC.decodeEventEnvelope(validBody()
                .replace("\"DeviceReleased\"", "\"DeviceRenamed\"")
                .replace("\"Device\"", "\"Gadget\""));
        EventEnvelope newerVersion = CODEC.decodeEventEnvelope(validBody().replace("\"schemaVersion\":1", "\"schemaVersion\":2"));

        assertThat(unknownType.type()).isEqualTo(EventType.UNRECOGNIZED);
        assertThat(unknownType.aggregate()).isEqualTo(AggregateType.UNRECOGNIZED);
        assertThat(unknownType.isHandleable()).isFalse();
        assertThat(newerVersion.isHandleable()).isFalse();
    }

    @Test
    void unknownEnumValuesInsidePayloadReadAsUnrecognizedAndFailPayloadValidation() {
        EventEnvelope envelope = CODEC.decodeEventEnvelope(validBody().replace("REREGISTERED", "STOLEN"));

        assertThatThrownBy(() -> CODEC.readPayload(envelope, DeviceReleased.class))
                .isInstanceOfSatisfying(InvalidEnvelopeException.class,
                        e -> assertThat(e.problems()).containsExactly("payload.reason:invalid"));
        assertThatThrownBy(() -> CODEC.readPayload(
                        CODEC.decodeEventEnvelope(validBody().replace("{\"reason\"", "{\"reason\":[1],\"x\"")),
                        DeviceReleased.class))
                .isInstanceOf(InvalidEnvelopeException.class);
    }

    @Test
    void wireNamesDirectionsAndControlEventsFollowContractSection6() {
        assertThat(EventType.fromWire("SessionStateChanged")).isEqualTo(EventType.SESSION_STATE_CHANGED);
        assertThat(EventType.fromWire(null)).isEqualTo(EventType.UNRECOGNIZED);
        assertThat(EventType.DEVICE_RELEASED.direction()).isEqualTo(EventType.Direction.CORE_TO_REALTIME);
        assertThat(EventType.DELETION_REQUESTED.direction()).isEqualTo(EventType.Direction.CORE_TO_REALTIME);
        assertThat(EventType.SESSION_STATE_CHANGED.direction()).isEqualTo(EventType.Direction.REALTIME_TO_CORE);
        assertThat(Stream.of(EventType.values()).filter(EventType::isControlEvent)).containsExactlyInAnyOrder(
                EventType.SESSION_RECORD_CAPTURED, EventType.DELETION_COMPLETED, EventType.DEVICE_RELEASED,
                EventType.DELETION_REQUESTED);
        assertThat(EventType.UNRECOGNIZED.wire()).isNull();
        assertThat(EventType.UNRECOGNIZED.payloadType()).isNull();
        assertThat(AggregateType.fromWire("DataDeletionRequest")).isEqualTo(AggregateType.DATA_DELETION_REQUEST);
        assertThat(AggregateType.fromWire(null)).isEqualTo(AggregateType.UNRECOGNIZED);
    }

    static Stream<Arguments> validPayloads() {
        Instant earlier = AT.minusSeconds(60);
        return Stream.of(
                Arguments.of(new SessionStateChanged(SESSION_ID, GUARDIAN_ID, null, ActivityType.FREE_TALK,
                        SessionStatus.ENDED, 5L, EndReason.GUARDIAN_STOP, null, true, null, AT)),
                Arguments.of(new SessionRecordCaptured(SESSION_ID, GUARDIAN_ID, UUID.randomUUID(), ActivityType.FREE_TALK,
                        SessionStatus.ENDED, "GUARDIAN_STOP", true, earlier, AT, Map.of("cause", "GUARDIAN"),
                        UUID.randomUUID(), 1, "sha256:" + "a".repeat(64), Map.of("turns", 3))),
                Arguments.of(new DeviceStatusChanged(DEVICE_ID, ConnectionState.UNSTABLE, 120, 1, AT, earlier, GUARDIAN_ID)),
                Arguments.of(new DeletionCompleted(UUID.randomUUID(), GUARDIAN_ID, AT,
                        new DeletionCompleted.Counts(1, 2, 0, 0))),
                Arguments.of(new DeviceReleased(DEVICE_ID, GUARDIAN_ID, DeviceReleased.Reason.ACCOUNT_DELETED)),
                Arguments.of(new DeletionRequested(UUID.randomUUID(), GUARDIAN_ID, UUID.randomUUID(), AT)));
    }

    @ParameterizedTest
    @MethodSource("validPayloads")
    void everyPayloadTypeRoundTripsThroughTheEnvelope(EventPayload payload) {
        String traceId = TraceIds.newTraceId();

        EventEnvelope sent = CODEC.envelopeFor(EVENT_ID, payload, AT, traceId);
        EventEnvelope received = CODEC.decodeEventEnvelope(CODEC.encodeEventEnvelope(sent));

        assertThat(received).isEqualTo(sent);
        assertThat(received.type()).isEqualTo(payload.eventType());
        assertThat(received.aggregate()).isEqualTo(payload.eventType().aggregateType());
        assertThat(received.aggregateId()).isEqualTo(payload.aggregateId());
        assertThat(received.schemaVersion()).isEqualTo(EventType.CURRENT_SCHEMA_VERSION);
        assertThat(CODEC.readPayload(received, payload.eventType().payloadType())).isEqualTo(payload);
    }

    static Stream<Arguments> invalidPayloads() {
        return Stream.of(
                Arguments.of(new SessionStateChanged(null, null, null, null, SessionStatus.REQUESTED, 0L, null,
                        FailureCode.DEVICE_NO_ACK, null, null, null),
                        List.of("sessionId:missing", "guardianId:missing", "status:invalid", "statusVersion:invalid",
                                "at:missing")),
                Arguments.of(new SessionRecordCaptured(null, null, null, ActivityType.UNRECOGNIZED, SessionStatus.ACTIVE,
                        null, null, null, null, null, null, null, "sha256:XYZ", null),
                        List.of("sessionId:missing", "guardianId:missing", "childId:missing", "activityType:invalid",
                                "finalStatus:invalid", "snapshotId:missing", "snapshotHash:invalid")),
                Arguments.of(new DeviceStatusChanged(null, ConnectionState.UNRECOGNIZED, -1, -1, null, null, null),
                        List.of("deviceId:missing", "state:invalid", "since:missing", "rttMs:negative",
                                "recentDisconnects:negative")),
                Arguments.of(new DeletionCompleted(null, null, null, null),
                        List.of("deletionRequestId:missing", "guardianId:missing", "completedAt:missing")),
                Arguments.of(new DeviceReleased(null, null, null), List.of("deviceId:missing", "reason:invalid")),
                Arguments.of(new DeletionRequested(null, null, null, null),
                        List.of("deletionRequestId:missing", "guardianId:missing", "requestedAt:missing")));
    }

    @ParameterizedTest
    @MethodSource("invalidPayloads")
    void senderRefusesToWrapAPayloadThatBreaksTheContract(EventPayload payload, List<String> expectedProblems) {
        assertThat(payload.problems()).containsExactlyElementsOf(expectedProblems);
        assertThatThrownBy(() -> EventEnvelope.create(EVENT_ID, payload, "{}", AT, null))
                .isInstanceOfSatisfying(InvalidEnvelopeException.class,
                        e -> assertThat(e.problems()).containsExactlyElementsOf(expectedProblems));
    }

    @Test
    void recordConstructorEnforcesRequiredFieldsAndTraceFormat() {
        assertThatThrownBy(() -> new EventEnvelope(null, "X", 1, AT, "Device", DEVICE_ID, null, "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventEnvelope(EVENT_ID, " ", 1, AT, "Device", DEVICE_ID, null, "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventEnvelope(EVENT_ID, "X", 0, AT, "Device", DEVICE_ID, null, "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventEnvelope(EVENT_ID, "X", 1, null, "Device", DEVICE_ID, null, "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventEnvelope(EVENT_ID, "X", 1, AT, "", DEVICE_ID, null, "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventEnvelope(EVENT_ID, "X", 1, AT, "Device", null, null, "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventEnvelope(EVENT_ID, "X", 1, AT, "Device", DEVICE_ID, null, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventEnvelope(EVENT_ID, "X", 1, AT, "Device", DEVICE_ID, "XYZ", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventEnvelope.create(EVENT_ID, null, "{}", AT, null))
                .isInstanceOf(IllegalArgumentException.class);
        EventEnvelope created = EventEnvelope.create(
                EVENT_ID, new DeviceReleased(DEVICE_ID, null, DeviceReleased.Reason.REREGISTERED), "{}", AT, "bad");
        assertThat(created.traceId()).isNull();
    }
}
