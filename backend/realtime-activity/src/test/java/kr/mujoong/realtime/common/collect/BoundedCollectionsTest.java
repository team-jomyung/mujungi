package kr.mujoong.realtime.common.collect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 상한 집합 · 지도 시험(2차 리뷰 8): 넣은 순서를 지키고 상한을 넘으면 가장 먼저 넣은 것부터 잊는다. */
@Tag("voice-session")
class BoundedCollectionsTest {

    @Test
    void aMapForgetsTheEldestEntryOnceItIsOverTheLimit() {
        Map<String, Integer> map = BoundedCollections.map(2);
        map.put("a", 1);
        map.put("b", 2);
        map.put("c", 3);

        assertThat(map).containsOnlyKeys("b", "c");
        assertThat(map.keySet()).containsExactly("b", "c");
    }

    @Test
    void setsKeepTheLatestElementsAndTheSynchronizedOneBehavesTheSame() {
        Set<Integer> plain = BoundedCollections.set(3);
        Set<Integer> shared = BoundedCollections.synchronizedSet(3);
        for (int i = 1; i <= 5; i++) {
            plain.add(i);
            shared.add(i);
        }

        assertThat(plain).containsExactly(3, 4, 5);
        assertThat(shared).containsExactlyInAnyOrder(3, 4, 5);
        assertThat(plain.add(5)).as("이미 있는 값은 순서를 바꾸지 않는다").isFalse();
    }

    @Test
    void aLimitBelowOneIsRejected() {
        assertThatThrownBy(() -> BoundedCollections.map(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BoundedCollections.set(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
