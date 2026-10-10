package kr.mujoong.core.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.core.common.db.ClockParams;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.device.DeviceSecrets;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.db.MujoongPostgres.Role;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * 업무 통합 시험의 행 만들기(시험 소스 전용, Step 28). 만든 보호자는 {@link DbTestSupport}가 정리하도록 등록한다. 시각은 시험 Clock 값을
 * 받는다(DB 시각 함수 없음).
 */
public final class BusinessFixtures {

    private final DbTestSupport db;
    private final NamedParameterJdbcTemplate jdbc;

    public BusinessFixtures(DbTestSupport db) {
        this.db = db;
        this.jdbc = db.jdbc();
    }

    /** 참조 시드(정책 1 · 승인 문구 2)를 mujoong_owner로 멱등 적용한다(infra/seed/001-reference.sql). */
    public static void applyReferenceSeed(MujoongPostgres postgres) {
        try (Connection owner = postgres.connect(Role.OWNER); Statement statement = owner.createStatement()) {
            statement.execute(Files.readString(postgres.repoRoot().resolve("infra/seed/001-reference.sql"),
                    StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (SQLException e) {
            throw new IllegalStateException("참조 시드 적용 실패", e);
        }
    }

    /** 보호자 · 아이('하늘' · 8세) · 현재 정책 4항목 동의(GIVEN)를 만든다. */
    public Family family(Instant effectiveFrom) {
        UUID guardianId = db.insertGuardian("ACTIVE");
        UUID childId = UUID.randomUUID();
        MapSqlParameterSource child = ClockParams.at(effectiveFrom);
        ClockParams.uuid(child, "id", childId);
        ClockParams.uuid(child, "guardianId", guardianId);
        jdbc.update("""
                insert into core.core_child (id, guardian_id, display_name, age, interests_json, status, created_at, updated_at)
                values (:id, :guardianId, '하늘', 8, '["공룡","그림"]'::jsonb, 'ACTIVE', :now, :now)
                """, child);
        for (String type : List.of("VOICE_AND_TEXT", "GUARDIAN_VIEW", "REPORT_SHARING", "RAW_AUDIO")) {
            consent(guardianId, childId, type, "GIVEN", effectiveFrom);
        }
        return new Family(guardianId, childId);
    }

    /** 동의 한 행. */
    public void consent(UUID guardianId, UUID childId, String type, String status, Instant effectiveFrom) {
        MapSqlParameterSource params = ClockParams.at(effectiveFrom);
        ClockParams.uuid(params, "id", UUID.randomUUID());
        ClockParams.uuid(params, "guardianId", guardianId);
        ClockParams.uuid(params, "childId", childId);
        ClockParams.text(params, "type", type);
        ClockParams.text(params, "status", status);
        jdbc.update("""
                insert into core.core_consent (id, actor_guardian_id, child_id, consent_type, consent_status, consent_version,
                                               policy_version, effective_from, created_at)
                values (:id, :guardianId, :childId, :type, :status,
                        (select coalesce(max(consent_version), 0) + 1 from core.core_consent
                          where child_id = :childId and consent_type = :type),
                        '1.0', :now, :now)
                """, params);
    }

    /** 사본이 있는 REGISTERED 기기(state null이면 사본 없음). */
    public UUID registeredDevice(UUID guardianId, ConnectionState state, Instant reportedAt, Instant now) {
        UUID deviceId = UUID.randomUUID();
        MapSqlParameterSource params = ClockParams.at(now);
        ClockParams.uuid(params, "id", deviceId);
        ClockParams.uuid(params, "guardianId", guardianId);
        ClockParams.text(params, "code", DbTestSupport.randomCode());
        ClockParams.text(params, "bootstrapHash", DeviceSecrets.hash(UUID.randomUUID().toString()));
        ClockParams.text(params, "tokenHash", DeviceSecrets.hash(UUID.randomUUID().toString()));
        ClockParams.text(params, "state", state == null ? null : state.name());
        ClockParams.instant(params, "reportedAt", reportedAt);
        jdbc.update("""
                insert into core.core_device (id, guardian_id, registration_code, code_issued_at, code_expires_at,
                    bootstrap_secret_hash, device_token_hash, token_issued_at, first_connected_at, registration_status,
                    registered_at, reported_connection_state, reported_at, last_seen_at, connection_since, rtt_ms,
                    created_at, updated_at)
                values (:id, :guardianId, :code, :now, :now, :bootstrapHash, :tokenHash, :now, :now, 'REGISTERED', :now,
                    :state, :reportedAt, :reportedAt, :reportedAt, case when :state is null then null else 100 end,
                    :now, :now)
                """, params);
        db.trackDevice(deviceId);
        return deviceId;
    }

    /** 보호자 · 아이. */
    public record Family(UUID guardianId, UUID childId) {
    }
}
