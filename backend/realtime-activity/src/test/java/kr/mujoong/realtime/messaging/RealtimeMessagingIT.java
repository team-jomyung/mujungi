package kr.mujoong.realtime.messaging;

import static kr.mujoong.realtime.testing.RealtimeItEnvironment.WAIT;
import static kr.mujoong.realtime.testing.RealtimeItEnvironment.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.event.EventEnvelope;
import kr.mujoong.contract.event.payload.DeviceReleased;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.contract.ws.CloseCode;
import kr.mujoong.realtime.testing.RealtimeItEnvironment;
import kr.mujoong.testsupport.device.DeviceSimulatorClient;
import kr.mujoong.testsupport.http.ContractStubServer.RecordedRequest;
import kr.mujoong.testsupport.http.ContractStubServer.StubResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import tools.jackson.databind.JsonNode;

/**
 * Realtime 메시징 통합 시험 (U1 3.5 Step 30 · RD-8 · RD-9 · RD-11 · 편차 (q) · BR3.8 · BR5.3 · BR5.14 · A1 · A4).
 *
 * <p>DeviceStatusChanged가 행 guardian_id와 payload guardianId를 함께 싣고 Core 대역까지 가는지, Core 응답별 Outbox 상태, DeviceReleased
 * 수신 → 4401 + OFFLINE, 같은 이벤트 중복 수신, Realtime 메시징 삭제 참가자의 멱등을 실제 앱 · DB로 본다.
 */
@Tag("u1")
@Tag("db")
class RealtimeMessagingIT {

    private static final JsonCodec JSON = JsonCodec.shared();

    private RealtimeItEnvironment env;

    @BeforeEach
    void start() {
        env = RealtimeItEnvironment.start();
    }

    @AfterEach
    void stop() {
        env.close();
    }

    @Test
    void deviceStatusChangedCarriesTheGuardianInTheRowAndThePayloadAndReachesCore() {
        env.connectDevice();

        List<RecordedRequest> published = env.core.awaitRequests("POST", "/internal/events", 1, WAIT);
        JsonNode event = published.get(0).json();
        assertThat(event.get("eventType").asString()).isEqualTo("DeviceStatusChanged");
        assertThat(event.get("payload").get("state").asString()).isEqualTo("CONNECTED");
        assertThat(event.get("payload").get("guardianId").asString()).isEqualTo(env.guardianId.toString());
        assertThat(published.get(0).header("X-Internal-Token")).isEqualTo(env.internalToken);
        awaitTrue(() -> outbox("DeviceStatusChanged").stream().allMatch(row -> row.status()
                == OutboxEventRow.Status.PUBLISHED), "PUBLISHED");
        OutboxEventRow row = outbox("DeviceStatusChanged").get(0);
        assertThat(row.guardianId()).isEqualTo(env.guardianId);
        assertThat(row.payloadJson()).isEqualTo("{}");
        // A4: Realtime Outbox에 guardian_id가 빈 행이 없다
        assertThat(env.db.single("select count(*) from realtime.realtime_outbox_event where guardian_id is null",
                new MapSqlParameterSource(), Long.class)).isZero();
    }

    @Test
    void coreAnswersDecideTheOutboxStateRetryThenPublishOrFailAtOnce() {
        env.core.route("POST", "/internal/events").respond(StubResponse.error(503, ErrorCode.SERVICE_NOT_READY),
                StubResponse.json(200, "{}"));
        env.connectDevice();
        awaitTrue(() -> !outbox("DeviceStatusChanged").isEmpty() && outbox("DeviceStatusChanged").get(0).status()
                == OutboxEventRow.Status.PUBLISHED, "재시도 뒤 PUBLISHED");
        assertThat(outbox("DeviceStatusChanged").get(0).retryCount()).isEqualTo(1);

        env.core.route("POST", "/internal/events").respond(StubResponse.error(409, ErrorCode.EVENT_CONFLICT));
        DeviceSimulatorClient second = env.connectDevice();
        awaitTrue(() -> outbox("DeviceStatusChanged").stream().anyMatch(row -> row.status()
                == OutboxEventRow.Status.FAILED), "409 → FAILED");
        OutboxEventRow failed = outbox("DeviceStatusChanged").stream()
                .filter(row -> row.status() == OutboxEventRow.Status.FAILED).findFirst().orElseThrow();
        assertThat(failed.lastError()).isEqualTo(LastError.EVENT_CONFLICT);
        assertThat(failed.retryCount()).isZero();
        second.close();
    }

