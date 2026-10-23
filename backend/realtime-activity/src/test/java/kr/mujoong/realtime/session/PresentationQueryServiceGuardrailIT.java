package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.guardrail.GuardrailPresentation;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.realtime.log.*;
import kr.mujoong.realtime.testing.RealtimeDb;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

@Tag("db") @Tag("service-guardrail")
class PresentationQueryServiceGuardrailIT {
    private static RealtimeDb db;
    private final Instant now = Instant.parse("2026-10-21T00:00:00Z");
    private ActivitySessionRepository sessions;
    private SessionGuardrailFactsRepository facts;
    private SessionEventRepository events;
    private SessionViewQuery query;
    private TranscriptRepository transcripts;
    private UUID id;
    @BeforeAll static void open() { db = RealtimeDb.open(); }
    @AfterAll static void close() { db.close(); }
    @BeforeEach void setup() {
        db.wipe(); id = UUID.randomUUID(); sessions = new ActivitySessionRepository(db.jdbc());
        events = new SessionEventRepository(db.jdbc()); facts = new SessionGuardrailFactsRepository(db.jdbc(), events);
        transcripts = new TranscriptRepository(db.jdbc());
        var session = new NewActivitySession(id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ActivityType.FREE_TALK,
                UUID.randomUUID().toString(), new ConsentSnapshot("1.0", true, true, false, false), new ChildContext("별", 8, List.of()), null, null);
        db.inTransaction(() -> sessions.insert(session, now));
        query = new SessionViewQuery(sessions, transcripts, new SessionGapRepository(db.jdbc()), mock(LatencyRecorder.class),
                new MutableClock(now), mock(SessionRegistry.class), facts);
    }
    private SessionGuardrailFacts value(long revision, SessionGuardrailFacts.Participation participation) {
        return new SessionGuardrailFacts(1, id, revision, 1, SessionGuardrailFacts.Notice.COMPLETED, participation,
                SessionGuardrailFacts.Goodbye.PLAYING, 7, now);
    }
    private void append(SessionGuardrailFacts value) {
        db.inTransaction(() -> facts.append(sessions.findForUpdate(id).orElseThrow(), value));
    }
    @Test void restartKeepsCommittedParticipationButDoesNotRestoreDynamicPlayback() {
        append(value(1, SessionGuardrailFacts.Participation.ACCEPTED));
        var view = query.view(id, 0).orElseThrow().guardrail();
        assertThat(view.participation()).isEqualTo(GuardrailPresentation.Participation.ACCEPTED);
        assertThat(view.goodbye()).isEqualTo(GuardrailPresentation.Goodbye.UNKNOWN);
        assertThat(view.confirmedStopBubbleSeq()).isNull();
    }
    @Test void conflictingRevisionIsNullRatherThanAnInventedWinner() {
        append(value(1, SessionGuardrailFacts.Participation.ACCEPTED));
        db.inTransaction(() -> events.insert(new SessionEvent(UUID.randomUUID(), id, SessionGuardrailFactsRepository.EVENT_TYPE,
                null, JsonCodec.shared().write(value(1, SessionGuardrailFacts.Participation.REFUSED)), now)));
        assertThat(query.view(id, 0).orElseThrow().guardrail()).isNull();
    }
    @Test void corruptFactsAreNullAndLegacyAbsenceIsNull() {
        assertThat(query.view(id, 0).orElseThrow().guardrail()).isNull();
        db.inTransaction(() -> events.insert(new SessionEvent(UUID.randomUUID(), id, SessionGuardrailFactsRepository.EVENT_TYPE, null, "{}", now)));
        assertThat(query.view(id, 0).orElseThrow().guardrail()).isNull();
    }
    @Test void stopReferenceRequiresTheExactVisibleFinalCertainChildRow() {
        var params = new MapSqlParameterSource("id", UUID.randomUUID()).addValue("session", id).addValue("at", java.sql.Timestamp.from(now));
        db.jdbc().update("insert into realtime.realtime_transcript(id,session_id,sequence_no,speaker,text,assembly_status,review_needed,created_at)"
                + " values(:id,:session,7,'CHILD','synthetic','FINAL',false,:at)", params);
        assertThat(transcripts.isVisibleFinalChild(id, 7)).isTrue();
        assertThat(transcripts.isVisibleFinalChild(UUID.randomUUID(), 7)).isFalse();
        db.jdbc().update("update realtime.realtime_transcript set review_needed = true where session_id = :session", params);
        assertThat(transcripts.isVisibleFinalChild(id, 7)).isFalse();
        db.jdbc().update("update realtime.realtime_transcript set review_needed = false, deleted_at = :at where session_id = :session", params);
        assertThat(transcripts.isVisibleFinalChild(id, 7)).isFalse();
    }
}
