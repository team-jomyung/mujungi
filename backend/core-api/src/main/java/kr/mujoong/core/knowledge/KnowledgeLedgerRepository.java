package kr.mujoong.core.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.common.db.ClockParams;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** BR4·BR5: 호출자는 보호자 우선 잠금을 보유한 트랜잭션으로 쓰기를 호출한다. */
@Repository
public class KnowledgeLedgerRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonCodec json = JsonCodec.shared();

    public KnowledgeLedgerRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record ChildState(UUID childId, UUID guardianId, String baseVersion, boolean graphMayExist, String state, String initializationTransactionId) {}
    public record Operation(UUID childId, UUID activityId, String candidateRef, String seedId, String state,
            String reusedSeedId, String transactionId, KnowledgeSeed seed, String model, String promptVersion,
            Instant deadline, List<String> checkCodes) {}
    public record Reservation(Operation operation, boolean created) {}

    public ChildState ensure(UUID guardian, UUID child) {
        if (deletedChild(child)) throw new IllegalStateException("Knowledge child deleted");
        jdbc.update("insert into core.core_child_graph_state(child_id,guardian_id) values (:child,:guardian) on conflict do nothing",
                Map.of("child", child, "guardian", guardian));
        ChildState state = child(child).orElseThrow();
        if (!state.guardianId().equals(guardian)) throw new IllegalArgumentException("Child ownership mismatch");
        return state;
    }

    public Optional<ChildState> child(UUID child) {
        return jdbc.query("select * from core.core_child_graph_state where child_id=:child",
                Map.of("child", child), (rs, n) -> new ChildState(child, rs.getObject("guardian_id", UUID.class),
                        rs.getString("base_version"), rs.getBoolean("graph_may_exist"), rs.getString("state"), rs.getString("initialization_transaction_id")))
                .stream().findFirst();
    }

    public List<UUID> children(UUID guardian) {
        return jdbc.query("select child_id from core.core_child_graph_state where guardian_id=:guardian order by child_id",
                Map.of("guardian", guardian), (rs, n) -> rs.getObject(1, UUID.class));
    }

    public void markMayExist(UUID child) {
        jdbc.update("update core.core_child_graph_state set graph_may_exist=true where child_id=:child", Map.of("child", child));
    }

    public void initialization(UUID child, String handle) {
        jdbc.update("update core.core_child_graph_state set initialization_transaction_id=:handle,graph_may_exist=true where child_id=:child",
                new MapSqlParameterSource("child", child).addValue("handle", handle));
    }

    public void baseLoaded(UUID child, SeedCatalog catalog, Instant now) {
        jdbc.update("insert into core.core_graph_version(version,source_digest,loaded_at) values (:version,:digest,:now) on conflict do nothing",
                ClockParams.instant(new MapSqlParameterSource("version", catalog.version()).addValue("digest", catalog.digest()), "now", now));
        String digest = jdbc.queryForObject("select source_digest from core.core_graph_version where version=:version",
                Map.of("version", catalog.version()), String.class);
        if (!catalog.digest().equals(digest)) throw new IllegalArgumentException("Catalog version conflict");
        jdbc.update("update core.core_child_graph_state set base_version=:version where child_id=:child",
                Map.of("child", child, "version", catalog.version()));
    }

    public Optional<Operation> operation(UUID child, String candidate) {
        return operations("child_id=:child and candidate_ref=:candidate", Map.of("child", child, "candidate", candidate)).stream().findFirst();
    }

    public List<Operation> pending(UUID child) {
        return operations("child_id=:child and state in ('PROJECTING','CLEANUP_REQUIRED')", Map.of("child", child));
    }

    public void expireReservations(UUID child, Instant now) {
        jdbc.update("update core.core_seed_generation_operation set state='NONE' where child_id=:child and state='RESERVED' and deadline<=:now",
                ClockParams.instant(new MapSqlParameterSource("child", child), "now", now));
    }

    private List<Operation> operations(String predicate, Map<String, ?> params) {
        return jdbc.query("select * from core.core_seed_generation_operation where " + predicate, params,
                (rs, n) -> new Operation(rs.getObject("child_id", UUID.class), rs.getObject("activity_id", UUID.class),
                        rs.getString("candidate_ref"), rs.getString("seed_id"), rs.getString("state"),
                        rs.getString("reused_seed_id"), rs.getString("graph_transaction_id"),
                        rs.getString("validated_seed") == null ? null : json.read(rs.getString("validated_seed"), KnowledgeSeed.class),
                        rs.getString("model"), rs.getString("prompt_version"), ClockParams.instant(rs, "deadline"),
                        java.util.Arrays.asList(json.read(rs.getString("check_codes"), String[].class))));
    }

    public Reservation reserve(UUID child, UUID activity, String candidate, Instant deadline) {
        jdbc.queryForObject("select next_generated_number from core.core_child_graph_state where child_id=:child for update",
                Map.of("child", child), Long.class);
        Optional<Operation> existing = operation(child, candidate);
        if (existing.isPresent()) return new Reservation(existing.get(), false);
        if (!"ACTIVE".equals(child(child).orElseThrow().state()) || deletedSource(child, activity)) return null;
        Integer count = jdbc.queryForObject("select count(*) from core.core_seed_generation_operation where child_id=:child and activity_id=:activity and state in ('RESERVED','PROJECTING','COMMITTED','CLEANUP_REQUIRED')",
                Map.of("child", child, "activity", activity), Integer.class);
        if (count != null && count >= 2) return null;
        Long number = jdbc.queryForObject("update core.core_child_graph_state set next_generated_number=next_generated_number+1 where child_id=:child returning next_generated_number-1",
                Map.of("child", child), Long.class);
        String seedId = "seed:G" + String.format(java.util.Locale.ROOT, "%04d", number);
        jdbc.update("insert into core.core_seed_generation_operation(child_id,activity_id,candidate_ref,seed_id,state,deadline) values (:child,:activity,:candidate,:seed,'RESERVED',:deadline)",
                ClockParams.instant(new MapSqlParameterSource("child", child).addValue("activity", activity)
                        .addValue("candidate", candidate).addValue("seed", seedId), "deadline", deadline));
        return new Reservation(operation(child, candidate).orElseThrow(), true);
    }

    public void projecting(Operation op, String handle, KnowledgeSeed seed, String model, String prompt) {
        int updated = jdbc.update("update core.core_seed_generation_operation set state='PROJECTING',graph_transaction_id=:handle,validated_seed=cast(:seed as jsonb),model=:model,prompt_version=:prompt where child_id=:child and candidate_ref=:candidate and state='RESERVED'",
                key(op).addValue("handle", handle).addValue("seed", json.write(seed)).addValue("model", model).addValue("prompt", prompt));
        if (updated != 1) throw new IllegalStateException("Reservation no longer active");
        markMayExist(op.childId());
    }

    public void committed(Operation op, Instant now) {
        if (!"PROJECTING".equals(op.state())) throw new IllegalStateException("Projection required");
        jdbc.update("insert into core.generated_seed(child_id,seed_id,activity_id,candidate_ref,seed,model,prompt_version,check_codes,created_at) values (:child,:id,:activity,:candidate,cast(:seed as jsonb),:model,:prompt,cast(:checks as jsonb),:now)",
                ClockParams.instant(key(op).addValue("id", op.seedId()).addValue("activity", op.activityId())
                        .addValue("seed", json.write(op.seed())).addValue("model", op.model()).addValue("prompt", op.promptVersion())
                        .addValue("checks", json.write(op.checkCodes())), "now", now));
        jdbc.update("update core.core_seed_generation_operation set state='COMMITTED' where child_id=:child and candidate_ref=:candidate", key(op));
    }

    public void finish(Operation op, String reused) {
        jdbc.update("update core.core_seed_generation_operation set state=:state,reused_seed_id=:reused,validated_seed=null,graph_transaction_id=null where child_id=:child and candidate_ref=:candidate",
                key(op).addValue("state", reused == null ? "NONE" : "REUSED").addValue("reused", reused));
    }

    public List<KnowledgeSeed> successful(UUID child) {
        return jdbc.query("select g.seed from core.generated_seed g where g.child_id=:child and not exists (select 1 from core.core_graph_deleted_source d where d.child_id=g.child_id and d.activity_id=g.activity_id) order by g.seed_id",
                Map.of("child", child), (rs, n) -> json.read(rs.getString(1), KnowledgeSeed.class));
    }

    public void check(Operation op, String code) {
        if (!java.util.Set.of("CONTENT_ACCEPTED", "CONTENT_REJECTED", "MODEL_NONE", "DUPLICATE_REUSED", "GENERATION_FAILED").contains(code)) {
            throw new IllegalArgumentException("Unknown validation code");
        }
        jdbc.update("update core.core_seed_generation_operation set check_codes=case when check_codes @> cast(:codes as jsonb) then check_codes else check_codes || cast(:codes as jsonb) end where child_id=:child and candidate_ref=:candidate and activity_id=:activity and seed_id=:id",
                key(op).addValue("activity", op.activityId()).addValue("id", op.seedId()).addValue("codes", json.write(List.of(code))));
    }

    public void finishOwnedReservation(Operation op) {
        jdbc.update("update core.core_seed_generation_operation set state='NONE' where child_id=:child and candidate_ref=:candidate and activity_id=:activity and seed_id=:id and state='RESERVED'",
                key(op).addValue("activity", op.activityId()).addValue("id", op.seedId()));
    }

    public boolean deletedSource(UUID child, UUID activity) {
        Integer count = jdbc.queryForObject("select count(*) from core.core_graph_deleted_source where child_id=:child and activity_id=:activity",
                Map.of("child", child, "activity", activity), Integer.class);
        return count != null && count > 0;
    }

    public void markDeleting(UUID child, UUID activity) {
        if (activity == null) {
            jdbc.update("insert into core.core_graph_deleted_child(child_id,guardian_id) select child_id,guardian_id from core.core_child_graph_state where child_id=:child on conflict do nothing", Map.of("child", child));
            jdbc.update("update core.core_child_graph_state set state='DELETING' where child_id=:child", Map.of("child", child));
        } else {
            jdbc.update("insert into core.core_graph_deleted_source(child_id,activity_id) values (:child,:activity) on conflict do nothing",
                    Map.of("child", child, "activity", activity));
        }
    }

    public boolean deletedChild(UUID child) {
        return jdbc.queryForObject("select count(*) from core.core_graph_deleted_child where child_id=:child", Map.of("child", child), Integer.class) > 0;
    }

    public void forgetDeletedChildren(UUID guardian) {
        jdbc.update("delete from core.core_graph_deleted_child where guardian_id=:guardian", Map.of("guardian", guardian));
    }

    public List<String> sourceSeeds(UUID child, UUID activity) {
        return jdbc.query("select seed_id from core.core_seed_generation_operation where child_id=:child and activity_id=:activity",
                Map.of("child", child, "activity", activity), (rs, n) -> rs.getString(1));
    }

    public void deleteLedger(UUID child, UUID activity) {
        var params = new MapSqlParameterSource("child", child).addValue("activity", activity);
        String predicate = activity == null ? "child_id=:child" : "child_id=:child and activity_id=:activity";
        jdbc.update("delete from core.generated_seed where " + predicate, params);
        jdbc.update("delete from core.core_seed_generation_operation where " + predicate, params);
        if (activity == null) {
            jdbc.update("delete from core.core_graph_deleted_source where child_id=:child", params);
            jdbc.update("delete from core.core_child_graph_state where child_id=:child", params);
        }
    }

    private MapSqlParameterSource key(Operation op) {
        return new MapSqlParameterSource("child", op.childId()).addValue("candidate", op.candidateRef());
    }
}
