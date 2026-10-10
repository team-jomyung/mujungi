package kr.mujoong.core.health;

import java.util.Objects;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.core.observability.EventLogger;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 실행 중 DB 점검자 (U1 3.5 Step 21 · RD-16 · OD-11 · NFR4.13).
 *
 * <p>maintenance 스케줄러가 {@code mujoong.health.db-check-interval}(5초)마다 {@link #runOnce}를 부른다. {@code SELECT 1} 한 번의 성공 ·
 * 실패를 {@link ReadinessState}에 넘긴다(연속 3회 실패면 NOT_READY, 성공 1회면 READY). 실패는 APP_ERROR 한 줄(예외 종류만, 병합
 * 대상)로 남기고 요청 경로는 DB를 부르지 않는다.
 */
@Component
public class DbHealthChecker {

    private final JdbcTemplate jdbc;
    private final ReadinessState readiness;
    private final EventLogger events;

    public DbHealthChecker(JdbcTemplate jdbc, ReadinessState readiness, EventLogger events) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.events = Objects.requireNonNull(events, "events");
    }

    /** 한 번 점검한다. */
    public ReadinessState.State runOnce() {
        boolean success;
        try {
            Integer one = jdbc.queryForObject("select 1", Integer.class);
            success = one != null && one == 1;
        } catch (DataAccessException e) {
            events.appError(EventLog.Component.HEALTH, e);
            success = false;
        }
        return readiness.recordDbCheck(success);
    }
}
