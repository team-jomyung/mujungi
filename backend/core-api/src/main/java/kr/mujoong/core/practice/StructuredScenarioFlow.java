package kr.mujoong.core.practice;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.knowledge.BehaviorDefinition;
import kr.mujoong.core.knowledge.VariantPlanner;
import kr.mujoong.core.luna.LunaInputSanitizer;
import kr.mujoong.core.luna.LunaPurpose;

/** 운영 서비스가 사용하는 U10 경로. 식별/권한 목표는 바꾸지 않고 공급자용 사본만 정리한다. */
public final class StructuredScenarioFlow {
    private final ScenarioScriptGenerator generator;
    private final ScenarioPrivacyContextPort privacy;
    private final LunaInputSanitizer sanitizer;
    private final ScenarioGraphContext graph;
    private final ScenarioRepository repository;
    private final ExecutorService executor;
    private final Duration historyTimeout;
    private final JsonCodec json;
    public StructuredScenarioFlow(ScenarioScriptGenerator generator,ScenarioPrivacyContextPort privacy,LunaInputSanitizer sanitizer,
            ScenarioGraphContext graph,ScenarioRepository repository,ExecutorService executor,Duration historyTimeout,JsonCodec json){
        this.generator=generator;this.privacy=privacy;this.sanitizer=sanitizer;this.graph=graph;this.repository=repository;
        this.executor=executor;this.historyTimeout=historyTimeout;this.json=json;
    }
    public ScenarioScriptGenerator.Result generate(PracticeSessionContextPort.SessionContext session,
            PracticeSessionContextPort.GoalSnapshot goal,BehaviorDefinition definition,ScenarioRepository.Stored previous,
            PracticeAccountPort.AccountContext account,Instant started,ScenarioDeadline deadline){
        var aliases=deadline.call(executor,deadline.remaining(),()->privacy.require(session.guardianId(),session.childId()));
        var history=deadline.call(executor,historyTimeout,()->repository.recentExposed(session.guardianId(),session.childId(),started,deadline.limit(historyTimeout)));
        var original=goal.goalContext();
        Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("ageYears",account.ageYears());payload.put("interests",account.interests());
        var cleanGoal=original.mapContent(text->(String)sanitizer.sanitize(Map.of("text",text),aliases.aliases(),LunaPurpose.SCENARIO_GENERATION).payload().get("text"));
        Map<String,Object> semanticGoal=new LinkedHashMap<>();
        semanticGoal.put("kind",cleanGoal.kind());semanticGoal.put("skillRef",cleanGoal.skillRef());
        if(cleanGoal.candidate()){
            semanticGoal.put("targetExpression",cleanGoal.targetExpression());semanticGoal.put("fourElements",cleanGoal.fourElements());
        }
        payload.put("goal",semanticGoal);
        String opportunity=original.candidate()?original.fourElements().situation():definition.opportunityCondition();
        String relation=previous==null?goal.relationType():previous.scenario().relationType();
        if(!original.candidate()){
            payload.put("behaviorName",definition.displayName());payload.put("behaviorDefinition",definition.behaviorDefinitionText());
            payload.put("scenarioCondition",definition.scenarioCondition());
        }
        payload.put("opportunityCondition",opportunity);payload.put("relation",relation);
        payload.put("history",history.stream().map(e->Map.of("scenarioText",e.scenarioText(),"triggerScript",e.triggerScript())).toList());
        if(previous!=null)payload.put("sourceScenario",Map.of("scenarioText",previous.scenario().scenarioText(),"triggerScript",previous.scenario().triggerScript()));
        var exposures=history.stream().filter(e->e.skillRef()!=null&&ScenarioGraphContext.complete(e.graphContext()))
                .map(e->new VariantPlanner.Exposure(e.skillRef(),e.graphContext())).toList();
        var knowledge=graph.open(session.guardianId(),session.childId(),original.skillRef(),exposures,deadline);
        knowledge.context().ifPresent(value->payload.put("structureContext",structureContext(value.seed())));
        payload.put("baseOnly",true); // 첫 BASE의 실제 좌표를 받은 뒤에만 변형 계획을 정한다.
        var clean=sanitizer.sanitize(payload,aliases.aliases(),LunaPurpose.SCENARIO_GENERATION).payload();
        String cleanOpportunity=(String)clean.get("opportunityCondition"),cleanRelation=(String)clean.get("relation");
        String sourceText=null;
        if(previous!=null)sourceText=(String)((Map<?,?>)clean.get("sourceScenario")).get("scenarioText");
        var duplicate=new java.util.concurrent.atomic.AtomicBoolean();
        var rules=new ScenarioScriptValidation.Rules(cleanRelation,cleanOpportunity,cleanGoal.targetExpression(),aliases.originalNames(),
                sourceText,fingerprint->{boolean seen=repository.seen(session.childId(),fingerprint);if(seen)duplicate.set(true);return seen;},knowledge);
        try{return generator.generate(new ScenarioScriptGenerator.Input(clean,aliases.aliases(),rules,knowledge.context().isPresent(),json.write(cleanGoal)),deadline);}
        catch(ScenarioScriptGenerator.ContentExhausted exhausted){exhausted.retryableDuplicate=duplicate.get();throw exhausted;}
    }

    /**
     * 씨앗의 고정값과 좌표 허용 어휘. 변형 계획은 BASE 좌표가 이 어휘 안에 글자 그대로 있을 때만 나오므로 모델에 목록을 준다.
     * 목록은 계획기 상수를 정렬해 넣는다(Set 순회 순서는 실행마다 다를 수 있어 입력이 흔들리지 않게).
     */
    static Map<String,Object> structureContext(kr.mujoong.core.knowledge.KnowledgeSeed seed){
        Map<String,Object> context=new LinkedHashMap<>();
        context.put("skillRef",seed.seedId());context.put("signature",seed.signature());
        context.put("invariantSentence",seed.invariantSentence());context.put("preservationRule",seed.preservationRule());
        context.put("relationship",seed.relationship());context.put("situation",seed.situation());
        context.put("allowedTopics",sorted(VariantPlanner.TOPICS));
        context.put("allowedPlaces",sorted(VariantPlanner.PLACES));
        context.put("allowedParticipants",sorted(VariantPlanner.PARTICIPANTS));
        context.put("allowedRelationships",sorted(kr.mujoong.core.knowledge.KnowledgeSeed.RELATIONSHIPS));
        context.put("allowedSituations",sorted(kr.mujoong.core.knowledge.KnowledgeSeed.SITUATIONS));
        return context;
    }
    private static List<String> sorted(java.util.Set<String> values){return values.stream().sorted().toList();}
}
