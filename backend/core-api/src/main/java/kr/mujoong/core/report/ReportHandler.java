package kr.mujoong.core.report;

import static kr.mujoong.core.result.ResultData.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import kr.mujoong.core.knowledge.BehaviorDefinitionLookup;
import kr.mujoong.core.record.ResultRecordReader;
import kr.mujoong.core.result.*;
import kr.mujoong.core.result.check.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** WF9·BR9: 읽기 스냅숏 뒤 AI 한 번과 분류 한 번, 쓰기 확인 안에서 10항목을 확정한다. */
@Component
public class ReportHandler implements JobHandler {
    private final ReportRepository reports;private final ResultRecordReader records;private final SessionResultStateRepository states;
    private final ActivityResultRepository results;private final SceneJudgmentRepository scenes;private final StructuredExperienceRepository structures;
    private final BehaviorDefinitionLookup definitions;private final QuoteCandidatePicker picker;private final ReportAssembler assembler;
    private final ResultTextGateway ai;private final OutputChecker checker;private final TransactionTemplate tx;
    public ReportHandler(ReportRepository reports,ResultRecordReader records,SessionResultStateRepository states,ActivityResultRepository results,
            SceneJudgmentRepository scenes,StructuredExperienceRepository structures,BehaviorDefinitionLookup definitions,QuoteCandidatePicker picker,
            ReportAssembler assembler,ResultTextGateway ai,OutputChecker checker,PlatformTransactionManager transactions){
        this.reports=reports;this.records=records;this.states=states;this.results=results;this.scenes=scenes;this.structures=structures;this.definitions=definitions;
        this.picker=picker;this.assembler=assembler;this.ai=ai;this.checker=checker;tx=new TransactionTemplate(transactions);
    }
    public String jobType(){return "REPORT";}
    private record Snapshot(ReportRepository.Report report,List<ReportInput> inputs){}
    public Outcome handle(JobContext job){
        Snapshot snapshot=tx.execute(t->load(job));if(snapshot==null)return Outcome.failed("REPORT_DISCARDED",false);
        var inputs=snapshot.inputs();var assembled=assembler.assemble(snapshot.report(),inputs);var quotes=picker.pick(inputs);
        Map<Integer,List<QuoteCandidatePicker.Quote>> candidates=new LinkedHashMap<>();for(int no:List.of(2,6,10))candidates.put(no,quotes);
        candidates.put(5,quotes.stream().filter(q->hasSource(inputs,q.activityNo(),"CHILD_RECALL",q.utteranceSeq())).map(q->new QuoteCandidatePicker.Quote(q.activityNo(),q.sessionId(),q.utteranceSeq(),q.quote(),q.at(),"CHILD_RECALL")).toList());
        List<Map<String,Object>> requestSections=new ArrayList<>();for(int no:List.of(2,5,6,10))requestSections.add(Map.of("sectionNo",no,"quotes",candidates.get(no).stream().map(QuoteCandidatePicker.Quote::ai).toList()));
        // 결과 문장·구조화는 DONE만, 식별자는 요청 내 activityNo만 전송한다.
        Set<Integer> quotedActivities=new HashSet<>();quotes.forEach(q->quotedActivities.add(q.activityNo()));
        var activityData=inputs.stream().filter(i->quotedActivities.contains(i.activityNo())).map(i->{Map<String,Object> data=new LinkedHashMap<>();data.put("activityNo",i.activityNo());data.put("activityType",i.record().activityType());data.put("confirmed",i.record().confirmed());data.put("endReason",i.record().endReason());if(i.done()){data.put("sentences",transcript(i.result().sentencesJson()).stream().map(s->text(s,"text")).toList());data.put("structured",i.structured());}return data;}).toList();
        Map<String,String> aliases=snapshot.report().childDisplayName()==null?Map.of():Map.of(snapshot.report().childDisplayName(),"아이");
        var generated=objects(ai.call("report",Map.of("sections",requestSections,"activities",activityData),aliases,job.deadline()).get("sections"));
        List<OutputChecker.Draft> drafts=new ArrayList<>();Map<Integer,List<ReportRepository.Citation>> chosen=new HashMap<>();Map<Integer,String> body=new HashMap<>();
        for(int no:List.of(2,5,6,10)){
            var section=generated.stream().filter(s->number(s,"sectionNo")==no).findFirst().orElse(Map.of());
            var validated=validateCitations(no,objects(section.get("citations")),candidates.get(no),inputs);
            String text=text(section,"text");
            boolean invalid=Boolean.TRUE.equals(section.get("missing"))||section.isEmpty()||validated.size()!=objects(section.get("citations")).size();
            // 5항목은 한 줄마다 구조화 출처가 있어야 한다. 출처 없는 줄은 채택하지 않는다.
            if(no==5&&(validated.isEmpty()||text.lines().filter(l->!l.isBlank()).count()!=validated.size()))invalid=true;
            body.put(no,text);chosen.put(no,validated);
            drafts.add(new OutputChecker.Draft("section"+no,invalid?null:text,validated.stream().map(ReportRepository.Citation::quote).filter(Objects::nonNull).toList(),null));
        }
        var checked=checker.check(drafts,job.deadline());
        for(int no:List.of(2,5,6,10)){
            boolean templated=checked.get("section"+no)==null;var citations=templated?candidates.get(no).stream().sorted(Comparator.comparing(QuoteCandidatePicker.Quote::at)).map(QuoteCandidatePicker.Quote::citation).toList():chosen.get(no);
            String text=templated?fallback(citations):body.get(no);boolean missing=templated&&citations.isEmpty();if(no==10)text=ReportAssembler.LIMITATION+"\n"+text;
            assembled.put(no,new ReportRepository.Section(no,ReportAssembler.TITLES.get(no-1),text,missing,templated,citations));
        }
        boolean done=job.commit(()->{for(var input:inputs)reports.resultStatus(job.subjectId(),input.record().sessionId(),input.status());if(!reports.writeSections(job.subjectId(),job.jobId(),assembler.level(inputs),List.copyOf(assembled.values())))throw new IllegalStateException("REPORT_DISCARDED");},()->{});
        return done?Outcome.done():Outcome.failed("REPORT_DISCARDED",false);
    }
    private Snapshot load(JobContext job){
        var report=reports.findOwned(job.subjectId(),job.guardianId()).orElse(null);if(report==null||!report.status().equals("GENERATING"))return null;
        List<ReportInput> inputs=new ArrayList<>();int ordinal=0;
        for(var selected:reports.activities(report.id())){
            var record=records.find(selected.sessionId(),job.guardianId()).orElseThrow().record();String status=states.find(record.sessionId(),job.guardianId()).map(s->s.jobStatus()).orElse("NONE");
            var result=status.equals("DONE")?results.current(record.sessionId()).orElse(null):null;
            List<Map<String,Object>> defs=new ArrayList<>();for(var g:goals(practice(record.practiceJson())))try{var d=definitions.get(text(g,"behaviorKey"),(int)number(g,"behaviorVersion"));defs.add(Map.of("displayName",d.displayName(),"coreFunction",d.behaviorDefinitionText()));}catch(IllegalArgumentException missing){/* BR12.2: 없는 고정 버전은 7항목에서 뺀다. */}
            inputs.add(new ReportInput(++ordinal,record,status,result,result==null?List.of():scenes.find(result.id()),result==null?Map.of():structures.find(result.id()).map(s->practice(s)).orElse(Map.of()),defs));
        }
        return new Snapshot(report,List.copyOf(inputs));
    }
    private List<ReportRepository.Citation> validateCitations(int section,List<Map<String,Object>> refs,List<QuoteCandidatePicker.Quote> candidates,List<ReportInput> inputs){
        List<ReportRepository.Citation> result=new ArrayList<>();
        for(var ref:refs){int no=(int)number(ref,"activityNo");long seq=number(ref,"utteranceSeq");String source=section==5?text(ref,"source"):"TRANSCRIPT";
            if(section==5&&source.equals("GUARDIAN_INPUT")){inputs.stream().filter(i->i.activityNo()==no&&hasSource(inputs,no,source,0)).findFirst().ifPresent(i->result.add(new ReportRepository.Citation(i.record().sessionId(),null,null,null,source)));continue;}
            if(section==5&&!source.equals("CHILD_RECALL"))continue;
            candidates.stream().filter(q->q.activityNo()==no&&q.utteranceSeq()==seq&&QuoteMatcher.matches(text(ref,"quote"),q.quote())).findFirst().ifPresent(q->result.add(q.citation()));
        }
        return result;
    }
    private static boolean hasSource(List<ReportInput> inputs,int activity,String source,long seq){return inputs.stream().filter(i->i.activityNo()==activity&&i.done()).flatMap(i->i.structured().values().stream()).flatMap(v->v instanceof Map<?,?>?java.util.stream.Stream.of(object(v)):objects(v).stream()).anyMatch(f->source.equals(f.get("source"))&&(source.equals("GUARDIAN_INPUT")||number(f,"utteranceSeq")==seq));}
    private static String fallback(List<ReportRepository.Citation> citations){if(citations.isEmpty())return "자료 부족";var format=DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ReportService.ZONE);return String.join("\n",citations.stream().filter(c->c.quote()!=null).map(c->"\""+c.quote()+"\" ("+format.format(c.at())+")").toList());}
}
