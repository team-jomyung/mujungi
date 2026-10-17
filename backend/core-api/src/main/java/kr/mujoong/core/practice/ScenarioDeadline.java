package kr.mujoong.core.practice;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.common.error.CoreApiException;

/** 최초 벽시계·단조시계 예산을 공유한다. 정각과 취소는 만료다. */
public final class ScenarioDeadline {
    private final MujoongClock clock;
    private final Instant deadline;
    private final long started;
    private final Duration budget;
    public ScenarioDeadline(MujoongClock clock,Instant deadline) {
        this.clock=clock;this.deadline=deadline;this.started=clock.monotonicNanos();
        this.budget=Duration.between(clock.now(),deadline);
    }
    public Duration remaining(){
        var wall=Duration.between(clock.now(),deadline);var mono=budget.minus(clock.elapsedSince(started));
        return wall.compareTo(mono)<0?wall:mono;
    }
    public Duration limit(Duration maximum){check();var left=remaining();return left.compareTo(maximum)<0?left:maximum;}
    public Instant callDeadline(Duration maximum){return clock.now().plus(limit(maximum));}
    public void check(){if(Thread.currentThread().isInterrupted()||remaining().compareTo(Duration.ZERO)<=0)throw failed();}
    public <T>T call(ExecutorService executor,Duration maximum,Callable<T> operation){
        var limit=limit(maximum);var future=executor.submit(operation);
        try {var result=future.get(limit.toNanos(),TimeUnit.NANOSECONDS);check();return result;}
        catch(java.util.concurrent.TimeoutException timeout){future.cancel(true);throw failed();}
        catch(InterruptedException interrupted){future.cancel(true);Thread.currentThread().interrupt();throw failed();}
        catch(java.util.concurrent.ExecutionException failure){
            if(failure.getCause() instanceof RuntimeException runtime)throw runtime;
            throw failed();
        }
    }
    static CoreApiException failed(){return CoreApiException.of(ErrorCode.SCENARIO_GEN_FAILED);}
}
