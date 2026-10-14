package kr.mujoong.realtime.practice;

import static org.assertj.core.api.Assertions.*;
import static kr.mujoong.realtime.practice.PracticeTypes.*;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u5b")
class PracticeDeletionIT {
    @Test void deletesOwnFiveTablesAndPreservesSessionAndOtherGuardian() {
        var db = new PracticeDb(); var other = new PracticeDb(); var p = db.pending();
        var owned = db.repository.claimAttempt(p.id(), 1, PracticeModelTest.T, Duration.ofSeconds(5)).orElseThrow();
        db.repository.finalizeResult(p.id(), 1, owned.work().activeAttemptToken(), db.evaluation(owned), PracticeModelTest.T);
        db.repository.saveCue(new CueDelivery(UUID.randomUUID(), db.attempt.id(), db.attempt.scenarioId(), Consent.DECLINED, DeliveryState.NOT_GIVEN, null, null, null));
        new PracticeDeletionService(db.repository).delete(db.guardian);
        assertThat(db.repository.snapshot(db.session).goals()).isEmpty(); assertThat(db.repository.response(p.id())).isEmpty();
        assertThat(other.repository.snapshot(other.session).goals()).hasSize(1);
        assertThat(PracticeDb.JDBC.queryForObject("select count(*) from realtime.realtime_activity_session where id=?", Integer.class, db.session)).isEqualTo(1);
    }
    @Test void duplicateDeletionIsIdempotent() {
        var db = new PracticeDb(); var service = new PracticeDeletionService(db.repository);
        service.delete(db.guardian); service.delete(db.guardian); assertThat(db.repository.snapshot(db.session).attempts()).isEmpty();
    }
    @Test void lateEvaluationCannotResurrectDeletedResponse() {
        var db = new PracticeDb(); var p = db.pending(); var owned = db.repository.claimAttempt(p.id(), 1, PracticeModelTest.T, Duration.ofSeconds(5)).orElseThrow();
        new PracticeDeletionService(db.repository).delete(db.guardian);
        assertThat(db.repository.finalizeResult(p.id(), 1, owned.work().activeAttemptToken(), db.evaluation(owned), PracticeModelTest.T)).isFalse();
        assertThat(db.repository.snapshot(db.session).evaluations()).isEmpty();
    }
    @Test void outerTransactionRollbackPreservesAllOriginalRows() {
        var db = new PracticeDb(); var p = db.pending();
        assertThatThrownBy(() -> PracticeDb.TX.execute(status -> {
            new PracticeDeletionService(db.repository).delete(db.guardian);
            throw new IllegalStateException("다음 삭제 참가자 합성 실패");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(db.repository.response(p.id())).contains(p); assertThat(db.repository.snapshot(db.session).attempts()).hasSize(1);
    }
    @Test void unknownGuardianDoesNotDeleteAndNullIsRejected() {
        var db = new PracticeDb(); var service = new PracticeDeletionService(db.repository);
        service.delete(UUID.randomUUID()); assertThat(db.repository.snapshot(db.session).goals()).hasSize(1);
        assertThatThrownBy(() -> service.delete(null)).isInstanceOf(NullPointerException.class);
    }
}
