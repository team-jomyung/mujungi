package kr.mujoong.core.result;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import kr.mujoong.contract.time.MujoongClock;
/** BR1.8: 처리기에 작업 상태를 직접 쓰는 저장소를 제공하지 않는다. */
public final class JobContext {
    @FunctionalInterface interface Committer { boolean commit(Runnable writer,Runnable afterCommit); }
    private final ResultJob job;
    private final MujoongClock clock;
    private final Committer committer;
    private boolean committed;
    JobContext(ResultJob job,MujoongClock clock,Committer committer){this.job=job;this.clock=clock;this.committer=committer;}
    public UUID jobId(){return job.id();}
    public UUID subjectId(){return job.subjectId();}
    public UUID guardianId(){return job.guardianId();}
    public String jobType(){return job.jobType();}
    public int attemptNo(){return job.attemptCount();}
    public Instant deadline(){return job.leaseUntil();}
    public MujoongClock clock(){return clock;}
    public Duration remaining(){return Duration.between(clock.now(),deadline());}
    public boolean commit(Runnable writer){return commit(writer,()->{});}
    public boolean commit(Runnable writer,Runnable afterCommit){
        if(committed)throw new IllegalStateException("RESULT_ALREADY_COMMITTED");
        committed=committer.commit(writer,afterCommit);return committed;
    }
    boolean committed(){return committed;}
}
