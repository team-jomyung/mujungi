package kr.mujoong.contract.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.error.ErrorEnvelope;
import kr.mujoong.contract.event.payload.DeviceStatusChanged;
import kr.mujoong.contract.event.payload.SessionStateChanged;
import kr.mujoong.contract.secret.SecretCompare;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.EndRequestReason;
import kr.mujoong.contract.status.FailureCode;
import kr.mujoong.contract.status.RegistrationStatus;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.trace.TraceIds;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** 계약 JSON 규칙(계약 10절 · BR5.9 · BR8.10 · BR5.5): 모르는 필드 무시 · 모르는 enum '알 수 없음' · ISO 8601 UTC · 정규화. */
@Tag("skeleton")
class JsonCodecTest {

    private static final JsonCodec CODEC = JsonCodec.shared();
    private static final UUID DEVICE_ID = UUID.fromString("00000000-0000-4000-8000-000000000004");

    /** 시험용 계약 값(JSON 형식만 보는 레코드). */
    record Sample(String name, Instant at, SessionStatus status, Integer count) {
    }

    @Test
    void instantsAreWrittenAsIsoUtcWithMillisecondsAndNullsAreOmitted() {
        String json = CODEC.write(new Sample("a", Instant.parse("2026-10-15T09:00:00.123456789Z"), SessionStatus.ACTIVE, null));

        assertThat(json).isEqualTo("{\"name\":\"a\",\"at\":\"2026-10-15T09:00:00.123Z\",\"status\":\"ACTIVE\"}");
    }

    @Test
    void readsIsoInstantsWithOffsetsAsTheSameUtcInstant() {
        Sample sample = CODEC.read("{\"at\":\"2026-10-15T18:00:00+09:00\"}", Sample.class);

        assertThat(sample.at()).isEqualTo(Instant.parse("2026-10-15T09:00:00Z"));
    }

    @Test
    void unknownFieldsAreIgnored() {
        Sample sample = CODEC.read("{\"name\":\"a\",\"future\":{\"nested\":[1,2]},\"count\":2}", Sample.class);

        assertThat(sample).isEqualTo(new Sample("a", null, null, 2));
    }

    @Test
    void unknownEnumValuesReadAsUnrecognizedInsteadOfFailing() {
        SessionStateChanged payload = CODEC.read("""
                {"sessionId":"00000000-0000-4000-8000-000000000002","guardianId":"00000000-0000-4000-8000-000000000003",
                 "status":"HIBERNATING","activityType":"KARAOKE","endReason":42,"statusVersion":2,
                 "at":"2026-10-15T09:00:00Z","technicalPause":{"kind":"ALIENS","since":"2026-10-15T09:00:00Z"}}
                """, SessionStateChanged.class);

        assertThat(payload.status()).isEqualTo(SessionStatus.UNRECOGNIZED);
        assertThat(payload.activityType()).isEqualTo(ActivityType.UNRECOGNIZED);
        assertThat(payload.endReason()).isEqualTo(EndReason.UNRECOGNIZED);
        assertThat(payload.technicalPause().kind()).isEqualTo(SessionStateChanged.TechnicalPause.Kind.UNRECOGNIZED);
        assertThat(payload.problems()).containsExactly("status:invalid");
    }

    @Test
    void knownEnumValuesStillReadExactly() {
        DeviceStatusChanged payload = CODEC.read("""
                {"deviceId":"00000000-0000-4000-8000-000000000004","state":"UNSTABLE","since":"2026-10-15T09:00:00Z"}
                """, DeviceStatusChanged.class);

        assertThat(payload.state()).isEqualTo(ConnectionState.UNSTABLE);
        assertThat(payload.deviceId()).isEqualTo(DEVICE_ID);
        assertThat(payload.problems()).isEmpty();
    }

