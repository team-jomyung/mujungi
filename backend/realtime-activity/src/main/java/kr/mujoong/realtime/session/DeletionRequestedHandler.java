package kr.mujoong.realtime.session;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import kr.mujoong.contract.event.EventType;
import kr.mujoong.contract.event.payload.DeletionCompleted;
import kr.mujoong.contract.event.payload.DeletionRequested;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.common.db.ClockParams;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.log.RealtimeVoiceDeletionParticipant;
import kr.mujoong.realtime.messaging.InboxEventRow;
import kr.mujoong.realtime.messaging.InboxHandler;
import kr.mujoong.realtime.messaging.OutboxEventRow;
import kr.mujoong.realtime.messaging.OutboxPublishedListener;
import kr.mujoong.realtime.messaging.RealtimeDeletionParticipant;
import kr.mujoong.realtime.messaging.RealtimeMessagingDeletionParticipant;
import kr.mujoong.realtime.messaging.RealtimeOutboxWriter;
import kr.mujoong.realtime.observability.EventLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * DeletionRequested 처리기 — Realtime 쪽 계정 삭제 지휘 (U4b L6 · FR8.1 · BR9.1 ~ BR9.5 · U4b-R16 · U4b-J19 · U4b-J20 · R-12).
 *
 * <p>Inbox 처리 트랜잭션 안에서 다음을 한다. 실패(참가자 예외 · 파일 삭제 실패 · 정지 상한 초과)는 예외로 올려 트랜잭션 전체를 되돌리고 Inbox 재시도
 * (BR5.8)에 맡긴다.
 *
 * <ol>
 *   <li>그 보호자의 세션 실행기(진행 중 · 정산 창 중)를 DB 전이 · 이벤트 · 스냅숏 없이 강제 정지한다. 상한은 deletion-stop-wait다(BR9.1).
 *   <li>참가자를 순서 값 순으로 부른다: 음성 100 → 연습 150(등록된 빈이 없으면 건너뜀) → 세션 200 → 메시징 900(BR9.2 · BR9.3).
 *   <li>같은 트랜잭션 끝에 DeletionCompleted{counts}를 쓴다. 같은 deletionRequestId로 이미 썼으면 쓰지 않는다(BR9.4).
 *   <li>그 행이 PUBLISHED가 되면 메시징 참가자를 한 번 더 부른다({@link #published}, BR9.5).
 * </ol>
 *
 * <p>counts(R-12): 모든 DB 삭제가 이 한 트랜잭션에 있어 실패한 시도는 부분 커밋이 없고, 재시도는 지울 행을 처음부터 다시 센다. 트랜잭션 밖에서 먼저
 * 사라지는 것은 원음 파일뿐이라 audioFiles는 지운 파일 수가 아니라 음성 참가자가 삭제 표시한 원음 메타 행 수(파일이 이미 없던 행 포함)로 센다 — 그래서
 * 재시도해도 앞 시도의 건수가 빠지지 않는다. transcripts는 원문을 비운 전사 행 수, sessions는 지운 세션 행 수, practiceRecords는 연습 참가자(150)가
 * 돌려준 건수 합(없으면 0)이다.
 */
@Component
public class DeletionRequestedHandler implements InboxHandler, OutboxPublishedListener {

    private static final String COMPLETED_EXISTS = "select exists(select 1 from realtime.realtime_outbox_event"
            + " where event_type = :type and aggregate_id = :requestId)";
    private static final String REQUEST_RECEIVED = "select exists(select 1 from realtime.realtime_inbox_event"
            + " where event_type = :type and aggregate_id = :requestId and guardian_id = :guardianId)";

    /** 정지 상한을 넘겼다(Inbox 재시도). */
    public static final class DeletionStopTimeoutException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public DeletionStopTimeoutException(int remaining) {
            super("강제 정지를 상한 안에 마치지 못한 세션 실행기 " + remaining + "개");
        }
    }

    private final SessionRegistry registry;
    private final NamedParameterJdbcTemplate jdbc;
    private final List<RealtimeDeletionParticipant> participants;
    private final RealtimeMessagingDeletionParticipant messaging;
    private final RealtimeOutboxWriter outbox;
    private final JsonCodec json;
    private final MujoongClock clock;
    private final EventLogger events;
    private final Duration stopWait;

    @Autowired
    public DeletionRequestedHandler(SessionRegistry registry, NamedParameterJdbcTemplate jdbc,
            List<RealtimeDeletionParticipant> participants, RealtimeMessagingDeletionParticipant messaging,
            RealtimeOutboxWriter outbox, JsonCodec json, MujoongClock clock, EventLogger events,
            RealtimeProperties properties) {
        this(registry, jdbc, participants, messaging, outbox, json, clock, events,
                properties.session().deletionStopWait());
    }

    public DeletionRequestedHandler(SessionRegistry registry, NamedParameterJdbcTemplate jdbc,
            List<RealtimeDeletionParticipant> participants, RealtimeMessagingDeletionParticipant messaging,
            RealtimeOutboxWriter outbox, JsonCodec json, MujoongClock clock, EventLogger events, Duration stopWait) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        List<RealtimeDeletionParticipant> ordered = new ArrayList<>(Objects.requireNonNull(participants, "participants"));
        ordered.sort(Comparator.comparingInt(RealtimeDeletionParticipant::order));
        this.participants = List.copyOf(ordered);
        this.messaging = Objects.requireNonNull(messaging, "messaging");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = Objects.requireNonNull(events, "events");
        this.stopWait = Objects.requireNonNull(stopWait, "stopWait");
    }

    @Override
    public EventType eventType() {
        return EventType.DELETION_REQUESTED;
    }

    /** 순서 값 순 참가자(시험 · 진단). */
    public List<RealtimeDeletionParticipant> participants() {
        return participants;
    }

    @Override
    public Result handle(InboxEventRow event, Instant now) {
        DeletionRequested payload = json.read(event.payloadJson(), DeletionRequested.class);
        if (payload == null || payload.guardianId() == null || payload.deletionRequestId() == null) {
            throw new IllegalArgumentException("DeletionRequested에 guardianId · deletionRequestId가 없다");
        }
        UUID guardianId = payload.guardianId();
        UUID requestId = payload.deletionRequestId();
        stopExecutors(guardianId);
        int transcripts = 0;
        int audioFiles = 0;
        int sessions = 0;
        int practiceRecords = 0;
        for (RealtimeDeletionParticipant participant : participants) {
            Map<String, Integer> counts = participant.deleteByGuardian(guardianId, requestId);
            switch (participant.order()) {
                case RealtimeDeletionParticipant.ORDER_VOICE -> {
                    transcripts += counts.getOrDefault(RealtimeVoiceDeletionParticipant.TRANSCRIPT, 0);
                    audioFiles += counts.getOrDefault(RealtimeVoiceDeletionParticipant.AUDIO_RECORDING, 0);
                }
                case RealtimeDeletionParticipant.ORDER_PRACTICE ->
                    practiceRecords += counts.values().stream().mapToInt(Integer::intValue).sum();
                case RealtimeDeletionParticipant.ORDER_SESSION ->
                    sessions += counts.getOrDefault(RealtimeSessionDeletionParticipant.SESSION, 0);
                default -> {
                    // 메시징 참가자의 건수는 counts에 넣지 않는다
                }
            }
        }
        if (!completedAlreadyWritten(requestId)) {
            outbox.write(new DeletionCompleted(requestId, guardianId, now,
                    new DeletionCompleted.Counts(sessions, transcripts, audioFiles, practiceRecords)), guardianId, now,
                    event.traceId());
        }
        return Result.done();
    }

    /**
     * BR9.1: 그 보호자의 세션 실행기(진행 중 · 정산 창 중)를 강제 정지하고 상한까지 기다린다. 상한을 넘기면 예외(Inbox 재시도). 시작 트랜잭션 중(INIT)
     * 실행기는 시작한 뒤 편지함의 정지 명령을 처리한다.
     */
    private void stopExecutors(UUID guardianId) {
        List<UUID> ids = jdbc.query("select id from realtime.realtime_activity_session where guardian_id = :guardianId",
                ClockParams.uuid(new MapSqlParameterSource(), "guardianId", guardianId),
                (rs, rowNum) -> ClockParams.uuid(rs, "id"));
        List<CompletableFuture<Void>> stops = new ArrayList<>();
        for (UUID id : ids) {
            registry.entry(id).ifPresent(entry -> {
                SessionMailbox.DeletionStop stop = new SessionMailbox.DeletionStop();
                if (entry.mailbox().putSafety(stop)) {
                    stops.add(stop.stopped());
                }
            });
        }
        if (stops.isEmpty()) {
            return;
        }
        CompletableFuture<Void> all = CompletableFuture.allOf(stops.toArray(CompletableFuture[]::new));
        if (!await(all)) {
            int remaining = (int) stops.stream().filter(stop -> !stop.isDone()).count();
            throw new DeletionStopTimeoutException(remaining);
        }
    }

    /** deletion-stop-wait까지 기다린다(모니터 대기, 시계는 주입한 단조 읽기). */
    private boolean await(CompletableFuture<Void> all) {
        Object monitor = new Object();
        all.whenComplete((ignored, error) -> {
            synchronized (monitor) {
                monitor.notifyAll();
            }
        });
        long deadline = clock.monotonicNanos() + stopWait.toNanos();
        synchronized (monitor) {
            while (!all.isDone()) {
                long left = deadline - clock.monotonicNanos();
                if (left <= 0) {
                    return false;
                }
                try {
                    monitor.wait(Math.max(1L, Duration.ofNanos(left).toMillis()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    private boolean requestReceived(UUID requestId, UUID guardianId) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.text(params, "type", EventType.DELETION_REQUESTED.wire());
        ClockParams.uuid(params, "requestId", requestId);
        ClockParams.uuid(params, "guardianId", guardianId);
        return Boolean.TRUE.equals(jdbc.queryForObject(REQUEST_RECEIVED, params, Boolean.class));
    }

    private boolean completedAlreadyWritten(UUID requestId) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.text(params, "type", EventType.DELETION_COMPLETED.wire());
        ClockParams.uuid(params, "requestId", requestId);
        return Boolean.TRUE.equals(jdbc.queryForObject(COMPLETED_EXISTS, params, Boolean.class));
    }

    /**
     * BR9.5: DeletionCompleted가 PUBLISHED가 되면 메시징 참가자를 한 번 더 부른다(발행된 완료 행까지 정리). 실패는 남기기만 한다 — 놓친 행은 하루
     * 정리 작업이 지운다.
     *
     * <p>이 처리기가 받은 DeletionRequested(같은 요청 id · 같은 보호자의 Inbox 행)가 있을 때만 부른다. 그 요청 없이 쓰인 완료 행(예: 기동 복구가
     * 되살린 제어 이벤트)으로 보호자의 다른 행을 지우지 않는다.
     */
    @Override
    public void published(OutboxEventRow row) {
        if (!EventType.DELETION_COMPLETED.wire().equals(row.eventType()) || row.guardianId() == null) {
            return;
        }
        try {
            if (!requestReceived(row.aggregateId(), row.guardianId())) {
                return;
            }
            messaging.deleteByGuardian(row.guardianId(), row.aggregateId());
        } catch (RuntimeException e) {
            events.appError(EventLog.Component.REALTIME_MESSAGING, e);
        }
    }
}
