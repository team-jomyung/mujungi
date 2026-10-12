package kr.mujoong.core.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.*;

@Tag("u5a")
class PragmaticsKnowledgeTest {
    private final KnowledgeJdbcRepository repository = mock(KnowledgeJdbcRepository.class);
    private final MutableClock clock = new MutableClock();
    private PragmaticsKnowledge service() {
        return new PragmaticsKnowledge(repository,clock,new KnowledgeProperties(false),Optional.empty());
    }
    private BehaviorDefinition definition(String status, String criteria) {
        return new BehaviorDefinition(UUID.randomUUID(),"test",1,"목표","행동","기회","상황",criteria,status,clock.now(),null);
    }
    @Test void completeActiveVersionCanBeUsedWithoutChangingVersion() {
        var definition = definition("ACTIVE","판정");
        when(repository.findVersion("test",1)).thenReturn(Optional.of(definition));
        when(repository.markUsed("test",1,clock.now())).thenReturn(1);
        assertThat(service().requireActive("test",1)).isEqualTo(definition);
        service().markUsed("test",1);
        verify(repository).markUsed("test",1,clock.now());
    }
    @Test void incompleteMissingAndDraftVersionsAreUnavailable() {
        when(repository.findVersion("missing",1)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().requireActive("missing",1)).isInstanceOf(IllegalArgumentException.class);
        for (BehaviorDefinition definition : List.of(definition("ACTIVE"," "),definition("DRAFT","판정"))) {
            when(repository.findVersion("test",1)).thenReturn(Optional.of(definition));
            assertThatThrownBy(() -> service().requireActive("test",1)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(definition("ACTIVE",null).complete()).isFalse();
    }
    @Test void retiredVersionRemainsAvailableOnlyToPinnedReaders() {
        var definition = definition("RETIRED","판정");
        when(repository.findVersion("test",1)).thenReturn(Optional.of(definition));
        assertThat(service().requireVersion("test",1)).isEqualTo(definition);
        assertThatThrownBy(() -> service().requireActive("test",1)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void concurrentRetirementMakesUseFailRatherThanCommitInvalidReference() {
        when(repository.findVersion("test",1)).thenReturn(Optional.of(definition("ACTIVE","판정")));
        when(repository.markUsed("test",1,clock.now())).thenReturn(0);
        assertThatThrownBy(() -> service().markUsed("test",1)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void supportRetainsConditionAndLimitation() {
        var item = new SupportItem(UUID.randomUUID(),"test","적용 조건","도움말","한계");
        when(repository.findApprovedSupport("test")).thenReturn(List.of(item));
        assertThat(service().supportItems("test")).containsExactly(item);
        assertThat(service().supportItems("test").getFirst().limitationText()).isEqualTo("한계");
    }
    @Test void disabledGraphNeverCallsAdapterAndEnabledGraphNeedsOne() {
        KnowledgeGraphLookup graph = mock(KnowledgeGraphLookup.class);
        var disabled = new PragmaticsKnowledge(repository,clock,new KnowledgeProperties(false),Optional.of(graph));
        assertThat(disabled.relatedBehaviorKeys("test")).isEmpty();
        verifyNoInteractions(graph);
        assertThatThrownBy(() -> new PragmaticsKnowledge(repository,clock,new KnowledgeProperties(true),Optional.empty()))
                .isInstanceOf(IllegalStateException.class);
        when(graph.findRelatedBehaviorKeys("test")).thenReturn(List.of("other"));
        var enabled = new PragmaticsKnowledge(repository,clock,new KnowledgeProperties(true),Optional.of(graph));
        assertThat(enabled.relatedBehaviorKeys("test")).containsExactly("other");
    }

    @Test void pinAcceptsHistoricalRetiredVersionAndReturnsFrozenSnapshot() {
        var retired=definition("RETIRED","판정");
        when(repository.lockVersion("test",1)).thenReturn(Optional.of(retired));
        when(repository.markPinned("test",1,clock.now())).thenReturn(1);
        when(repository.findVersion("test",1)).thenReturn(Optional.of(retired));
        assertThat(service().pinVersion("test",1)).isEqualTo(retired);
        verify(repository).lockVersion("test",1);
        verify(repository).markPinned("test",1,clock.now());
    }
    @Test void pinRejectsMissingIncompleteDraftAndFailedUpdate() {
        when(repository.lockVersion("test",1)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().pinVersion("test",1)).isInstanceOf(IllegalArgumentException.class);
        for(var definition: List.of(definition("DRAFT","판정"),definition("ACTIVE"," "))) {
            when(repository.lockVersion("test",1)).thenReturn(Optional.of(definition));
            assertThatThrownBy(() -> service().pinVersion("test",1)).isInstanceOf(IllegalArgumentException.class);
        }
        when(repository.lockVersion("test",1)).thenReturn(Optional.of(definition("ACTIVE","판정")));
        when(repository.markPinned("test",1,clock.now())).thenReturn(0);
        assertThatThrownBy(() -> service().pinVersion("test",1)).isInstanceOf(IllegalArgumentException.class);
    }
}