    static Stream<Arguments> wireEnums() {
        return Stream.of(
                Arguments.of((Function<String, Enum<?>>) SessionStatus::fromWire, "CLOSING", SessionStatus.CLOSING,
                        SessionStatus.UNRECOGNIZED),
                Arguments.of((Function<String, Enum<?>>) EndReason::fromWire, "SILENCE", EndReason.SILENCE,
                        EndReason.UNRECOGNIZED),
                Arguments.of((Function<String, Enum<?>>) FailureCode::fromWire, "SERVER_RESTART", FailureCode.SERVER_RESTART,
                        FailureCode.UNRECOGNIZED),
                Arguments.of((Function<String, Enum<?>>) ConnectionState::fromWire, "OFFLINE", ConnectionState.OFFLINE,
                        ConnectionState.UNRECOGNIZED),
                Arguments.of((Function<String, Enum<?>>) RegistrationStatus::fromWire, "RELEASED", RegistrationStatus.RELEASED,
                        RegistrationStatus.UNRECOGNIZED),
                Arguments.of((Function<String, Enum<?>>) ActivityType::fromWire, "FREE_TALK", ActivityType.FREE_TALK,
                        ActivityType.UNRECOGNIZED),
                Arguments.of((Function<String, Enum<?>>) EndRequestReason::fromWire, "CORE_ABANDONED",
                        EndRequestReason.CORE_ABANDONED, EndRequestReason.UNRECOGNIZED));
    }

    @ParameterizedTest
    @MethodSource("wireEnums")
    void fromWireReadsKnownNamesAndMapsUnknownNullAndTheSentinelToUnrecognized(
            Function<String, Enum<?>> fromWire, String known, Enum<?> expected, Enum<?> unrecognized) {
        assertThat(fromWire.apply(known)).isEqualTo(expected);
        assertThat(fromWire.apply(known.toLowerCase())).isEqualTo(unrecognized);
        assertThat(fromWire.apply(null)).isEqualTo(unrecognized);
        assertThat(fromWire.apply("UNRECOGNIZED")).isEqualTo(unrecognized);
    }

    @Test
    void sessionStatusPartitionsAndRegistrationTransitionsFollowTheStateMachines() {
        assertThat(Stream.of(SessionStatus.values()).filter(SessionStatus::isTerminal)).containsExactly(
                SessionStatus.ENDED, SessionStatus.ENDED_UNCONFIRMED, SessionStatus.ABORTED, SessionStatus.DECLINED,
                SessionStatus.FAILED);
        assertThat(Stream.of(SessionStatus.values()).filter(SessionStatus::isNonTerminal)).hasSize(6);
        assertThat(SessionStatus.UNRECOGNIZED.isTerminal()).isFalse();
        assertThat(SessionStatus.UNRECOGNIZED.isNonTerminal()).isFalse();
        assertThat(RegistrationStatus.UNREGISTERED.canTransitionTo(RegistrationStatus.REGISTERED)).isTrue();
        assertThat(RegistrationStatus.UNREGISTERED.canTransitionTo(RegistrationStatus.EXPIRED)).isTrue();
        assertThat(RegistrationStatus.REGISTERED.canTransitionTo(RegistrationStatus.RELEASED)).isTrue();
        assertThat(RegistrationStatus.REGISTERED.canTransitionTo(RegistrationStatus.EXPIRED)).isFalse();
        assertThat(RegistrationStatus.RELEASED.canTransitionTo(RegistrationStatus.REGISTERED)).isFalse();
        assertThat(RegistrationStatus.UNRECOGNIZED.canTransitionTo(RegistrationStatus.REGISTERED)).isFalse();
    }

    @Test
    void canonicalJsonSortsKeysRecursivelyAndHashesStably() {
        String a = "{\"b\":1,\"a\":{\"y\":[{\"d\":1,\"c\":2}],\"x\":null}}";
        String b = "{ \"a\" : { \"x\" : null, \"y\" : [ { \"c\" : 2, \"d\" : 1 } ] }, \"b\" : 1 }";

        assertThat(CODEC.canonicalize(a)).isEqualTo("{\"a\":{\"x\":null,\"y\":[{\"c\":2,\"d\":1}]},\"b\":1}");
        assertThat(CODEC.canonicalize(b)).isEqualTo(CODEC.canonicalize(a));
        assertThat(CODEC.sha256OfCanonical(a)).isEqualTo(CODEC.sha256OfCanonical(b))
                .isEqualTo(SecretCompare.sha256Hex(CODEC.canonicalize(a)));
        assertThat(CODEC.sha256OfCanonical("{\"b\":2}")).isNotEqualTo(CODEC.sha256OfCanonical("{\"b\":1}"));
        assertThat(CODEC.canonicalJson(new Sample("z", null, null, 1))).isEqualTo("{\"count\":1,\"name\":\"z\"}");
    }

