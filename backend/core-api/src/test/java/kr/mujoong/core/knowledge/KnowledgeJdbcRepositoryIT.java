package kr.mujoong.core.knowledge;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.Map;
import kr.mujoong.core.common.db.DbTestSupport;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;

@Tag("u5a")
@Tag("db")
class KnowledgeJdbcRepositoryIT {
    private DbTestSupport db;
    private KnowledgeJdbcRepository repository;
    @BeforeEach void setUp() { db=DbTestSupport.coreApp(); repository=new KnowledgeJdbcRepository(db.jdbc()); }
    @AfterEach void close() { if(db!=null) db.close(); }

    @Test void seededDefinitionsAreCompleteVersionedAndSupportIncludesLimitations() {
        assertThat(repository.findVersion("unknown",1)).isEmpty();
        for(String key : new String[]{"ACCEPT_STOP","ASK_TO_JOIN","ASK_FOR_CLARIFICATION"}) {
            var definition=repository.findVersion(key,1).orElseThrow();
            assertThat(definition.complete()).isTrue();
            assertThat(definition.status()).isEqualTo("ACTIVE");
            assertThat(definition.version()).isEqualTo(1);
        }
        assertThat(repository.findApprovedSupport("ACCEPT_STOP")).isNotEmpty()
                .allSatisfy(item -> {
                    assertThat(item.conditionText()).isNotBlank();
                    assertThat(item.contentText()).isNotBlank();
                    assertThat(item.limitationText()).isNotBlank();
                });
        assertThat(repository.findApprovedSupport("unknown")).isEmpty();
    }
    @Test void firstUseTimestampIsIdempotentAndUnknownVersionIsNotMarked() {
        db.inRolledBackTx(status -> {
            String key=freshDefinition();
            assertThat(repository.markUsed(key,1,DbTestSupport.EPOCH)).isEqualTo(1);
            assertThat(repository.markUsed(key,1,DbTestSupport.EPOCH.plus(Duration.ofDays(1)))).isEqualTo(1);
            assertThat(repository.findVersion(key,1).orElseThrow().usedAt()).isEqualTo(DbTestSupport.EPOCH);
            assertThat(repository.markUsed(key,999,DbTestSupport.EPOCH)).isZero();
            return null;
        });
    }
    @Test void usedDefinitionCannotBeRewrittenAtDatabaseBoundary() {
        assertThatThrownBy(() -> db.inRolledBackTx(status -> {
            repository.markUsed("ACCEPT_STOP",1,DbTestSupport.EPOCH);
            db.jdbc().update("update core.core_behavior_definition set judgment_criteria='changed' where behavior_key=:key",
                    Map.of("key","ACCEPT_STOP"));
            return null;
        })).isInstanceOf(DataAccessException.class);
    }
    @Test void usedDefinitionCannotBeDeletedOrUnpinned() {
        for(String sql : new String[]{
                "delete from core.core_behavior_definition where behavior_key=:key",
                "update core.core_behavior_definition set used_at=null where behavior_key=:key",
                "update core.core_behavior_definition set status='DRAFT' where behavior_key=:key"}) {
            assertThatThrownBy(() -> db.inRolledBackTx(status -> {
                repository.markUsed("ACCEPT_STOP",1,DbTestSupport.EPOCH);
                db.jdbc().update(sql,Map.of("key","ACCEPT_STOP"));
                return null;
            })).isInstanceOf(DataAccessException.class);
        }
    }

    @Test void retiredPinnedDefinitionCanBeReadButCannotBecomeANewCandidate() {
        db.inRolledBackTx(status -> {
            String key=freshDefinition();
            db.jdbc().update("update core.core_behavior_definition set status='RETIRED' where behavior_key=:key",
                    Map.of("key",key));
            assertThat(repository.lockVersion(key,1).orElseThrow().status()).isEqualTo("RETIRED");
            assertThat(repository.markUsed(key,1,DbTestSupport.EPOCH)).isZero();
            assertThat(repository.markPinned(key,1,DbTestSupport.EPOCH)).isEqualTo(1);
            assertThat(repository.findVersion(key,1).orElseThrow().usedAt()).isEqualTo(DbTestSupport.EPOCH);
            assertThat(repository.lockVersion("missing",1)).isEmpty();
            return null;
        });
    }
    @Test void pinLockBlocksConcurrentEditWhichFailsAfterPinCommits() throws Exception {
        String key="CONCURRENCY_"+java.util.UUID.randomUUID();
        db.jdbc().update("""
                insert into core.core_behavior_definition(id,behavior_key,version,display_name,
                behavior_definition_text,opportunity_condition,scenario_condition,judgment_criteria,status,created_at)
                values(:id,:key,1,'목표','행동','기회','상황','판정','ACTIVE',:created)
                """, Map.of("id",java.util.UUID.randomUUID(),"key",key,
                "created",kr.mujoong.core.common.db.ClockParams.timestamptz(DbTestSupport.EPOCH)));
        var pinned=new java.util.concurrent.CountDownLatch(1);
        var commit=new java.util.concurrent.CountDownLatch(1);
        var pinPid=new java.util.concurrent.atomic.AtomicInteger();
        var editPid=new java.util.concurrent.atomic.AtomicInteger();
        var pin=db.startTx(pinPid,status -> {
            repository.lockVersion(key,1).orElseThrow();
            repository.markPinned(key,1,DbTestSupport.EPOCH);
            pinned.countDown();
            try { if (!commit.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("pin commit not released"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
            return null;
        });
        assertThat(pinned.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var edit=db.startTx(editPid,status -> {
            db.jdbc().update("update core.core_behavior_definition set scenario_condition='changed' where behavior_key=:key",
                    Map.of("key",key));
            return null;
        });
        try { db.awaitLockWait(editPid); } finally { commit.countDown(); }
        pin.get(5,java.util.concurrent.TimeUnit.SECONDS);
        assertThatThrownBy(() -> edit.get(5,java.util.concurrent.TimeUnit.SECONDS))
                .hasCauseInstanceOf(DataAccessException.class);
        assertThat(repository.findVersion(key,1).orElseThrow().scenarioCondition()).isEqualTo("상황");
    }

    private String freshDefinition() {
        String key="TEST_"+java.util.UUID.randomUUID();
        db.jdbc().update("""
                insert into core.core_behavior_definition(id,behavior_key,version,display_name,
                behavior_definition_text,opportunity_condition,scenario_condition,judgment_criteria,status,created_at)
                values(:id,:key,1,'목표','행동','기회','상황','판정','ACTIVE',:created)
                """, Map.of("id",java.util.UUID.randomUUID(),"key",key,
                "created",kr.mujoong.core.common.db.ClockParams.timestamptz(DbTestSupport.EPOCH)));
        return key;
    }
}
