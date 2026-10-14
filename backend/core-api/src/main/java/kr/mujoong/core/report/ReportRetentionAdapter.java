package kr.mujoong.core.report;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.identity.GuardianWriteLock;
import kr.mujoong.core.result.ReportRetention;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@Component
public class ReportRetentionAdapter implements ReportRetention {
    private final ReportRepository reports;private final GuardianWriteLock guardians;private final MujoongClock clock;
    private final java.time.Duration retention;private final TransactionTemplate tx;
    public ReportRetentionAdapter(ReportRepository reports,GuardianWriteLock guardians,MujoongClock clock,CoreProperties properties,PlatformTransactionManager transactions){this.reports=reports;this.guardians=guardians;this.clock=clock;retention=properties.timing().report().unsavedRetention();tx=new TransactionTemplate(transactions);}
    public void discardExpired(){for(var report:reports.expired(clock.now().minus(retention)))tx.executeWithoutResult(t->{
        guardians.statusForShare(report.guardianId());
        var current=reports.lock(report.id()).orElse(null);
        if(current!=null&&(current.status().equals("READY")||current.status().equals("FAILED")))reports.discard(report.id(),clock.now());
    });}
}
