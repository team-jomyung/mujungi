package kr.mujoong.core.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/** 자유대화 관찰 횟수 기록과 연습 장면 참고 씨앗 선택 규칙(contracts/knowledge-graph.md 참고 씨앗). DB 없이 원장을 대역으로 둔다. */
@Tag("knowledge-graph")
class ReferenceSeedTest {
    private static final Duration WEEK = Duration.ofDays(7);
    private static final SeedCatalog CATALOG = new SeedCatalog("v1", "a".repeat(64), List.of(
            GraphModelTest.seed("seed:S1", KnowledgeSeed.Origin.POC), GraphModelTest.seed("seed:S2", KnowledgeSeed.Origin.POC),
            GraphModelTest.seed("seed:S3", KnowledgeSeed.Origin.POC), GraphModelTest.seed("seed:S4", KnowledgeSeed.Origin.POC)));
    private final UUID guardian = UUID.randomUUID(), child = UUID.randomUUID();
    private final KnowledgeLedgerRepository ledger = mock(KnowledgeLedgerRepository.class);
    private final KnowledgeAccessPolicy access = mock(KnowledgeAccessPolicy.class);
    private final MemoryChildKnowledgeGraph graph = spy(new MemoryChildKnowledgeGraph());
    private final MutableClock clock = new MutableClock(Instant.parse("2030-01-01T00:00:00Z"));
    private final ChildKnowledgeService service = service(true);

    ReferenceSeedTest() {
        when(ledger.child(child)).thenReturn(Optional.of(state("ACTIVE", "v1", null)));
        when(ledger.pending(child)).thenReturn(List.of());
        when(ledger.successful(child)).thenReturn(List.of());
        String tx = graph.begin();
        graph.replaceBase(tx, child, CATALOG);
        graph.commit(tx);
    }

    @AfterEach void close() { service.close(); }

    private ChildKnowledgeService service(boolean enabled) {
        return new ChildKnowledgeService(ledger, access, graph, mock(PlatformTransactionManager.class), clock, CATALOG, enabled,
                Duration.ofSeconds(2));
    }
    private KnowledgeLedgerRepository.ChildState state(String state, String version, String initialization) {
        return new KnowledgeLedgerRepository.ChildState(child, guardian, version, true, state, initialization);
    }
    private SeedObservation seen(int count) { return new SeedObservation(count, clock.now()); }
    private Optional<String> pick(String goalSeed, Set<String> eligible) {
        return service.referenceSeed(guardian, child, goalSeed, () -> eligible, WEEK).map(ChildKnowledgeService.ReferenceSeed::seedId);
    }

    @Test void picksTheMostObservedSavedSeedOtherThanTheGoalSeed() {
        assertThat(service.recordObservations(guardian, child,
                () -> Map.of("seed:S1", seen(5), "seed:S2", seen(3), "seed:S3", seen(2)))).isTrue();

        assertThat(pick("seed:S1", Set.of("seed:S1", "seed:S2", "seed:S3"))).contains("seed:S2");
        assertThat(pick("NONE", Set.of("seed:S2", "seed:S3"))).contains("seed:S2");
        // 담은 목표에 없는 씨앗은 관찰이 가장 많아도 고르지 않는다.
        assertThat(pick("seed:S2", Set.of("seed:S2", "seed:S3"))).contains("seed:S3");
        var reference = service.referenceSeed(guardian, child, "NONE", () -> Set.of("seed:S2"), WEEK).orElseThrow();
        assertThat(reference.invariantSentence()).isEqualTo(GraphModelTest.seed("seed:S2", KnowledgeSeed.Origin.POC).invariantSentence());
    }

    @Test void aSeedObservedOnlyOnceIsNotReferenced() {
        service.recordObservations(guardian, child, () -> Map.of("seed:S2", seen(1), "seed:S3", seen(2)));

        assertThat(pick("NONE", Set.of("seed:S2"))).isEmpty();
        assertThat(pick("NONE", Set.of("seed:S2", "seed:S3"))).contains("seed:S3");
    }

    @Test void equalCountsPreferTheMoreRecentObservationThenTheSeedId() {
        var older = new SeedObservation(2, clock.now().minus(Duration.ofDays(3)));
        service.recordObservations(guardian, child, () -> Map.of("seed:S2", older, "seed:S3", seen(2), "seed:S4", seen(2)));

        assertThat(pick("NONE", Set.of("seed:S2", "seed:S3", "seed:S4"))).contains("seed:S3");
        assertThat(pick("NONE", Set.of("seed:S2", "seed:S4"))).contains("seed:S4");
    }

    @Test void aReferencedSeedRestsForTheCooldownCountedFromTheLastReference() {
        service.recordObservations(guardian, child, () -> Map.of("seed:S2", seen(4), "seed:S3", seen(2)));
        assertThat(service.markReferenced(guardian, child, "seed:S2", clock.now())).isTrue();

        clock.advance(WEEK.minusSeconds(1));
        assertThat(pick("NONE", Set.of("seed:S2", "seed:S3"))).contains("seed:S3");
        assertThat(pick("NONE", Set.of("seed:S2"))).isEmpty();
        clock.advance(Duration.ofSeconds(1));
        assertThat(pick("NONE", Set.of("seed:S2", "seed:S3"))).contains("seed:S2");
    }

