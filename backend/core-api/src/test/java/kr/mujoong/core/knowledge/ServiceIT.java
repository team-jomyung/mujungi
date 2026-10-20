package kr.mujoong.core.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.luna.CoreLunaClient;
import kr.mujoong.core.luna.LunaResult;
import kr.mujoong.core.textai.ForbiddenExpressionPolicy;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

@Tag("knowledge-graph") @Tag("db")
class ServiceIT {
    static class Fixture implements AutoCloseable {
        final DbTestSupport db = DbTestSupport.coreApp();
        final KnowledgeLedgerRepository ledger = spy(new KnowledgeLedgerRepository(db.jdbc()));
        final KnowledgeAccessPolicy access = mock(KnowledgeAccessPolicy.class);
        final MemoryChildKnowledgeGraph graph = spy(new MemoryChildKnowledgeGraph());
        final MutableClock clock = new MutableClock(Instant.parse("2030-01-01T00:00:00Z"));
        final UUID guardian, child, activity = UUID.randomUUID();
        final ChildKnowledgeService service;
        final CoreLunaClient luna = mock(CoreLunaClient.class);
        Fixture() {
            guardian = db.inTx(s -> db.insertGuardian("ACTIVE"));
            child = db.inTx(s -> db.insertChild(guardian));
            var lock = new kr.mujoong.core.identity.GuardianWriteLock(db.jdbc());
            doAnswer(invocation -> { lock.lockActive(guardian); return null; }).when(access).requireAllowed(guardian, child);
            doAnswer(invocation -> { lock.statusForUpdate(guardian); return null; }).when(access).requireDeletionOwner(guardian, child);
            when(access.source(guardian, child, activity)).thenAnswer(invocation -> {
                lock.lockActive(guardian);
                return new KnowledgeAccessPolicy.Source(List.of("서로 다른 합성 전사"), List.of("가상아이"));
            });
            service = new ChildKnowledgeService(ledger, access, graph, new DataSourceTransactionManager(db.dataSource()),
                    clock, GraphModelTest.catalog(), true, Duration.ofSeconds(2));
            assertThat(service.initializeChild(guardian, child)).isTrue();
        }
        SeedGenerationService generator() {
            return new SeedGenerationService(service, Optional.of(luna), new SeedContentValidator(new ForbiddenExpressionPolicy()),
                    true, Duration.ofSeconds(10), "합성 씨앗");
        }
        SeedGenerationService.Request request(String candidate) {
            return new SeedGenerationService.Request(guardian, child, activity, candidate, "NONE", GraphModelTest.SIGNATURE);
        }
        void response(Map<String, Object> output) {
            when(luna.call(any(), any())).thenReturn(new LunaResult(JsonCodec.shared().write(output), "fake-model",
                    "seed-generation.v2", Duration.ZERO, 1, false));
        }
        @Override public void close() {
            service.close();
            db.inTx(s -> { ledger.deleteLedger(child, null); ledger.forgetDeletedChildren(guardian); return null; });
            db.close();
        }
    }

