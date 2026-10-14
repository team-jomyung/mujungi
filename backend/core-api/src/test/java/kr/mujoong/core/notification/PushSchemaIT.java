package kr.mujoong.core.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.core.common.db.ClockParams;
import kr.mujoong.core.common.db.DbTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

@Tag("u7")
@Tag("db")
class PushSchemaIT {
    @Test
    void twoTablesHaveTheDesignedColumnsIndexesAndGuardianForeignKeys() {
        try (var db = DbTestSupport.coreApp()) {
            assertThat(columns(db, "core_push_subscription")).containsExactly("id", "guardian_id", "endpoint", "p256dh", "auth",
                    "platform", "created_at", "last_used_at", "revoked_at");
            assertThat(columns(db, "core_push_dispatch")).containsExactly("id", "guardian_id", "session_id", "event_type",
                    "event_key", "status", "attempt_count", "last_error_code", "requested_at", "sent_at", "activity_type",
                    "next_attempt_at", "claim_id", "lease_until", "recipient_progress");
            assertThat(db.jdbc().queryForList("""
                    select indexname from pg_indexes where schemaname='core' and indexname in
                    ('core_push_subscription_active_guardian_idx','core_push_dispatch_guardian_idx','core_push_dispatch_due_idx')
                    order by indexname
                    """, Map.of(), String.class)).containsExactly("core_push_dispatch_due_idx", "core_push_dispatch_guardian_idx",
                            "core_push_subscription_active_guardian_idx");
            assertThat(db.jdbc().queryForObject("""
                    select count(*) from information_schema.table_constraints
                    where table_schema='core' and table_name in ('core_push_subscription','core_push_dispatch')
                    and constraint_type='FOREIGN KEY'
                    """, Map.of(), Integer.class)).isEqualTo(2);
        }
    }

    @Test
    void dispatchRejectsInvalidStateAttemptsProgressAndHalfClaims() {
        try (var db = DbTestSupport.coreApp()) {
            UUID guardian = db.insertGuardian("ACTIVE");
            for (String assignment : List.of("event_type='UNKNOWN'", "status='UNKNOWN'", "activity_type='UNKNOWN'",
                    "attempt_count=-1", "recipient_progress='[]'::jsonb", "claim_id='00000000-0000-0000-0000-000000000001'::uuid")) {
                assertThatThrownBy(() -> db.inRolledBackTx(tx -> {
                    UUID id = UUID.randomUUID();
                    var params = ClockParams.at(DbTestSupport.EPOCH).addValue("id", id).addValue("guardian", guardian)
                            .addValue("session", UUID.randomUUID()).addValue("eventKey", id.toString());
                    db.jdbc().update("""
                            insert into core.core_push_dispatch
                            (id,guardian_id,session_id,event_type,event_key,status,attempt_count,requested_at,
                             activity_type,next_attempt_at,recipient_progress)
                            values(:id,:guardian,:session,'ACTIVITY_COMPLETED',:eventKey,'REQUESTED',0,:now,
                                   'FREE_TALK',:now,'{}'::jsonb)
                            """, params);
                    db.jdbc().update("update core.core_push_dispatch set " + assignment + " where id=:id", params);
                    return null;
                })).isInstanceOf(DataIntegrityViolationException.class);
            }
        }
    }

    private List<String> columns(DbTestSupport db, String table) {
        return db.jdbc().queryForList("""
                select column_name from information_schema.columns
                where table_schema='core' and table_name=:table order by ordinal_position
                """, Map.of("table", table), String.class);
    }
}
