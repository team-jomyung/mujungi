package kr.mujoong.core.account;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.event.payload.DeletionRequested;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.account.DeletionRequestRepository.DeletionRequest;
import kr.mujoong.core.activity.ActivityCopyRepository;
import kr.mujoong.core.activity.ActivityCopyRepository.ActivityCopy;
import kr.mujoong.core.activity.RealtimeSessionClient;
import kr.mujoong.core.common.db.ClockParams;
import kr.mujoong.core.common.deletion.DeletionParticipant;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.identity.SocialAccountRepository;
import kr.mujoong.core.messaging.CoreOutboxWriter;
import kr.mujoong.core.observability.EventLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 계정 삭제 지휘 (U2 AccountDeletion · FR1.6 · AC1.6.3 · AC1.6.6 · AC1.6.7 · R11 · J19 · BR5.14 (d) · U2-J12).
 *
 * <p>{@link #runOnce}는 처리할 때가 된 REQUESTED 요청을 하나씩 선점하고(FOR UPDATE SKIP LOCKED + next_attempt_at을 세션 대기 시한만큼 미룸 —
 * 여러 인스턴스 · 바퀴가 겹쳐도 한 곳만 처리) 다음 한 단계를 진행한다. 각 단계는 멱등이라 실패하면 시도 수 · 예외 종류만 남기고 다음 바퀴에
 * 다시 한다.
 *
 * <ol>
 *   <li>진행 세션 사본이 있으면 CONSENT_WITHDRAWN 끝내기를 한 번 보내고, 사본이 종료 상태가 되거나 {@code deletion-session-wait}(30초)가 지날
 *       때까지 기다린다(AC1.6.3).
 *   <li>Core 참가자를 {@code @Order} 순서로 모두 부른다(활동 → 기기 → 동의 → 아이 → 메시징, 뒤 유닛 참가자도 빈이면 함께).
 *   <li>한 트랜잭션에 DeletionRequested Outbox + 요청의 core_cleared_at · realtime_requested_at.
 *   <li>DeletionCompleted 수신(realtime_completed_at, {@link DeletionCompletedHandler}) 뒤: 참가자를 한 번 더(전달이 끝난 메시징 행 정리,
 *       BR5.14 (d)) → 한 트랜잭션에 소셜 계정 · 로그인 세션 행 · 보호자 행 삭제 + 요청 DONE.
 * </ol>
 *
 * <p>Realtime 쪽 삭제와 DeletionCompleted 발행은 U4가 붙인다. 그 전까지 요청은 REQUESTED로 남고 보호자는 계속 차단된다(J19).
 */
@Component
public class DeletionCommander {

    /** 한 바퀴에 다루는 요청 수. */
    static final int BATCH = 10;

    private final DeletionRequestRepository requests;
    private final List<DeletionParticipant> participants;
    private final ActivityCopyRepository activities;
    private final RealtimeSessionClient realtime;
    private final CoreOutboxWriter outbox;
    private final SocialAccountRepository socialAccounts;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final EventLogger events;
    private final MujoongClock clock;
    private final Duration sessionWait;
    private final Duration tick;

    @Autowired
    public DeletionCommander(
            DeletionRequestRepository requests,
            List<DeletionParticipant> participants,
            ActivityCopyRepository activities,
            RealtimeSessionClient realtime,
            CoreOutboxWriter outbox,
            SocialAccountRepository socialAccounts,
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            EventLogger events,
            MujoongClock clock,
            CoreProperties properties) {
        this.requests = Objects.requireNonNull(requests, "requests");
        this.participants = List.copyOf(Objects.requireNonNull(participants, "participants"));
        this.activities = Objects.requireNonNull(activities, "activities");
        this.realtime = Objects.requireNonNull(realtime, "realtime");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.socialAccounts = Objects.requireNonNull(socialAccounts, "socialAccounts");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sessionWait = properties.timing().account().deletionSessionWait();
        this.tick = properties.timing().account().deletionTick();
    }

    /** 한 요청에서 일어난 일(시험 · 로그용). */
    public enum Step {
        /** 잠그지 못했다(다른 곳이 처리 중 · 이미 완료). */
        SKIPPED,
        /** 진행 세션 끝내기를 보냈다. */
        SESSION_END_REQUESTED,
        /** 세션 끝 · Realtime 완료를 기다린다. */
        WAITING,
        /** Core 참가자를 마치고 DeletionRequested를 썼다. */
        REALTIME_REQUESTED,
        /** 완료(DONE). */
        DONE,
        /** 실패(다음 바퀴에 다시). */
        FAILED
    }

