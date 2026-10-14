package kr.mujoong.core.result;
import java.time.Instant;
import java.util.*;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.identity.GuardianWriteLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
/** BR1.1~BR1.12: 상태 전이와 결과 확정의 유일한 업무 경계. */
@Service
public class JobRunner {
    private static final Set<String> RETRYABLE=Set.of("PROVIDER_UNAVAILABLE","PROVIDER_ERROR","TIMEOUT","SCHEMA_INVALID","INTERNAL");
    private final Map<String,JobHandler> handlers=new HashMap<>();
    private final ResultJobRepository jobs;
    private final ReportJobState reports;
    private final GuardianWriteLock guardians;
    private final MujoongClock clock;
    private final CoreProperties.ResultTiming timing;
    private final TransactionTemplate tx;
    private final kr.mujoong.core.observability.EventLogger events;
    public JobRunner(ResultJobRepository jobs,ReportJobState reports,GuardianWriteLock guardians,MujoongClock clock,
            CoreProperties properties,PlatformTransactionManager transactions,List<JobHandler> handlers,kr.mujoong.core.observability.EventLogger events){
        this.jobs=jobs;this.reports=reports;this.guardians=guardians;this.clock=clock;this.timing=properties.timing().result();
        this.tx=new TransactionTemplate(transactions);this.events=events;handlers.forEach(this::register);
    }
    public void register(JobHandler handler){if(handlers.putIfAbsent(handler.jobType(),handler)!=null)throw new IllegalStateException("DUPLICATE_JOB_HANDLER");}
    public ResultJob submit(String type,UUID subject,UUID guardian,String key,String trigger,Instant deadline){return jobs.submit(type,subject,guardian,key,trigger,deadline,clock.now());}
    public List<ResultJob> claim(String type){return tx.execute(t->jobs.claim(type,1,clock.now(),timing.attemptTimeout()));}
    public void startup(){tx.executeWithoutResult(t->jobs.startupCheck(clock.now()));}
    public List<UUID> expire(){return tx.execute(t->{
        List<UUID> expired=new ArrayList<>();
        for(ResultJob j:jobs.expired(clock.now())){failure(j,Outcome.failed("TIMEOUT",true));expired.add(j.id());}
        for(ResultJob j:jobs.unfinished())if(j.status().equals("REQUESTED"))jobs.expireRequested(j,clock.now());
        return expired;
    });}
    public void execute(ResultJob job){
        JobContext context=new JobContext(job,clock,(writer,after)->commit(job,writer,after));
        Outcome outcome;
        try{JobHandler handler=handlers.get(job.jobType());outcome=handler==null?Outcome.failed("INTERNAL",false):handler.handle(context);}
        catch(ResultAiException error){outcome=Outcome.failed(error.code(),true);}
        catch(RuntimeException error){outcome=Outcome.failed("INTERNAL",true);}
        if(context.committed())return;
        if(outcome==null||outcome.successful())outcome=Outcome.failed("INTERNAL",false);
        Outcome failed=outcome;tx.executeWithoutResult(t->failure(job,failed));
    }
    private boolean commit(ResultJob job,Runnable writer,Runnable after){
        boolean committed=Boolean.TRUE.equals(tx.execute(t->{
            // 프로젝트 공통 잠금 순서: 보호자 → 작업 → 리포트.
            if(!guardians.statusForShare(job.guardianId()).orElse("").equals("ACTIVE")){
                failure(job,Outcome.failed("ACCOUNT_DELETING",false));return false;
            }
            var current=jobs.lock(job.id()).orElse(null);
            if(current==null||!current.status().equals("RUNNING")||current.attemptCount()!=job.attemptCount())return false;
            if(!clock.now().isBefore(current.leaseUntil())){failure(job,Outcome.failed("TIMEOUT",true));return false;}
            if(job.jobType().equals("REPORT")&&!reports.lockGenerating(job.subjectId())){
                failure(job,Outcome.failed("REPORT_DISCARDED",false));return false;
            }
            writer.run();
            if(!jobs.transition(job,"DONE",null,null,clock.now(),"SUCCEEDED"))throw new IllegalStateException("RESULT_COMMIT_LOST");
            return true;
        }));
        if(committed)try{after.run();}catch(RuntimeException error){
            // 영속 pending_candidates를 다음 누락 점검에서 복구한다. DONE을 되돌리지 않는다.
            events.appError(kr.mujoong.contract.log.EventLog.Component.CORE_MAINTENANCE,"ResultAfterCommitFailed");
        }
        return committed;
    }
    private void failure(ResultJob job,Outcome outcome){
        Instant now=clock.now();String code=outcome.code();
        boolean retry=outcome.retryable()&&RETRYABLE.contains(code)&&job.attemptCount()<timing.jobAttempts();
        Instant next=retry?now.plus(timing.jobRetryBackoff().get(job.attemptCount()-1)):null;
        if(job.deadlineAt()!=null&&(!now.isBefore(job.deadlineAt())||(next!=null&&!next.isBefore(job.deadlineAt())))){
            retry=false;code="DEADLINE_EXCEEDED";
        }
        jobs.transition(job,retry?"REQUESTED":"FAILED",code,next,now,"FAILED");
    }
}
