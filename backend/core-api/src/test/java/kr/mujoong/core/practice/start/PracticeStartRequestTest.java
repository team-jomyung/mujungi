package kr.mujoong.core.practice.start;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import kr.mujoong.core.common.error.CoreApiException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("practice-legacy")
class PracticeStartRequestTest {
    @Test void acceptsThreeGoalsButRejectsFourBeforePreparation() {
        List<UUID> ids=java.util.stream.IntStream.range(0,4).mapToObj(i->UUID.randomUUID()).toList();
        assertThat(new PracticeStartRequest(UUID.randomUUID(),ids.subList(0,3)).goalSelectionIds()).hasSize(3);
        assertThatThrownBy(()->new PracticeStartRequest(UUID.randomUUID(),ids)).isInstanceOf(CoreApiException.class);
    }
    @Test void reorderingTheSameSelectionDoesNotChangeIdempotencyIdentity() {
        UUID key = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
        assertThat(new PracticeStartRequest(key, List.of(a, b)).requestHash())
                .isEqualTo(new PracticeStartRequest(key, List.of(b, a)).requestHash());
        assertThat(new PracticeStartRequest(key, List.of(a)).requestHash())
                .isNotEqualTo(new PracticeStartRequest(key, List.of(b)).requestHash());
    }
    @Test void malformedSelectionCannotEnterPreparation() {
        UUID key = UUID.randomUUID(), goal = UUID.randomUUID();
        assertThatThrownBy(() -> new PracticeStartRequest(key, List.of())).isInstanceOf(CoreApiException.class);
        assertThatThrownBy(() -> new PracticeStartRequest(key, List.of(goal, goal))).isInstanceOf(CoreApiException.class);
        assertThatThrownBy(() -> new PracticeStartRequest(null, List.of(goal))).isInstanceOf(CoreApiException.class);
        assertThatThrownBy(() -> new PracticeStartRequest(key, null)).isInstanceOf(CoreApiException.class);
    }
}
