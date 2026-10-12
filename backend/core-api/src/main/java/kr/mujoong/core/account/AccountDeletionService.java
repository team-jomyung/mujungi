package kr.mujoong.core.account;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.account.DeletionRequestRepository.DeletionRequest;
import kr.mujoong.core.common.db.ClockParams;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.identity.Child;
import kr.mujoong.core.identity.ChildRepository;
import kr.mujoong.core.identity.GuardianSessions;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 계정 삭제 접수 (U2 AccountDeletion · FR1.6 · AC1.6.1 · AC1.6.7 · R10 · 편차 (U2-h)).
 *
 * <p>한 트랜잭션에서 보호자 행을 잠그고 ACTIVE면 DELETION_REQUESTED로 바꾸며 REQUESTED 요청 행 하나를 만든다. 이미 접수됐으면 열린 요청의
 * id를 그대로 돌려준다(멱등). 커밋 뒤 그 보호자의 모든 로그인 세션을 지운다(다른 기기 포함 — 다음 호출은 401). 실제 삭제는
 * {@link DeletionCommander}가 뒤에서 한다. 보호자 행이 없으면 401이다.
 */
@Component
public class AccountDeletionService {

    private final DeletionRequestRepository requests;
    private final ChildRepository children;
    private final GuardianSessions sessions;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final MujoongClock clock;

    public AccountDeletionService(
            DeletionRequestRepository requests,
            ChildRepository children,
            GuardianSessions sessions,
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            MujoongClock clock) {
        this.requests = Objects.requireNonNull(requests, "requests");
        this.children = Objects.requireNonNull(children, "children");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 접수한다.
     *
     * @return 삭제 요청 id(다시 불러도 같은 id)
     */
    public UUID request(UUID guardianId) {
        Objects.requireNonNull(guardianId, "guardianId");
        UUID requestId = transactions.execute(status -> {
            MapSqlParameterSource params = ClockParams.uuid(new MapSqlParameterSource(), "id", guardianId);
            List<String> guardian = jdbc.queryForList(
                    "select status from core.core_guardian where id = :id for update", params, String.class);
            if (guardian.isEmpty()) {
                throw CoreApiException.of(ErrorCode.UNAUTHENTICATED);
            }
            Optional<DeletionRequest> open = requests.findOpenByGuardian(guardianId);
            if (open.isPresent()) {
                return open.get().id();
            }
            Instant now = clock.now();
            ClockParams.instant(params, "now", now);
            jdbc.update("""
                    update core.core_guardian set status = 'DELETION_REQUESTED', updated_at = :now
                     where id = :id and status = 'ACTIVE'
                    """, params);
            UUID id = UUID.randomUUID();
            UUID childId = children.findByGuardianId(guardianId).map(Child::getId).orElse(null);
            requests.insertRequested(id, guardianId, childId, now);
            return id;
        });
        sessions.closeAll(guardianId);
        return Objects.requireNonNull(requestId, "requestId");
    }
}
