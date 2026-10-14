package kr.mujoong.realtime.practice;

import static kr.mujoong.realtime.practice.PracticeTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.realtime.common.db.DbRetry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Tag("u5b")
@Tag("db")
class RealtimePracticeDeletionParticipantIT {
    private RealtimePracticeDeletionParticipant participant() {
        return new RealtimePracticeDeletionParticipant(new NamedParameterJdbcTemplate(PracticeDb.JDBC),
                new DbRetry(0, new java.util.Random(1), ignored -> {}), PracticeDb.TX.getTransactionManager());
    }

    @Test
    void countsAllFiveTablesAndPreservesTheSessionAndOtherGuardian() {
        var db = populated();
        var other = populated();

        Map<String, Integer> counts = participant().deleteByGuardian(db.guardian, UUID.randomUUID());

        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(
                "realtime_session_goal", 1, "realtime_practice_attempt", 1,
                "realtime_practice_response", 1, "realtime_practice_cue", 1,
                "realtime_practice_evaluation", 1));
        assertThat(db.repository.snapshot(db.session).goals()).isEmpty();
        assertThat(other.repository.snapshot(other.session).evaluations()).hasSize(1);
        assertThat(PracticeDb.JDBC.queryForObject(
                "select count(*) from realtime.realtime_activity_session where id=?", Integer.class, db.session))
                .isEqualTo(1);
    }

    @Test
    void duplicateAndUnknownGuardianReturnFiveZeroCounts() {
        var db = populated();
        var participant = participant();
        UUID request = UUID.randomUUID();
        participant.deleteByGuardian(db.guardian, request);

        assertThat(participant.deleteByGuardian(db.guardian, request)).hasSize(5).allSatisfy((table, count) ->
                assertThat(count).as(table).isZero());
        assertThat(participant.deleteByGuardian(UUID.randomUUID(), UUID.randomUUID()))
                .hasSize(5).allSatisfy((table, count) -> assertThat(count).as(table).isZero());
    }

    @Test
    void aLaterParticipantFailureRollsBackAllPracticeRows() {
        var db = populated();
        var before = db.repository.snapshot(db.session);

        assertThatThrownBy(() -> PracticeDb.TX.execute(status -> {
            participant().deleteByGuardian(db.guardian, UUID.randomUUID());
            throw new IllegalStateException("다음 참가자 합성 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(db.repository.snapshot(db.session)).isEqualTo(before);
        assertThat(participant().deleteByGuardian(db.guardian, UUID.randomUUID()).values())
                .containsOnly(1);
    }

    @Test
    void deletingPracticeFirstAllowsTheSessionParticipantToRemoveTheParent() {
        var db = populated();
        assertThatThrownBy(() -> PracticeDb.JDBC.update(
                "delete from realtime.realtime_activity_session where id=?", db.session))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        PracticeDb.TX.executeWithoutResult(status -> {
            participant().deleteByGuardian(db.guardian, UUID.randomUUID());
            assertThat(PracticeDb.JDBC.update(
                    "delete from realtime.realtime_activity_session where id=?", db.session)).isEqualTo(1);
        });

        assertThat(db.repository.snapshot(db.session).goals()).isEmpty();
    }

    private PracticeDb populated() {
        var db = new PracticeDb();
        var response = db.pending();
        var owned = db.repository.claimAttempt(response.id(), 1, PracticeModelTest.T, Duration.ofSeconds(5))
                .orElseThrow();
        db.repository.finalizeResult(response.id(), 1, owned.work().activeAttemptToken(),
                db.evaluation(owned), PracticeModelTest.T);
        db.repository.saveCue(new CueDelivery(UUID.randomUUID(), db.attempt.id(), db.attempt.scenarioId(),
                Consent.DECLINED, DeliveryState.NOT_GIVEN, null, null, null));
        return db;
    }
}
