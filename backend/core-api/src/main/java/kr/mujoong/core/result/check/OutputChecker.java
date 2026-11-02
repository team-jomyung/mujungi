package kr.mujoong.core.result.check;
import java.time.Instant;
import java.util.*;
import kr.mujoong.core.result.ResultAiException;
import kr.mujoong.core.result.ResultTextGateway;
import kr.mujoong.core.observability.EventLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
/** BR5.2: 인용→숫자→사전 뒤 통과 문장만 한 번에 2차 분류한다. */
@Component
public class OutputChecker {
    private static final java.util.Set<String> CATEGORIES=java.util.Set.of("DIAGNOSIS","TREATMENT","SCORE","CAUSE","GENERALIZATION","EMOTION_CLAIM","PRESSURE");
    private final ForbiddenDictionary dictionary;private final ResultTextGateway gateway;private final EventLogger events;
    public OutputChecker(ForbiddenDictionary dictionary,ResultTextGateway gateway){this(dictionary,gateway,null);}
    /** events가 있으면 걸린 문장마다 단계(인용 · 숫자 · 사전 · 2차 분류 종류)만 진단 한 줄로 남긴다. 문장 원문은 남기지 않는다(R-F14). */
    @Autowired
    public OutputChecker(ForbiddenDictionary dictionary,ResultTextGateway gateway,EventLogger events){this.dictionary=dictionary;this.gateway=gateway;this.events=events;}
    /** 서버 호출자가 정하는 용도다. 모델 응답이나 사용자 입력으로 선택하지 않는다. */
    public enum TextUse { OBSERVED_EVIDENCE, PRACTICE_PROPOSAL }
    public record Draft(String id,String text,List<String> verifiedQuotes,String fallback,TextUse textUse){
        public Draft(String id,String text,List<String> verifiedQuotes,String fallback){this(id,text,verifiedQuotes,fallback,TextUse.OBSERVED_EVIDENCE);}
        public Draft {Objects.requireNonNull(textUse);}
    }
    public boolean firstPass(Draft draft){return firstPassFailure(draft).isEmpty();}
    /** 1차 검사에서 처음 걸린 단계(EMPTY · QUOTE · NUMBER · DICTIONARY). 진단 코드용이며 원문은 돌려주지 않는다. */
    public Optional<String> firstPassFailure(Draft draft){
        if(draft.text()==null||draft.text().isBlank())return Optional.of("EMPTY");
        if(draft.textUse()==TextUse.PRACTICE_PROPOSAL){
            if(!NumberGuard.allowedLiteral(draft.text()))return Optional.of("NUMBER");
            return dictionary.allowedLiteral(draft.text())?Optional.empty():Optional.of("DICTIONARY");
        }
        if(!QuoteMatcher.allVerified(draft.text(),draft.verifiedQuotes()))return Optional.of("QUOTE");
        if(!NumberGuard.allowed(draft.text()))return Optional.of("NUMBER");
        return dictionary.allowed(draft.text())?Optional.empty():Optional.of("DICTIONARY");
    }
    public Map<String,String> check(List<Draft> drafts,Instant deadline){
        return check(drafts,deadline,()->{});
    }
    public Map<String,String> check(List<Draft> drafts,Instant deadline,Runnable accessCheck){
        Map<String,String> result=new LinkedHashMap<>();List<Draft> clean=new ArrayList<>();
        for(Draft draft:drafts){if(result.containsKey(draft.id()))throw new IllegalArgumentException("DUPLICATE_TEXT_ID");result.put(draft.id(),draft.fallback());
            var failure=firstPassFailure(draft);if(failure.isEmpty())clean.add(draft);else rejected(failure.get());}
        if(clean.isEmpty())return result;
        var input=clean.stream().map(d->d.textUse()==TextUse.PRACTICE_PROPOSAL
                ?Map.<String,Object>of("id",d.id(),"text",d.text(),"textUse",d.textUse().name())
                :Map.<String,Object>of("id",d.id(),"text",d.text())).toList();
        var output=gateway.call("classify",Map.of("sentences",input),Map.of(),deadline,accessCheck);
        if(!(output.get("results") instanceof List<?> rows))throw new ResultAiException("SCHEMA_INVALID");
        Map<String,Boolean> violations=new HashMap<>();
        Map<String,String> categories=new HashMap<>();
        for(Object row:rows){if(!(row instanceof Map<?,?> m)||!(m.get("id") instanceof String id)||!(m.get("violation") instanceof Boolean violation)||violations.putIfAbsent(id,violation)!=null)throw new ResultAiException("SCHEMA_INVALID");
            if(m.get("category") instanceof String category&&CATEGORIES.contains(category))categories.put(id,category);}
        if(!violations.keySet().equals(clean.stream().map(Draft::id).collect(java.util.stream.Collectors.toSet())))throw new ResultAiException("SCHEMA_INVALID");
        for(Draft d:clean){if(!violations.get(d.id()))result.put(d.id(),d.text());else rejected("CLASSIFY_"+categories.getOrDefault(d.id(),"UNKNOWN"));}
        return result;
    }
    /** 예: QUOTE → ResultTextRejectedQuote, CLASSIFY_EMOTION_CLAIM → ResultTextRejectedClassifyEmotionClaim. */
    private void rejected(String stage){
        if(events==null)return;
        var kind=new StringBuilder("ResultTextRejected");
        for(String word:stage.split("_"))if(!word.isEmpty())kind.append(word.charAt(0)).append(word.substring(1).toLowerCase(java.util.Locale.ROOT));
        events.appError(kr.mujoong.contract.log.EventLog.Component.CORE_MAINTENANCE,kind.toString());
    }
}
