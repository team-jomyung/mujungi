package kr.mujoong.core.practicegoal;

import static kr.mujoong.core.result.ResultData.*;
import java.util.*;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.knowledge.StructureSignature;
import kr.mujoong.core.result.check.ForbiddenDictionary;
import kr.mujoong.core.result.check.NumberGuard;
import org.springframework.stereotype.Component;

/** Rejects individual unsupported candidates, without inventing a predefined behavior. */
@Component
public class GoalCandidateValidator {
    static final int MAX_KEPT=3;
    private final ForbiddenDictionary dictionary;
    private final JsonCodec json=JsonCodec.shared();
    /** 빠진 후보 하나: 모델 출력의 몇 번째(0부터)가 어느 검사(reason)의 어느 칸(field)에서 빠졌는지. 원문은 담지 않는다. */
    public record Drop(int index,String reason,String field){}
    /** 남은 후보와 그 원래 순번, 빠진 후보의 사유. */
    public record Checked(List<GoalCandidateRepository.Draft> kept,List<Integer> indexes,List<Drop> dropped){}
    private record Verdict(GoalCandidateRepository.Draft draft,String reason,String field){
        static Verdict drop(String reason,String field){return new Verdict(null,reason,field);}
    }
    public GoalCandidateValidator(ForbiddenDictionary dictionary){this.dictionary=dictionary;}
    public List<GoalCandidateRepository.Draft> validate(List<Map<String,Object>> drafts,Set<String> skills,Map<Long,String> quotes){
        return check(drafts,skills,quotes).kept();
    }
    public Checked check(List<Map<String,Object>> drafts,Set<String> skills,Map<Long,String> quotes){
        var kept=new ArrayList<GoalCandidateRepository.Draft>();var indexes=new ArrayList<Integer>();var dropped=new ArrayList<Drop>();var seen=new HashSet<String>();
        for(int i=0;i<drafts.size();i++){
            if(kept.size()==MAX_KEPT){dropped.add(new Drop(i,"OVER_LIMIT","NONE"));continue;}
            var verdict=validate(drafts.get(i),skills,quotes);
            if(verdict.draft()==null){dropped.add(new Drop(i,verdict.reason(),verdict.field()));continue;}
            if(!seen.add(verdict.draft().displayName()+":"+verdict.draft().targetExpression())){dropped.add(new Drop(i,"DUPLICATE","NONE"));continue;}
            kept.add(verdict.draft());indexes.add(i);
        }
        return new Checked(List.copyOf(kept),List.copyOf(indexes),List.copyOf(dropped));
    }
    private Verdict validate(Map<String,Object> d,Set<String> skills,Map<Long,String> quotes){
        for(var limit:List.of(Map.entry("displayName",12),Map.entry("displayEvidence",40),Map.entry("targetExpression",200),Map.entry("reasonText",500),Map.entry("skillRef",80)))
            if(!bounded(d,limit.getKey(),limit.getValue()))return Verdict.drop("LENGTH",field(limit.getKey()));
        String skill=text(d,"skillRef");if(!"NONE".equals(skill)&&!skills.contains(skill))return Verdict.drop("SKILL_REF_UNKNOWN","SKILL_REF");
        var elements=object(d.get("fourElements"));
        for(var limit:List.of(Map.entry("situation",500),Map.entry("partner",200),Map.entry("intent",500),Map.entry("expression",500)))
            if(!bounded(elements,limit.getKey(),limit.getValue()))return Verdict.drop("LENGTH",field(limit.getKey()));
        var evidence=objects(d.get("evidence"));if(evidence.isEmpty())return Verdict.drop("NO_EVIDENCE","EVIDENCE");
        for(var e:evidence)if(number(e,"utteranceSeq")<1||!bounded(e,"quote",2000)||!Objects.equals(quotes.get(number(e,"utteranceSeq")),e.get("quote")))return Verdict.drop("QUOTE_MISMATCH","EVIDENCE");
        for(String key:List.of("displayName","displayEvidence","targetExpression","reasonText")){var unsafe=unsafe(text(d,key));if(unsafe!=null)return Verdict.drop(unsafe,field(key));}
        for(String key:List.of("situation","partner","intent","expression")){var unsafe=unsafe(text(elements,key));if(unsafe!=null)return Verdict.drop(unsafe,field(key));}
        StructureSignature signature=null;
        if(d.get("structureSignature")!=null)try{signature=json.read(json.write(d.get("structureSignature")),StructureSignature.class);if(unsafe(signature.requiredInference())!=null)signature=null;}catch(IllegalArgumentException|JsonCodec.JsonCodecException invalid){signature=null;}
        return new Verdict(new GoalCandidateRepository.Draft(text(d,"displayName"),text(d,"displayEvidence"),text(d,"targetExpression"),new FourElements(text(elements,"situation"),text(elements,"partner"),text(elements,"intent"),text(elements,"expression")),skill,text(d,"reasonText"),evidence.stream().map(e->new GoalCandidateRepository.Evidence(number(e,"utteranceSeq"),text(e,"quote"))).toList(),signature),null,null);
    }
    private boolean bounded(Map<String,Object> value,String key,int max){String s=text(value,key);return !s.isBlank()&&s.codePointCount(0,s.length())<=max;}
    /** 걸린 검사 코드, 통과면 null. 사전을 숫자보다 먼저 본다(기존 safe와 같은 순서). */
    private String unsafe(String value){if(!dictionary.allowedLiteral(value))return "FORBIDDEN_DICTIONARY";return NumberGuard.allowed(value)?null:"NUMBER_GUARD";}
    /** displayName → DISPLAY_NAME */
    static String field(String key){return key.replaceAll("([a-z])([A-Z])","$1_$2").toUpperCase(Locale.ROOT);}
}