    @Test
    void errorEnvelopeIsWrittenWithExactlyCodeMessageAndTraceId() {
        String traceId = TraceIds.newTraceId();

        String json = CODEC.writeError(ErrorEnvelope.of(ErrorCode.NOT_FOUND, traceId));

        assertThat(json).isEqualTo("{\"code\":\"NOT_FOUND\",\"message\":\"찾을 수 없어요.\",\"traceId\":\"" + traceId + "\"}");
    }

    @Test
    void readFailuresNeverEchoTheBody() {
        String body = "{\"name\": \"private-input-value\", broken";

        assertThatThrownBy(() -> CODEC.read(body, Sample.class))
                .isInstanceOf(JsonCodec.JsonCodecException.class)
                .hasMessageNotContaining("private-input-value")
                .hasMessageContaining("Sample");
        assertThatThrownBy(() -> CODEC.read(null, Sample.class)).isInstanceOf(JsonCodec.JsonCodecException.class);
        assertThatThrownBy(() -> CODEC.canonicalize("not json")).isInstanceOf(JsonCodec.JsonCodecException.class);
        assertThatThrownBy(() -> CODEC.canonicalize(null)).isInstanceOf(JsonCodec.JsonCodecException.class);
        assertThatThrownBy(() -> CODEC.read("{}", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void writeFailureNamesOnlyTheType() {
        Object selfReferencing = new Object() {
            @SuppressWarnings("unused")
            public Object getSelf() {
                return this;
            }
        };

        assertThatThrownBy(() -> CODEC.write(selfReferencing)).isInstanceOf(JsonCodec.JsonCodecException.class);
        assertThat(CODEC.write(List.of(1, 2))).isEqualTo("[1,2]");
        assertThat(new JsonCodec().write(null)).isEqualTo("null");
    }

    /** FT-1029-c: 공급자 출력 진단용 모양. OBJECT는 read로 지도가 읽히는 경우와 같다. */
    @Test
    void shapeTellsWhyTextIsNotAJsonObject() {
        assertThat(CODEC.shape("{\"a\":1}")).isEqualTo(JsonCodec.Shape.OBJECT);
        assertThat(CODEC.shape("  {\"a\":1}\n")).isEqualTo(JsonCodec.Shape.OBJECT);
        assertThat(CODEC.shape("[1,2]")).isEqualTo(JsonCodec.Shape.NOT_OBJECT);
        assertThat(CODEC.shape("\"글\"")).isEqualTo(JsonCodec.Shape.NOT_OBJECT);
        assertThat(CODEC.shape("```json\n{}\n```")).isEqualTo(JsonCodec.Shape.NOT_JSON);
        assertThat(CODEC.shape("{\"a\":")).isEqualTo(JsonCodec.Shape.NOT_JSON);
        assertThat(CODEC.shape(null)).isEqualTo(JsonCodec.Shape.NOT_JSON);
        // 뒤에 글이 붙은 객체: read가 거절하면 TRAILING_CONTENT, 받아 주면 OBJECT(read와 같은 판정)
        String trailing = "{\"a\":1} 설명";
        boolean readAccepts;
        try {
            readAccepts = CODEC.read(trailing, Object.class) instanceof java.util.Map<?, ?>;
        } catch (JsonCodec.JsonCodecException e) {
            readAccepts = false;
        }
        assertThat(CODEC.shape(trailing)).isEqualTo(readAccepts ? JsonCodec.Shape.OBJECT : JsonCodec.Shape.TRAILING_CONTENT);
    }
}
