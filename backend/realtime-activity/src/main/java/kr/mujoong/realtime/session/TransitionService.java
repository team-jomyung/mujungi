package kr.mujoong.realtime.session;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.common.db.DbRetry;
import kr.mujoong.realtime.log.AsyncRecorder;
import kr.mujoong.realtime.log.TranscriptRepository;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 세션 전이 장치 (U1 3.5 Step 23 · BR6.8 · BR6.13 S3a · RD-3 · RD-22 · J126).
 *
 * <p>전이 하나는 짧은 트랜잭션 하나다: 조건부 UPDATE(행 수 1일 때만 승자)와 SessionStateChanged Outbox 쓰기를 함께 커밋하고, 커밋 뒤
 * SESSION_TRANSITION 한 줄과 지표를 남긴다. 진 전이(0행)는 SessionEvent {@code TRANSITION_IGNORED}로 기록만 한다(BR6.8 위반 행).
 * 출발 상태가 둘 이상이면 {@code FOR UPDATE}로 읽은 상태를 조건으로 쓴다. 표에 없는 전이는 DB에 가지 않고 기록만 한다. 일시적 DB
 * 오류는 트랜잭션을 처음부터 다시 연다(DbRetry, 조건부 UPDATE라 멱등).
 */
@Component
public class TransitionService implements SessionTransitions {

    /** 진 · 표에 없는 전이의 SessionEvent 종류. */
    public static final String TRANSITION_IGNORED = "TRANSITION_IGNORED";

    private final ActivitySessionRepository sessions;
    private final TranscriptRepository transcripts;
    private final SessionStateEmitter emitter;
    private final TransactionTemplate transactions;
    private final DbRetry dbRetry;
    private final AsyncRecorder recorder;
    private final JsonCodec json;
    private final MujoongClock clock;
    private final SessionGuardrailFactsRepository guardrailFacts;

    public TransitionService(
            ActivitySessionRepository sessions,
            TranscriptRepository transcripts,
            SessionStateEmitter emitter,
            PlatformTransactionManager transactionManager,
            DbRetry dbRetry,
            AsyncRecorder recorder,
            JsonCodec json,
            MujoongClock clock) {
        this(sessions, transcripts, emitter, transactionManager, dbRetry, recorder, json, clock, null);
    }

