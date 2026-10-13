package kr.mujoong.realtime.testing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.session.ActivitySessionRepository;
import kr.mujoong.realtime.session.ActivitySessionRow;
import kr.mujoong.realtime.session.SessionChange;
import kr.mujoong.realtime.session.SessionTransitions;
import kr.mujoong.realtime.session.TransitionTable;
import org.springframework.dao.TransientDataAccessResourceException;

/**
 * 시험용 세션 전이 장치(메모리). 운영 {@code TransitionService}처럼 조건부 전이(출발 상태가 맞을 때만, 표에 있는 전이만)를 하고 성립한 전이를
 * 기록한다. {@link #failNext(int)}로 다음 몇 번의 호출을 DB 장애(DataAccessException)로 만들고, {@link #loseCommitNext(int)}로 다음 몇
 * 번의 성립한 전이를 행을 바꾼 뒤 DB 장애로 만든다(커밋은 됐는데 응답을 잃은 경우).
 */
public final class InMemoryTransitions implements SessionTransitions {

    /** 성립한 전이 한 건. */
    public record Applied(UUID sessionId, SessionStatus from, SessionStatus to, SessionChange change, Origin origin) {
    }

    private final Map<UUID, ActivitySessionRow> rows = new ConcurrentHashMap<>();
    private final List<Applied> applied = new ArrayList<>();
    private final MujoongClock clock;
    private int failures;
    private int crashes;
    private int lostCommits;

    public InMemoryTransitions(MujoongClock clock) {
        this.clock = clock;
    }

    /** PREPARING 행을 둔다. */
    public ActivitySessionRow seed(UUID sessionId, UUID guardianId, UUID deviceId) {
        ActivitySessionRow row = row(sessionId, guardianId, deviceId, SessionStatus.PREPARING, 1, SessionChange.none(),
                clock.now());
        rows.put(sessionId, row);
        return row;
    }

    /** 행 상태를 바깥에서 바꾼다(다른 쪽 전이가 이긴 경합 흉내). */
    public void force(UUID sessionId, SessionStatus status) {
        ActivitySessionRow current = rows.get(sessionId);
        rows.put(sessionId, copy(current, status, current.statusVersion() + 1, SessionChange.none()));
    }

    /** 다음 {@code count}번의 호출을 DB가 아닌 예외(버그 흉내)로 만든다. */
    public synchronized void crashNext(int count) {
        this.crashes = count;
    }

    /** 다음 {@code count}번의 호출을 DB 장애로 만든다. */
    public synchronized void failNext(int count) {
        this.failures = count;
    }

    /** 다음 {@code count}번의 성립한 전이는 행을 바꾼 뒤 DB 장애를 던진다(커밋 응답 유실 흉내, 리뷰 F5). */
    public synchronized void loseCommitNext(int count) {
        this.lostCommits = count;
    }

    @Override
    public synchronized Optional<ActivitySessionRow> transition(UUID sessionId, SessionStatus from, SessionStatus to,
            SessionChange change, Origin origin, String traceId) {
        maybeFail();
        ActivitySessionRow current = rows.get(sessionId);
        if (current == null || current.status() != from || !TransitionTable.allowed(from, to)) {
            return Optional.empty();
        }
        ActivitySessionRow next = copy(current, to, current.statusVersion() + 1, change);
        rows.put(sessionId, next);
        applied.add(new Applied(sessionId, from, to, change, origin));
        maybeLoseCommit();
        return Optional.of(next);
    }

