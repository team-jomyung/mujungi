package kr.mujoong.core.identity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.core.common.db.ClockParams;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 구글 계정 ↔ 보호자 {@code core.core_social_account} 저장소 (U2 GuardianIdentity · AC1.1.1 · AC1.6.5 · AC1.6.7 · U2-J3).
 *
 * <p>식별은 (provider, 구글 sub) 유일이다. 이메일은 저장하지 않는다. sub는 로그 · 예외에 쓰지 않는다. 트랜잭션은 호출자가 연다.
 */
@Repository
public class SocialAccountRepository {

    /** 지원하는 제공자(V2 CHECK). */
    public static final String PROVIDER_GOOGLE = "GOOGLE";

    private final NamedParameterJdbcTemplate jdbc;

    public SocialAccountRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** 구글 sub의 보호자(없으면 빈 값). */
    public Optional<UUID> findGuardianByGoogleSubject(String subject) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.text(params, "provider", PROVIDER_GOOGLE);
        ClockParams.text(params, "subject", Objects.requireNonNull(subject, "subject"));
        List<UUID> rows = jdbc.queryForList("""
                select guardian_id from core.core_social_account where provider = :provider and provider_user_id = :subject
                """, params, UUID.class);
        return rows.stream().findFirst();
    }

    /**
     * 연결 행을 넣는다. 같은 sub가 이미 있으면(동시 첫 로그인) 아무것도 하지 않는다.
     *
     * @return 넣었으면 true
     */
    public boolean insertGoogle(UUID guardianId, String subject, Instant now) {
        MapSqlParameterSource params = ClockParams.at(now);
        ClockParams.uuid(params, "id", UUID.randomUUID());
        ClockParams.uuid(params, "guardianId", Objects.requireNonNull(guardianId, "guardianId"));
        ClockParams.text(params, "provider", PROVIDER_GOOGLE);
        ClockParams.text(params, "subject", Objects.requireNonNull(subject, "subject"));
        return jdbc.update("""
                insert into core.core_social_account (id, guardian_id, provider, provider_user_id, created_at, updated_at)
                values (:id, :guardianId, :provider, :subject, :now, :now)
                on conflict (provider, provider_user_id) do nothing
                """, params) == 1;
    }

    /** 보호자의 연결 행을 모두 지운다(계정 삭제 마지막 단계, AC1.6.5). */
    public int deleteByGuardian(UUID guardianId) {
        return jdbc.update("delete from core.core_social_account where guardian_id = :guardianId",
                ClockParams.uuid(new MapSqlParameterSource(), "guardianId", Objects.requireNonNull(guardianId, "guardianId")));
    }
}
