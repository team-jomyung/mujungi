package kr.mujoong.core.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.FailureCode;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.core.activity.ActivityCopyRepository.ActivityCopy;
import kr.mujoong.core.activity.RealtimeSessionClient.InternalSessionView;
import kr.mujoong.core.activity.RealtimeSessionClient.ViewKind;
import kr.mujoong.core.activity.RealtimeSessionClient.ViewResult;
import kr.mujoong.core.activity.SessionViewService.LiveView;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.support.TestEvents;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 12 진행 조회 (BR6.15 · RD-6 · NFR4.3 · BR8.5 · OD-5): Realtime 응답 중계 · 실패 때 사본 + 빈 items + 요청 sinceSeq · 폴백 카운터 ·
 * observedAt 기준 30초 stale(조회 한 번 실패만으로는 아님) · 종료 세션 stale 아님 · 남의 세션 404 · 음수 sinceSeq 400.
 */
@Tag("skeleton")
class SessionViewServiceTest {

    private static final Instant REQUESTED_AT = Instant.parse("2001-05-01T09:00:00Z");
    private static final Duration STALE = Duration.ofSeconds(30);

    private final MutableClock clock = new MutableClock(REQUESTED_AT);
    private final TestEvents events = new TestEvents(clock);
    private final ActivityCopyRepository activities = mock(ActivityCopyRepository.class);
    private final RealtimeSessionClient realtime = mock(RealtimeSessionClient.class);
    private final SessionViewService service = new SessionViewService(activities, realtime,
            new StaleEvaluator(STALE, events.metrics(), events.logger()), events.metrics(), clock);
    private final UUID guardianId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    @Test
    void relaysRealtimeItemsAndNextSeqAndMarksObservedNow() {
        owned(ActivityCopies.copy(sessionId, guardianId, SessionStatus.ACTIVE, 3, REQUESTED_AT));
        clock.advance(Duration.ofSeconds(5));
        List<Map<String, Object>> items = List.of(Map.of("seq", 1, "speaker", "CHILD", "final", true));
        when(realtime.view(sessionId, 0)).thenReturn(ok(SessionStatus.ACTIVE, 4, items, 1L));

        LiveView view = service.view(guardianId, sessionId, 0);

        assertThat(view.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(view.statusVersion()).isEqualTo(4);
        assertThat(view.items()).isEqualTo(items);
        assertThat(view.nextSeq()).isEqualTo(1);
        assertThat(view.gaps()).isEmpty();
        assertThat(view.observedAt()).isEqualTo(clock.now());
        assertThat(view.serverTime()).isEqualTo(clock.now());
        assertThat(view.stale()).isFalse();
        assertThat(view.toString()).doesNotContain("CHILD");
        assertThat(events.metrics().count("mujoong.session.view.fallback")).isZero();
    }

    @Test
    void realtimeFailureFallsBackToCopyWithEmptyItemsAndRequestedSinceSeq() {
        ActivityCopy copy = ActivityCopies.copy(sessionId, guardianId, SessionStatus.ACTIVE, 3, REQUESTED_AT);
        owned(copy);
        when(realtime.view(sessionId, 7)).thenReturn(new ViewResult(ViewKind.UNAVAILABLE, null));

        LiveView view = service.view(guardianId, sessionId, 7);

        assertThat(view.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(view.statusVersion()).isEqualTo(3);
        assertThat(view.items()).isEmpty();
        assertThat(view.nextSeq()).isEqualTo(7);
        assertThat(view.requestedAt()).isEqualTo(REQUESTED_AT);
        assertThat(events.metrics().count("mujoong.session.view.fallback")).isEqualTo(1.0);
    }

    @Test
    void oneFailedViewIsNotStaleButThirtySecondsWithoutAnswerIs() {
        owned(ActivityCopies.copy(sessionId, guardianId, SessionStatus.ACTIVE, 3, REQUESTED_AT));
        when(realtime.view(sessionId, 0)).thenReturn(ok(SessionStatus.ACTIVE, 3, List.of(), 0L));
        Instant observed = clock.now();
        assertThat(service.view(guardianId, sessionId, 0).observedAt()).isEqualTo(observed);

        when(realtime.view(sessionId, 0)).thenReturn(new ViewResult(ViewKind.UNAVAILABLE, null));
        clock.advance(STALE);
        LiveView atThreshold = service.view(guardianId, sessionId, 0);
        clock.advance(Duration.ofMillis(1));
        LiveView pastThreshold = service.view(guardianId, sessionId, 0);

        assertThat(atThreshold.stale()).isFalse();
        assertThat(atThreshold.observedAt()).isEqualTo(observed);
        assertThat(pastThreshold.stale()).isTrue();
        assertThat(pastThreshold.observedAt()).isEqualTo(observed);
    }

    @Test
    void terminalSessionIsNeverStaleAndUnknownEnumsAreDropped() {
        ActivityCopy ended = new ActivityCopy(sessionId, guardianId, UUID.randomUUID(), UUID.randomUUID(),
                ActivityType.FREE_TALK, SessionStatus.ENDED, 6, "SOMETHING_NEW", "GUARDIAN_STOP", true, false, null, "k",
                "h", REQUESTED_AT, REQUESTED_AT.plusSeconds(3), REQUESTED_AT.plusSeconds(60), null, REQUESTED_AT,
                REQUESTED_AT);
        owned(ended);
        when(realtime.view(sessionId, 0)).thenReturn(new ViewResult(ViewKind.NOT_FOUND, null));
        clock.advance(Duration.ofHours(1));

        LiveView view = service.view(guardianId, sessionId, 0);

        assertThat(view.status()).isEqualTo(SessionStatus.ENDED);
        assertThat(view.stale()).isFalse();
        assertThat(view.endReason()).isNull();
        assertThat(view.failureCode()).isNull();
        assertThat(view.confirmed()).isTrue();
        assertThat(view.observedAt()).isNull();
    }

    @Test
    void realtimeUnrecognizedValuesAreNotForwarded() {
        owned(ActivityCopies.copy(sessionId, guardianId, SessionStatus.CLOSING, 5, REQUESTED_AT));
        when(realtime.view(sessionId, 0)).thenReturn(new ViewResult(ViewKind.OK, new InternalSessionView(sessionId, null,
                SessionStatus.ENDED, EndReason.UNRECOGNIZED, FailureCode.UNRECOGNIZED, true, null, null, null, 6L,
                clock.now(), null, null)));

        LiveView view = service.view(guardianId, sessionId, 0);

        assertThat(view.endReason()).isNull();
        assertThat(view.failureCode()).isNull();
        assertThat(view.activityType()).isEqualTo(ActivityType.FREE_TALK);
        assertThat(view.requestedAt()).isEqualTo(REQUESTED_AT);
        assertThat(view.items()).isEmpty();
        assertThat(view.nextSeq()).isZero();
    }

    @Test
    void otherGuardiansSessionIsNotFoundWithoutCallingRealtime() {
        when(activities.findOwned(sessionId, guardianId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.view(guardianId, sessionId, 0)).isInstanceOfSatisfying(CoreApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        verify(realtime, never()).view(any(), anyLong());
    }

    @Test
    void negativeSinceSeqIsValidationFailure() {
        assertThatThrownBy(() -> service.view(guardianId, sessionId, -1)).isInstanceOfSatisfying(CoreApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(activities, never()).findOwned(any(), any());
    }

    @Test
    void parseReadsKnownValuesAndDropsUnknownOrMissing() {
        assertThat(SessionViewService.parse(EndReason.class, "GUARDIAN_STOP")).isEqualTo(EndReason.GUARDIAN_STOP);
        assertThat(SessionViewService.parse(EndReason.class, "UNRECOGNIZED")).isNull();
        assertThat(SessionViewService.parse(EndReason.class, "NOT_A_REASON")).isNull();
        assertThat(SessionViewService.parse(FailureCode.class, null)).isNull();
    }

    private void owned(ActivityCopy copy) {
        when(activities.findOwned(copy.sessionId(), copy.guardianId())).thenReturn(Optional.of(copy));
    }

    private ViewResult ok(SessionStatus status, long statusVersion, List<Map<String, Object>> items, Long nextSeq) {
        return new ViewResult(ViewKind.OK, new InternalSessionView(sessionId, ActivityType.FREE_TALK, status, null, null,
                false, REQUESTED_AT, null, null, statusVersion, clock.now(), items, nextSeq));
    }
}