    @Test void aLaterSyncResetsSeedsNoLongerObservedButKeepsTheReferenceTime() {
        service.recordObservations(guardian, child, () -> Map.of("seed:S2", seen(4), "seed:S3", seen(3)));
        service.markReferenced(guardian, child, "seed:S3", clock.now());
        service.recordObservations(guardian, child, () -> Map.of("seed:S3", seen(3)));

        var profiles = graph.profiles(child);
        assertThat(profiles).filteredOn(p -> p.seed().seedId().equals("seed:S2"))
                .singleElement().satisfies(p -> {
                    assertThat(p.observedCount()).isZero();
                    assertThat(p.lastObservedAt()).isNull();
                });
        assertThat(profiles).filteredOn(p -> p.seed().seedId().equals("seed:S3"))
                .singleElement().satisfies(p -> assertThat(p.lastReferencedAt()).isEqualTo(clock.now()));
    }

    @Test void noSavedSeedsMeansNoGraphRead() {
        assertThat(pick("seed:S1", Set.of())).isEmpty();
        verify(graph, never()).profiles(any());
    }

    @Test void aGraphThatIsNotReadyIsNeitherWrittenNorRead() {
        service.recordObservations(guardian, child, () -> Map.of("seed:S2", seen(4)));
        for (var notReady : List.of(state("DELETING", "v1", null), state("ACTIVE", "v0", null), state("ACTIVE", "v1", "7"))) {
            when(ledger.child(child)).thenReturn(Optional.of(notReady));
            assertThat(service.recordObservations(guardian, child, () -> Map.of("seed:S2", seen(9)))).isFalse();
            assertThat(service.markReferenced(guardian, child, "seed:S2", clock.now())).isFalse();
            assertThat(pick("NONE", Set.of("seed:S2"))).isEmpty();
        }
        when(ledger.child(child)).thenReturn(Optional.of(state("ACTIVE", "v1", null)));
        when(ledger.deletedChild(child)).thenReturn(true);
        assertThat(pick("NONE", Set.of("seed:S2"))).isEmpty();
        verify(graph, times(1)).writeObservations(any(), eq(child), any());
        verify(graph, never()).markReferenced(any(), any(), any(), any());
    }

    @Test void deniedAccessYieldsNoReferenceAndReadsNothingBeforeTheCheck() {
        service.recordObservations(guardian, child, () -> Map.of("seed:S2", seen(4)));
        doThrow(new IllegalStateException("consent withdrawn")).when(access).requireAllowed(guardian, child);
        var savedGoalsRead = new java.util.concurrent.atomic.AtomicBoolean();
        var countsRead = new java.util.concurrent.atomic.AtomicBoolean();

        assertThat(service.referenceSeed(guardian, child, "NONE", () -> { savedGoalsRead.set(true); return Set.of("seed:S2"); }, WEEK)).isEmpty();
        assertThatThrownBy(() -> service.recordObservations(guardian, child, () -> { countsRead.set(true); return Map.of(); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(savedGoalsRead).isFalse();
        assertThat(countsRead).isFalse();
    }

    @Test void aDisabledGraphNeverRecordsOrReferences() {
        try (var disabled = service(false)) {
            assertThat(disabled.recordObservations(guardian, child, () -> Map.of("seed:S2", seen(4)))).isFalse();
            assertThat(disabled.referenceSeed(guardian, child, "NONE", () -> Set.of("seed:S2"), WEEK)).isEmpty();
            disabled.requestReferenceMark(guardian, child, "seed:S2");
        }
        verifyNoInteractions(access);
        verify(graph, never()).writeObservations(any(), any(), any());
    }

    @Test void reloadingTheBaseCatalogKeepsRecordsOfSeedsStillInTheCatalog() {
        service.recordObservations(guardian, child, () -> Map.of("seed:S2", seen(4), "seed:S4", seen(3)));
        service.markReferenced(guardian, child, "seed:S2", clock.now());
        var reduced = new SeedCatalog("v1", "a".repeat(64), CATALOG.seeds().subList(0, 3));
        String tx = graph.begin();
        graph.replaceBase(tx, child, reduced);
        graph.commit(tx);

        assertThat(graph.profiles(child)).extracting(p -> p.seed().seedId()).containsExactly("seed:S1", "seed:S2", "seed:S3");
        assertThat(graph.profiles(child)).filteredOn(p -> p.seed().seedId().equals("seed:S2")).singleElement().satisfies(p -> {
            assertThat(p.observedCount()).isEqualTo(4);
            assertThat(p.lastReferencedAt()).isEqualTo(clock.now());
        });
    }
}
