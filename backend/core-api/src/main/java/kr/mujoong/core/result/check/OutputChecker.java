package kr.mujoong.core.result.check;
import java.time.Instant;
import java.util.*;
import kr.mujoong.core.result.ResultAiException;
import kr.mujoong.core.result.ResultTextGateway;
import org.springframework.stereotype.Component;
/** BR5.2: 인용→숫자→사전 뒤 통과 문장만 한 번에 2차 분류한다. */
@Component
public class OutputChecker {
    private final ForbiddenDictionary dictionary;private final ResultTextGateway gateway;
    public OutputChecker(ForbiddenDictionary dictionary,ResultTextGateway gateway){this.dictionary=dictionary;this.gateway=gateway;}
    public record Draft(String id,String text,List<String> verifiedQuotes,String fallback){}
    public boolean firstPass(Draft draft){return draft.text()!=null&&!draft.text().isBlank()&&QuoteMatcher.allVerified(draft.text(),draft.verifiedQuotes())&&NumberGuard.allowed(draft.text())&&dictionary.allowed(draft.text());}
    public Map<String,String> check(List<Draft> drafts,Instant deadline){
        Map<String,String> result=new LinkedHashMap<>();List<Draft> clean=new ArrayList<>();
        for(Draft draft:drafts){if(result.containsKey(draft.id()))throw new IllegalArgumentException("DUPLICATE_TEXT_ID");result.put(draft.id(),draft.fallback());if(firstPass(draft))clean.add(draft);}
        if(clean.isEmpty())return result;
        var input=clean.stream().map(d->Map.<String,Object>of("id",d.id(),"text",d.text())).toList();
        var output=gateway.call("classify",Map.of("sentences",input),Map.of(),deadline);
        if(!(output.get("results") instanceof List<?> rows))throw new ResultAiException("SCHEMA_INVALID");
        Map<String,Boolean> violations=new HashMap<>();
        for(Object row:rows){if(!(row instanceof Map<?,?> m)||!(m.get("id") instanceof String id)||!(m.get("violation") instanceof Boolean violation)||violations.putIfAbsent(id,violation)!=null)throw new ResultAiException("SCHEMA_INVALID");}
        if(!violations.keySet().equals(clean.stream().map(Draft::id).collect(java.util.stream.Collectors.toSet())))throw new ResultAiException("SCHEMA_INVALID");
        for(Draft d:clean)if(!violations.get(d.id()))result.put(d.id(),d.text());
        return result;
    }
}