    @Override
    public synchronized ActivitySessionRepository.MultiOriginTransition transitionFromAny(UUID sessionId,
            Set<SessionStatus> origins, SessionStatus to, Function<SessionStatus, SessionChange> changeFor,
            Origin origin, String traceId) {
        maybeFail();
        ActivitySessionRow current = rows.get(sessionId);
        if (current == null) {
            return new ActivitySessionRepository.MultiOriginTransition(
                    ActivitySessionRepository.MultiOriginTransition.Outcome.NOT_FOUND, null, null);
        }
        if (!origins.contains(current.status())) {
            return new ActivitySessionRepository.MultiOriginTransition(
                    ActivitySessionRepository.MultiOriginTransition.Outcome.NOT_IN_ORIGINS, null, current);
        }
        SessionChange change = changeFor.apply(current.status());
        ActivitySessionRow next = copy(current, to, current.statusVersion() + 1, change);
        rows.put(sessionId, next);
        applied.add(new Applied(sessionId, current.status(), to, change, origin));
        maybeLoseCommit();
        return new ActivitySessionRepository.MultiOriginTransition(
                ActivitySessionRepository.MultiOriginTransition.Outcome.TRANSITIONED, current.status(), next);
    }

    @Override
    public synchronized Optional<ActivitySessionRow> annotate(UUID sessionId, SessionStatus expected,
            SessionChange change) {
        maybeFail();
        ActivitySessionRow current = rows.get(sessionId);
        if (current == null || current.status() != expected) {
            return Optional.empty();
        }
        ActivitySessionRow next = copy(current, current.status(), current.statusVersion(), change);
        rows.put(sessionId, next);
        return Optional.of(next);
    }

    @Override
    public Optional<ActivitySessionRow> find(UUID sessionId) {
        return Optional.ofNullable(rows.get(sessionId));
    }

    public SessionStatus status(UUID sessionId) {
        return rows.get(sessionId).status();
    }

    public synchronized List<Applied> applied() {
        return List.copyOf(applied);
    }

    private void maybeFail() {
        if (crashes > 0) {
            crashes--;
            throw new IllegalStateException("시험용 실행기 예외");
        }
        if (failures > 0) {
            failures--;
            throw new TransientDataAccessResourceException("시험용 DB 장애");
        }
    }

    private void maybeLoseCommit() {
        if (lostCommits > 0) {
            lostCommits--;
            throw new TransientDataAccessResourceException("시험용 커밋 응답 유실");
        }
    }

    private ActivitySessionRow copy(ActivitySessionRow current, SessionStatus status, int version,
            SessionChange change) {
        return new ActivitySessionRow(current.id(), current.guardianId(), current.childId(), current.deviceId(),
                current.activityType(), status, version, current.startIdempotencyKey(),
                or(change.providerSessionId(), current.providerSessionId()), current.consentVersion(),
                current.consentCheckedAt(), current.consentSnapshotJson(), current.policyVersion(),
                current.dataCollectionAllowed(), current.transcriptStorageAllowed(), current.childContextJson(),
                current.introContentVersionId(), current.closingContentVersionId(),
                or(change.endReason(), current.endReason()),
                change.endDetailValue() != null ? change.endDetailValue() : current.endDetail(),
                or(change.terminationRequestedAt(), current.terminationRequestedAt()),
                or(change.outputStoppedAt(), current.outputStoppedAt()),
                or(change.transportClosedAt(), current.transportClosedAt()),
                or(change.providerClosedAt(), current.providerClosedAt()), or(change.startedAt(), current.startedAt()),
                or(change.endedAt(), current.endedAt()), current.rowVersion() + 1, current.createdAt(), clock.now());
    }

    private static <T> T or(T value, T fallback) {
        return value != null ? value : fallback;
    }

    private static ActivitySessionRow row(UUID id, UUID guardianId, UUID deviceId, SessionStatus status, int version,
            SessionChange change, Instant now) {
        return new ActivitySessionRow(id, guardianId, UUID.randomUUID(), deviceId, ActivityType.FREE_TALK, status,
                version, UUID.randomUUID().toString(), null, null, null,
                "{\"policyVersion\":\"1.0\",\"voiceAndText\":true,\"guardianView\":true,\"reportSharing\":false,"
                        + "\"rawAudio\":false}",
                "1.0", true, true, "{\"displayName\":\"하늘\",\"age\":8,\"interests\":[\"공룡\"]}", null, null,
                change.endReason(), null, null, null, null, null, null, null, 0L, now, now);
    }
}
