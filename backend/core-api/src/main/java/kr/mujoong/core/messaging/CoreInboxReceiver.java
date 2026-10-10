package kr.mujoong.core.messaging;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.event.EventEnvelope;
import kr.mujoong.contract.event.EventPayload;
import kr.mujoong.contract.event.EventType;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.device.DeviceJdbcRepository;
import kr.mujoong.core.messaging.CoreInboxRepository.InboxEvent;
import kr.mujoong.core.messaging.CoreInboxRepository.NewInboxEvent;
import kr.mujoong.core.observability.EventLogger;
import org.springframework.stereotype.Component;

/**
 * Core Inbox 수신 (U1 3.5 Step 20 · BR5.4 ~ BR5.6 · BR5.9 · RD-9 · A3 · A4 · RD-11 · 편차 (p)(q)).
 *
 * <p>{@code POST /internal/events}(내부 토큰 · 준비 상태 확인은 필터 몫, Step 31)의 본문을 받아:
 *
 * <ol>
 *   <li>봉투 검사(필수 7필드 · schemaVersion ≥ 1). 아는 eventType이고 지원 버전이면 payload 형식도 본다. 위반은 400 INVALID_ENVELOPE.
 *   <li>{@code INSERT ... ON CONFLICT (event_id) DO NOTHING}: 새 행이면 처리기가 있고 지원 버전일 때 RECEIVED(next_attempt_at = 수신
 *       시각), 아니면 IGNORED(payload 비움). 커밋한 뒤에만 200이다(BR5.6). RECEIVED는 곧바로 처리 풀에 제출한다(가득 차면 건너뛰고
 *       재호출 폴러가 집는다).
 *   <li>이미 있는 eventId: payload_hash가 같으면 200(처리기 다시 부르지 않음), 다르면 기존 행 last_error에 EVENT_CONFLICT를 남기고 409.
 * </ol>
 *
 * <p>guardian_id(A4)는 payload.guardianId가 먼저이고, 없는 옛 DeviceStatusChanged만 기기 행에서 찾는다(RD-11 · (q)). traceId는 봉투 값이
 * 형식에 맞을 때만 둔다(A3 · (p)). 본문 · payload는 로그 · 예외 메시지에 넣지 않는다(BR5.13).
 */
@Component
public class CoreInboxReceiver {

    /** 수신 결과. */
    public enum Outcome {
        /** 새 RECEIVED 행(200). */
        ACCEPTED(200),
        /** 같은 eventId · 같은 해시(200). */
        DUPLICATE(200),
        /** 처리기 없음 · 상위 schemaVersion(200, BR5.9). */
        IGNORED(200),
        /** 같은 eventId · 다른 해시(409 EVENT_CONFLICT). */
        CONFLICT(409),
        /** 봉투 · payload 형식 오류(400 INVALID_ENVELOPE). */
        INVALID(400);

        private final int httpStatus;

        Outcome(int httpStatus) {
            this.httpStatus = httpStatus;
        }

        /** 응답 상태 코드. */
        public int httpStatus() {
            return httpStatus;
        }
    }

    /** 수신 결과(eventId는 봉투가 읽혔을 때만). */
    public record Result(Outcome outcome, UUID eventId) {
    }

    private final CoreInboxRepository inbox;
    private final CoreInboxDispatcher dispatcher;
    private final DeviceJdbcRepository devices;
    private final JsonCodec json;
    private final EventLogger events;
    private final MujoongClock clock;

    public CoreInboxReceiver(
            CoreInboxRepository inbox,
            CoreInboxDispatcher dispatcher,
            DeviceJdbcRepository devices,
            JsonCodec json,
            EventLogger events,
            MujoongClock clock) {
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.devices = Objects.requireNonNull(devices, "devices");
        this.json = Objects.requireNonNull(json, "json");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 본문 하나를 받는다. */
    public Result receive(String body) {
        EventEnvelope envelope;
        EventPayload payload = null;
        try {
            envelope = json.decodeEventEnvelope(body);
            if (envelope.isHandleable()) {
                payload = json.readPayload(envelope, envelope.type().payloadType());
            }
        } catch (EventEnvelope.InvalidEnvelopeException e) {
            return new Result(Outcome.INVALID, null);
        }
        boolean handled = payload != null && dispatcher.hasHandler(envelope.type());
        String payloadHash = json.sha256OfCanonical(envelope.payloadJson());
        Instant now = clock.now();
        NewInboxEvent row = new NewInboxEvent(envelope.eventId(), envelope.eventType(), envelope.schemaVersion(),
                envelope.occurredAt(), envelope.aggregateId(), guardianOf(envelope, payload), envelope.payloadJson(),
                payloadHash, envelope.traceId(), now);
        boolean inserted = handled ? inbox.insertReceived(row) : inbox.insertIgnored(row);
        if (inserted) {
            if (handled) {
                dispatcher.submit(envelope.eventId());
                return new Result(Outcome.ACCEPTED, envelope.eventId());
            }
            logDelivery(envelope, "IGNORED", 0);
            return new Result(Outcome.IGNORED, envelope.eventId());
        }
        Optional<InboxEvent> existing = inbox.findById(envelope.eventId());
        if (existing.isEmpty()) {
            // 같은 순간 삭제 참가자가 지운 행: 보내는 쪽 재시도가 다시 넣는다(Outbox 대기표)
            throw new IllegalStateException("Inbox 행이 넣기 직후 사라졌다");
        }
        if (existing.get().payloadHash().equals(payloadHash)) {
            logDelivery(envelope, "DUPLICATE", existing.get().retryCount());
            return new Result(Outcome.DUPLICATE, envelope.eventId());
        }
        inbox.recordConflict(envelope.eventId(), "EVENT_CONFLICT");
        logDelivery(envelope, "EVENT_CONFLICT", existing.get().retryCount());
        return new Result(Outcome.CONFLICT, envelope.eventId());
    }

    /** A4 · RD-11: payload.guardianId → (옛 DeviceStatusChanged만) 기기 행 → 없으면 null. */
    private UUID guardianOf(EventEnvelope envelope, EventPayload payload) {
        if (payload == null) {
            return null;
        }
        if (payload.guardianId() != null) {
            return payload.guardianId();
        }
        if (envelope.type() == EventType.DEVICE_STATUS_CHANGED) {
            return devices.findGuardianId(envelope.aggregateId()).orElse(null);
        }
        return null;
    }

    private void logDelivery(EventEnvelope envelope, String status, int retryCount) {
        events.log(EventCode.EVENT_DELIVERY, EventLog.Component.CORE_MESSAGING, envelope.aggregateId(), entry -> {
            entry.attr(AttrKey.EVENT_ID, envelope.eventId()).attr(AttrKey.STATUS, status)
                    .attr(AttrKey.RETRY_COUNT, (long) retryCount);
            if (envelope.type() != EventType.UNRECOGNIZED) {
                entry.attr(AttrKey.EVENT_TYPE, envelope.type().wire());
            }
        });
    }
}
