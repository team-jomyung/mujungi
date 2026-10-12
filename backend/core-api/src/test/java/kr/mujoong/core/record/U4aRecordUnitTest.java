package kr.mujoong.core.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.event.payload.SessionRecordCaptured;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.core.activity.ActivityCopyRepository;
import kr.mujoong.core.activity.ActivityCopyRepository.ActivityCopy;
import kr.mujoong.core.messaging.CoreInboxRepository.InboxEvent;
import kr.mujoong.core.messaging.handler.InboxHandler.Result;
import kr.mujoong.core.support.TestEvents;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * U4a ActivityRecord 단위 시험: 기록 수신 분기(사본 없음 · 남의 사본 · 해시 불일치 · 멱등 · 충돌 · DECLINED, 설계 R5 · R6)와 26 날짜 경계(R7 ·
 * U4a-J8).
 */
@Tag("u4a")
class U4aRecordUnitTest {

    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
    private static final JsonCodec JSON = JsonCodec.shared();

    private final ActivityCopyRepository activities = mock(ActivityCopyRepository.class);
    private final ActivityRecordRepository records = mock(ActivityRecordRepository.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final TestEvents events = new TestEvents(new MutableClock(NOW));
    private final SessionRecordCapturedHandler handler = new SessionRecordCapturedHandler(activities, records, publisher,
            events.logger(), JSON);

    private final UUID sessionId = UUID.randomUUID();
    private final UUID guardianId = UUID.randomUUID();
    private final UUID childId = UUID.randomUUID();

    @Test
    void anEventWithoutAMatchingCopyIsDroppedWithoutStoring() {
        when(activities.findById(sessionId)).thenReturn(Optional.empty());
        assertThat(handler.handle(event(captured(SessionStatus.ENDED, record(), null)), NOW)).isEqualTo(Result.done());

        when(activities.findById(sessionId)).thenReturn(Optional.of(copy(UUID.randomUUID(), childId)));
        handler.handle(event(captured(SessionStatus.ENDED, record(), null)), NOW);
        when(activities.findById(sessionId)).thenReturn(Optional.of(copy(guardianId, UUID.randomUUID())));
        handler.handle(event(captured(SessionStatus.ENDED, record(), null)), NOW);

        verifyNoInteractions(records, publisher);
    }

    @Test
    void aHashThatDoesNotMatchTheRecordFailsTheHandlerWithoutLeakingContent() {
        when(activities.findById(sessionId)).thenReturn(Optional.of(copy(guardianId, childId)));

        assertThatThrownBy(() -> handler.handle(event(captured(SessionStatus.ENDED, record(), "sha256:" + "0".repeat(64))), NOW))
                .isInstanceOf(SessionRecordCapturedHandler.SnapshotHashMismatchException.class)
                .hasMessageNotContaining("블록");
        verifyNoInteractions(records, publisher);
    }

    @Test
    void theFirstStorePublishesOnceTheSameHashIsSilentAndADifferentHashIsLoggedAsAConflict() {
        when(activities.findById(sessionId)).thenReturn(Optional.of(copy(guardianId, childId)));
        SessionRecordCaptured payload = captured(SessionStatus.ENDED, record(), null);
        when(records.insertIfAbsent(any(), any())).thenReturn(Optional.empty())
                .thenReturn(Optional.of(payload.snapshotHash()))
                .thenReturn(Optional.of("sha256:" + "1".repeat(64)));

        handler.handle(event(payload), NOW);
        handler.handle(event(payload), NOW);
        handler.handle(event(payload), NOW);

        verify(publisher).publishEvent(new ActivityRecorded(sessionId, guardianId, childId, "FREE_TALK", "ENDED", NOW));
        assertThat(events.capture().events(EventCode.APP_ERROR)).singleElement()
                .satisfies(line -> assertThat(kr.mujoong.testsupport.log.LogCapture.attrs(line))
                        .containsEntry("errorKind", "EventConflict"));
    }

    @Test
    void aDeclinedRecordKeepsOnlyTheHeaderAndListsAtTheRequestTime() {
        when(activities.findById(sessionId)).thenReturn(Optional.of(copy(guardianId, childId)));
        when(records.insertIfAbsent(any(), any())).thenReturn(Optional.empty());
        org.mockito.ArgumentCaptor<ActivityRecordRepository.NewRecord> stored =
                org.mockito.ArgumentCaptor.forClass(ActivityRecordRepository.NewRecord.class);

        handler.handle(event(captured(SessionStatus.DECLINED, record(), null)), NOW);

        verify(records).insertIfAbsent(stored.capture(), any());
        assertThat(stored.getValue().transcriptJson()).isEqualTo("[]");
        assertThat(stored.getValue().gapsJson()).isEqualTo("[]");
        assertThat(stored.getValue().audioRefsJson()).isEqualTo("[]");
        assertThat(stored.getValue().practiceJson()).isNull();
        assertThat(stored.getValue().recordFrom()).isNull();
        assertThat(stored.getValue().listedAt()).as("시작 시각이 없으면 사본 요청 시각").isEqualTo(NOW.minusSeconds(600));
        assertThat(stored.getValue().toString()).doesNotContain("블록");
    }

    @Test
    void anInvalidPayloadIsIgnored() {
        SessionRecordCaptured invalid = new SessionRecordCaptured(sessionId, guardianId, childId, ActivityType.FREE_TALK,
                SessionStatus.ACTIVE, null, true, NOW, NOW, null, UUID.randomUUID(), 1, "sha256:" + "0".repeat(64), null);
        handler.handle(event(invalid), NOW);
        verify(activities, never()).findById(any());
    }

    @Test
    void calendarBoundariesAreSeoulMidnightsAndArgumentsAreStrict() {
        RecordCalendar.Range october = RecordCalendar.of(YearMonth.of(2026, 10));
        assertThat(october.from()).isEqualTo(Instant.parse("2026-09-30T15:00:00Z"));
        assertThat(october.to()).isEqualTo(Instant.parse("2026-10-31T15:00:00Z"));
        RecordCalendar.Range december = RecordCalendar.of(YearMonth.of(2026, 12));
        assertThat(december.to()).isEqualTo(Instant.parse("2026-12-31T15:00:00Z"));
        RecordCalendar.Range day = RecordCalendar.of(LocalDate.of(2026, 10, 15));
        assertThat(day.from()).isEqualTo(Instant.parse("2026-10-14T15:00:00Z"));
        assertThat(day.to()).isEqualTo(Instant.parse("2026-10-15T15:00:00Z"));

        assertThat(RecordCalendar.month("2026-10")).contains(YearMonth.of(2026, 10));
        for (String bad : new String[] {"2026-13", "2026-1", "26-10", "2026-10-01", "", " 2026-10"}) {
            assertThat(RecordCalendar.month(bad)).as(bad).isEmpty();
        }
        assertThat(RecordCalendar.month(null)).isEmpty();
        assertThat(RecordCalendar.date("2026-02-28")).contains(LocalDate.of(2026, 2, 28));
        for (String bad : new String[] {"2026-02-30", "2026-2-1", "2026-02", "x"}) {
            assertThat(RecordCalendar.date(bad)).as(bad).isEmpty();
        }
        assertThat(RecordCalendar.date(null)).isEmpty();
    }

    // ------------------------------------------------------------------ 도움

    private static Map<String, Object> record() {
        return Map.of(
                "utterances", List.of(Map.of("seq", 1, "speaker", "CHILD", "text", "블록 쌓았어", "uncertain", false,
                        "startedAt", "2026-10-14T16:11:00Z")),
                "gaps", List.of(),
                "recordScope", Map.of("from", "2026-10-14T16:10:00Z", "to", "2026-10-14T16:20:00Z"),
                "audio", List.of(Map.of("recordingId", "r1", "storagePath", "audio/r1.wav")));
    }

    private SessionRecordCaptured captured(SessionStatus status, Map<String, Object> record, String hash) {
        String snapshotHash = hash != null ? hash : "sha256:" + JSON.sha256OfCanonical(JSON.write(record));
        return new SessionRecordCaptured(sessionId, guardianId, childId, ActivityType.FREE_TALK, status, null, true,
                status == SessionStatus.DECLINED ? null : NOW.minusSeconds(300), NOW, null, UUID.randomUUID(), 1,
                snapshotHash, record);
    }

    private InboxEvent event(SessionRecordCaptured payload) {
        InboxEvent event = mock(InboxEvent.class);
        when(event.payloadJson()).thenReturn(JSON.write(payload));
        return event;
    }

    private ActivityCopy copy(UUID guardian, UUID child) {
        return new ActivityCopy(sessionId, guardian, child, UUID.randomUUID(), ActivityType.FREE_TALK, SessionStatus.ENDED, 5,
                null, null, true, false, null, "k", "h", NOW.minusSeconds(600), null, NOW, NOW, NOW, NOW);
    }
}
