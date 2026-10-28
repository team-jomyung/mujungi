package kr.mujoong.core.admin;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.core.common.db.ClockParams;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 관리자 보호자 · 기기 · 아이 목록 (JIRA-280). Core 스키마만 읽는다.
 *
 * <p>보호자 이름 · 이메일은 저장하지 않으므로(U2-J3) 화면 이름은 서비스가 id로 만든다. 기기는 지금 등록된(REGISTERED) 기기만 본다. 아이는
 * 기기가 아니라 보호자에 붙어 있다(보호자당 아이 하나).
 */
@Repository
public class AdminGuardianRepository {

    private static final String GUARDIAN_FILTER = """
            from core.core_guardian g
            where g.status = 'ACTIVE'
              and (cast(:guardian as uuid) is null or g.id = :guardian)
              and (cast(:child as uuid) is null or exists (select 1 from core.core_child c where c.guardian_id = g.id
                   and c.id = :child))
              and (cast(:device as uuid) is null or exists (select 1 from core.core_device d where d.guardian_id = g.id
                   and d.id = :device))
              and (cast(:like as varchar) is null or cast(g.id as text) like :prefix
                   or exists (select 1 from core.core_child c where c.guardian_id = g.id and c.display_name ilike :like)
                   or exists (select 1 from core.core_device d where d.guardian_id = g.id and cast(d.id as text) like :prefix))
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public AdminGuardianRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** 아이 한 명. */
    public record ChildRow(UUID id, UUID guardianId, String displayName) {

        @Override
        public String toString() {
            return "ChildRow[id=" + id + "]";
        }
    }

    /** 등록된 기기 한 대. */
    public record DeviceRow(UUID id, UUID guardianId) {
    }

    public int count(AdminFilter filter) {
        Integer total = jdbc.queryForObject("select count(*) " + GUARDIAN_FILTER, AdminActivityRepository.params(filter),
                Integer.class);
        return total == null ? 0 : total;
    }

    /** 보호자 id 한 쪽(가입 최근 순, 같으면 id 순). */
    public List<UUID> page(AdminFilter filter, int offset, int limit) {
        MapSqlParameterSource params = AdminActivityRepository.params(filter).addValue("offset", offset)
                .addValue("limit", limit);
        return jdbc.query("select g.id " + GUARDIAN_FILTER + " order by g.created_at desc, g.id desc limit :limit offset :offset",
                params, (rs, n) -> ClockParams.uuid(rs, "id"));
    }

    public List<ChildRow> children(Collection<UUID> guardianIds) {
        if (guardianIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("select id, guardian_id, display_name from core.core_child where guardian_id = any(:guardians)"
                        + " order by created_at, id",
                ClockParams.uuidArray(new MapSqlParameterSource(), "guardians", guardianIds),
                (rs, n) -> new ChildRow(ClockParams.uuid(rs, "id"), ClockParams.uuid(rs, "guardian_id"),
                        rs.getString("display_name")));
    }

    public List<DeviceRow> registeredDevices(Collection<UUID> guardianIds) {
        if (guardianIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("select id, guardian_id from core.core_device where guardian_id = any(:guardians)"
                        + " and registration_status = 'REGISTERED' order by registered_at, id",
                ClockParams.uuidArray(new MapSqlParameterSource(), "guardians", guardianIds),
                (rs, n) -> new DeviceRow(ClockParams.uuid(rs, "id"), ClockParams.uuid(rs, "guardian_id")));
    }

    /** 기기 · 아이 하나의 활동 수. 기기는 그 활동을 한 기기다(해제된 과거 기기 포함). */
    public record DeviceChildCount(UUID deviceId, UUID childId, int count) {
    }

    /**
     * 아이들의 기간 안 끝난 활동 수를 기기별로 센다. 활동 목록과 같은 분류(화면 상태)를 쓰고 유형 · 상태 · 기기 필터를 적용한다. 보호자 · 아이 ·
     * 검색어 필터는 어느 보호자를 싣는지만 정하고 수에는 쓰지 않는다.
     */
    public List<DeviceChildCount> activityCounts(Collection<UUID> childIds, AdminFilter filter) {
        if (childIds.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = ClockParams.uuidArray(new MapSqlParameterSource(), "children", childIds);
        ClockParams.instant(params, "from", filter.from());
        ClockParams.instant(params, "to", filter.to());
        ClockParams.text(params, "type", filter.type());
        ClockParams.uuid(params, "device", filter.deviceId());
        ClockParams.text(params, "status", filter.status());
        return jdbc.query(AdminActivityRepository.CLASSIFIED.formatted(AdminActivityRepository.TERMINAL,
                        AdminActivityRepository.IN_RANGE + " and a.child_id = any(:children)") + """
                        select device_id, child_id, count(*) as total from classified
                        where (cast(:type as varchar) is null or activity_type = :type)
                          and (cast(:device as uuid) is null or device_id = :device)
                          and (cast(:status as varchar) is null or ui_status = :status)
                        group by device_id, child_id
                        """, params,
                (rs, n) -> new DeviceChildCount(ClockParams.uuid(rs, "device_id"), ClockParams.uuid(rs, "child_id"),
                        rs.getInt("total")));
    }
}
