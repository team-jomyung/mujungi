package kr.mujoong.core.knowledge;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u15")
class U15GraphModelTest {
    static final StructureSignature SIGNATURE = new StructureSignature("RECOGNIZE", "말 뒤의 의도",
            "상대의 의도 1층", "YES", "YES");
    static KnowledgeSeed seed(String id, KnowledgeSeed.Origin origin) {
        return new KnowledgeSeed(id, origin, "의도 이해", SIGNATURE, "상대의 뜻을 확인한다", "OTHER",
                "의도 확인을 보존한다", "CLASSMATE", "SCHOOL_GROUP", true);
    }
    static SeedCatalog catalog() {
        return new SeedCatalog("v1", "a".repeat(64), List.of(seed("seed:S1", KnowledgeSeed.Origin.POC)));
    }
    @Test void validatesSignature() { assertThat(SIGNATURE.targetSkill()).isEqualTo("RECOGNIZE"); }
    @Test void rejectsUnknownTarget() {
        assertThatThrownBy(() -> new StructureSignature("UNKNOWN", "의도", "상대의 의도 1층", "YES", "YES"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsUnknownPerspective() {
        assertThatThrownBy(() -> new StructureSignature("REPAIR", "의도", "unknown", "YES", "YES"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void generatedIdentifierMustBeGenerated() {
        assertThatThrownBy(() -> seed("seed:S1", KnowledgeSeed.Origin.GENERATED))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void generatedLengthCountsCodePoints() {
        var s = seed("seed:G0001", KnowledgeSeed.Origin.GENERATED);
        assertThat(new KnowledgeSeed(s.seedId(), s.origin(), s.label(), s.signature(), "😀".repeat(60),
                s.family(), s.preservationRule(), s.relationship(), s.situation(), true).recoverable()).isTrue();
        assertThatThrownBy(() -> new KnowledgeSeed(s.seedId(), s.origin(), s.label(), s.signature(), "😀".repeat(61),
                s.family(), s.preservationRule(), s.relationship(), s.situation(), true))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void catalogRejectsDuplicateIds() {
        var s = seed("seed:S1", KnowledgeSeed.Origin.POC);
        assertThatThrownBy(() -> new SeedCatalog("v1", "a".repeat(64), List.of(s, s)))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void catalogIsImmutable() {
        assertThatThrownBy(() -> catalog().seeds().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void representationDoesNotExposeText() {
        assertThat(seed("seed:G0001", KnowledgeSeed.Origin.GENERATED).toString()).doesNotContain("의도", "확인");
    }

    @Test void writesAreInvisibleUntilCommitAndIsolated() {
        var graph = new MemoryChildKnowledgeGraph();
        UUID child = UUID.randomUUID();
        String tx = graph.begin();
        graph.replaceBase(tx, child, catalog());
        assertThat(graph.seeds(child)).isEmpty();
        graph.commit(tx);
        assertThat(graph.seeds(child)).hasSize(1);
        assertThat(graph.seeds(UUID.randomUUID())).isEmpty();
    }
    @Test void rollbackDiscardsWrites() {
        var graph = new MemoryChildKnowledgeGraph();
        UUID child = UUID.randomUUID();
        String tx = graph.begin();
        graph.replaceBase(tx, child, catalog());
        assertThat(graph.terminate(tx)).isTrue();
        assertThat(graph.seeds(child)).isEmpty();
        assertThatThrownBy(() -> graph.commit(tx)).isInstanceOf(IllegalStateException.class);
    }
    @Test void reloadPreservesGenerated() {
        var graph = new MemoryChildKnowledgeGraph();
        UUID child = UUID.randomUUID();
        String tx = graph.begin();
        graph.writeGeneratedSeed(tx, child, seed("seed:G0001", KnowledgeSeed.Origin.GENERATED));
        graph.replaceBase(tx, child, catalog());
        graph.commit(tx);
        tx = graph.begin();
        graph.replaceBase(tx, child, catalog());
        graph.commit(tx);
        assertThat(graph.seeds(child)).hasSize(2);
    }
    @Test void rejectsVersionCollision() {
        var graph = new MemoryChildKnowledgeGraph();
        UUID child = UUID.randomUUID();
        String tx = graph.begin();
        graph.replaceBase(tx, child, catalog());
        graph.commit(tx);
        String next = graph.begin();
        assertThatThrownBy(() -> graph.replaceBase(next, child, new SeedCatalog("v1", "b".repeat(64), catalog().seeds())))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void competingVersionsCannotOverwriteCommittedDigest() {
        var graph = new MemoryChildKnowledgeGraph();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        String a = graph.begin(), b = graph.begin();
        graph.replaceBase(a, first, catalog());
        graph.replaceBase(b, second, new SeedCatalog("v1", "b".repeat(64), catalog().seeds()));
        graph.commit(a);
        assertThatThrownBy(() -> graph.commit(b)).isInstanceOf(IllegalArgumentException.class);
        assertThat(graph.seeds(second)).isEmpty();
        graph.terminate(b);
    }
    @Test void deletionIsChildScopedAndIdempotent() {
        var graph = new MemoryChildKnowledgeGraph();
        UUID child = UUID.randomUUID(), other = UUID.randomUUID();
        String tx = graph.begin();
        graph.replaceBase(tx, child, catalog());
        graph.replaceBase(tx, other, catalog());
        graph.commit(tx);
        tx = graph.begin();
        graph.deleteChild(tx, child);
        graph.deleteChild(tx, child);
        graph.commit(tx);
        assertThat(graph.seeds(child)).isEmpty();
        assertThat(graph.seeds(other)).hasSize(1);
    }
    @Test void deletesOnlyRequestedSeeds() {
        var graph = new MemoryChildKnowledgeGraph();
        UUID child = UUID.randomUUID();
        String tx = graph.begin();
        graph.replaceBase(tx, child, catalog());
        graph.writeGeneratedSeed(tx, child, seed("seed:G0001", KnowledgeSeed.Origin.GENERATED));
        graph.commit(tx);
        tx = graph.begin();
        graph.deleteSeeds(tx, child, List.of("seed:G0001"));
        graph.commit(tx);
        assertThat(graph.seeds(child)).extracting(KnowledgeSeed::seedId).containsExactly("seed:S1");
    }
}
