package kr.mujoong.core.result;
import java.util.UUID;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.identity.GuardianWriteLock;
import kr.mujoong.core.record.ActivityRecorded;
import kr.mujoong.core.record.ResultRecordReader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.*;
import org.springframework.transaction.support.TransactionTemplate;
/** BR2.1 · R-12: 기록 커밋 후 새 트랜잭션에서 머리와 작업을 함께 접수한다. */
@Service
public class ResultIntake {
    private final ResultRecordReader records;private final SessionResultStateRepository states;private final ResultJobRepository jobs;
    private final GuardianWriteLock guardians;private final MujoongClock clock;private final java.time.Duration undecidedLimit;
    private final TransactionTemplate tx;
    private final kr.mujoong.core.observability.EventLogger events;
    public ResultIntake(ResultRecordReader records,SessionResultStateRepository states,ResultJobRepository jobs,GuardianWriteLock guardians,
            MujoongClock clock,CoreProperties properties,PlatformTransactionManager transactions,kr.mujoong.core.observability.EventLogger events){
        this.records=records;this.states=states;this.jobs=jobs;this.guardians=guardians;this.clock=clock;this.undecidedLimit=properties.timing().result().undecidedLimit();
        this.tx=new TransactionTemplate(transactions);this.events=events;tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void recorded(ActivityRecorded event){accept(event.sessionId(),event.guardianId(),"ACTIVITY_RECORDED");}
    public void accept(UUID session,UUID guardian,String trigger){tx.executeWithoutResult(t->{
        if(!guardians.statusForShare(guardian).orElse("").equals("ACTIVE")||states.find(session,guardian).isPresent())return;
        var input=records.find(session,guardian).orElse(null);if(input==null)return;
        var r=input.record();var decision=ResultEligibility.decide(r.finalStatus(),input.hasUtterance(),input.copyStatus(),input.failureCode());
        if(decision==ResultEligibility.Decision.UNDECIDED){
            if(clock.now().isBefore(r.recordedAt().plus(undecidedLimit)))return;
            decision=ResultEligibility.Decision.NOT_ELIGIBLE;
            events.appError(kr.mujoong.contract.log.EventLog.Component.CORE_MAINTENANCE,"ResultEligibilityExpired");
        }
        boolean eligible=decision==ResultEligibility.Decision.ELIGIBLE;
        if(states.insert(session,guardian,r.childId(),r.activityType(),eligible,clock.now())&&eligible){
            var job=jobs.submit("SESSION_RESULT",session,guardian,"SR:"+session+":1",trigger,null,clock.now());
            states.attach(session,job.id(),1,clock.now());
        }
    });}
}
