package kr.mujoong.core.result;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.consent.ConsentPolicyService;
import kr.mujoong.core.identity.GuardianWriteLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
/** BR2.4: 보호자 잠금 뒤 FAILED만 새 차수로 바꾼다. */
@Service
public class ResultRetryService {
    private final SessionResultStateRepository states;private final ResultJobRepository jobs;private final GuardianWriteLock guardians;
    private final MujoongClock clock;private final TransactionTemplate tx;private final ConsentPolicyService consent;
    public ResultRetryService(SessionResultStateRepository states,ResultJobRepository jobs,GuardianWriteLock guardians,MujoongClock clock,PlatformTransactionManager transactions,ConsentPolicyService consent){this.states=states;this.jobs=jobs;this.guardians=guardians;this.clock=clock;this.consent=consent;tx=new TransactionTemplate(transactions);}
    public void retry(UUID guardian,UUID session){tx.executeWithoutResult(t->{
        guardians.lockActive(guardian);var state=states.lock(session,guardian).orElseThrow(()->CoreApiException.of(ErrorCode.NOT_FOUND));
        if(!state.jobStatus().equals("FAILED"))throw CoreApiException.of(ErrorCode.JOB_NOT_FAILED);
        if(!consent.check(guardian).snapshot().voiceAndText())throw CoreApiException.of(ErrorCode.CONSENT_MISSING);
        int ordinal=state.runOrdinal()+1;
        var job=jobs.submit("SESSION_RESULT",session,guardian,"SR:"+session+":"+ordinal,"RETRY",null,clock.now());
        if(!states.retry(session,job.id(),ordinal,clock.now()))throw CoreApiException.of(ErrorCode.JOB_NOT_FAILED);
    });}
}