    @Test void disabledHistoricalGraphRequiresDeletionRetry() {
        try (var f = new Fixture(); var disabled = new ChildKnowledgeService(f.ledger, f.access, f.graph,
                new DataSourceTransactionManager(f.db.dataSource()), f.clock, GraphModelTest.catalog(), false, Duration.ofSeconds(2))) {
            clearInvocations(f.graph);
            assertThat(disabled.seedSummaries(f.guardian, f.child)).isEmpty();
            assertThat(disabled.initializeChild(f.guardian, f.child)).isFalse();
            assertThatThrownBy(() -> disabled.deleteChild(f.guardian, f.child)).isInstanceOf(IllegalStateException.class);
            assertThat(f.ledger.child(f.child).orElseThrow().state()).isEqualTo("DELETING");
            verifyNoInteractions(f.graph);
        }
    }
    @Test void closedInitializerDoesNotFailCommittedRegistration() {
        try (var f = new Fixture()) {
            f.service.close();
            assertThatCode(() -> f.service.requestInitialization(f.guardian, f.child)).doesNotThrowAnyException();
        }
    }
    @Test void lateReadDoesNotInitializeOrWrite() throws Exception {
        try (var f = new Fixture(); var shortRead = new ChildKnowledgeService(f.ledger, f.access, f.graph,
                new DataSourceTransactionManager(f.db.dataSource()), f.clock, GraphModelTest.catalog(), true, Duration.ofMillis(150))) {
            var entered = new java.util.concurrent.CountDownLatch(1);
            var released = new java.util.concurrent.CountDownLatch(1);
            var finished = new java.util.concurrent.CountDownLatch(1);
            clearInvocations(f.graph);
            doAnswer(invocation -> {
                entered.countDown();
                while (released.getCount() != 0) {
                    try { released.await(); } catch (InterruptedException ignored) { /* remote read ignores cancellation */ }
                }
                finished.countDown();
                return List.of();
            }).when(f.graph).seeds(f.child);
            try {
                assertThat(shortRead.seedSummaries(f.guardian, f.child)).isEmpty();
                assertThat(entered.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            } finally { released.countDown(); }
            assertThat(finished.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            verify(f.graph, never()).begin();
            verify(f.graph, never()).replaceBase(anyString(), any(), any());
        }
    }
    @Test void simultaneousCandidatesReuseOneCommittedStructure() throws Exception {
        try (var f = new Fixture(); var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var both = new java.util.concurrent.CountDownLatch(2);
            when(f.luna.call(any(), any())).thenAnswer(invocation -> {
                both.countDown();
                assertThat(both.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                return new LunaResult(JsonCodec.shared().write(RulesTest.output("뜻을 살펴본다")), "fake-model", "seed-generation.v2", Duration.ZERO, 1, false);
            });
            var generator = f.generator();
            var first = workers.submit(() -> generator.generateSeed(f.request("a")));
            var second = workers.submit(() -> generator.generateSeed(f.request("b")));
            assertThat(first.get(8, java.util.concurrent.TimeUnit.SECONDS).seedId()).startsWith("seed:G");
            assertThat(second.get(8, java.util.concurrent.TimeUnit.SECONDS).seedId()).startsWith("seed:G");
            assertThat(f.ledger.successful(f.child)).hasSize(1);
            assertThat(f.graph.seeds(f.child)).hasSize(2);
        }
    }
    @Test void replayWhileModelIsRunningDoesNotCallModelAgain() throws Exception {
        try (var f = new Fixture(); var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var entered = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            when(f.luna.call(any(), any())).thenAnswer(invocation -> {
                entered.countDown();
                assertThat(release.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                return new LunaResult(JsonCodec.shared().write(RulesTest.output("뜻을 살펴본다")), "fake-model", "seed-generation.v2", Duration.ZERO, 1, false);
            });
            var generator = f.generator();
            var first = workers.submit(() -> generator.generateSeed(f.request("a")));
            try {
                assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(generator.generateSeed(f.request("a")).seedId()).isEqualTo("NONE");
            } finally { release.countDown(); }
            assertThat(first.get(8, java.util.concurrent.TimeUnit.SECONDS).seedId()).isEqualTo("seed:G0001");
            verify(f.luna, times(1)).call(any(), any());
        }
    }
    @Test void accountParticipantRemovesGraphLedgerAndTombstone() {
        try (var f = new Fixture()) {
            var participant = new KnowledgeDeletionParticipant(f.service);
            assertThat(participant.deleteByGuardian(f.guardian, UUID.randomUUID())).containsEntry("knowledge_graph", 1);
            assertThat(participant.deleteByGuardian(f.guardian, UUID.randomUUID())).containsEntry("knowledge_graph", 0);
            assertThat(f.graph.seeds(f.child)).isEmpty();
            assertThat(f.ledger.deletedChild(f.child)).isFalse();
        }
    }
    @Test void contextVersionIsCapturedWithItsSeedAcrossRefresh() {
        try (var f = new Fixture()) {
            var before = f.service.skillContext(f.guardian, f.child, "seed:S1").orElseThrow();
            var next = new SeedCatalog("v2", "c".repeat(64), GraphModelTest.catalog().seeds());
            try (var newer = new ChildKnowledgeService(f.ledger, f.access, f.graph,
                    new DataSourceTransactionManager(f.db.dataSource()), f.clock, next, true, Duration.ofSeconds(2))) {
                assertThat(newer.initializeChild(f.guardian, f.child)).isTrue();
                var after = newer.skillContext(f.guardian, f.child, "seed:S1").orElseThrow();
                assertThat(before.graphVersion()).isEqualTo("v1");
                assertThat(after.graphVersion()).isEqualTo("v2");
                assertThat(after.seed().seedId()).isEqualTo(before.seed().seedId());
            }
        }
    }
    @Test void failedSourceDeletionHidesSeedsFromReadModelAndRebuildButRetainsCleanupTargets() {
        try (var f = new Fixture()) {
            f.response(RulesTest.output("뜻을 살펴본다"));
            assertThat(f.generator().generateSeed(f.request("a")).seedId()).isEqualTo("seed:G0001");
            UUID other = UUID.randomUUID();
            when(f.access.source(f.guardian, f.child, other)).thenReturn(new KnowledgeAccessPolicy.Source(List.of(), List.of()));
            var otherRequest = new SeedGenerationService.Request(f.guardian, f.child, other, "b", "NONE", GraphModelTest.SIGNATURE);
            f.response(RulesTest.output("차이를 생각한다"));
            assertThat(f.generator().generateSeed(otherRequest).seedId()).isEqualTo("seed:G0002");
            doThrow(new IllegalStateException("offline")).when(f.graph).deleteSeeds(anyString(), eq(f.child), anyList());
            assertThatThrownBy(() -> f.service.deleteGeneratedSeedsForRecord(f.guardian, f.child, f.activity)).isInstanceOf(IllegalStateException.class);
            assertThat(f.ledger.sourceSeeds(f.child, f.activity)).containsExactly("seed:G0001");
            assertThat(f.service.seedSummaries(f.guardian, f.child)).extracting(KnowledgeSeed::seedId).containsExactlyInAnyOrder("seed:S1", "seed:G0002");
            assertThat(f.service.skillContext(f.guardian, f.child, "seed:G0001")).isEmpty();
            String wipe = f.graph.begin(); f.graph.deleteChild(wipe, f.child); f.graph.commit(wipe);
            assertThat(f.service.initializeChild(f.guardian, f.child)).isTrue();
            assertThat(f.graph.seeds(f.child)).extracting(KnowledgeSeed::seedId).containsExactlyInAnyOrder("seed:S1", "seed:G0002");
            when(f.luna.call(any(), any())).thenAnswer(call -> {
                assertThat(JsonCodec.shared().write(call.getArgument(0))).doesNotContain("seed:G0001");
                return new LunaResult("{\"outcome\":\"NONE\",\"none_reason\":\"NO_VALID_SEED\"}", "fake", "v2", Duration.ZERO, 1, false);
            });
            f.generator().generateSeed(new SeedGenerationService.Request(f.guardian, f.child, other, "c", "NONE", GraphModelTest.SIGNATURE));
            verify(f.luna, times(3)).call(any(), any());
            doCallRealMethod().when(f.graph).deleteSeeds(anyString(), eq(f.child), anyList());
            f.service.deleteGeneratedSeedsForRecord(f.guardian, f.child, f.activity);
            assertThat(f.ledger.sourceSeeds(f.child, f.activity)).isEmpty();
            assertThat(f.ledger.successful(f.child)).extracting(KnowledgeSeed::seedId).containsExactly("seed:G0002");
        }
    }
    @Test void failedForeignInvocationCannotCancelOwnedReservation() throws Exception {
        try (var f = new Fixture(); var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var entered = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            when(f.luna.call(any(), any())).thenAnswer(call -> {
                entered.countDown();
                assertThat(release.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                return new LunaResult(JsonCodec.shared().write(RulesTest.output("뜻을 살펴본다")), "fake", "v2", Duration.ZERO, 1, false);
            });
            var generator = f.generator();
            var first = workers.submit(() -> generator.generateSeed(f.request("same")));
            try {
                assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                UUID invalid = UUID.randomUUID();
                when(f.access.source(f.guardian, f.child, invalid)).thenThrow(new IllegalStateException("source unavailable"));
                assertThat(generator.generateSeed(new SeedGenerationService.Request(f.guardian, f.child, invalid, "same", "NONE", GraphModelTest.SIGNATURE)).seedId()).isEqualTo("NONE");
                assertThat(f.ledger.operation(f.child, "same").orElseThrow().state()).isEqualTo("RESERVED");
            } finally { release.countDown(); }
            assertThat(first.get(8, java.util.concurrent.TimeUnit.SECONDS).seedId()).isEqualTo("seed:G0001");
            assertThat(generator.generateSeed(f.request("same")).seedId()).isEqualTo("seed:G0001");
        }
    }
    @Test void validationEvidenceDistinguishesAcceptanceAndFinalRejection() {
        try (var f = new Fixture()) {
            f.response(RulesTest.output("뜻을 살펴본다"));
            assertThat(f.generator().generateSeed(f.request("good")).seedId()).isEqualTo("seed:G0001");
            assertThat(f.ledger.operation(f.child, "good").orElseThrow().checkCodes()).containsExactly("CONTENT_ACCEPTED");
            assertThat(f.db.jdbc().queryForObject("select check_codes::text from core.generated_seed where child_id=:child", Map.of("child", f.child), String.class)).contains("CONTENT_ACCEPTED");
            f.response(RulesTest.output("가상아이의 이야기"));
            assertThat(f.generator().generateSeed(f.request("bad")).seedId()).isEqualTo("NONE");
            assertThat(f.ledger.operation(f.child, "bad").orElseThrow().checkCodes()).containsExactly("CONTENT_REJECTED");
        }
    }
    @Test void initializedReadAndNoneReference() {
        try (var f = new Fixture()) {
            assertThat(f.service.seedSummaries(f.guardian, f.child)).hasSize(1);
            assertThat(f.service.skillContext(f.guardian, f.child, "NONE")).isEmpty();
            assertThat(f.service.skillContext(f.guardian, f.child, "seed:S1")).isPresent();
        }
    }
    @Test void permissionsAreCheckedBeforeRead() {
        try (var f = new Fixture()) {
            doThrow(new IllegalStateException("denied")).when(f.access).requireAllowed(f.guardian, f.child);
            assertThat(f.service.seedSummaries(f.guardian, f.child)).isEmpty();
        }
    }
    @Test void graphFailureReturnsEmpty() {
        try (var f = new Fixture()) {
            doThrow(new IllegalStateException("graph down")).when(f.graph).seeds(f.child);
            assertThat(f.service.seedSummaries(f.guardian, f.child)).isEmpty();
        }
    }
    @Test void uncommittedGraphGhostIsHidden() {
        try (var f = new Fixture()) {
            String tx = f.graph.begin();
            f.graph.writeGeneratedSeed(tx, f.child, GraphModelTest.seed("seed:G9999", KnowledgeSeed.Origin.GENERATED));
            f.graph.commit(tx);
            assertThat(f.service.seedSummaries(f.guardian, f.child)).extracting(KnowledgeSeed::seedId).containsExactly("seed:S1");
        }
    }
    @Test void generatesAndReplaysWithoutModelCall() {
        try (var f = new Fixture()) {
            f.response(RulesTest.output("뜻을 살펴본다"));
            var generator = f.generator();
            assertThat(generator.generateSeed(f.request("a")).seedId()).isEqualTo("seed:G0001");
            assertThat(generator.generateSeed(f.request("a")).seedId()).isEqualTo("seed:G0001");
            verify(f.luna, times(1)).call(any(), any());
            assertThat(f.ledger.successful(f.child)).hasSize(1);
            assertThat(f.service.seedSummaries(f.guardian, f.child)).hasSize(2);
        }
    }
    @Test void duplicateIsDurableAndDoesNotConsumeQuota() {
        try (var f = new Fixture()) {
            f.response(Map.of("outcome", "DUPLICATE", "duplicate_of", "seed:S1"));
            var generator = f.generator();
            assertThat(generator.generateSeed(f.request("a")).seedId()).isEqualTo("seed:S1");
            assertThat(generator.generateSeed(f.request("a")).seedId()).isEqualTo("seed:S1");
            assertThat(f.ledger.successful(f.child)).isEmpty();
            verify(f.luna, times(1)).call(any(), any());
        }
    }
    @Test void invalidOutputUsesOneCorrectionThenNone() {
        try (var f = new Fixture()) {
            f.response(RulesTest.output("가상아이의 이야기"));
            assertThat(f.generator().generateSeed(f.request("a")).seedId()).isEqualTo("NONE");
            verify(f.luna, times(2)).call(any(), any());
            assertThat(f.ledger.successful(f.child)).isEmpty();
        }
    }
    @Test void commitFailureKeepsRecoverableProjection() {
        try (var f = new Fixture()) {
            f.response(RulesTest.output("뜻을 살펴본다"));
            doThrow(new IllegalStateException("lost reply")).when(f.graph).commit(anyString());
            assertThat(f.generator().generateSeed(f.request("a")).seedId()).isEqualTo("NONE");
            assertThat(f.ledger.pending(f.child)).hasSize(1);
            assertThat(f.ledger.successful(f.child)).isEmpty();
            doCallRealMethod().when(f.graph).commit(anyString());
            assertThat(f.service.initializeChild(f.guardian, f.child)).isTrue();
            assertThat(f.ledger.pending(f.child)).isEmpty();
        }
    }
    @Test void sourceDeletionKeepsBaseAndPreventsRegeneration() {
        try (var f = new Fixture()) {
            f.response(RulesTest.output("뜻을 살펴본다"));
            var generator = f.generator();
            assertThat(generator.generateSeed(f.request("a")).seedId()).startsWith("seed:G");
            f.service.deleteGeneratedSeedsForRecord(f.guardian, f.child, f.activity);
            assertThat(f.service.seedSummaries(f.guardian, f.child)).extracting(KnowledgeSeed::seedId).containsExactly("seed:S1");
            assertThat(generator.generateSeed(f.request("a")).seedId()).isEqualTo("NONE");
            verify(f.luna, times(1)).call(any(), any());
        }
    }
    @Test void childDeletionTombstoneBlocksReinitialization() {
        try (var f = new Fixture()) {
            f.service.deleteChild(f.guardian, f.child);
            assertThat(f.service.initializeChild(f.guardian, f.child)).isFalse();
            assertThat(f.service.seedSummaries(f.guardian, f.child)).isEmpty();
            f.service.deleteChild(f.guardian, f.child);
            assertThat(f.graph.seeds(f.child)).isEmpty();
        }
    }
    @Test void failedDeleteRetriesWithoutLosingTarget() {
        try (var f = new Fixture()) {
            doThrow(new IllegalStateException("offline")).when(f.graph).deleteChild(anyString(), eq(f.child));
            assertThatThrownBy(() -> f.service.deleteChild(f.guardian, f.child)).isInstanceOf(IllegalStateException.class);
            assertThat(f.ledger.child(f.child).orElseThrow().state()).isEqualTo("DELETING");
            doCallRealMethod().when(f.graph).deleteChild(anyString(), eq(f.child));
            f.service.deleteChild(f.guardian, f.child);
            assertThat(f.ledger.child(f.child)).isEmpty();
        }
    }
    @Test void graphCommitThenDatabaseFailureLeavesGhostHiddenAndRecoverable() {
        try (var f = new Fixture()) {
            f.response(RulesTest.output("뜻을 살펴본다"));
            doThrow(new IllegalStateException("db failed")).when(f.ledger).committed(any(), any());
            assertThat(f.generator().generateSeed(f.request("a")).seedId()).isEqualTo("NONE");
            assertThat(f.graph.seeds(f.child)).hasSize(2);
            assertThat(f.ledger.successful(f.child)).isEmpty();
            assertThat(f.ledger.pending(f.child)).hasSize(1);
            assertThat(f.service.initializeChild(f.guardian, f.child)).isTrue();
            assertThat(f.graph.seeds(f.child)).extracting(KnowledgeSeed::seedId).containsExactly("seed:S1");
        }
    }
    @Test void deletionDuringModelCallPreventsLateWrite() {
        try (var f = new Fixture()) {
            when(f.luna.call(any(), any())).thenAnswer(invocation -> {
                f.service.deleteGeneratedSeedsForRecord(f.guardian, f.child, f.activity);
                return new LunaResult(JsonCodec.shared().write(RulesTest.output("뜻을 살펴본다")), "fake-model", "seed-generation.v2", Duration.ZERO, 1, false);
            });
            assertThat(f.generator().generateSeed(f.request("a")).seedId()).isEqualTo("NONE");
            assertThat(f.graph.seeds(f.child)).hasSize(1);
            assertThat(f.ledger.successful(f.child)).isEmpty();
        }
    }
    @Test void expiredReservationsReleaseCapacityForNewCandidate() {
        try (var f = new Fixture()) {
            f.db.inTx(s -> {
                f.ledger.reserve(f.child, f.activity, "stale-a", f.clock.now().minusSeconds(1));
                f.ledger.reserve(f.child, f.activity, "stale-b", f.clock.now().minusSeconds(1));
                return null;
            });
            f.response(RulesTest.output("뜻을 살펴본다"));
            assertThat(f.generator().generateSeed(f.request("fresh")).seedId()).isEqualTo("seed:G0003");
        }
    }
    @Test void unresolvedRemoteTerminationKeepsDeletionRetryable() {
        try (var f = new Fixture()) {
            f.db.inTx(s -> {
                var op = f.ledger.reserve(f.child, f.activity, "a", f.clock.now().plusSeconds(5)).operation();
                f.ledger.projecting(op, "unknown", GraphModelTest.seed(op.seedId(), KnowledgeSeed.Origin.GENERATED), "fake", "v2");
                return null;
            });
            doReturn(false).when(f.graph).terminate("unknown");
            assertThatThrownBy(() -> f.service.deleteChild(f.guardian, f.child)).isInstanceOf(IllegalStateException.class);
            assertThat(f.ledger.pending(f.child)).hasSize(1);
            doReturn(true).when(f.graph).terminate("unknown");
        }
    }
}
