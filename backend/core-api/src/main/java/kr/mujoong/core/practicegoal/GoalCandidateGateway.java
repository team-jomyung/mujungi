package kr.mujoong.core.practicegoal;

import static kr.mujoong.core.result.ResultData.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.*;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.core.knowledge.ChildKnowledgeService;
import kr.mujoong.core.luna.*;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.result.ResultAiException;
import kr.mujoong.core.result.check.OutputChecker;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/** U14 new-schema provider; legacy U6 providers remain supported separately. */
@Service
public class GoalCandidateGateway {
    private static final java.util.regex.Pattern STORED_UUID=java.util.regex.Pattern.compile("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private final Optional<CoreLunaClient> luna;
    private final ChildKnowledgeService knowledge;
    private final GoalCandidateValidator validator;
    private static final String PROMPT_VERSION="goal-candidates.v4";
    /** 화용 지표 코드(FT-1029-b, ai-goal-candidate-output.schema.json assessment). */
    private static final List<String> INDICATORS=List.of("P1","P2","P3","P4","P5","U5");
    private static final Map<String,String> CHECK_FIELDS=Map.of("goal-reason","REASON_TEXT","goal-display","DISPLAY_EVIDENCE","goal-name","DISPLAY_NAME","goal-target","TARGET_EXPRESSION");
    private final OutputChecker checker;
    private final EventLogger events;
    private final JsonCodec json=JsonCodec.shared();
    public GoalCandidateGateway(Optional<CoreLunaClient> luna,ChildKnowledgeService knowledge,GoalCandidateValidator validator,OutputChecker checker,EventLogger events){this.luna=luna;this.knowledge=knowledge;this.validator=validator;this.checker=checker;this.events=events;}
    public boolean available(){return luna.isPresent();}
    public List<GoalCandidateRepository.Draft> generate(UUID guardian,UUID child,Map<String,Object> input,Map<String,String> aliases,Instant deadline,Runnable access){
        access.run();var catalog=knowledge.seedSummaries(guardian,child);
        Map<String,Object> request=new LinkedHashMap<>(input);
        request.put("seedSummaries",catalog.stream().map(s->Map.of("seedId",s.seedId(),"label",s.label(),"signature",s.signature(),"invariantSentence",s.invariantSentence())).toList());
        // Whitelist input fields: age, account identifiers, raw guardian input and audio never enter this request.
        request.keySet().retainAll(Set.of("transcript","structured","scenes","seedSummaries","endReason"));
        // Only the modern candidate boundary excludes uncertain material; legacy result readers are unchanged.
        request.put("transcript",objects(request.get("transcript")).stream().filter(row->!Boolean.TRUE.equals(row.get("uncertain"))).toList());
        // AI가 같은 말을 되풀이한 구간은 근거에서 빼고 보낸다(FT-1027-i). 인용 검사도 남은 전사로 하므로 그 구간의 아이 발화는 근거가 될 수 없다
        request.put("transcript",RepeatedAiSpan.without(objects(request.get("transcript"))));
        request=new LinkedHashMap<>(new LunaInputSanitizer(json).sanitize(request,aliases).payload());
        // Local UUIDs become per-call aliases; the separate seed:* skillRef catalog identifiers stay unchanged.
        Map<String,String> references=new HashMap<>();
        request.replaceAll((key,value)->referenceIds(value,references));
        var quotes=childQuotes(objects(request.get("transcript")));
        String prompt;try{prompt=new ClassPathResource("prompts/goal/"+PROMPT_VERSION+".txt").getContentAsString(StandardCharsets.UTF_8);}catch(java.io.IOException missing){throw new ResultAiException("INTERNAL");}
        access.run();LunaResult generated;
        try{generated=luna.orElseThrow(()->new ResultAiException("PROVIDER_UNAVAILABLE")).call(LunaRequest.of(LunaPurpose.GOAL_CANDIDATES,PROMPT_VERSION,prompt,request,Map.of(),deadline));}
        catch(LunaException failed){throw new ResultAiException(failed.reason()==LunaFailureReason.TIMEOUT?"TIMEOUT":"PROVIDER_ERROR");}
        Map<String,Object> output=object(json.read(generated.outputJson(),Map.class));
        if(!(output.get("candidates") instanceof List<?>))throw new ResultAiException("SCHEMA_INVALID");
        var proposed=objects(output.get("candidates"));
        assessed(guardian,proposed.size(),output.get("assessment"));
        var validated=validator.check(proposed,catalog.stream().map(s->s.seedId()).collect(java.util.stream.Collectors.toSet()),quotes);
        // 서버 검사에서 빠진 후보는 사유 코드만 남긴다(R-F14: 후보 글 · 인용 없음). 모델이 0개를 냈는지, 냈는데 서버가 버렸는지를 가른다
        for(var drop:validated.dropped())dropped(guardian,proposed.size(),drop);
        var candidates=validated.kept();
        var checks=new ArrayList<OutputChecker.Draft>();
        for(int i=0;i<candidates.size();i++){var c=candidates.get(i);var refs=c.evidence().stream().map(GoalCandidateRepository.Evidence::quote).toList();checks.add(new OutputChecker.Draft("goal-reason-"+i,c.reasonText(),refs,null));checks.add(new OutputChecker.Draft("goal-display-"+i,c.displayEvidence(),refs,null));checks.add(new OutputChecker.Draft("goal-name-"+i,c.displayName(),refs,null));checks.add(new OutputChecker.Draft("goal-target-"+i,c.targetExpression(),refs,null,OutputChecker.TextUse.PRACTICE_PROPOSAL));}
        var checked=checker.check(checks,deadline,access);var kept=new ArrayList<GoalCandidateRepository.Draft>();
        for(int i=0;i<candidates.size();i++){
            OutputChecker.Draft failed=null;
            for(int j=i*4;j<i*4+4&&failed==null;j++)if(checked.get(checks.get(j).id())==null)failed=checks.get(j);
            if(failed==null){kept.add(candidates.get(i));continue;}
            String reason="CHECK_"+checker.firstPassFailure(failed).orElse("CLASSIFIER");
            dropped(guardian,proposed.size(),new GoalCandidateValidator.Drop(validated.indexes().get(i),reason,CHECK_FIELDS.get(failed.id().substring(0,failed.id().lastIndexOf('-')))));
        }
        return List.copyOf(kept);
    }
    /** 화용 지표 평가 요약 한 줄(FT-1029-b). 지표 코드만 판정별로 나눠 싣고 근거 · 사유 글은 담지 않는다(R-F14). */
    private void assessed(UUID guardian,int proposed,Object assessment){
        Map<String,List<String>> byRating=new LinkedHashMap<>();
        for(String rating:List.of("NEED","OK","NOT_OBSERVED"))byRating.put(rating,new ArrayList<>());
        if(assessment instanceof List<?> rows)for(var row:rows)
            if(row instanceof Map<?,?> item&&item.get("indicator") instanceof String indicator&&INDICATORS.contains(indicator)
                    &&item.get("rating") instanceof String rating&&byRating.containsKey(rating))byRating.get(rating).add(indicator);
        events.log(EventCode.GOAL_CANDIDATES_ASSESSED,EventLog.Component.CORE_MAINTENANCE,guardian,entry->entry.attr(AttrKey.PROMPT_VERSION,PROMPT_VERSION)
                .attr(AttrKey.CANDIDATES_PROPOSED,proposed).attr(AttrKey.NEED_INDICATORS,byRating.get("NEED"))
                .attr(AttrKey.OK_INDICATORS,byRating.get("OK")).attr(AttrKey.NOT_OBSERVED_INDICATORS,byRating.get("NOT_OBSERVED")));
    }
    private void dropped(UUID guardian,int proposed,GoalCandidateValidator.Drop drop){
        events.log(EventCode.GOAL_CANDIDATE_DROPPED,EventLog.Component.CORE_MAINTENANCE,guardian,entry->entry.attr(AttrKey.PROMPT_VERSION,PROMPT_VERSION)
                .attr(AttrKey.CANDIDATE_INDEX,drop.index()).attr(AttrKey.CANDIDATES_PROPOSED,proposed).attr(AttrKey.DROP_REASON,drop.reason()).attr(AttrKey.DROP_FIELD,drop.field()));
    }
    private Object referenceIds(Object value,Map<String,String> references){
        if(value instanceof Map<?,?> map){Map<String,Object> result=new LinkedHashMap<>();map.forEach((key,item)->result.put((String)referenceIds(String.valueOf(key),references),referenceIds(item,references)));return result;}
        if(value instanceof List<?> list)return list.stream().map(item->referenceIds(item,references)).toList();
        if(value instanceof String text){var matches=STORED_UUID.matcher(text);return matches.replaceAll(match->references.computeIfAbsent(match.group().toLowerCase(Locale.ROOT),id->"참조-"+UUID.randomUUID()));}
        return value;
    }
}