    @Autowired
    public TransitionService(ActivitySessionRepository sessions, TranscriptRepository transcripts,
            SessionStateEmitter emitter, PlatformTransactionManager transactionManager, DbRetry dbRetry,
            AsyncRecorder recorder, JsonCodec json, MujoongClock clock, SessionGuardrailFactsRepository guardrailFacts) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.transcripts = Objects.requireNonNull(transcripts, "transcripts");
        this.emitter = Objects.requireNonNull(emitter, "emitter");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.dbRetry = Objects.requireNonNull(dbRetry, "dbRetry");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.guardrailFacts = guardrailFacts;
    }

    @Override
    public Optional<ActivitySessionRow> transition(UUID sessionId, SessionStatus from, SessionStatus to,
            SessionChange change, Origin origin, String traceId) {
        return transition(sessionId, from, to, change, origin, traceId, Extras.none());
    }

    /**
     * U4b: 전이와 같은 트랜잭션에서 기술 일시정지를 SessionStateChanged에 싣고(BR7.2), DECLINED면 그 세션의 전사 행을 지운다(BR3.2).
     * 진 전이(0행)는 아무것도 지우지 않는다.
     */
    @Override
    public Optional<ActivitySessionRow> transition(UUID sessionId, SessionStatus from, SessionStatus to,
            SessionChange change, Origin origin, String traceId, Extras extras) {
        Objects.requireNonNull(extras, "extras");
        if (!TransitionTable.allowed(from, to)) {
            ignored(sessionId, from, to, "NOT_ALLOWED");
            return Optional.empty();
        }
        Optional<ActivitySessionRow> changed = dbRetry.execute(DbRetry.Idempotency.IDEMPOTENT,
                () -> transactions.execute(status -> {
                    Instant now = clock.now();
                    if (extras.guardrailFacts() != null) {
                        Optional<ActivitySessionRow> locked = sessions.findForUpdate(sessionId);
                        if (locked.isEmpty() || locked.get().status() != from
                                || locked.get().statusVersion() + 1 != extras.guardrailFacts().statusVersion()) {
                            return Optional.empty();
                        }
                    }
                    Optional<ActivitySessionRow> row = sessions.transition(sessionId, from, to, now, change);
                    row.ifPresent(value -> {
                        if (extras.eraseTranscripts()) {
                            transcripts.deleteBySession(sessionId);
                        }
                        emitter.write(value, now, traceId, extras.technicalPause());
                        if (extras.guardrailFacts() != null) {
                            Objects.requireNonNull(guardrailFacts, "guardrailFacts")
                                    .append(value, extras.guardrailFacts());
                        }
                    });
                    return row;
                }));
        if (changed == null || changed.isEmpty()) {
            ignored(sessionId, from, to, "LOST");
            return Optional.empty();
        }
        emitter.committed(from, changed.get(), origin, traceId);
        return changed;
    }

    @Override
    public ActivitySessionRepository.MultiOriginTransition transitionFromAny(UUID sessionId, Set<SessionStatus> origins,
            SessionStatus to, Function<SessionStatus, SessionChange> changeFor, Origin origin, String traceId) {
        ActivitySessionRepository.MultiOriginTransition result = dbRetry.execute(DbRetry.Idempotency.IDEMPOTENT,
                () -> transactions.execute(status -> {
                    Instant now = clock.now();
                    ActivitySessionRepository.MultiOriginTransition outcome =
                            sessions.transitionFromAny(sessionId, origins, to, now, changeFor);
                    if (outcome.transitioned()) {
                        emitter.write(outcome.row(), now, traceId);
                    }
                    return outcome;
                }));
        Objects.requireNonNull(result, "result");
        if (result.transitioned()) {
            emitter.committed(result.from(), result.row(), origin, traceId);
        } else if (result.row() != null) {
            ignored(sessionId, result.row().status(), to, "NOT_IN_ORIGINS");
        }
        return result;
    }

    @Override
    public Optional<ActivitySessionRow> annotate(UUID sessionId, SessionStatus expected, SessionChange change) {
        return dbRetry.execute(DbRetry.Idempotency.IDEMPOTENT,
                () -> sessions.annotate(sessionId, expected, clock.now(), change));
    }

    @Override
    public Optional<ActivitySessionRow> find(UUID sessionId) {
        return dbRetry.execute(DbRetry.Idempotency.IDEMPOTENT, () -> sessions.find(sessionId));
    }

    @Override
    public Optional<SessionGuardrailFacts> recordGuardrailFacts(SessionGuardrailFacts facts, int expectedStatusVersion) {
        Objects.requireNonNull(facts, "facts");
        return dbRetry.execute(DbRetry.Idempotency.IDEMPOTENT, () -> transactions.execute(status -> {
            Optional<ActivitySessionRow> locked = sessions.findForUpdate(facts.sessionId());
            if (locked.isEmpty() || locked.get().statusVersion() != expectedStatusVersion) {
                return Optional.empty();
            }
            return Optional.of(Objects.requireNonNull(guardrailFacts, "guardrailFacts").append(locked.get(), facts));
        }));
    }

    /**
     * 기동 S3a(BR6.13): 비종료 세션을 모두 FAILED(SERVER_RESTART)로 닫는다. 세션마다 한 트랜잭션(SessionStateChanged 함께)이다.
     *
     * @return 닫은 세션 수
     */
    public int failInProgressAfterRestart() {
        int closed = 0;
        for (ActivitySessionRow row : dbRetry.execute(DbRetry.Idempotency.IDEMPOTENT, sessions::findInProgress)) {
            ActivitySessionRepository.MultiOriginTransition result = transitionFromAny(row.id(),
                    TransitionTable.Trigger.SERVER_RESTART.origins(), SessionStatus.FAILED,
                    read -> TransitionTable.changeFor(TransitionTable.Trigger.SERVER_RESTART, null, clock.now()),
                    Origin.REALTIME, null);
            if (result.transitioned()) {
                closed++;
            }
        }
        return closed;
    }

    private void ignored(UUID sessionId, SessionStatus from, SessionStatus to, String cause) {
        String payload = json.write(new IgnoredTransition(from == null ? null : from.name(), to.name(), cause));
        recorder.putEvent(new SessionEvent(UUID.randomUUID(), sessionId, TRANSITION_IGNORED, null, payload,
                clock.now()));
    }

    /** SessionEvent payload(전사 · 비밀 없음). */
    public record IgnoredTransition(String from, String to, String cause) {
    }
}
