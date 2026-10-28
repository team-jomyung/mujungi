package kr.mujoong.core.practice;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.knowledge.ChildKnowledgeService;
import kr.mujoong.core.knowledge.ChildKnowledgeService.ReferenceSeed;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

/** 참고 씨앗 조회가 실패하거나 느려도 연습 시작은 막지 않고, 전체 상황 생성 마감만은 그대로 지킨다. */
@Tag("scenario-generation")
class ReferenceSeedSelectorTest {
    private static final Duration WEEK = Duration.ofDays(7);
    final ChildKnowledgeService knowledge = mock(ChildKnowledgeService.class);
    final MutableClock clock = new MutableClock();
    final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final UUID guardian = UUID.randomUUID(), child = UUID.randomUUID();
    final List<UUID> asked = new ArrayList<>();
    final ReferenceSeedSelector selector = selector(Duration.ofSeconds(2));

    ReferenceSeedSelector selector(Duration budget) {
        return new ReferenceSeedSelector(knowledge, id -> { asked.add(id); return Set.of("seed:S2", "seed:S3"); }, executor, budget, WEEK);
    }
    @BeforeEach void enabled() { when(knowledge.enabled()).thenReturn(true); }
    @AfterEach void close() { executor.shutdownNow(); }
    ScenarioDeadline deadline() { return new ScenarioDeadline(clock, clock.now().plusSeconds(15)); }

    @Test @SuppressWarnings("unchecked")
    void passesTheSavedGoalSeedsLazilyWithTheCooldown() {
        var reference = new ReferenceSeed("seed:S2", "불편을 말했지만 원한 건 행동이었다");
        var eligible = ArgumentCaptor.forClass(Supplier.class);
        when(knowledge.referenceSeed(eq(guardian), eq(child), eq("seed:S1"), eligible.capture(), eq(WEEK))).thenReturn(Optional.of(reference));

        assertThat(selector.select(guardian, child, "seed:S1", deadline())).contains(reference);
        // 담은 목표 조회는 그래프 서비스가 권한을 확인한 뒤 공급자를 부를 때만 일어난다.
        assertThat(asked).isEmpty();
        assertThat(eligible.getValue().get()).isEqualTo(Set.of("seed:S2", "seed:S3"));
        assertThat(asked).containsExactly(child);
    }

    @Test void aDisabledGraphIsNotAsked() {
        when(knowledge.enabled()).thenReturn(false);

        assertThat(selector.select(guardian, child, "seed:S1", deadline())).isEmpty();
        verify(knowledge, never()).referenceSeed(any(), any(), any(), any(), any());
    }

    @Test void aFailedLookupFallsBackToNoReference() {
        when(knowledge.referenceSeed(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("graph unavailable"));

        assertThat(selector.select(guardian, child, "seed:S1", deadline())).isEmpty();
    }

    @Test void aLookupSlowerThanItsBudgetFallsBackWhileTheScenarioDeadlineRemains() {
        when(knowledge.referenceSeed(any(), any(), any(), any(), any())).thenAnswer(call -> {
            Thread.sleep(Duration.ofSeconds(5));
            return Optional.of(new ReferenceSeed("seed:S2", "늦은 응답"));
        });

        assertThat(selector(Duration.ofMillis(50)).select(guardian, child, "seed:S1", deadline())).isEmpty();
    }

    @Test void theOverallScenarioDeadlineStillFails() {
        when(knowledge.referenceSeed(any(), any(), any(), any(), any())).thenAnswer(call -> {
            clock.advance(Duration.ofSeconds(15));
            return Optional.empty();
        });

        assertThatThrownBy(() -> selector.select(guardian, child, "seed:S1", deadline())).isInstanceOf(CoreApiException.class);
    }

    @Test void usingASeedRequestsTheReferenceMark() {
        selector.used(guardian, child, new ReferenceSeed("seed:S3", "합성 문장"));

        verify(knowledge).requestReferenceMark(guardian, child, "seed:S3");
    }
}
