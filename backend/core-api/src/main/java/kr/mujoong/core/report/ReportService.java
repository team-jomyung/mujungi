package kr.mujoong.core.report;

import java.time.*;
import java.util.*;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.child.ChildProfileService;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.identity.GuardianWriteLock;
import kr.mujoong.core.record.ResultRecordReader;
import kr.mujoong.core.result.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** BR9.1~BR9.3·BR10.2~BR10.3: 보호자 잠금 안에서 멱등 생성과 저장·버림을 직렬화한다. */
@Service
public class ReportService {
    public static final ZoneId ZONE=ZoneId.of("Asia/Seoul");
    public static final String NOTICE="이 리포트는 진단 결과가 아니에요. 전문가에게 보여 줄 참고 자료예요. 제한사항은 마지막 항목에 있어요.";
    private final ReportRepository reports;private final ResultRecordReader records;private final ChildProfileService children;
    private final SessionResultStateRepository states;private final ResultJobRepository jobs;private final GuardianWriteLock guardians;
    private final MujoongClock clock;private final CoreProperties properties;private final JsonCodec json;private final TransactionTemplate tx;
    public ReportService(ReportRepository reports,ResultRecordReader records,ChildProfileService children,SessionResultStateRepository states,
            ResultJobRepository jobs,GuardianWriteLock guardians,MujoongClock clock,CoreProperties properties,JsonCodec json,PlatformTransactionManager transactions){
        this.reports=reports;this.records=records;this.children=children;this.states=states;this.jobs=jobs;this.guardians=guardians;this.clock=clock;this.properties=properties;this.json=json;tx=new TransactionTemplate(transactions);
    }
    private ResultSummaryReader summaries;
    /** 28 활동 고르기 요약 읽기. 선택 의존이라 생성자로 만드는 시험은 요약 없이 돈다. */
    @org.springframework.beans.factory.annotation.Autowired
    void summaries(ResultSummaryReader reader){summaries=reader;}
    /** summary: 정리 끝 결과 문장(ResultSummaryReader, 없으면 null). 동의 철회로 끝난 활동은 늘 null. */
    public record PreviewActivity(UUID sessionId,LocalDate date,String activityType,Instant startedAt,String summary){}
    public record Preview(LocalDate firstRecordDate,List<PreviewActivity> activities){}
    public record Created(UUID reportId,String status){}
    public Preview preview(UUID guardian,LocalDate from,LocalDate to){
        validateDates(from,to);var child=children.childContext(guardian);if(child.isEmpty())return new Preview(null,List.of());
        var all=records.reportRecords(guardian,child.get().childId());
        var inRange=all.stream().filter(r->{var date=r.listedAt().atZone(ZONE).toLocalDate();return !date.isBefore(from)&&!date.isAfter(to);}).toList();
        Map<UUID,String> summaryBySession=summaries==null?Map.of():summaries.summaries(guardian,inRange.stream()
                .filter(r->!"CONSENT_WITHDRAWN".equals(r.endReason())).map(r->r.sessionId()).toList());
        return new Preview(all.isEmpty()?null:all.getFirst().listedAt().atZone(ZONE).toLocalDate(),inRange.stream()
                .map(r->new PreviewActivity(r.sessionId(),r.listedAt().atZone(ZONE).toLocalDate(),r.activityType(),r.startedAt(),summaryBySession.get(r.sessionId())))
                .toList());
    }
    public Created create(UUID guardian,LocalDate from,LocalDate to,List<UUID> sessions,String key){
        validateDates(from,to);
        if(sessions==null||sessions.isEmpty()||sessions.stream().anyMatch(Objects::isNull)||new HashSet<>(sessions).size()!=sessions.size())throw invalid();
        try{if(key==null||key.length()!=36)throw invalid();UUID.fromString(key);}catch(RuntimeException e){throw invalid();}
        String hash=json.sha256OfCanonical(json.write(Map.of("from",from.toString(),"to",to.toString(),"sessionIds",sessions.stream().map(UUID::toString).sorted().toList())));
        return tx.execute(t->{
            guardians.lockActive(guardian);var previous=reports.byKey(guardian,key);
            if(previous.isPresent()){var r=previous.get();if(!r.requestHash().equals(hash))throw CoreApiException.of(ErrorCode.IDEMPOTENCY_CONFLICT);return new Created(r.id(),r.status());}
            var child=children.childContext(guardian).orElseThrow(ReportService::invalid);
            var allowed=preview(guardian,from,to).activities();Set<UUID> selected=new HashSet<>(sessions);
            var chosen=allowed.stream().filter(a->selected.contains(a.sessionId())).toList();if(chosen.size()!=sessions.size())throw invalid();
            for(var old:reports.unsaved(guardian))reports.discard(old.id(),clock.now());
            UUID id=UUID.randomUUID();var activities=chosen.stream().map(a->new ReportRepository.Activity(a.sessionId(),a.date(),a.activityType(),states.find(a.sessionId(),guardian).map(s->s.jobStatus()).orElse("NONE"))).toList();
            reports.create(id,guardian,child.childId(),key,hash,from,to,child.displayName(),child.age(),activities,clock.now());
            var job=jobs.submit("REPORT",id,guardian,"RP:"+id,"REPORT_REQUEST",clock.now().plus(properties.timing().report().readyTarget()),clock.now());reports.attachJob(id,job.id());
            return new Created(id,"GENERATING");
        });
    }
    public ReportRepository.Report owned(UUID guardian,UUID id){return reports.findOwned(id,guardian).orElseThrow(()->CoreApiException.of(ErrorCode.NOT_FOUND));}
    public void saved(UUID guardian,UUID id){tx.executeWithoutResult(t->{guardians.lockActive(guardian);var r=owned(guardian,id);if(r.status().equals("SAVED"))return;if(!r.status().equals("READY"))throw CoreApiException.of(ErrorCode.REPORT_NOT_READY);reports.saved(id,clock.now());});}
    public void discard(UUID guardian,UUID id){tx.executeWithoutResult(t->{guardians.lockActive(guardian);var r=owned(guardian,id);if(!r.status().equals("SAVED"))reports.discard(id,clock.now());});}
    public void validateDates(LocalDate from,LocalDate to){if(from==null||to==null||from.isAfter(to)||to.isAfter(clock.now().atZone(ZONE).toLocalDate()))throw invalid();}
    private static CoreApiException invalid(){return CoreApiException.of(ErrorCode.VALIDATION_FAILED);}
}
