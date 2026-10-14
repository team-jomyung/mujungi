package kr.mujoong.realtime.session;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.event.payload.SessionRecordCaptured;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.common.db.DbRetry;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.log.AsyncRecorder;
import kr.mujoong.realtime.log.SessionGapRepository;
import kr.mujoong.realtime.log.TranscriptRepository;
import kr.mujoong.realtime.messaging.RealtimeOutboxWriter;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.recording.AudioRecordingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결과 입력 스냅숏과 SessionRecordCaptured (U4b L5 · FR7.1 · FR1.5 · BR8.1 · BR8.4 · BR8.5 · BR8.8 · NFR8 · R-03).
 *
 * <p>종료 세션의 행 · 전사 · 구간 · 원음 메타를 DB에서 읽어 {@link ResultSnapshotBuilder}로 record와 해시를 만들고, 스냅숏 행과
 * SessionRecordCaptured Outbox를 한 트랜잭션에 쓴다. 세션당 한 번이고(유일 제약 · ON CONFLICT) 이미 있으면 Outbox도 쓰지 않는다. 페이로드는
 * 계약 스키마의 필수(sessionId · guardianId · childId · activityType · finalStatus · snapshotId · snapshotHash)를 세션 행 · 스냅숏에서 채운다 —
 * guardianId는 세션 행 guardian_id다(R-03). 계약 검사({@code problems})를 통과하지 못하면 쓰지 않고 예외로 올린다.
 *
 * <p>실행기가 정산 창을 끝낸 뒤 부르고(practice 값 포함), 실행기 없이 닫힌 세션(Core 취소 · 고아 점검 · 기동 정리 · 실행기 최상위 예외)은
 * {@link #captureMissing}이 연습 원본 복구 후 다시 쓴다(재무장, BR1.8).
 */
@Component
public class RecordCaptureService implements SessionSettlement {

    /** 스냅숏을 쓴 세션 사건(payload는 해시 · 판뿐, BR12.1). */
    public static final String EVENT_RECORD_CAPTURED = "RECORD_CAPTURED";
    /** 한 번에 다시 쓰는 세션 수 상한. */
    static final int MISSING_BATCH = 50;

    private final ActivitySessionRepository sessions;
    private final TranscriptRepository transcripts;
    private final SessionGapRepository gaps;
    private final AudioRecordingRepository audio;
    private final ResultInputSnapshotRepository snapshots;
    private final RealtimeOutboxWriter outbox;
    private final AsyncRecorder recorder;
    private final SessionRegistry registry;
    private final TransactionTemplate transactions;
    private final DbRetry dbRetry;
    private final JsonCodec json;
    private final MujoongClock clock;
    private final EventLogger events;
    private final Duration settleGrace;
    private PracticeRecordRecovery practiceRecovery;

    @Autowired
    public RecordCaptureService(ActivitySessionRepository sessions, TranscriptRepository transcripts,
            SessionGapRepository gaps, AudioRecordingRepository audio, ResultInputSnapshotRepository snapshots,
            RealtimeOutboxWriter outbox, AsyncRecorder recorder, SessionRegistry registry,
            PlatformTransactionManager transactionManager, DbRetry dbRetry, MujoongClock clock, EventLogger events,
            RealtimeProperties properties) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.transcripts = Objects.requireNonNull(transcripts, "transcripts");
        this.gaps = Objects.requireNonNull(gaps, "gaps");
        this.audio = Objects.requireNonNull(audio, "audio");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.dbRetry = Objects.requireNonNull(dbRetry, "dbRetry");
        this.json = JsonCodec.shared();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = Objects.requireNonNull(events, "events");
        this.settleGrace = properties.timing().session().recordSettle();
    }

    @Autowired(required = false)
    public void setPracticeRecovery(PracticeRecordRecovery practiceRecovery) {
        this.practiceRecovery = Objects.requireNonNull(practiceRecovery);
    }

    @Override
    public boolean capture(UUID sessionId, Object practice, Instant cutoffAt) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(cutoffAt, "cutoffAt");
        Object resolvedPractice = dbRetry.execute(DbRetry.Idempotency.IDEMPOTENT,
                () -> recoverPractice(sessionId, practice));
        Boolean written = dbRetry.execute(DbRetry.Idempotency.IDEMPOTENT,
                () -> transactions.execute(status -> write(sessionId, resolvedPractice, cutoffAt)));
        return Boolean.TRUE.equals(written);
    }

    private Object recoverPractice(UUID sessionId, Object supplied) {
        if (supplied != null || snapshots.exists(sessionId)) return supplied;
        ActivitySessionRow session = sessions.find(sessionId)
                .orElseThrow(() -> new IllegalStateException("정산할 세션 행이 없다: " + sessionId));
        if (!session.status().isTerminal())
            throw new IllegalStateException("종료 세션만 정산한다: " + session.status());
        if (!requiresPractice(session)) return null;
        if (practiceRecovery == null) throw new IllegalStateException("연습 원본 복구 연결이 없다");
        Object recovered = practiceRecovery.recover(sessionId);
        if (recovered == null) throw new IllegalStateException("시작한 연습의 결과 원본이 없다");
        return recovered;
    }

    private static boolean requiresPractice(ActivitySessionRow session) {
        return session.activityType() == ActivityType.PRACTICE && session.startedAt() != null
                && session.status() != SessionStatus.DECLINED;
    }

    /** 쓰기가 실패하면 앱 오류 한 줄을 남기고 정산 재무장({@link #captureMissing})에 맡긴다. */
    @Override
    public void captureOrDefer(UUID sessionId, Instant cutoffAt) {
        try {
            capture(sessionId, null, cutoffAt);
        } catch (RuntimeException e) {
            events.appError(EventLog.Component.SESSION_LIFECYCLE, e);
        }
    }

    /** 한 트랜잭션: 읽기 → 조립 → 스냅숏 행 → (새로 썼으면) Outbox. */
    private boolean write(UUID sessionId, Object practice, Instant cutoffAt) {
        if (snapshots.exists(sessionId)) {
            return false;
        }
        ActivitySessionRow session = sessions.find(sessionId)
                .orElseThrow(() -> new IllegalStateException("정산할 세션 행이 없다: " + sessionId));
        if (!session.status().isTerminal()) {
            throw new IllegalStateException("종료 세션만 정산한다: " + session.status());
        }
        if (practice == null && requiresPractice(session))
            throw new IllegalStateException("시작한 연습의 결과 원본이 없다");
        ResultSnapshotBuilder.Built built = ResultSnapshotBuilder.build(new ResultSnapshotBuilder.Input(session,
                transcripts.findAll(sessionId), gaps.findBySession(sessionId), audio.findBySession(sessionId),
                practice), json);
        EndReason endReason = session.endReason() != null ? session.endReason()
                : TransitionTable.endReason(session.status());
        boolean confirmed = Boolean.TRUE.equals(session.confirmed());
        UUID snapshotId = UUID.randomUUID();
        Instant now = clock.now();
        boolean inserted = snapshots.insert(new ResultInputSnapshotRepository.NewSnapshot(snapshotId, sessionId,
                ResultInputSnapshotRepository.SNAPSHOT_VERSION, session.status(), endReason, confirmed,
                session.policyVersion(), cutoffAt, built.snapshotHash(), built.recordJson()), now);
        if (!inserted) {
            return false;
        }
        SessionRecordCaptured payload = payload(session, endReason, confirmed, snapshotId, built);
        List<String> problems = payload.problems();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("SessionRecordCaptured 계약 위반: " + problems);
        }
        outbox.write(payload, session.guardianId(), now, null);
        recorder.putEvent(new SessionEvent(UUID.randomUUID(), sessionId, EVENT_RECORD_CAPTURED, null,
                "{\"snapshotVersion\":" + ResultInputSnapshotRepository.SNAPSHOT_VERSION + ",\"snapshotHash\":\""
                        + built.snapshotHash() + "\"}", now));
        return true;
    }

    /** BR8.8 · R-03: 세션 행과 스냅숏에서 채운다. termination의 없는 시각은 null이다. */
    static SessionRecordCaptured payload(ActivitySessionRow session, EndReason endReason, boolean confirmed,
            UUID snapshotId, ResultSnapshotBuilder.Built built) {
        Map<String, Object> termination = new LinkedHashMap<>();
        termination.put("requestedAt", session.terminationRequestedAt());
        termination.put("outputStoppedAt", session.outputStoppedAt());
        termination.put("transportClosedAt", session.transportClosedAt());
        termination.put("providerClosedAt", session.providerClosedAt());
        return new SessionRecordCaptured(session.id(), session.guardianId(), session.childId(), session.activityType(),
                session.status(), endReason.name(), confirmed, session.startedAt(), session.endedAt(), termination,
                snapshotId, ResultInputSnapshotRepository.SNAPSHOT_VERSION, built.snapshotHash(), built.record());
    }

    /**
     * 실행기 없이 닫혔거나 정산 쓰기가 실패한 종료 세션을 다시 정산한다(재무장 · 기동 정리, BR1.8 · BR8.1). 살아 있는 실행기(정산 창 중)가 있는
     * 세션과 정산 창보다 최근에 끝난 세션은 건너뛴다.
     *
     * @return 새로 쓴 스냅숏 수
     */
    public int captureMissing() {
        Instant endedBefore = clock.now().minus(settleGrace);
        List<UUID> missing = dbRetry.execute(DbRetry.Idempotency.IDEMPOTENT,
                () -> snapshots.findTerminalWithoutSnapshot(endedBefore, MISSING_BATCH));
        int written = 0;
        for (UUID sessionId : missing) {
            if (registry.isAlive(sessionId)) {
                continue;
            }
            try {
                if (capture(sessionId, null, clock.now())) {
                    written++;
                }
            } catch (RuntimeException e) {
                events.appError(EventLog.Component.SESSION_LIFECYCLE, e);
            }
        }
        return written;
    }
}
