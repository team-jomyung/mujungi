package kr.mujoong.core.practice.start;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.common.db.ClockParams;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PracticePreparationRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonCodec json;
    public PracticePreparationRepository(NamedParameterJdbcTemplate jdbc, JsonCodec json) { this.jdbc=jdbc; this.json=json; }
    public record Preparation(UUID sessionId, UUID guardianId, UUID childId, UUID key, String requestHash,
            List<FrozenGoal> goals, String status, UUID claimId, Instant leaseUntil, UUID firstScenarioId) {
        public Preparation { goals=List.copyOf(goals); }
        @Override public String toString() { return "Preparation[sessionId="+sessionId+"]"; }
    }
    private Optional<Preparation> find(String where, MapSqlParameterSource params) {
        return jdbc.query("select * from core.core_practice_preparation where "+where,params,(r,n)->new Preparation(
                ClockParams.uuid(r,"session_id"),ClockParams.uuid(r,"guardian_id"),ClockParams.uuid(r,"child_id"),
                ClockParams.uuid(r,"idempotency_key"),r.getString("request_hash"),
                Arrays.asList(json.read(r.getString("goals_json"),FrozenGoal[].class)),r.getString("status"),
                ClockParams.uuid(r,"claim_id"),ClockParams.instant(r,"lease_until"),ClockParams.uuid(r,"first_scenario_id")))
                .stream().findFirst();
    }
    public Optional<Preparation> find(UUID guardian, UUID key) {
        return find("guardian_id=:guardian and idempotency_key=:key",new MapSqlParameterSource("guardian",guardian).addValue("key",key));
    }
    public Optional<Preparation> findSession(UUID session) { return find("session_id=:session",new MapSqlParameterSource("session",session)); }
    public Preparation insert(UUID guardian, UUID child, PracticeStartRequest request, List<FrozenGoal> goals,
            Instant now, Instant deadline) {
        UUID session=UUID.randomUUID(),claim=UUID.randomUUID();
        jdbc.update("""
                insert into core.core_practice_preparation(session_id,guardian_id,child_id,idempotency_key,
                request_hash,goals_json,status,claim_id,lease_until,created_at,updated_at)
                values(:session,:guardian,:child,:key,:hash,cast(:goals as jsonb),'PREPARING',:claim,:deadline,:now,:now)
                """,ClockParams.at(now).addValue("session",session).addValue("guardian",guardian).addValue("child",child)
                .addValue("key",request.idempotencyKey()).addValue("hash",request.requestHash()).addValue("goals",json.write(goals))
                .addValue("claim",claim).addValue("deadline",ClockParams.timestamptz(deadline)));
        return findSession(session).orElseThrow();
    }
    public Preparation reclaim(Preparation old, Instant now, Instant deadline) {
        jdbc.update("""
                update core.core_practice_preparation set claim_id=:claim,lease_until=:deadline,updated_at=:now
                where session_id=:session and status='PREPARING'
                """,ClockParams.at(now).addValue("session",old.sessionId()).addValue("claim",UUID.randomUUID())
                .addValue("deadline",ClockParams.timestamptz(deadline)));
        return findSession(old.sessionId()).orElseThrow();
    }
    public int ready(Preparation p, UUID scenario, Instant now) {
        return jdbc.update("""
                update core.core_practice_preparation set status='READY',first_scenario_id=:scenario,updated_at=:now
                where session_id=:session and claim_id=:claim and status='PREPARING' and lease_until>:now
                """,ClockParams.at(now).addValue("session",p.sessionId()).addValue("claim",p.claimId()).addValue("scenario",scenario));
    }
    public void release(Preparation p, boolean failed, Instant now) {
        jdbc.update("""
                update core.core_practice_preparation set status=:status,lease_until=:now,updated_at=:now
                where session_id=:session and claim_id=:claim and status='PREPARING'
                """,ClockParams.at(now).addValue("session",p.sessionId()).addValue("claim",p.claimId())
                .addValue("status",failed?"FAILED":"PREPARING"));
    }
    public List<Preparation> pendingExposures() {
        return jdbc.queryForList("""
                select session_id from core.core_practice_preparation p
                where status='READY' and exposure_reconciled_at is null
                order by created_at
                """,new MapSqlParameterSource(),UUID.class).stream().map(id->findSession(id).orElseThrow()).toList();
    }
    public void reconciled(UUID session, Instant now) {
        jdbc.update("update core.core_practice_preparation set exposure_reconciled_at=:now where session_id=:session",
                ClockParams.at(now).addValue("session",session));
    }
}
