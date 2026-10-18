package kr.mujoong.realtime.messaging;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.realtime.common.db.ClockParams;
import kr.mujoong.realtime.common.db.Transactions;
import kr.mujoong.realtime.config.RealtimeOutboxProperties.ControlEventPolicy;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.startup.StartupFailureLine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * FAILED 제어 이벤트 재무장 함수 (U1 3.5 Step 26 · A1 · RD-10 · SD-21 · BR5.14 · J124 · LC-11).
 *
 * <p>조건 표(RD-10)는 {@link RealtimeOutboxRepository#REARM_EVENT_TYPES} · {@link RealtimeOutboxRepository#REARM_EXCLUDED_ERRORS}
 * 한 곳이다: 자기 Outbox의 FAILED 행 중 Realtime → Core 제어 이벤트(DeletionCompleted · SessionRecordCaptured)이고 last_error가
 * EVENT_CONFLICT · INVALID_ENVELOPE · ACCOUNT_DELETED가 아니며 payload를 아직 비우지 않은 행을 PENDING · retry_count 0 · available_at =
 * now로 되돌린다(last_error는 둔다). 시작 복구 S3d({@link #rearmOnStart})와 계정 삭제 참가자({@link #rearmForGuardian}: FAILED 제어
 * 이벤트를 지우지 않고 되살림)가 같은 조건을 쓴다. 정책 {@code RETRY_FOREVER}는 보류 옵션이라 빈 생성 단계에서 기동을 거부한다(J124).
 */
@Component
public class ControlEventRearm {

    private static final String REARM_FOR_GUARDIAN = "update realtime.realtime_outbox_event"
            + " set status = 'PENDING', retry_count = 0, available_at = :now"
            + " where status = 'FAILED' and guardian_id = :guardianId and event_type = any(:types) and purged_at is null"
            + " and (last_error is null or last_error <> all(:excludedErrors))";

    private final RealtimeOutboxRepository outbox;
    private final NamedParameterJdbcTemplate jdbc;

    @Autowired
    public ControlEventRearm(RealtimeOutboxRepository outbox, NamedParameterJdbcTemplate jdbc,
            RealtimeProperties properties) {
        this(outbox, jdbc, properties.outbox().controlEventPolicy());
    }

    public ControlEventRearm(RealtimeOutboxRepository outbox, NamedParameterJdbcTemplate jdbc, ControlEventPolicy policy) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        requireSupported(policy);
    }

    /**
     * 정책 확인. {@code REARM_ON_START}만 지원한다(J124: {@code RETRY_FOREVER}는 보류 옵션 → 기동 실패, 거부 줄에는 키 이름만).
     */
    public static void requireSupported(ControlEventPolicy policy) {
        if (policy != ControlEventPolicy.REARM_ON_START) {
            String reason = "mujoong.outbox.control-event-policy는 REARM_ON_START만 지원한다(보류 옵션 거부, J124)";
            StartupFailureLine.print("ControlEventPolicyUnsupported", reason);
            throw new IllegalStateException(reason);
        }
    }

    /** S3d: 시작 복구. 되살린 행 수를 돌려준다(STARTUP_RECOVERY rearmedControlEvents). */
    public int rearmOnStart(Instant now) {
        return outbox.rearmControlEvents(Objects.requireNonNull(now, "now"));
    }

    /** BR5.14: 한 보호자의 FAILED 제어 이벤트를 같은 조건으로 되살린다(삭제 참가자가 지우기 전에, 같은 트랜잭션에서 부른다). */
    public int rearmForGuardian(UUID guardianId, Instant now) {
        Transactions.requireActive("ControlEventRearm.rearmForGuardian");
        MapSqlParameterSource params = ClockParams.at(Objects.requireNonNull(now, "now"));
        ClockParams.uuid(params, "guardianId", Objects.requireNonNull(guardianId, "guardianId"));
        ClockParams.textArray(params, "types", RealtimeOutboxRepository.REARM_EVENT_TYPES);
        ClockParams.textArray(params, "excludedErrors", RealtimeOutboxRepository.REARM_EXCLUDED_ERRORS);
        return jdbc.update(REARM_FOR_GUARDIAN, params);
    }
}
