package kr.mujoong.realtime.practicelegacy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import kr.mujoong.testsupport.time.ManualTimerDriver;
import kr.mujoong.testsupport.time.MutableClock;
import static kr.mujoong.realtime.practicelegacy.PracticeTypes.*;

final class EvaluationFixture {
    final PracticeDb db = new PracticeDb();
    final PracticeResponse response = db.pending();
    final MutableClock clock = new MutableClock(PracticeModelTest.T);
    final ManualTimerDriver timers = new ManualTimerDriver(clock);
    final List<EvaluationProvider.Request> requests = new ArrayList<>();
    final List<CompletableFuture<EvaluationProvider.Result>> answers = new ArrayList<>();
    final List<ResponseEvaluator.Completion> completed = new ArrayList<>();
    final List<RuntimeException> failures = new ArrayList<>();
    final EvaluationProvider provider = request -> {
        requests.add(request); var future = new CompletableFuture<EvaluationProvider.Result>();
        answers.add(future); return future;
    };
    static EvaluationBudget budget() {
        return new EvaluationBudget("u5b-q6", Duration.ofSeconds(12), Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ZERO);
    }
    ResponseEvaluator evaluator() { return evaluator(db.repository, Runnable::run, budget()); }
    ResponseEvaluator evaluator(PracticeRepository repository, Executor calls, EvaluationBudget budget) {
        return new ResponseEvaluator(repository, budget, provider, clock, timers, calls, Runnable::run, completed::add, failures::add);
    }
    void start(ResponseEvaluator evaluator) { evaluator.evaluate(response.id(), "고정 기준", "합성 반응"); }
    EvaluationProvider.Result good() { return new EvaluationProvider.Result(Label.functional, "충족", 1, false, "fixture", "fixture"); }
    PracticeResponse saved() { return db.repository.response(response.id()).orElseThrow(); }
}
