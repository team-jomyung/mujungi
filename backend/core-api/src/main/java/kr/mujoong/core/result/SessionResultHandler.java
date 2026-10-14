package kr.mujoong.core.result;

import static kr.mujoong.core.result.ResultData.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.activity.ActivityContextRepository;
import kr.mujoong.core.consent.ConsentPolicyService;
import kr.mujoong.core.identity.ChildRepository;
import kr.mujoong.core.knowledge.BehaviorDefinitionLookup;
import kr.mujoong.core.practice.*;
import kr.mujoong.core.record.ActivityRecordRepository.StoredRecord;
import kr.mujoong.core.record.ResultRecordReader;
import kr.mujoong.core.result.check.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** WF3·WF4: 읽기 → 트랜잭션 밖 AI → 한 번 확정 → 후보 등록 후크. */
@Component
public class SessionResultHandler implements JobHandler {
    private final ResultRecordReader records;private final ActivityContextRepository contexts;private final ConsentPolicyService consent;
    private final ChildRepository children;private final BehaviorDefinitionLookup definitions;private final ScenarioLookup scenarios;
    private final ResultTextGateway ai;private final OutputChecker checker;private final CandidateScreen candidateScreen;private final TipMatcher tips;
    private final NotDoneReasonMapper reasons;private final ActivityResultRepository results;private final StructuredExperienceRepository structured;
    private final SceneJudgmentRepository judgments;private final CandidateRegistry registry;private final JsonCodec json;
    private final TransactionTemplate tx;
    public SessionResultHandler(ResultRecordReader records,ActivityContextRepository contexts,ConsentPolicyService consent,ChildRepository children,
            BehaviorDefinitionLookup definitions,ScenarioLookup scenarios,ResultTextGateway ai,OutputChecker checker,CandidateScreen candidateScreen,
            TipMatcher tips,NotDoneReasonMapper reasons,ActivityResultRepository results,StructuredExperienceRepository structured,
            SceneJudgmentRepository judgments,CandidateRegistry registry,JsonCodec json,PlatformTransactionManager transactions){
        this.records=records;this.contexts=contexts;this.consent=consent;this.children=children;this.definitions=definitions;this.scenarios=scenarios;
        this.ai=ai;this.checker=checker;this.candidateScreen=candidateScreen;this.tips=tips;this.reasons=reasons;this.results=results;this.structured=structured;
        this.judgments=judgments;this.registry=registry;this.json=json;tx=new TransactionTemplate(transactions);
    }
    public String jobType(){return "SESSION_RESULT";}
    private record Input(StoredRecord record,List<Map<String,Object>> transcript,Map<String,Object> practice,Map<String,Object> background,
            Map<String,String> aliases,boolean consent){}
    public Outcome handle(JobContext job){
        Input input=tx.execute(t->load(job));if(input==null)return Outcome.failed("INTERNAL",false);
        var r=input.record();var transcript=input.transcript();var practice=input.practice();var quotes=childQuotes(transcript);
        String level=ResultLevelCalculator.before(r,transcript,practice,input.consent());boolean insufficient=level.equals("INSUFFICIENT");
        boolean free=r.activityType().equals("FREE_TALK");
        Map<String,Object> experience=new LinkedHashMap<>();List<SceneJudgmentRepository.Scene> scenes=new ArrayList<>();
        List<Map<String,Object>> sentenceDrafts=List.of();List<CandidateRegistry.Candidate> candidateDrafts=List.of();
        Map<String,Object> common=new LinkedHashMap<>();common.put("transcript",transcript);common.put("endReason",r.endReason());
        if(!insufficient){
            if(free){var structureInput=new LinkedHashMap<>(common);structureInput.putAll(input.background());
                experience=verifiedExperience(ai.call("structure",structureInput,input.aliases(),job.deadline()),quotes);
            }else{
                var judgeInput=new LinkedHashMap<>(common);judgeInput.put("goals",judgmentGoals(practice,job.guardianId(),r.sessionId()));
                var output=ai.call("judgment",judgeInput,input.aliases(),job.deadline());
                scenes=sceneRows(practice,output,quotes,false);
            }
            level=ResultLevelCalculator.after(r,practice,experience);
            var sentenceInput=new LinkedHashMap<>(common);sentenceInput.put("structured",experience);sentenceInput.put("scenes",publicScenes(scenes));
            sentenceDrafts=objects(ai.call("sentences",sentenceInput,input.aliases(),job.deadline()).get("sentences"));
            var active=definitions.active();var candidateInput=new LinkedHashMap<>(common);candidateInput.put("structured",experience);candidateInput.put("scenes",publicScenes(scenes));
            candidateInput.put("definitions",active.stream().map(d->Map.of("behaviorKey",d.behaviorKey(),"behaviorVersion",d.version(),"displayName",d.displayName(),"coreFunction",d.behaviorDefinitionText())).toList());
            candidateDrafts=candidateScreen.screen(objects(ai.call("candidates",candidateInput,input.aliases(),job.deadline()).get("candidates")),active,quotes);
        }else if(!free&&!practice.isEmpty())scenes=sceneRows(practice,Map.of(),quotes,true);

        List<OutputChecker.Draft> checks=new ArrayList<>();List<Map<String,Object>> sentences=new ArrayList<>();
        if(!insufficient){
            List<Map<String,Object>> targets=free?List.of(Map.of()):goals(practice);
            int index=0;
            for(var goal:targets){String goalId=free?null:text(goal,"goalSelectionId");
                var matching=sentenceDrafts.stream().filter(s->free||Objects.equals(goalId,s.get("goalSelectionId"))).limit(2).toList();
                if(matching.isEmpty())matching=List.of(Map.of("text",template(r,goal,scenes)));
                for(var sentence:matching){String id="s"+(++index),fallback=template(r,goal,scenes);
                    var refs=objects(sentence.get("citations")).stream().map(c->quotes.get(number(c,"utteranceSeq"))).filter(Objects::nonNull).toList();
                    String body=text(sentence,"text");checks.add(new OutputChecker.Draft(id,body,refs,fallback));
                    Map<String,Object> saved=new LinkedHashMap<>();saved.put("text",body);saved.put("checkId",id);if(goalId!=null)saved.put("goalSelectionId",goalId);sentences.add(saved);
                }
            }
            for(int i=0;i<candidateDrafts.size();i++){var c=candidateDrafts.get(i);checks.add(new OutputChecker.Draft("c"+i,c.reasonText(),c.evidence().stream().map(e->quotes.get(e.utteranceSeq())).toList(),null));}
        }
        // INSUFFICIENT는 이 목록이 비어 2차 분류도 호출하지 않는다.
        var checked=checker.check(checks,job.deadline());
        for(var s:sentences){s.put("text",checked.get(s.remove("checkId")));}
        List<CandidateRegistry.Candidate> candidates=new ArrayList<>();for(int i=0;i<candidateDrafts.size();i++)if(checked.get("c"+i)!=null)candidates.add(candidateDrafts.get(i));
        Set<String> keys=new LinkedHashSet<>();for(var goal:goals(practice))if(!text(goal,"behaviorKey").isBlank())keys.add(text(goal,"behaviorKey"));candidates.forEach(c->keys.add(c.behaviorKey()));
        var match=tips.match(keys);var help=goals(practice).stream().map(g->Map.of("goalSelectionId",text(g,"goalSelectionId"),"help",HelpSummarizer.summarize(g))).toList();
        Map<String,Object> finalExperience=experience;List<SceneJudgmentRepository.Scene> finalScenes=scenes;String finalLevel=level;
        var stored=new AtomicReference<ActivityResultRepository.Result>();
        boolean done=job.commit(()->{
            var previous=results.current(r.sessionId());
            var result=results.insert(r.sessionId(),job.jobId(),finalLevel,json.write(sentences),json.write(help),json.write(match.tips()),match.noTipReason(),json.write(Map.of("hiddenTips",match.hidden(),"promptVersion",ai.promptVersion())),json.write(candidates),job.clock().now());
            stored.set(result);if(!finalExperience.isEmpty())structured.insert(result.id(),r.sessionId(),json.write(finalExperience),job.clock().now());
            for(var scene:finalScenes)judgments.insert(result.id(),r.sessionId(),scene,job.clock().now());
            previous.ifPresent(p->results.invalidate(p.id(),job.clock().now()));
        },()->{if(!candidates.isEmpty())registry.register(r.sessionId(),r.childId(),candidates);tx.executeWithoutResult(t->results.candidatesRegistered(stored.get().id(),job.clock().now()));});
        return done?Outcome.done():Outcome.failed("INTERNAL",false);
    }
    private Input load(JobContext job){
        var input=records.find(job.subjectId(),job.guardianId()).orElse(null);if(input==null)return null;
        var r=input.record();Map<String,Object> background=new LinkedHashMap<>();
        if(r.activityType().equals("FREE_TALK"))contexts.findBySession(r.sessionId()).ifPresent(c->{background.put("knownContent",c.knownContent());background.put("situationType",c.situationType());background.put("sourceType",c.sourceType());background.put("question",c.question());});
        Map<String,String> aliases=new LinkedHashMap<>();children.findByGuardianId(job.guardianId()).ifPresent(c->{if(c.getDisplayName()!=null&&!c.getDisplayName().isBlank())aliases.put(c.getDisplayName(),"아이");});
        return new Input(r,transcript(r.transcriptJson()),practice(r.practiceJson()),background,aliases,consent.check(job.guardianId()).snapshot().voiceAndText());
    }
    private Map<String,Object> verifiedExperience(Map<String,Object> output,Map<Long,String> quotes){
        Map<String,Object> result=new LinkedHashMap<>();result.put("characters",objects(output.get("characters")).stream().filter(c->!"CHILD_RECALL".equals(c.get("source"))||quotes.containsKey(number(c,"utteranceSeq"))).toList());
        for(String key:List.of("situation","partnerUtterance","childResponse","explanation")){var field=object(output.get(key));boolean valid=!field.isEmpty()&&(!"CHILD_RECALL".equals(field.get("source"))||(quotes.containsKey(number(field,"utteranceSeq"))&&QuoteMatcher.allVerified(text(field,"text"),List.of(quotes.get(number(field,"utteranceSeq"))))));result.put(key,valid?field:null);}
        return result;
    }
    private List<Map<String,Object>> judgmentGoals(Map<String,Object> practice,UUID guardian,UUID session){
        return goals(practice).stream().map(goal->{var copy=new LinkedHashMap<>(goal);try{var d=definitions.get(text(goal,"behaviorKey"),(int)number(goal,"behaviorVersion"));copy.put("coreFunction",d.behaviorDefinitionText());copy.put("judgmentCriteria",d.judgmentCriteria());}catch(IllegalArgumentException missing){/* BR12.2: 고정 정의가 없으면 정의 없이 재판정한다. */}
            var ids=objects(goal.get("responses")).stream().map(r->uuid(r.get("scenarioId"))).filter(Objects::nonNull).distinct().toList();copy.put("scenarios",ids.stream().map(id->Map.of("scenarioId",id,"content",scenarios.get(id,guardian,session))).toList());return (Map<String,Object>)copy;}).toList();
    }
    private List<SceneJudgmentRepository.Scene> sceneRows(Map<String,Object> practice,Map<String,Object> output,Map<Long,String> quotes,boolean insufficient){
        List<SceneJudgmentRepository.Scene> result=new ArrayList<>();
        for(var goal:goals(practice)){UUID id=uuid(goal.get("goalSelectionId"));if(id==null)continue;
            var generated=objects(output.get("goals")).stream().filter(g->id.toString().equals(g.get("goalSelectionId"))).findFirst().orElse(Map.of());
            for(int scene=1;scene<=2;scene++){int sceneNo=scene;String responseType=scene==1?"FIRST":"NEW_SCENARIO";
                var response=objects(goal.get("responses")).stream().filter(r->responseType.equals(r.get("responseType"))).findFirst().orElse(Map.of());
                var judged=objects(generated.get("scenes")).stream().filter(s->number(s,"sceneNo")==sceneNo).findFirst().orElse(Map.of());
                String value=insufficient?null:(String)judged.get("value"),timing=(String)judged.get("timing");
                for(String ref:List.of("firstResponse","afterHelpResponse")){var citation=object(judged.get(ref));if(!citation.isEmpty()&&!QuoteMatcher.matches(text(citation,"quote"),quotes.get(number(citation,"utteranceSeq"))))value=null;}
                if(response.isEmpty()||number(response,"utteranceSeq")==0)value=null;
                String reason=value==null?reasons.map(goal,response):null;
                if(value==null)timing=null;String live=text(response,"label");
                result.add(new SceneJudgmentRepository.Scene(id,(int)Math.max(1,number(goal,"order")),scene,scene==1?"ORIGINAL":"NEW_SCENARIO",uuid(response.get("scenarioId")),value,timing,value==null?null:text(judged,"rationale"),live.isBlank()?null:live,!Objects.equals(value,live),reason,SceneChipMapper.chip(value,timing)));
            }
        }
        return result;
    }
    private List<Map<String,Object>> publicScenes(List<SceneJudgmentRepository.Scene> scenes){return scenes.stream().map(s->Map.<String,Object>of("goalSelectionId",s.goalSelectionId(),"sceneNo",s.sceneNo(),"sceneChip",s.sceneChip(),"chipText",SceneChipMapper.text(s.sceneChip(),s.notDoneReason()))).toList();}
    private String template(StoredRecord r,Map<String,Object> goal,List<SceneJudgmentRepository.Scene> scenes){
        if(r.activityType().equals("FREE_TALK")){long minutes=r.recordFrom()==null||r.recordTo()==null?0:Math.max(0,Duration.between(r.recordFrom(),r.recordTo()).toMinutes());return minutes+"분 동안 이야기를 나눴어요.";}
        UUID id=uuid(goal.get("goalSelectionId"));long count=scenes.stream().filter(s->s.goalSelectionId().equals(id)&&!s.sceneChip().equals("NOT_DONE")).count();return text(goal,"displayName")+" 연습을 했어요. 장면 2개 중 "+count+"개를 진행했어요.";
    }
}