    @Test
    void deviceReleasedClosesTheSocketWith4401AndWritesOfflineOnce() {
        DeviceSimulatorClient device = env.connectDevice();
        UUID eventId = UUID.randomUUID();
        String body = JSON.encodeEventEnvelope(JSON.envelopeFor(eventId,
                new DeviceReleased(env.deviceId, env.guardianId, DeviceReleased.Reason.REREGISTERED), Instant.now(),
                "0123456789abcdef0123456789abcdef"));

        RealtimeInboxReceiver.Result first = env.app.bean(RealtimeInboxReceiver.class).receive(body);
        RealtimeInboxReceiver.Result again = env.app.bean(RealtimeInboxReceiver.class).receive(body);

        assertThat(first.outcome()).isEqualTo(RealtimeInboxReceiver.Outcome.ACCEPTED);
        assertThat(again.outcome()).isEqualTo(RealtimeInboxReceiver.Outcome.DUPLICATE);
        assertThat(device.awaitClose(WAIT)).contains(CloseCode.UNAUTHORIZED.code());
        awaitTrue(() -> env.ledger().map(row -> row.state() == ConnectionState.OFFLINE && row.connectionId() == null)
                .orElse(false), "OFFLINE");
        awaitTrue(() -> env.app.bean(RealtimeInboxRepository.class).find(eventId)
                .map(row -> row.status() == InboxEventRow.Status.PROCESSED).orElse(false), "PROCESSED");
        List<RecordedRequest> published = env.core.awaitRequests("POST", "/internal/events", 2, WAIT);
        assertThat(published).extracting(request -> request.json().get("payload").get("state").asString())
                .contains("OFFLINE");
    }

    @Test
    void anUnknownEventTypeIsIgnoredAndAConflictingDuplicateIsReported() {
        UUID eventId = UUID.randomUUID();
        EventEnvelope envelope = JSON.envelopeFor(eventId,
                new DeviceReleased(env.deviceId, env.guardianId, DeviceReleased.Reason.ACCOUNT_DELETED), Instant.now(),
                null);
        String body = JSON.encodeEventEnvelope(envelope);
        String conflicting = JSON.encodeEventEnvelope(JSON.envelopeFor(eventId,
                new DeviceReleased(env.deviceId, env.guardianId, DeviceReleased.Reason.REREGISTERED), Instant.now(),
                null));
        String unknown = body.replace("\"DeviceReleased\"", "\"SomethingNew\"").replace(eventId.toString(),
                UUID.randomUUID().toString());

        assertThat(env.app.bean(RealtimeInboxReceiver.class).receive(body).outcome())
                .isEqualTo(RealtimeInboxReceiver.Outcome.ACCEPTED);
        assertThat(env.app.bean(RealtimeInboxReceiver.class).receive(conflicting).outcome())
                .isEqualTo(RealtimeInboxReceiver.Outcome.CONFLICT);
        assertThat(env.app.bean(RealtimeInboxReceiver.class).receive(unknown).outcome())
                .isIn(RealtimeInboxReceiver.Outcome.IGNORED, RealtimeInboxReceiver.Outcome.INVALID);
        assertThat(env.app.bean(RealtimeInboxReceiver.class).receive("{not json").outcome())
                .isEqualTo(RealtimeInboxReceiver.Outcome.INVALID);
    }

    @Test
    void theRealtimeMessagingDeletionParticipantIsIdempotentAndKeepsOtherGuardians() {
        env.core.route("POST", "/internal/events").respond(StubResponse.error(503, ErrorCode.SERVICE_NOT_READY));
        env.connectDevice();
        awaitTrue(() -> !outbox("DeviceStatusChanged").isEmpty(), "Outbox 행");
        UUID other = UUID.randomUUID();
        long before = countRows();

        Map<String, Integer> first = env.app.bean(RealtimeMessagingDeletionParticipant.class)
                .deleteByGuardian(other, UUID.randomUUID());
        Map<String, Integer> again = env.app.bean(RealtimeMessagingDeletionParticipant.class)
                .deleteByGuardian(env.guardianId, UUID.randomUUID());
        Map<String, Integer> twice = env.app.bean(RealtimeMessagingDeletionParticipant.class)
                .deleteByGuardian(env.guardianId, UUID.randomUUID());

        assertThat(first.values()).allMatch(count -> count == 0);
        assertThat(countRows()).isEqualTo(before);
        // PENDING 행은 남긴다(BR5.14 (a)(b)), 두 번째 실행은 같은 결과다
        assertThat(outbox("DeviceStatusChanged")).isNotEmpty()
                .allMatch(row -> row.status() == OutboxEventRow.Status.PENDING);
        assertThat(again.keySet()).containsExactlyInAnyOrderElementsOf(twice.keySet());
        assertThat(twice.values()).allMatch(count -> count == 0);
    }

    private List<OutboxEventRow> outbox(String type) {
        List<UUID> ids = env.db.jdbc().queryForList(
                "select event_id from realtime.realtime_outbox_event where event_type = :type order by created_at",
                new MapSqlParameterSource("type", type), UUID.class);
        return ids.stream().map(id -> env.app.bean(RealtimeOutboxRepository.class).find(id)).flatMap(Optional::stream)
                .toList();
    }

    private long countRows() {
        return env.db.single("select count(*) from realtime.realtime_outbox_event", new MapSqlParameterSource(),
                Long.class);
    }
}
