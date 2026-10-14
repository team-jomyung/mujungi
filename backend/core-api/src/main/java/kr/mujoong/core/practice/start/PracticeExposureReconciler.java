package kr.mujoong.core.practice.start;

import java.util.UUID;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.practice.PracticeAccountPort;
import kr.mujoong.core.practice.ScenarioService;
import kr.mujoong.core.record.ActivityRecorded;
import kr.mujoong.core.record.RecordQueryService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class PracticeExposureReconciler {
    private final PracticePreparationRepository preparations;
    private final PracticeAccountPort accounts;
    private final RecordQueryService records;
    private final ScenarioService scenarios;
    private final MujoongClock clock;
    private final EventLogger events;
    private final TransactionTemplate tx;
    public PracticeExposureReconciler(PracticePreparationRepository preparations,PracticeAccountPort accounts,
            RecordQueryService records,ScenarioService scenarios,MujoongClock clock,EventLogger events,
            PlatformTransactionManager transactions) {
        this.preparations=preparations;this.accounts=accounts;this.records=records;this.scenarios=scenarios;
        this.clock=clock;this.events=events;this.tx=new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void recorded(ActivityRecorded event) {
        if("PRACTICE".equals(event.activityType())) reconcile(event.sessionId());
    }
    public void runOnce() {
        for(var p:preparations.pendingExposures()) {
            if(records.practiceSnapshot(p.guardianId(),p.sessionId()).isPresent()) reconcile(p.sessionId());
        }
    }
    public void reconcile(UUID sessionId) {
        try {
            tx.executeWithoutResult(status->{
                var p=preparations.findSession(sessionId).orElse(null);
                if(p==null) return;
                accounts.requireActive(p.guardianId(),true);
                scenarios.reconcileExposures(sessionId);
                preparations.reconciled(sessionId,clock.now());
            });
        } catch(RuntimeException failure) {
            events.appError(EventLog.Component.ACTIVITY_COORDINATOR,"PracticeExposureReconciliationFailed");
        }
    }
}
