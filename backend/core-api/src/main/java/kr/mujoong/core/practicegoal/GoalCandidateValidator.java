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
    private final ForbiddenDictionary dictionary;
    private final JsonCodec json=JsonCodec.shared();
    public GoalCandidateValidator(ForbiddenDictionary dictionary){this.dictionary=dictionary;}
    public List<GoalCandidateRepository.Draft> validate(List<Map<String,Object>> drafts,Set<String> skills,Map<Long,String> quotes){
        var kept=new ArrayList<GoalCandidateRepository.Draft>();var seen=new HashSet<String>();
        for(var draft:drafts){var checked=validate(draft,skills,quotes);if(checked.isPresent()&&seen.add(checked.get().displayName()+":"+checked.get().targetExpression()))kept.add(checked.get());if(kept.size()==3)break;}
        return List.copyOf(kept);
    }
    private Optional<GoalCandidateRepository.Draft> validate(Map<String,Object> d,Set<String> skills,Map<Long,String> quotes){
        if(!bounded(d,"displayName",12)||!bounded(d,"displayEvidence",40)||!bounded(d,"targetExpression",200)||!bounded(d,"reasonText",500)||!bounded(d,"skillRef",80))return Optional.empty();
        String skill=text(d,"skillRef");if(!"NONE".equals(skill)&&!skills.contains(skill))return Optional.empty();
        var elements=object(d.get("fourElements"));
        if(!bounded(elements,"situation",500)||!bounded(elements,"partner",200)||!bounded(elements,"intent",500)||!bounded(elements,"expression",500))return Optional.empty();
        var evidence=objects(d.get("evidence"));if(evidence.isEmpty())return Optional.empty();
        for(var e:evidence)if(number(e,"utteranceSeq")<1||!bounded(e,"quote",2000)||!Objects.equals(quotes.get(number(e,"utteranceSeq")),e.get("quote")))return Optional.empty();
        for(String key:List.of("displayName","displayEvidence","targetExpression","reasonText"))if(!safe(text(d,key)))return Optional.empty();
        for(String key:List.of("situation","partner","intent","expression"))if(!safe(text(elements,key)))return Optional.empty();
        StructureSignature signature=null;
        if(d.get("structureSignature")!=null)try{signature=json.read(json.write(d.get("structureSignature")),StructureSignature.class);if(!safe(signature.requiredInference()))signature=null;}catch(IllegalArgumentException|JsonCodec.JsonCodecException invalid){signature=null;}
        return Optional.of(new GoalCandidateRepository.Draft(text(d,"displayName"),text(d,"displayEvidence"),text(d,"targetExpression"),new FourElements(text(elements,"situation"),text(elements,"partner"),text(elements,"intent"),text(elements,"expression")),skill,text(d,"reasonText"),evidence.stream().map(e->new GoalCandidateRepository.Evidence(number(e,"utteranceSeq"),text(e,"quote"))).toList(),signature));
    }
    private boolean bounded(Map<String,Object> value,String key,int max){String s=text(value,key);return !s.isBlank()&&s.codePointCount(0,s.length())<=max;}
    private boolean safe(String value){return dictionary.allowedLiteral(value)&&NumberGuard.allowed(value);}
}
