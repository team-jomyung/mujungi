package kr.mujoong.realtime.session.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.realtime.log.SessionGapRepository;
import kr.mujoong.realtime.session.ActivitySessionRow;
import kr.mujoong.realtime.session.SessionViewQuery;
import kr.mujoong.realtime.testing.InMemoryTransitions;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 내부 LiveView 선 형식의 U3 몫: 끊김 구간 {@code gaps}(계약 live-view Gap: from · to · kind, AC3.2.10(b) · U3-J18). */
@Tag("u3")
class SessionInternalControllerU3Test {

    private static final Instant T0 = Instant.parse("2026-10-15T09:00:00Z");

    @Test
    void gapsAreWiredAsFromToKindWithAnOpenGapCarryingANullEnd() {
        MutableClock clock = new MutableClock(T0);
        ActivitySessionRow session = new InMemoryTransitions(clock).seed(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID());
        UUID closedId = UUID.randomUUID();
        UUID openId = UUID.randomUUID();
        SessionViewQuery.View view = new SessionViewQuery.View(session, T0.plusSeconds(60), List.of(), 0, List.of(
                new SessionGapRepository.SessionGapRow(closedId, session.id(), SessionGapRepository.Kind.DISCONNECT, T0,
                        T0.plusSeconds(7)),
                new SessionGapRepository.SessionGapRow(openId, session.id(), SessionGapRepository.Kind.DISCONNECT,
                        T0.plusSeconds(30), null)));

        Map<String, Object> wire = SessionInternalController.wire(view);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> gaps = (List<Map<String, Object>>) wire.get("gaps");
        assertThat(gaps).hasSize(2);
        assertThat(gaps.get(0)).containsExactly(Map.entry("from", T0), Map.entry("to", T0.plusSeconds(7)),
                Map.entry("kind", SessionGapRepository.Kind.DISCONNECT));
        assertThat(gaps.get(1)).containsEntry("from", T0.plusSeconds(30)).containsEntry("to", null)
                .containsEntry("kind", SessionGapRepository.Kind.DISCONNECT);
        // 구간 id · 전사는 선 형식에 없다
        assertThat(gaps.get(0)).doesNotContainKeys("id", "sessionId");
    }

    @Test
    void aViewWithoutGapsWiresAnEmptyArray() {
        MutableClock clock = new MutableClock(T0);
        ActivitySessionRow session = new InMemoryTransitions(clock).seed(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID());

        Map<String, Object> wire = SessionInternalController.wire(
                new SessionViewQuery.View(session, T0, List.of(), 0, null));

        assertThat(wire.get("gaps")).isEqualTo(List.of());
    }
}
