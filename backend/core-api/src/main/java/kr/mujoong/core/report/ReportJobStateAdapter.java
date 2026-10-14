package kr.mujoong.core.report;
import java.util.UUID;
import kr.mujoong.core.result.ReportJobState;
import org.springframework.stereotype.Component;
/** report → result 의존 방향을 유지하는 작업 상태 확인 어댑터. */
@Component
public class ReportJobStateAdapter implements ReportJobState {
    private final ReportRepository reports;
    public ReportJobStateAdapter(ReportRepository reports){this.reports=reports;}
    public boolean lockGenerating(UUID id){return reports.lock(id).map(r->r.status().equals("GENERATING")).orElse(false);}
}
