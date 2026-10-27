package kr.mujoong.core.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("ai-usage")
class AiUsageScopeTest {

    private static AiUsageLink link() {
        return new AiUsageLink(UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void theLinkIsVisibleOnlyInsideTheCallAndNestedCallsRestoreTheOuterLink() {
        AiUsageLink outer = link();
        AiUsageLink inner = link();

        AiUsageScope.call(outer, () -> {
            assertThat(AiUsageScope.current()).contains(outer);
            AiUsageScope.call(inner, () -> assertThat(AiUsageScope.current()).contains(inner));
            assertThat(AiUsageScope.current()).contains(outer);
            return null;
        });

        assertThat(AiUsageScope.current()).isEmpty();
    }

    @Test
    void theLinkIsClearedEvenWhenTheWorkThrows() {
        assertThatThrownBy(() -> AiUsageScope.call(link(), () -> {
            throw new IllegalStateException("업무 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(AiUsageScope.current()).isEmpty();
    }

    @Test
    void propagateCarriesTheLinkToAnExecutorThreadAndLeavesThatThreadClean() throws Exception {
        AiUsageLink link = link();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Optional<AiUsageLink> seen = AiUsageScope.call(link, () -> {
                try {
                    return executor.submit(AiUsageScope.propagate(AiUsageScope::current)).get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            Optional<AiUsageLink> afterwards = executor.submit(AiUsageScope::current).get();

            assertThat(seen).contains(link);
            assertThat(afterwards).as("같은 실행기 스레드의 다음 작업에 남지 않는다").isEmpty();
        }
    }

    @Test
    void withoutALinkPropagateReturnsTheTaskUnchanged() {
        java.util.concurrent.Callable<String> task = () -> "결과";

        assertThat(AiUsageScope.propagate(task)).isSameAs(task);
    }
}
