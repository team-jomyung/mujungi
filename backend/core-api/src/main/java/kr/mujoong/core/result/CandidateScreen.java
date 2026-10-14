package kr.mujoong.core.result;
import java.util.*;
import kr.mujoong.core.knowledge.BehaviorDefinition;
import kr.mujoong.core.practice.CandidateRegistry;
import kr.mujoong.core.result.check.*;
import org.springframework.stereotype.Component;
/** BR6.2: 근거 없는 후보·보호자 입력만 근거인 후보를 버린다. */
@Component
public class CandidateScreen {
    private final ForbiddenDictionary dictionary;
    private final kr.mujoong.core.observability.EventLogger events;
    public CandidateScreen(ForbiddenDictionary dictionary,kr.mujoong.core.observability.EventLogger events){this.dictionary=dictionary;this.events=events;}
    public List<CandidateRegistry.Candidate> screen(List<Map<String,Object>> drafts,List<BehaviorDefinition> active,Map<Long,String> quotes){
        List<CandidateRegistry.Candidate> kept=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(var draft:drafts){String key=ResultData.text(draft,"behaviorKey"),reason=ResultData.text(draft,"reasonText");int version=(int)ResultData.number(draft,"behaviorVersion");
            if(active.stream().noneMatch(d->d.behaviorKey().equals(key)&&d.version()==version&&d.status().equals("ACTIVE"))||!dictionary.allowed(reason)||!NumberGuard.allowed(reason)||!seen.add(key+":"+version)){rejected("ResultCandidateRejected");continue;}
            var refs=ResultData.objects(draft.get("evidence")).stream().filter(e->"CHILD_RECALL".equals(e.get("kind"))&&QuoteMatcher.matches(ResultData.text(e,"quote"),quotes.get(ResultData.number(e,"utteranceSeq"))))
                .map(e->new CandidateRegistry.Evidence(ResultData.number(e,"utteranceSeq"),"CHILD_RECALL")).distinct().toList();
            if(refs.size()!=ResultData.objects(draft.get("evidence")).size())rejected("ResultEvidenceRejected");
            if(refs.isEmpty()||!QuoteMatcher.allVerified(reason,refs.stream().map(e->quotes.get(e.utteranceSeq())).toList())){rejected("ResultCandidateNoEvidence");continue;}
            if(kept.size()==3){rejected("ResultCandidateLimit");continue;}
            kept.add(new CandidateRegistry.Candidate(key,version,reason,refs));
        }
        return List.copyOf(kept);
    }
    private void rejected(String code){events.appError(kr.mujoong.contract.log.EventLog.Component.CORE_MAINTENANCE,code);}
}
