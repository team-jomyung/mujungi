package kr.mujoong.core.report;
import static kr.mujoong.core.result.ResultData.*;
import java.time.Instant;
import java.util.*;
import kr.mujoong.core.result.ResultData;
import org.springframework.stereotype.Component;
/** BR9.7: 최근 활동부터 의미 있는 확정 아이 발화를 우선하여 활동당 5개·항목당 30개. */
@Component
public class QuoteCandidatePicker {
    public record Quote(int activityNo,UUID sessionId,long utteranceSeq,String quote,Instant at,String source){
        public ReportRepository.Citation citation(){return new ReportRepository.Citation(sessionId,utteranceSeq,quote,at,source);}
        public Map<String,Object> ai(){return Map.of("activityNo",activityNo,"utteranceSeq",utteranceSeq,"quote",quote,"source",source);}
        @Override public String toString(){return "Quote[activityNo="+activityNo+", utteranceSeq="+utteranceSeq+"]";}
    }
    public List<Quote> pick(List<ReportInput> inputs){
        List<Quote> result=new ArrayList<>();
        for(var input:inputs.stream().filter(ReportInput::aiAllowed).sorted(Comparator.comparing((ReportInput i)->i.record().listedAt()).reversed().thenComparingInt(ReportInput::activityNo)).toList()){
            var utterances=transcript(input.record().transcriptJson()).stream().filter(u->"CHILD".equals(u.get("speaker"))&&!Boolean.TRUE.equals(u.get("uncertain")))
                .sorted(Comparator.comparing((Map<String,Object> u)->!ResultData.meaningful(u)).thenComparingLong(u->number(u,"seq"))).limit(5).toList();
            for(var u:utterances){if(result.size()==30)return List.copyOf(result);result.add(new Quote(input.activityNo(),input.record().sessionId(),number(u,"seq"),text(u,"text"),at(u,input.record().listedAt()),"TRANSCRIPT"));}
        }
        return List.copyOf(result);
    }
    private static Instant at(Map<String,Object> u,Instant fallback){try{return Instant.parse(text(u,"startedAt"));}catch(RuntimeException e){return fallback;}}
}
