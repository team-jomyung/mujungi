package kr.mujoong.core.result;
import java.util.Arrays;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.practice.CandidateRegistry;
import kr.mujoong.core.record.ResultRecordReader;
import kr.mujoong.core.startup.CoreStartupSequence;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
/** BR2.3: 누락 접수·후보 등록·미저장 리포트 정리를 재실행한다. */
@Component
public class ResultReconciler implements SmartLifecycle {
    private final ResultRecordReader records;private final ResultIntake intake;private final ActivityResultRepository results;
    private final ResultJobRepository jobs;private final SessionResultStateRepository states;private final CandidateRegistry candidates;
    private final ReportRetention retention;private final MujoongClock clock;private final JsonCodec json;private final EventLogger events;
    private final java.time.Duration interval;private final TransactionTemplate tx;private ThreadPoolTaskScheduler scheduler;
    public ResultReconciler(ResultRecordReader records,ResultIntake intake,ActivityResultRepository results,ResultJobRepository jobs,
            SessionResultStateRepository states,CandidateRegistry candidates,ReportRetention retention,MujoongClock clock,JsonCodec json,
            EventLogger events,CoreProperties properties,PlatformTransactionManager transactions){
        this.records=records;this.intake=intake;this.results=results;this.jobs=jobs;this.states=states;this.candidates=candidates;
        this.retention=retention;this.clock=clock;this.json=json;this.events=events;interval=properties.timing().result().reconcileInterval();tx=new TransactionTemplate(transactions);
    }
    public void runOnce(){
        for(var input:records.missing())try{intake.accept(input.record().sessionId(),input.record().guardianId(),"RECONCILE");}catch(RuntimeException e){failure();}
        for(var result:results.pendingCandidates())try{registerCandidates(result);}catch(RuntimeException e){failure();}
        retention.discardExpired();
    }
    public void registerCandidates(ActivityResultRepository.Result result){
        var job=jobs.find(result.jobId()).orElse(null);if(job==null)return;
        var head=states.find(result.sessionId(),job.guardianId()).orElse(null);
        if(head==null||!head.jobStatus().equals("DONE")||!result.id().equals(head.currentResultId()))return;
        var pending=json.read(result.pendingCandidatesJson(),CandidateRegistry.Candidate[].class);
        if(pending.length>0)candidates.register(result.sessionId(),head.childId(),Arrays.asList(pending));
        tx.executeWithoutResult(t->results.candidatesRegistered(result.id(),clock.now()));
    }
    private void failure(){events.appError(EventLog.Component.CORE_MAINTENANCE,"ResultReconcileFailed");}
    @Override public synchronized void start(){if(scheduler!=null)return;var s=new ThreadPoolTaskScheduler();s.setPoolSize(1);s.setDaemon(true);s.setThreadNamePrefix("result-reconcile-");s.initialize();s.scheduleWithFixedDelay(()->{try{runOnce();}catch(RuntimeException e){failure();}},interval);scheduler=s;}
    @Override public synchronized void stop(){if(scheduler!=null){scheduler.shutdown();scheduler=null;}}
    @Override public synchronized boolean isRunning(){return scheduler!=null;}
    @Override public int getPhase(){return CoreStartupSequence.PHASE+2;}
}
