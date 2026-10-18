package kr.mujoong.core.knowledge;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.UUID;
import kr.mujoong.core.common.db.DbTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("knowledge-graph") @Tag("db")
class LedgerIT {
    private static final Instant DEADLINE = Instant.parse("2030-01-01T00:00:00Z");
    private void run(java.util.function.BiConsumer<KnowledgeLedgerRepository, UUID> action) {
        try (var db = DbTestSupport.coreApp()) {
            db.inRolledBackTx(status -> {
                UUID guardian = db.insertGuardian("ACTIVE");
                UUID child = db.insertChild(guardian);
                var ledger = new KnowledgeLedgerRepository(db.jdbc());
                ledger.ensure(guardian, child);
                action.accept(ledger, child);
                return null;
            });
        }
    }

    @Test void candidateReservationIsIdempotent() {
        run((ledger, child) -> {
            UUID activity = UUID.randomUUID();
            var first = ledger.reserve(child, activity, "candidate", DEADLINE);
            var replay = ledger.reserve(child, activity, "candidate", DEADLINE);
            assertThat(first.created()).isTrue();
            assertThat(replay.created()).isFalse();
            assertThat(replay.operation()).isEqualTo(first.operation());
        });
    }
    @Test void twoReservationsConsumeActivityLimit() {
        run((ledger, child) -> {
            UUID activity = UUID.randomUUID();
            assertThat(ledger.reserve(child, activity, "a", DEADLINE)).isNotNull();
            assertThat(ledger.reserve(child, activity, "b", DEADLINE)).isNotNull();
            assertThat(ledger.reserve(child, activity, "c", DEADLINE)).isNull();
        });
    }
    @Test void failedNumberIsNotReused() {
        run((ledger, child) -> {
            UUID activity = UUID.randomUUID();
            var first = ledger.reserve(child, activity, "a", DEADLINE).operation();
            ledger.finish(first, null);
            var next = ledger.reserve(child, activity, "b", DEADLINE).operation();
            assertThat(first.seedId()).isEqualTo("seed:G0001");
            assertThat(next.seedId()).isEqualTo("seed:G0002");
            assertThat(ledger.successful(child)).isEmpty();
        });
    }
    @Test void reuseReleasesSlotAndReplaysTarget() {
        run((ledger, child) -> {
            UUID activity = UUID.randomUUID();
            var first = ledger.reserve(child, activity, "a", DEADLINE).operation();
            ledger.finish(first, "seed:S1");
            assertThat(ledger.reserve(child, activity, "a", DEADLINE).operation().reusedSeedId()).isEqualTo("seed:S1");
            assertThat(ledger.reserve(child, activity, "b", DEADLINE)).isNotNull();
            assertThat(ledger.reserve(child, activity, "c", DEADLINE)).isNotNull();
        });
    }
    @Test void pendingProjectionHasNoSuccessfulSeed() {
        run((ledger, child) -> {
            var op = ledger.reserve(child, UUID.randomUUID(), "a", DEADLINE).operation();
            ledger.projecting(op, "tx-1", GraphModelTest.seed(op.seedId(), KnowledgeSeed.Origin.GENERATED), "fake", "v2");
            assertThat(ledger.pending(child)).hasSize(1);
            assertThat(ledger.child(child).orElseThrow().graphMayExist()).isTrue();
            assertThat(ledger.successful(child)).isEmpty();
        });
    }
    @Test void onlyCommittedProjectionBecomesSuccessful() {
        run((ledger, child) -> {
            var op = ledger.reserve(child, UUID.randomUUID(), "a", DEADLINE).operation();
            ledger.projecting(op, "tx-1", GraphModelTest.seed(op.seedId(), KnowledgeSeed.Origin.GENERATED), "fake", "v2");
            ledger.committed(ledger.operation(child, "a").orElseThrow(), DEADLINE);
            assertThat(ledger.successful(child)).hasSize(1);
            assertThat(ledger.pending(child)).isEmpty();
        });
    }
    @Test void sourceTombstonePreventsLateRegeneration() {
        run((ledger, child) -> {
            UUID activity = UUID.randomUUID();
            ledger.markDeleting(child, activity);
            assertThat(ledger.reserve(child, activity, "a", DEADLINE)).isNull();
            assertThat(ledger.reserve(child, UUID.randomUUID(), "b", DEADLINE)).isNotNull();
        });
    }
    @Test void childDeletionBlocksEverySource() {
        run((ledger, child) -> {
            ledger.markDeleting(child, null);
            assertThat(ledger.reserve(child, UUID.randomUUID(), "a", DEADLINE)).isNull();
            ledger.deleteLedger(child, null);
            assertThat(ledger.child(child)).isEmpty();
        });
    }
}
