package kr.mujoong.core.child;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.core.common.db.ClockParams;
import kr.mujoong.core.common.db.DbRetry;
import kr.mujoong.core.common.deletion.DeletionParticipant;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 아이 정보 계정 삭제 참가자 (U2 ChildProfile · FR1.6 · AC1.6.6 · 공통 완료 조건 J19).
 *
 * <p>한 트랜잭션에서 그 보호자 아이의 종료 표현 → 아이 행을 지운다. 아이를 가리키는 세션 사본 · 동의는 앞 참가자가 지웠다
 * ({@link DeletionParticipant#ORDER_CHILD_PROFILE}, 남아 있으면 외래 키 위반으로 실패하고 지휘가 다시 부른다). 멱등이다.
 */
@Component
@Order(DeletionParticipant.ORDER_CHILD_PROFILE)
public class ChildProfileDeletionParticipant implements DeletionParticipant {

    /** 반환 지도의 키. */
    public static final String STOP_EXPRESSION = "core_stop_expression";
    public static final String CHILD = "core_child";

    private final NamedParameterJdbcTemplate jdbc;
    private final DbRetry dbRetry;
    private final TransactionTemplate transactions;

    public ChildProfileDeletionParticipant(
            NamedParameterJdbcTemplate jdbc, DbRetry dbRetry, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.dbRetry = Objects.requireNonNull(dbRetry, "dbRetry");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    @Override
    public Map<String, Integer> deleteByGuardian(UUID guardianId, UUID deletionRequestId) {
        Objects.requireNonNull(guardianId, "guardianId");
        Objects.requireNonNull(deletionRequestId, "deletionRequestId");
        MapSqlParameterSource params = ClockParams.uuid(new MapSqlParameterSource(), "guardianId", guardianId);
        return dbRetry.execute(DbRetry.Idempotency.IDEMPOTENT, () -> transactions.execute(status -> {
            int expressions = jdbc.update("""
                    delete from core.core_stop_expression
                     where child_id in (select id from core.core_child where guardian_id = :guardianId)
                    """, params);
            int children = jdbc.update("delete from core.core_child where guardian_id = :guardianId", params);
            Map<String, Integer> deleted = new LinkedHashMap<>();
            deleted.put(STOP_EXPRESSION, expressions);
            deleted.put(CHILD, children);
            return Map.copyOf(deleted);
        }));
    }
}
