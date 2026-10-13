package kr.mujoong.core.consent;

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
 * 동의 계정 삭제 참가자 (U2 ConsentPolicy · FR1.6 · AC1.6.6 · 공통 완료 조건 J19).
 *
 * <p>보호자가 준 동의 행(아이 등록 전 행 포함)과 그 보호자 아이의 동의 행을 한 트랜잭션에서 모두 지운다. core_consent가 core_child를
 * 가리키므로 아이 참가자보다 먼저다({@link DeletionParticipant#ORDER_CONSENT}). 멱등이다.
 */
@Component
@Order(DeletionParticipant.ORDER_CONSENT)
public class ConsentDeletionParticipant implements DeletionParticipant {

    /** 반환 지도의 키. */
    public static final String CONSENT = "core_consent";

    private final NamedParameterJdbcTemplate jdbc;
    private final DbRetry dbRetry;
    private final TransactionTemplate transactions;

    public ConsentDeletionParticipant(
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
        Integer deleted = dbRetry.execute(DbRetry.Idempotency.IDEMPOTENT, () -> transactions.execute(status ->
                jdbc.update("""
                        delete from core.core_consent
                         where actor_guardian_id = :guardianId
                            or child_id in (select id from core.core_child where guardian_id = :guardianId)
                        """, params)));
        return Map.of(CONSENT, deleted == null ? 0 : deleted);
    }
}
