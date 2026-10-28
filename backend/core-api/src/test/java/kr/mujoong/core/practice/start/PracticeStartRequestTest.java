package kr.mujoong.core.practice.start;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import kr.mujoong.core.common.error.CoreApiException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("practice-legacy")
class PracticeStartRequestTest {
    /** FT-1027-a: 한 번에 목표 1개(이전 3개). 상한을 넘으면 준비 전에 거절한다. */
    @Test void acceptsOneGoalButRejectsTwoBeforePreparation() {
        List<UUID> ids=java.util.stream.IntStream.range(0,2).mapToObj(i->UUID.randomUUID()).toList();
        assertThat(new PracticeStartRequest(UUID.randomUUID(),ids.subList(0,1)).goalSelectionIds()).hasSize(1);
        assertThatThrownBy(()->new PracticeStartRequest(UUID.randomUUID(),ids)).isInstanceOf(CoreApiException.class);
    }
    @Test void theSameSelectionKeepsItsIdempotencyIdentity() {
        UUID key = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
        assertThat(new PracticeStartRequest(key, List.of(a)).requestHash())
                .isEqualTo(new PracticeStartRequest(key, List.of(a)).requestHash());
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