    /** 처리할 때가 된 요청을 처리한다. */
    public List<Step> runOnce() {
        return requests.findDueIds(clock.now(), BATCH).stream().map(this::process).toList();
    }

    /** 요청 하나의 다음 단계. */
    public Step process(UUID requestId) {
        Instant claimedAt = clock.now();
        Optional<DeletionRequest> snapshot = transactions.execute(status ->
                requests.claim(requestId, claimedAt, claimedAt.plus(sessionWait)));
        if (snapshot == null || snapshot.isEmpty()) {
            return Step.SKIPPED;
        }
        DeletionRequest request = snapshot.get();
        try {
            if (request.realtimeCompletedAt() != null) {
                return finish(request);
            }
            if (request.realtimeRequestedAt() != null) {
                // DeletionCompleted를 기다린다: 선점 시한(세션 대기)마다 한 번 확인하고, 수신 처리기가 곧바로 당긴다
                return Step.WAITING;
            }
            Optional<Step> waiting = endSessionFirst(request);
            if (waiting.isPresent()) {
                return waiting.get();
            }
            runParticipants(request);
            transactions.executeWithoutResult(status -> {
                Instant now = clock.now();
                outbox.write(new DeletionRequested(request.id(), request.guardianId(), request.childId(), request.requestedAt()),
                        request.guardianId(), now);
                requests.markRealtimeRequested(request.id(), now, now.plus(tick));
            });
            return Step.REALTIME_REQUESTED;
        } catch (RuntimeException e) {
            events.appError(EventLog.Component.CORE_MAINTENANCE, e);
            Instant now = clock.now();
            transactions.executeWithoutResult(status ->
                    requests.recordFailure(request.id(), e.getClass().getSimpleName(), now, now.plus(tick)));
            return Step.FAILED;
        }
    }

    /** ① 진행 세션이 있으면 CONSENT_WITHDRAWN 끝내기 → 종료 확인 또는 시한까지 기다림. 기다릴 필요가 없으면 빈 값. */
    private Optional<Step> endSessionFirst(DeletionRequest request) {
        Optional<ActivityCopy> inProgress = activities.findInProgressByGuardian(request.guardianId());
        if (inProgress.isEmpty()) {
            return Optional.empty();
        }
        Instant now = clock.now();
        if (request.sessionEndRequestedAt() == null) {
            // 실패해도 시한 안에 다시 보내지 않는다: 기기 확인이 없으면 30초 뒤 데이터 삭제로 넘어간다(AC1.6.3)
            realtime.endForConsentWithdrawal(inProgress.get().sessionId(), request.id());
            transactions.executeWithoutResult(status ->
                    requests.markSessionEndRequested(request.id(), now, now.plus(tick)));
            return Optional.of(Step.SESSION_END_REQUESTED);
        }
        if (now.isBefore(request.sessionEndRequestedAt().plus(sessionWait))) {
            transactions.executeWithoutResult(status -> requests.postpone(request.id(), now, now.plus(tick)));
            return Optional.of(Step.WAITING);
        }
        return Optional.empty();
    }

    private void runParticipants(DeletionRequest request) {
        for (DeletionParticipant participant : participants) {
            participant.deleteByGuardian(request.guardianId(), request.id());
        }
    }

    /** ④ 마무리: 참가자 한 번 더 → 소셜 계정 · 로그인 세션 행 · 보호자 행 삭제 + DONE(한 트랜잭션). */
    private Step finish(DeletionRequest request) {
        runParticipants(request);
        transactions.executeWithoutResult(status -> {
            Instant now = clock.now();
            socialAccounts.deleteByGuardian(request.guardianId());
            MapSqlParameterSource params = ClockParams.uuid(new MapSqlParameterSource(), "id", request.guardianId());
            jdbc.update("delete from core.core_spring_session where principal_name = :name",
                    new MapSqlParameterSource("name", request.guardianId().toString()));
            jdbc.update("delete from core.core_guardian where id = :id", params);
            requests.markDone(request.id(), now);
        });
        return Step.DONE;
    }
}
