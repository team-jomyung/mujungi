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
    @Test void firstPinTimestampIsWrittenOnceAndUnknownVersionIsNotMarked() {
        db.inRolledBackTx(status -> {
            String key=freshDefinition();
            assertThat(repository.markPinned(key,1,DbTestSupport.EPOCH)).isEqualTo(1);
            // 이미 고정된 행은 다시 갱신하지 않는다(U5a 리뷰 1)
            assertThat(repository.markPinned(key,1,DbTestSupport.EPOCH.plus(Duration.ofDays(1)))).isZero();
            assertThat(repository.findVersion(key,1).orElseThrow().usedAt()).isEqualTo(DbTestSupport.EPOCH);
            assertThat(repository.markPinned(key,999,DbTestSupport.EPOCH)).isZero();
            return null;
        });
    }
    @Test void usedDefinitionCannotBeRewrittenAtDatabaseBoundary() {
        assertThatThrownBy(() -> db.inRolledBackTx(status -> {
            String key=freshDefinition();
            repository.markPinned(key,1,DbTestSupport.EPOCH);
            db.jdbc().update("update core.core_behavior_definition set judgment_criteria='changed' where behavior_key=:key",
                    Map.of("key",key));
            return null;
        })).isInstanceOf(DataAccessException.class);
    }
    @Test void usedDefinitionCannotBeDeletedOrUnpinned() {
        for(String sql : new String[]{
                "delete from core.core_behavior_definition where behavior_key=:key",
                "update core.core_behavior_definition set used_at=null where behavior_key=:key",
                "update core.core_behavior_definition set status='DRAFT' where behavior_key=:key"}) {
            assertThatThrownBy(() -> db.inRolledBackTx(status -> {
                String key=freshDefinition();
                repository.markPinned(key,1,DbTestSupport.EPOCH);
                db.jdbc().update(sql,Map.of("key",key));
                return null;
            })).isInstanceOf(DataAccessException.class);
        }
    }

    @Test void retiredDefinitionCanStillBePinnedForHistoricalSessions() {
        db.inRolledBackTx(status -> {
            String key=freshDefinition();
            db.jdbc().update("update core.core_behavior_definition set status='RETIRED' where behavior_key=:key",
                    Map.of("key",key));
            assertThat(repository.readForPin(key,1).orElseThrow().status()).isEqualTo("RETIRED");
            assertThat(repository.markPinned(key,1,DbTestSupport.EPOCH)).isEqualTo(1);
            assertThat(repository.findVersion(key,1).orElseThrow().usedAt()).isEqualTo(DbTestSupport.EPOCH);
            assertThat(repository.readForPin("missing",1)).isEmpty();
            return null;
        });
    }
    @Test void pinningAPinnedDefinitionNeitherWaitsForNorBlocksAnotherGuardiansPin() throws Exception {
        // U5a 리뷰 1: 공용 정의 행을 FOR UPDATE로 잡으면 다른 보호자의 후보 등록 · C10 생성이 그 행 앞에 줄을 서고, 보호자 잠금과 순서가
        // 엇갈리면 교착(40P01)이 난다. 고정은 FOR KEY SHARE로 읽고 처음 한 번만 used_at을 찍는다
        String key=freshDefinition(); // 트랜잭션 밖이라 커밋된다(고정된 정의는 지울 수 없어 키마다 새로 만든다)
        var knowledge=new PragmaticsKnowledge(repository,new kr.mujoong.testsupport.time.MutableClock(DbTestSupport.EPOCH),
                new KnowledgeProperties(false),java.util.Optional.empty());
        db.inTx(status -> knowledge.pinVersion(key,1));
        var held=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var first=db.startTx(new java.util.concurrent.atomic.AtomicInteger(),status -> {
            knowledge.pinVersion(key,1);
            held.countDown();
            try { if (!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("first pin not released"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
            return null;
        });
        try {
            assertThat(held.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var second=db.startTx(new java.util.concurrent.atomic.AtomicInteger(),status -> knowledge.pinVersion(key,1));
            assertThat(second.get(3,java.util.concurrent.TimeUnit.SECONDS).usedAt()).as("먼저 연 트랜잭션을 기다리지 않는다")
                    .isEqualTo(DbTestSupport.EPOCH);
        } finally {
            release.countDown();
        }
        first.get(5,java.util.concurrent.TimeUnit.SECONDS);
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
            repository.readForPin(key,1).orElseThrow();
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
