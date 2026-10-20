package kr.mujoong.core.practice;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.identity.GuardianWriteLock;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 스프링 없이 서비스를 시험할 때 쓰는 계정 포트(JDBC). 보호자 행 잠금은 실제 U2 {@link GuardianWriteLock}에 맡긴다. 운영 구현
 * {@link PracticeAccountAdapter}는 앱 문맥 시험(PracticeAccountIT)이 본다.
 */
public final class JdbcTestPracticeAccounts implements PracticeAccountPort {

    private final NamedParameterJdbcTemplate jdbc;
    private final GuardianWriteLock guardianLock;

    public JdbcTestPracticeAccounts(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.guardianLock = new GuardianWriteLock(jdbc);
    }

    @Override
    public void requireGuardian(UUID guardianId) {
        if (guardianId == null) {
            throw CoreApiException.of(ErrorCode.UNAUTHENTICATED);
        }
        List<String> states = jdbc.queryForList("select status from core.core_guardian where id = :id",
                Map.of("id", guardianId), String.class);
        if (states.isEmpty()) {
            throw CoreApiException.of(ErrorCode.UNAUTHENTICATED);
        }
        if (!"ACTIVE".equals(states.getFirst())) {
            throw CoreApiException.of(ErrorCode.ACCOUNT_DELETION_IN_PROGRESS);
        }
    }

    @Override
    public AccountContext requireActive(UUID guardianId, boolean lock) {
        if (lock && guardianId != null) {
            requireTransaction();
            guardianLock.lockActive(guardianId);
        } else {
            requireGuardian(guardianId);
        }
        return jdbc.query("""
                select id, age, interests_json::text interests from core.core_child
                 where guardian_id = :id and status = 'ACTIVE' and deleted_at is null
                """, Map.of("id", guardianId), (rs, n) -> {
                    String interests = rs.getString("interests");
                    return new AccountContext(guardianId, rs.getObject("id", UUID.class), (Integer) rs.getObject("age"),
                            interests == null ? List.of() : Arrays.asList(JsonCodec.shared().read(interests, String[].class)));
                }).stream().findFirst().orElseThrow(() -> CoreApiException.of(ErrorCode.NOT_FOUND));
    }

    @Override
    public AccountContext requireChild(UUID childId, boolean lock) {
        if (childId == null) {
            throw CoreApiException.of(ErrorCode.VALIDATION_FAILED);
        }
        UUID owner = jdbc.queryForList("select guardian_id from core.core_child where id = :id", Map.of("id", childId),
                UUID.class).stream().findFirst().orElseThrow(() -> CoreApiException.of(ErrorCode.NOT_FOUND));
        AccountContext context = requireActive(owner, lock);
        if (!context.childId().equals(childId)) {
            throw CoreApiException.of(ErrorCode.NOT_FOUND);
        }
        return context;
    }

    @Override
    public void lockForDeletion(UUID guardianId) {
        requireTransaction();
        guardianLock.statusForUpdate(guardianId);
    }

    @Override
    public List<UUID> childrenForDeletion(UUID guardianId) {
        return jdbc.queryForList("select id from core.core_child where guardian_id = :id", Map.of("id", guardianId), UUID.class);
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("보호자 행 잠금은 트랜잭션 안에서만 쓴다");
        }
    }
}
