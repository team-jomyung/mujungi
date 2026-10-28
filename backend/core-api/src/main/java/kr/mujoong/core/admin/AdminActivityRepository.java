package kr.mujoong.core.admin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.core.common.db.ClockParams;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 관리자 활동 조회 (JIRA-280). Core 스키마만 읽고 쓰지 않는다.
 *
 * <p>끝난 활동만 본다(진행 중 상태 제외). 계정 삭제가 접수된 보호자는 빠진다. 활동 시각은 시작 시각, 시작 전이면 요청 시각이다.
 *
 * <p>끝 분류({@code end_category}):
 * <ul>
 *   <li>{@code ENDED} · {@code ENDED_UNCONFIRMED}: 화면 상태 COMPLETED. 확인 안 된 종료는 분류로 따로 남겨 정상 종료와 섞지 않는다.
 *   <li>{@code INTERRUPTED}: ABORTED · FAILED 가운데 시작했거나(시작 시각) 발화가 남은 활동(서버 재시작 중단 포함, BR14.1).
 *   <li>{@code DECLINED}(아이 참여 거절) · {@code NOT_STARTED}(시작 전 실패 · 중단): 화면 상태 FAILED_TO_START.
 * </ul>
 * 전사 원문 열은 목록 질의에서 읽지 않고 발화 수만 센다. 원문은 {@link #transcript}만 읽는다.
 */
@Repository
public class AdminActivityRepository {

    static final String TERMINAL = "('ENDED', 'ENDED_UNCONFIRMED', 'ABORTED', 'DECLINED', 'FAILED')";

    static final String CLASSIFIED = """
            with base as (
                select a.session_id, a.guardian_id, a.child_id, a.device_id, a.activity_type, a.status as raw_status,
                    a.end_reason, a.failure_code, coalesce(a.started_at, a.requested_at) as activity_at, a.started_at,
                    a.ended_at, c.display_name as child_name, r.session_id is not null as has_record,
                    case when r.session_id is null then null else (select count(*) from jsonb_array_elements(r.transcript_json) e
                        where e->>'speaker' = 'CHILD') end as child_turns,
                    case when r.session_id is null then null else (select count(*) from jsonb_array_elements(r.transcript_json) e
                        where e->>'speaker' = 'AI') end as ai_turns,
                    r.termination_json->>'endDetail' as end_detail,
                    case
                        when a.status = 'ENDED' then 'ENDED'
                        when a.status = 'ENDED_UNCONFIRMED' then 'ENDED_UNCONFIRMED'
                        when a.status = 'DECLINED' then 'DECLINED'
                        when a.started_at is not null or coalesce(jsonb_array_length(r.transcript_json), 0) > 0 then 'INTERRUPTED'
                        else 'NOT_STARTED'
                    end as end_category
                from core.core_activity a
                join core.core_guardian g on g.id = a.guardian_id and g.status = 'ACTIVE'
                join core.core_child c on c.id = a.child_id
                left join core.core_activity_record r on r.session_id = a.session_id
                where a.status in %s %s
            ), classified as (
                select base.*, case end_category
                    when 'ENDED' then 'COMPLETED' when 'ENDED_UNCONFIRMED' then 'COMPLETED'
                    when 'INTERRUPTED' then 'INTERRUPTED' else 'FAILED_TO_START' end as ui_status
                from base
            )
            """;

    static final String IN_RANGE = "and coalesce(a.started_at, a.requested_at) >= :from"
            + " and coalesce(a.started_at, a.requested_at) < :to";

    private static final String FILTERS = """
            where (cast(:type as varchar) is null or activity_type = :type)
              and (cast(:guardian as uuid) is null or guardian_id = :guardian)
              and (cast(:child as uuid) is null or child_id = :child)
              and (cast(:device as uuid) is null or device_id = :device)
              and (cast(:status as varchar) is null or ui_status = :status)
              and (cast(:like as varchar) is null or child_name ilike :like
                   or cast(session_id as text) like :prefix or cast(guardian_id as text) like :prefix
                   or cast(device_id as text) like :prefix)
            """;

    private static final String ORDER = " order by activity_at desc, session_id desc";

    private final NamedParameterJdbcTemplate jdbc;

    public AdminActivityRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** 활동 한 줄(원문 없음). 발화 수는 기록이 없으면 null이다. */
    public record ActivityRow(
            UUID sessionId,
            UUID guardianId,
            UUID childId,
            UUID deviceId,
            String activityType,
            String rawStatus,
            String endReason,
            String failureCode,
            String endDetail,
            Instant activityAt,
            Instant startedAt,
            Instant endedAt,
            String childName,
            boolean hasRecord,
            Integer childTurns,
            Integer aiTurns,
            String endCategory,
            String uiStatus) {

        /** 아이 이름은 문자열 표현에 넣지 않는다. */
        @Override
        public String toString() {
            return "ActivityRow[sessionId=" + sessionId + ", status=" + uiStatus + "]";
        }
    }

    /** 필터에 맞는 활동 수. */
    public int count(AdminFilter filter) {
        Integer total = jdbc.queryForObject(CLASSIFIED.formatted(TERMINAL, IN_RANGE) + "select count(*) from classified "
                + FILTERS, params(filter), Integer.class);
        return total == null ? 0 : total;
    }

    /** 필터에 맞는 활동 한 쪽(최근 순, 같은 시각은 id 순). */
    public List<ActivityRow> page(AdminFilter filter, int offset, int limit) {
        MapSqlParameterSource params = params(filter).addValue("offset", offset).addValue("limit", limit);
        return jdbc.query(CLASSIFIED.formatted(TERMINAL, IN_RANGE) + "select * from classified " + FILTERS + ORDER
                + " limit :limit offset :offset", params, AdminActivityRepository::row);
    }

    /** 필터에 맞는 활동 전부(집계용, 최근 순). */
    public List<ActivityRow> all(AdminFilter filter) {
        return jdbc.query(CLASSIFIED.formatted(TERMINAL, IN_RANGE) + "select * from classified " + FILTERS + ORDER,
                params(filter), AdminActivityRepository::row);
    }

    /** 끝난 활동 하나(기간 제한 없음). 진행 중 · 삭제 접수 보호자 · 없는 활동은 빈 값. */
    public Optional<ActivityRow> find(UUID sessionId) {
        MapSqlParameterSource params = ClockParams.uuid(new MapSqlParameterSource(), "session", sessionId);
        return jdbc.query(CLASSIFIED.formatted(TERMINAL, "and a.session_id = :session") + "select * from classified", params,
                AdminActivityRepository::row).stream().findFirst();
    }

    /** 세션 id들 가운데 관리자 조회 대상 활동(기간 제한 없음). */
    public List<ActivityRow> findAll(Collection<UUID> sessionIds) {
        if (sessionIds.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = ClockParams.uuidArray(new MapSqlParameterSource(), "sessions", sessionIds);
        return jdbc.query(CLASSIFIED.formatted(TERMINAL, "and a.session_id = any(:sessions)") + "select * from classified",
                params, AdminActivityRepository::row);
    }

    /** 활동 기록의 전사 JSON 배열(기록이 없으면 빈 값). 로그 · 예외 메시지에 싣지 않는다. */
    public Optional<String> transcript(UUID sessionId) {
        return jdbc.query("select transcript_json::text as transcript from core.core_activity_record where session_id = :session",
                ClockParams.uuid(new MapSqlParameterSource(), "session", sessionId), (rs, n) -> rs.getString("transcript"))
                .stream().findFirst();
    }

    static MapSqlParameterSource params(AdminFilter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.instant(params, "from", filter.from());
        ClockParams.instant(params, "to", filter.to());
        ClockParams.text(params, "type", filter.type());
        ClockParams.uuid(params, "guardian", filter.guardianId());
        ClockParams.uuid(params, "child", filter.childId());
        ClockParams.uuid(params, "device", filter.deviceId());
        ClockParams.text(params, "status", filter.status());
        ClockParams.text(params, "like", filter.likePattern());
        ClockParams.text(params, "prefix", filter.idPrefix());
        return params;
    }

    private static ActivityRow row(ResultSet rs, int n) throws SQLException {
        return new ActivityRow(ClockParams.uuid(rs, "session_id"), ClockParams.uuid(rs, "guardian_id"),
                ClockParams.uuid(rs, "child_id"), ClockParams.uuid(rs, "device_id"), rs.getString("activity_type"),
                rs.getString("raw_status"), rs.getString("end_reason"), rs.getString("failure_code"), rs.getString("end_detail"),
                ClockParams.instant(rs, "activity_at"), ClockParams.instant(rs, "started_at"), ClockParams.instant(rs, "ended_at"),
                rs.getString("child_name"), rs.getBoolean("has_record"), ClockParams.nullableInt(rs, "child_turns"),
                ClockParams.nullableInt(rs, "ai_turns"), rs.getString("end_category"), rs.getString("ui_status"));
    }
}
