package kr.mujoong.core.identity;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.core.common.db.ClockParams;
import kr.mujoong.core.common.error.CoreApiException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 보호자 데이터 쓰기 잠금 (U2 · 리뷰 9 · R6 · R8 · R9 · R10).
 *
 * <p>동의 · 아이 · 종료 표현을 쓰는 트랜잭션은 먼저 보호자 행을 FOR UPDATE로 잠그고 잠근 행의 상태를 다시 읽는다. 계정 삭제 접수
 * ({@code AccountDeletionService})도 같은 행을 FOR UPDATE로 잠그므로 둘은 줄을 선다: 접수가 먼저 커밋됐으면 여기서 403
 * ACCOUNT_DELETION_IN_PROGRESS, 쓰기가 먼저면 접수가 그 뒤에 와 지휘가 지운다. 요청 단계 확인(GuardianPrincipal)과 잠금 사이에 접수가
 * 끼어들어도 새 행을 쓰지 않는다. 트랜잭션 안에서만 부른다.
 */
@Component
public class GuardianWriteLock {

    private final NamedParameterJdbcTemplate jdbc;

    public GuardianWriteLock(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * 보호자 행을 잠그고 ACTIVE인지 확인한다.
     *
     * @throws CoreApiException 행이 없으면 UNAUTHENTICATED(세션만 남은 계정), ACTIVE가 아니면 ACCOUNT_DELETION_IN_PROGRESS
     */
    public void lockActive(UUID guardianId) {
        List<String> status = jdbc.queryForList("select status from core.core_guardian where id = :id for update",
                ClockParams.uuid(new MapSqlParameterSource(), "id", Objects.requireNonNull(guardianId, "guardianId")),
                String.class);
        if (status.isEmpty()) {
            throw CoreApiException.of(ErrorCode.UNAUTHENTICATED);
        }
        if (!Guardian.STATUS_ACTIVE.equals(status.get(0))) {
            throw CoreApiException.of(ErrorCode.ACCOUNT_DELETION_IN_PROGRESS);
        }
    }
}
