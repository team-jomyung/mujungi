package kr.mujoong.core.notification.transport;

import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.activity.StaleEvaluator;
import kr.mujoong.core.common.db.ClockParams;
import kr.mujoong.core.identity.Guardian;
import kr.mujoong.core.identity.GuardianWriteLock;
import kr.mujoong.core.notification.PushEventType;
import kr.mujoong.core.notification.PushSubscriptionRepository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 다중 구독 영속 발송. 보호자 → 활동 → 발송 행 순서로 잠그고 전송 결과를 저장한 뒤 잠금을 푼다. */
@Repository
public class PushDispatchRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final GuardianWriteLock guardians;
    private final PushSubscriptionRepository subscriptions;
    private final StaleEvaluator stale;
    private final MujoongClock clock;
    private final JsonCodec json;

    public PushDispatchRepository(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager manager,
            GuardianWriteLock guardians,
            PushSubscriptionRepository subscriptions,
            StaleEvaluator stale,
            MujoongClock clock,
            JsonCodec json) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.guardians = guardians;
        this.subscriptions = subscriptions;
        this.stale = stale;
        this.clock = clock;
        this.json = json;
    }

    /** 짧은 선점 트랜잭션은 보호자·활동 잠금을 잡지 않는다. 전송 단계가 선점 세대를 다시 검사한다. */
    public Optional<Dispatch> claimNext(Instant now, Duration lease) {
        return transactions.execute(
                tx -> {
                    var params = ClockParams.at(now).addValue("claim", UUID.randomUUID());
                    ClockParams.instant(params, "until", now.plus(lease));
                    return jdbc
                            .query(
                                    """
                                    with due as (
                                        select id from core.core_push_dispatch
                                         where status='REQUESTED' and next_attempt_at<=:now
                                           and (lease_until is null or lease_until<=:now)
                                         order by next_attempt_at,requested_at,id limit 1 for update skip locked
                                    )
                                    update core.core_push_dispatch p set claim_id=:claim,lease_until=:until,attempt_count=attempt_count+1
                                      from due where p.id=due.id returning p.*
                                    """,
                                    params,
                                    this::map)
                            .stream()
                            .findFirst();
                });
    }

    /** 트랜잭션마다 제한된 HTTP 전송 한 번과 결과 한 건을 저장한다. 뒤 수신처 실패가 앞 성공을 되돌리지 않는다. */
    public void processRecipient(
            Dispatch claimed,
            UUID subscriptionId,
            PushDeliverySender sender,
            int maximumAttempts,
            Duration lease) {
        transactions.executeWithoutResult(
                tx -> {
                    boolean ownerActive =
                            guardians
                                    .statusForShare(claimed.guardianId())
                                    .filter(Guardian.STATUS_ACTIVE::equals)
                                    .isPresent();
                    boolean eligible = ownerActive && deliveryEligible(claimed, clock.now());
                    Optional<Dispatch> current = locked(claimed);
                    if (current.isEmpty()) return;
                    Dispatch row = current.get();
                    Map<String, RecipientProgress> progress =
                            PushDeliveryLedger.read(json, json.write(row.recipients()));
                    String key = subscriptionId.toString();
                    RecipientProgress recipient = progress.get(key);
                    if (recipient == null || !recipient.pendingDelivery()) return;
                    var active =
                            eligible
                                    ? subscriptions.findActive(row.guardianId(), subscriptionId)
                                    : Optional
                                            .<kr.mujoong.core.notification.PushSubscription>empty();
                    RecipientProgress updated;
                    boolean accepted = false;
                    if (!eligible) updated = recipient.skip("PUSH_EVENT_OBSOLETE");
                    else if (active.isEmpty())
                        updated = recipient.skip("PUSH_SUBSCRIPTION_INACTIVE");
                    else {
                        // 행 잠금으로 전송 도중 기한이 지난 선점을 다른 작업자가 가져가지 못하게 한다.
                        var renewal = parameters(row, clock.now());
                        ClockParams.instant(renewal, "until", clock.now().plus(lease));
                        jdbc.update(
                                "update core.core_push_dispatch set lease_until=:until where id=:id"
                                        + " and claim_id=:claim and status='REQUESTED'",
                                renewal);
                        DeliveryOutcome outcome;
                        try {
                            var target = active.get();
                            outcome =
                                    sender.send(
                                            PushTarget.validated(
                                                    target.endpoint(),
                                                    target.p256dh(),
                                                    target.auth()),
                                            new PushPayload(
                                                    row.type(),
                                                    row.activityType(),
                                                    row.sessionId(),
                                                    clock.now()),
                                            topic(row.eventKey()));
                        } catch (RuntimeException failure) {
                            outcome = DeliveryOutcome.failed("PUSH_DELIVERY_FAILED");
                        }
                        updated = recipient.after(outcome, maximumAttempts);
                        accepted = outcome.status() == DeliveryOutcome.Status.ACCEPTED;
                        if (accepted)
                            subscriptions.markUsed(row.guardianId(), subscriptionId, clock.now());
                        if (outcome.status() == DeliveryOutcome.Status.EXPIRED)
                            subscriptions.expire(row.guardianId(), subscriptionId);
                    }
                    progress.put(key, updated);
                    var params =
                            parameters(row, clock.now())
                                    .addValue("progress", json.write(progress))
                                    .addValue("accepted", accepted);
                    ClockParams.text(params, "error", updated.lastError());
                    ClockParams.instant(params, "until", clock.now().plus(lease));
                    jdbc.update(
                            """
                            update core.core_push_dispatch set recipient_progress=cast(:progress as jsonb),lease_until=:until,
                                sent_at=case when :accepted then coalesce(sent_at,:now) else sent_at end,last_error_code=:error
                             where id=:id and claim_id=:claim and status='REQUESTED'
                            """,
                            params);
                });
    }

    /** 가장 최근 영속 결과로 종합 상태를 정한 뒤 선점을 해제한다. */
    public void finish(Dispatch claimed, Instant now, List<Duration> backoff) {
        transactions.executeWithoutResult(
                tx ->
                        locked(claimed)
                                .ifPresent(
                                        row -> {
                                            String status =
                                                    PushDeliveryLedger.status(row.recipients());
                                            Instant next =
                                                    "REQUESTED".equals(status)
                                                            ? now.plus(
                                                                    backoff.get(
                                                                            PushDeliveryLedger
                                                                                    .retryIndex(
                                                                                            row
                                                                                                    .recipients(),
                                                                                            backoff
                                                                                                    .size())))
                                                            : now;
                                            String error =
                                                    row.recipients().values().stream()
                                                            .filter(
                                                                    p ->
                                                                            p.status()
                                                                                            == RecipientProgress
                                                                                                    .Status
                                                                                                    .FAILED
                                                                                    || p
                                                                                            .pendingDelivery())
                                                            .map(RecipientProgress::lastError)
                                                            .filter(java.util.Objects::nonNull)
                                                            .findFirst()
                                                            .orElse(null);
                                            var params =
                                                    parameters(row, now).addValue("status", status);
                                            ClockParams.instant(params, "next", next);
                                            ClockParams.text(params, "error", error);
                                            jdbc.update(
                                                    """
                                                    update core.core_push_dispatch set status=:status,next_attempt_at=:next,claim_id=null,lease_until=null,last_error_code=:error
                                                     where id=:id and claim_id=:claim and status='REQUESTED'
                                                    """,
                                                    params);
                                        }));
    }

    private Optional<Dispatch> locked(Dispatch row) {
        return jdbc
                .query(
                        "select * from core.core_push_dispatch where id=:id and claim_id=:claim and"
                                + " status='REQUESTED' for update",
                        parameters(row, clock.now()),
                        this::map)
                .stream()
                .findFirst();
    }

    private boolean deliveryEligible(Dispatch row, Instant now) {
        var params =
                new MapSqlParameterSource()
                        .addValue("session", row.sessionId())
                        .addValue("guardian", row.guardianId());
        return jdbc
                .query(
                        """
                        select a.status,a.confirmed,a.started_at,a.end_reason,a.requested_at,d.reported_at,d.registration_status
                          from core.core_activity a left join core.core_device d on d.id=a.device_id and d.guardian_id=a.guardian_id
                         where a.session_id=:session and a.guardian_id=:guardian for share of a
                        """,
                        params,
                        (rs, n) -> {
                            SessionStatus status = SessionStatus.fromWire(rs.getString("status"));
                            if (row.type().technical()) {
                                if (!status.isNonTerminal()) return false;
                                return row.type() != PushEventType.DEVICE_STATUS_UNKNOWN
                                        || ("REGISTERED".equals(rs.getString("registration_status"))
                                                && stale.isSessionStale(
                                                        ClockParams.instant(rs, "reported_at"),
                                                        ClockParams.instant(rs, "requested_at"),
                                                        false,
                                                        now));
                            }
                            if (!rs.getBoolean("confirmed")) return false;
                            String reason = rs.getString("end_reason");
                            if (row.type() == PushEventType.ENDED_BY_CONNECTION)
                                return status == SessionStatus.ABORTED
                                        && "DISCONNECT".equals(reason);
                            return status == SessionStatus.ENDED
                                    && ClockParams.instant(rs, "started_at") != null
                                    && reason != null
                                    && List.of("CHILD_STOP", "GUARDIAN_STOP", "SILENCE")
                                            .contains(reason);
                        })
                .stream()
                .findFirst()
                .orElse(false);
    }

    private Dispatch map(ResultSet rs, int ignored) throws SQLException {
        return new Dispatch(
                rs.getObject("id", UUID.class),
                rs.getObject("guardian_id", UUID.class),
                rs.getObject("session_id", UUID.class),
                PushEventType.valueOf(rs.getString("event_type")),
                ActivityType.valueOf(rs.getString("activity_type")),
                rs.getString("event_key"),
                rs.getInt("attempt_count"),
                rs.getObject("claim_id", UUID.class),
                PushDeliveryLedger.read(json, rs.getString("recipient_progress")));
    }

    private static MapSqlParameterSource parameters(Dispatch row, Instant now) {
        return ClockParams.at(now).addValue("id", row.id()).addValue("claim", row.claimId());
    }

    public static String topic(String eventKey) {
        try {
            byte[] hash =
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(eventKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(java.util.Arrays.copyOf(hash, 24));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("푸시 Topic 생성 실패");
        }
    }

    public record Dispatch(
            UUID id,
            UUID guardianId,
            UUID sessionId,
            PushEventType type,
            ActivityType activityType,
            String eventKey,
            int attemptCount,
            UUID claimId,
            Map<String, RecipientProgress> recipients) {
        public Dispatch {
            recipients = Map.copyOf(recipients);
        }
    }
}
