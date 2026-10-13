package kr.mujoong.core.identity;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.core.common.db.ClockParams;
import kr.mujoong.core.common.error.CoreApiException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 보호자 행 잠금 · 상태 확인의 한 곳 (U2 · 리뷰 9 · 2차 리뷰 5 · R6 · R8 · R9 · R10 · BR2.2).
 *
 * <p>보호자 행을 잠그는 SQL은 이 클래스에만 있다(락 순서 원칙, {@link GuardianRepository} 주석). 모든 트랜잭션은 보호자 행을 첫 잠금으로 잡는다.
 *
 * <ul>
 *   <li>{@link #statusForShare}: FOR SHARE — U1 기기 등록(보호자 → 코드 → 옛 기기 순서, {@code DeviceJdbcRepository}가 부른다).
 *   <li>{@link #statusForUpdate}: FOR UPDATE — 계정 삭제 접수(상태를 바꾼다).
 *   <li>{@link #lockActive}: FOR UPDATE + ACTIVE 확인 — 동의 · 아이 · 종료 표현 쓰기. 삭제 접수가 먼저 커밋됐으면 403
 *       ACCOUNT_DELETION_IN_PROGRESS, 쓰기가 먼저면 접수가 그 뒤에 와 지휘가 지운다.
 * </ul>
 *
 * <p>모두 트랜잭션 안에서만 부른다. 행이 없으면 빈 값(세션만 남은 계정)이다. 호출 모양은 인스턴스 메서드 하나로 통일했다(3차 리뷰 7 — U1
 * {@code DeviceJdbcRepository}는 생성자 모양을 바꾸지 않고 같은 jdbc로 이 클래스를 만들어 쓴다).
 */
@Component
public class GuardianWriteLock {

    private static final String FOR_SHARE = "select status from core.core_guardian where id = :id for share";
    private static final String FOR_UPDATE = "select status from core.core_guardian where id = :id for update";

    private final NamedParameterJdbcTemplate jdbc;

    public GuardianWriteLock(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** 보호자 행을 FOR SHARE로 읽어 상태를 돌려준다(U1 등록 트랜잭션의 첫 잠금). */
    public Optional<String> statusForShare(UUID guardianId) {
        return status(FOR_SHARE, guardianId);
    }

    /** 보호자 행을 FOR UPDATE로 읽어 상태를 돌려준다. */
    public Optional<String> statusForUpdate(UUID guardianId) {
        return status(FOR_UPDATE, guardianId);
    }

    /**
     * 보호자 행을 잠그고 ACTIVE인지 확인한다.
     *
     * @throws CoreApiException 행이 없으면 UNAUTHENTICATED(세션만 남은 계정), ACTIVE가 아니면 ACCOUNT_DELETION_IN_PROGRESS
     */
    public void lockActive(UUID guardianId) {
        String status = statusForUpdate(guardianId).orElseThrow(() -> CoreApiException.of(ErrorCode.UNAUTHENTICATED));
        if (!Guardian.STATUS_ACTIVE.equals(status)) {
            throw CoreApiException.of(ErrorCode.ACCOUNT_DELETION_IN_PROGRESS);
        }
    }

    private Optional<String> status(String sql, UUID guardianId) {
        List<String> rows = jdbc.queryForList(sql,
                ClockParams.uuid(new MapSqlParameterSource(), "id", Objects.requireNonNull(guardianId, "guardianId")),
                String.class);
        return rows.stream().findFirst();
    }
}
