package kr.mujoong.core.admin;

import java.util.Objects;
import java.util.UUID;
import kr.mujoong.core.common.db.ClockParams;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 관리자 명단 {@code core.core_admin_account} 읽기 (JIRA-280).
 *
 * <p>등록 · 회수는 운영 SQL로만 한다(infra/RUNBOOK.md). 앱은 읽기만 하므로 쓰기 메서드가 없다.
 */
@Repository
public class AdminAccountRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AdminAccountRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** 보호자가 지금 활성 관리자인가. 회수(REVOKED)되면 다음 요청부터 바로 거절된다. */
    public boolean isActive(UUID guardianId) {
        Boolean active = jdbc.queryForObject("""
                select exists (select 1 from core.core_admin_account where guardian_id = :guardian and status = 'ACTIVE')
                """, ClockParams.uuid(new MapSqlParameterSource(), "guardian", guardianId), Boolean.class);
        return Boolean.TRUE.equals(active);
    }
}
