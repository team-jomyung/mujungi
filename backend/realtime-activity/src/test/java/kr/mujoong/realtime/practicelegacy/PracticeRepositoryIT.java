package kr.mujoong.realtime.practicelegacy;

import static org.assertj.core.api.Assertions.*;
import static kr.mujoong.realtime.practicelegacy.PracticeTypes.*;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u5b")
@Tag("db")
class PracticeRepositoryIT {
    @Test void keepsUniqueOpportunityAndOriginalBudgetAcrossRestart() {
        var db = new PracticeDb(); var first = db.pending();
        var duplicate = db.repository.accept(PracticeModelTest.pending(db.attempt, ResponseType.FIRST));
        assertThat(duplicate).isEqualTo(first);
        var reopened = new JdbcPracticeRepository(PracticeDb.JDBC, PracticeDb.TX);
        assertThat(reopened.response(first.id())).contains(first);
        var progressed = db.goal.progress(GoalStatus.RUNNING, Stage.FIRST_RESPONSE, null);
        db.repository.saveGoal(progressed); reopened.initialize(List.of(db.goal));
        assertThat(reopened.snapshot(db.session).goals()).containsExactly(progressed);
    }
    @Test void serializesConcurrentAcceptsAndClaimHasOneOwner() throws Exception {
        var db = new PracticeDb();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(db::pending); var b = pool.submit(db::pending);
            assertThat(a.get().id()).isEqualTo(b.get().id());
        }
        var p = db.pending();
        assertThat(db.repository.claimAttempt(p.id(), 1, PracticeModelTest.T, Duration.ofSeconds(5))).isPresent();
        assertThat(db.repository.claimAttempt(p.id(), 1, PracticeModelTest.T, Duration.ofSeconds(5))).isEmpty();
        assertThat(db.repository.snapshot(db.session).responses()).hasSize(1);
    }
    @Test void commitsEvaluationAndMatchingResponseTogetherAndIgnoresDuplicate() {
        var db = new PracticeDb(); var p = db.pending();
        var claimed = db.repository.claimAttempt(p.id(), 1, PracticeModelTest.T, Duration.ofSeconds(5)).orElseThrow();
        var result = db.evaluation(claimed);
        assertThat(db.repository.finalizeResult(p.id(), 1, claimed.work().activeAttemptToken(), result, PracticeModelTest.T)).isTrue();
        assertThat(db.repository.finalizeResult(p.id(), 1, claimed.work().activeAttemptToken(), result, PracticeModelTest.T)).isFalse();
        var snapshot = db.repository.snapshot(db.session);
        assertThat(snapshot.evaluations()).containsExactly(result);
        assertThat(snapshot.responses().getFirst().label()).isEqualTo(result.label());
    }
    @Test void rollsBackBothWritesWhenFinalUpdateFails() {
        var db = new PracticeDb(); var p = db.pending();
        var claimed = db.repository.claimAttempt(p.id(), 1, PracticeModelTest.T, Duration.ofSeconds(5)).orElseThrow();
        // 미래 acceptedAt보다 이른 closedAt은 Evaluation 삽입 다음 모델 검증에서 실패한다.
        assertThatThrownBy(() -> db.repository.finalizeResult(p.id(), 1, claimed.work().activeAttemptToken(),
                db.evaluation(claimed), PracticeModelTest.T.minusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(db.repository.snapshot(db.session).evaluations()).isEmpty();
        assertThat(db.repository.response(p.id()).orElseThrow().evaluationState()).isEqualTo(EvaluationState.PENDING);
    }
    @Test void terminationWinsAndPreservesAcceptedResponse() {
        var db = new PracticeDb(); var p = db.pending();
        var claimed = db.repository.claimAttempt(p.id(), 1, PracticeModelTest.T, Duration.ofSeconds(5)).orElseThrow();
        db.repository.terminate(db.session);
        assertThat(db.repository.finalizeResult(p.id(), 1, claimed.work().activeAttemptToken(), db.evaluation(claimed), PracticeModelTest.T)).isFalse();
        var saved = db.repository.response(p.id()).orElseThrow();
        assertThat(saved.evaluationState()).isEqualTo(EvaluationState.TERMINATED_PENDING);
        assertThat(saved.utteranceSeq()).isEqualTo(p.utteranceSeq()); assertThat(saved.label()).isNull();
        assertThatThrownBy(db::pending).isInstanceOf(IllegalStateException.class);
    }
    @Test void finalizationBeforeTerminationKeepsFinalResult() {
        var db = new PracticeDb(); var p = db.pending();
        var claimed = db.repository.claimAttempt(p.id(), 1, PracticeModelTest.T, Duration.ofSeconds(5)).orElseThrow();
        db.repository.finalizeResult(p.id(), 1, claimed.work().activeAttemptToken(), db.evaluation(claimed), PracticeModelTest.T);
        db.repository.terminate(db.session);
        assertThat(db.repository.response(p.id()).orElseThrow().label()).isEqualTo(Label.functional);
    }
    @Test void rejectsFrozenGoalConflictAndForeignScenario() {
        var db = new PracticeDb(); db.repository.initialize(List.of(db.goal));
        assertThatThrownBy(() -> db.repository.initialize(List.of(db.goal.progress(GoalStatus.RUNNING, Stage.FIRST_RESPONSE, null))))
                .isInstanceOf(IllegalArgumentException.class);
        var foreign = new PracticeAttempt(UUID.randomUUID(), db.goal.id(), AttemptType.NEW_SCENARIO,
                AttemptState.ISSUED, UUID.randomUUID(), UUID.randomUUID(), null, null);
        assertThatThrownBy(() -> db.repository.saveAttempt(foreign)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsExpiredTokenAndCountsRetryWithoutResettingDeadline() {
        var db = new PracticeDb(); var p = db.pending();
        var first = db.repository.claimAttempt(p.id(), 1, PracticeModelTest.T, Duration.ofSeconds(5)).orElseThrow();
        var second = db.repository.claimAttempt(p.id(), 1, PracticeModelTest.T.plusSeconds(5), Duration.ofSeconds(5)).orElseThrow();
        assertThat(second.work().attemptsStarted()).isEqualTo(2);
        assertThat(second.work().deadlineAt()).isEqualTo(first.work().deadlineAt());
        assertThat(db.repository.finalizeResult(p.id(), 1, first.work().activeAttemptToken(), db.evaluation(first), PracticeModelTest.T.plusSeconds(5))).isFalse();
        assertThat(db.repository.releaseAttempt(p.id(), second.work().activeAttemptToken())).isTrue();
        assertThat(db.repository.claimAttempt(p.id(), 1, PracticeModelTest.T.plusSeconds(10), Duration.ofSeconds(5))).isEmpty();
    }
}
