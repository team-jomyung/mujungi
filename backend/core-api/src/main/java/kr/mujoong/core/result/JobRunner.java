package kr.mujoong.core.result;
import java.time.Instant;
import java.util.*;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.config.CoreTimingProperties;
import kr.mujoong.core.identity.GuardianWriteLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
/** 상태 전이와 결과 확정의 유일한 업무 경계. */
@Service
public class JobRunner {
    private static final Set<String> RETRYABLE=Set.of("PROVIDER_UNAVAILABLE","PROVIDER_ERROR","TIMEOUT","SCHEMA_INVALID","INTERNAL");
    private final Map<String,JobHandler> handlers=new HashMap<>();
    private final ResultJobRepository jobs;
    private final ReportJobState reports;
    private final GuardianWriteLock guardians;
    private final MujoongClock clock;
    private final CoreTimingProperties.ResultTiming timing;
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
        JobContext context=new JobContext(job,clock,(writer,after)->commit(job,writer,after),()->requireActiveGuardian(job),()->nextAttempt(job,clock.now())!=null);
        Outcome outcome;
        try{context.requireActiveGuardian();JobHandler handler=handlers.get(job.jobType());outcome=handler==null?Outcome.failed("INTERNAL",false):handler.handle(context);}
        catch(ResultAiException error){
            // 동의 상실은 공급자 실패가 아니다. 기존 영속 오류 열거값으로 닫고 자동 재전송하지 않는다.
            outcome=error.code().equals("CONSENT_WITHDRAWN")?Outcome.failed("INTERNAL",false):Outcome.failed(error.code(),true);
        }
        catch(RuntimeException error){outcome=Outcome.failed("INTERNAL",true);}
        if(context.committed())return;
        if(outcome!=null&&outcome.isDeferred()){tx.executeWithoutResult(t->defer(job));return;}
        if(outcome==null||outcome.successful())outcome=Outcome.failed("INTERNAL",false);
        Outcome failed=outcome;tx.executeWithoutResult(t->failure(job,failed));
    }
    private void requireActiveGuardian(ResultJob job){
        tx.executeWithoutResult(t->{
            if(!guardians.statusForShare(job.guardianId()).orElse("").equals("ACTIVE"))
                throw new ResultAiException("ACCOUNT_DELETING");
        });
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
    /** 재시도 간격 · 시도 횟수 · 절대 마감 안에서 다음 시도 시각. 남은 시도가 없으면 null. */
    private Instant nextAttempt(ResultJob job,Instant now){
        if(job.attemptCount()>=timing.jobAttempts())return null;
        Instant next=now.plus(timing.jobRetryBackoff().get(job.attemptCount()-1));
        if(job.deadlineAt()!=null&&(!now.isBefore(job.deadlineAt())||!next.isBefore(job.deadlineAt())))return null;
        return next;
    }
    /**
     * 처리기가 미룬 시도: 오류 코드 없이 다음 재시도 시각에 다시 잡는다(시도 횟수는 쓴다). 그사이 마감 · 횟수가 다 찼으면
     * 마감 초과로 끝낸다(처리기는 retryAvailable이 참일 때만 미루므로 경합일 때만 생긴다).
     */
    private void defer(ResultJob job){
        Instant now=clock.now();Instant next=nextAttempt(job,now);
        if(next==null){failure(job,Outcome.failed("DEADLINE_EXCEEDED",false));return;}
        jobs.transition(job,"REQUESTED",null,next,now,"FAILED");
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
