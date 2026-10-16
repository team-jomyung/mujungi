package kr.mujoong.core.practicegoal;

import static kr.mujoong.core.result.ResultData.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.*;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.knowledge.ChildKnowledgeService;
import kr.mujoong.core.luna.*;
import kr.mujoong.core.result.ResultAiException;
import kr.mujoong.core.result.check.OutputChecker;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/** U14 new-schema provider; legacy U6 providers remain supported separately. */
@Service
public class GoalCandidateGateway {
    private final Optional<CoreLunaClient> luna;
    private final ChildKnowledgeService knowledge;
    private final GoalCandidateValidator validator;
    private final OutputChecker checker;
    private final JsonCodec json=JsonCodec.shared();
    public GoalCandidateGateway(Optional<CoreLunaClient> luna,ChildKnowledgeService knowledge,GoalCandidateValidator validator,OutputChecker checker){this.luna=luna;this.knowledge=knowledge;this.validator=validator;this.checker=checker;}
    public boolean available(){return luna.isPresent();}
    public List<GoalCandidateRepository.Draft> generate(UUID guardian,UUID child,Map<String,Object> input,Map<String,String> aliases,Instant deadline,Runnable access){
        access.run();var catalog=knowledge.seedSummaries(guardian,child);
        Map<String,Object> request=new LinkedHashMap<>(input);
        request.put("seedSummaries",catalog.stream().map(s->Map.of("seedId",s.seedId(),"label",s.label(),"signature",s.signature(),"invariantSentence",s.invariantSentence())).toList());
        // Whitelist input fields: age, account identifiers, raw guardian input and audio never enter this request.
        request.keySet().retainAll(Set.of("transcript","structured","scenes","seedSummaries","endReason"));
        request=new LinkedHashMap<>(new LunaInputSanitizer(json).sanitize(request,aliases).payload());
        var quotes=childQuotes(objects(request.get("transcript")));
        String prompt;try{prompt=new ClassPathResource("prompts/goal/goal-candidates.v1.txt").getContentAsString(StandardCharsets.UTF_8);}catch(java.io.IOException missing){throw new ResultAiException("INTERNAL");}
        access.run();LunaResult generated;
        try{generated=luna.orElseThrow(()->new ResultAiException("PROVIDER_UNAVAILABLE")).call(LunaRequest.of(LunaPurpose.GOAL_CANDIDATES,"goal-candidates.v1",prompt,request,Map.of(),deadline));}
        catch(LunaException failed){throw new ResultAiException(failed.reason()==LunaFailureReason.TIMEOUT?"TIMEOUT":"PROVIDER_ERROR");}
        Map<String,Object> output=object(json.read(generated.outputJson(),Map.class));
        if(!(output.get("candidates") instanceof List<?>))throw new ResultAiException("SCHEMA_INVALID");
        var candidates=validator.validate(objects(output.get("candidates")),catalog.stream().map(s->s.seedId()).collect(java.util.stream.Collectors.toSet()),quotes);
        var checks=new ArrayList<OutputChecker.Draft>();
        for(int i=0;i<candidates.size();i++){var c=candidates.get(i);var refs=c.evidence().stream().map(GoalCandidateRepository.Evidence::quote).toList();checks.add(new OutputChecker.Draft("goal-reason-"+i,c.reasonText(),refs,null));checks.add(new OutputChecker.Draft("goal-display-"+i,c.displayEvidence(),refs,null));}
        var checked=checker.check(checks,deadline,access);var kept=new ArrayList<GoalCandidateRepository.Draft>();
        for(int i=0;i<candidates.size();i++)if(checked.get("goal-reason-"+i)!=null&&checked.get("goal-display-"+i)!=null)kept.add(candidates.get(i));
        return List.copyOf(kept);
    }
}
