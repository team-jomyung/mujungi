package kr.mujoong.core.practice;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.common.error.CoreApiException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
/** Temporary, read-only V1 bridge until the U2 public service is available. No identity repository dependency. */
public class JdbcPracticeAccountAdapter implements PracticeAccountPort {
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonCodec json;
    public JdbcPracticeAccountAdapter(NamedParameterJdbcTemplate jdbc, JsonCodec json) { this.jdbc=jdbc; this.json=json; }
    @Override public AccountContext requireChild(UUID childId,boolean lock) {
        if(childId==null)throw CoreApiException.of(ErrorCode.VALIDATION_FAILED);
        UUID owner=jdbc.query("select guardian_id from core.core_child where id=:id",Map.of("id",childId),
            (rs,n)->rs.getObject("guardian_id",UUID.class)).stream().findFirst()
            .orElseThrow(()->CoreApiException.of(ErrorCode.NOT_FOUND));
        AccountContext context=requireActive(owner,lock);
        if(!context.childId().equals(childId))throw CoreApiException.of(ErrorCode.NOT_FOUND);
        return context;
    }
    @Override public void lockForDeletion(UUID guardianId) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Practice account lock requires a transaction");
        jdbc.queryForList("select id from core.core_guardian where id=:id for update",Map.of("id",guardianId),UUID.class);
    }
    @Override public List<UUID> childrenForDeletion(UUID guardianId) {
        return jdbc.queryForList("select id from core.core_child where guardian_id=:id",Map.of("id",guardianId),UUID.class);
    }
    @Override public AccountContext requireActive(UUID guardianId, boolean lock) {
        if (guardianId == null) throw CoreApiException.of(ErrorCode.UNAUTHENTICATED);
        if (lock && !TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Practice account lock requires a transaction");
        List<String> states=jdbc.queryForList("select status from core.core_guardian where id=:id"
                + (lock ? " for update" : ""), Map.of("id",guardianId), String.class);
        if (states.isEmpty()) throw CoreApiException.of(ErrorCode.UNAUTHENTICATED);
        if (!"ACTIVE".equals(states.getFirst())) throw CoreApiException.of(ErrorCode.ACCOUNT_DELETION_IN_PROGRESS);
        return jdbc.query("""
                select id, age, interests_json::text interests from core.core_child
                where guardian_id=:id and status='ACTIVE' and deleted_at is null
                """, Map.of("id",guardianId), (rs,n) -> {
                    String interests=rs.getString("interests");
                    return new AccountContext(guardianId,rs.getObject("id",UUID.class),(Integer)rs.getObject("age"),
                            interests == null ? List.of() : Arrays.asList(json.read(interests,String[].class)));
                }).stream().findFirst().orElseThrow(() -> CoreApiException.of(ErrorCode.NOT_FOUND));
    }
}
