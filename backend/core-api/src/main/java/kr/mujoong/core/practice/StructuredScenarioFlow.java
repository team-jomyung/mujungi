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
    private ReferenceSeedSelector references;
    /** 참고 씨앗을 고를 수 있을 때만 연결한다. 없으면 지금처럼 참고 없이 만든다. */
    public StructuredScenarioFlow references(ReferenceSeedSelector references){this.references=references;return this;}
    public ScenarioScriptGenerator.Result generate(PracticeSessionContextPort.SessionContext session,
            PracticeSessionContextPort.GoalSnapshot goal,BehaviorDefinition definition,ScenarioRepository.Stored previous,
            PracticeAccountPort.AccountContext account,Instant started,ScenarioDeadline deadline){
        var aliases=deadline.call(executor,deadline.remaining(),()->privacy.require(session.guardianId(),session.childId()));
        var history=deadline.call(executor,historyTimeout,()->repository.recentExposed(session.guardianId(),session.childId(),started,deadline.limit(historyTimeout)));
        var original=goal.goalContext();
        var continuity=deadline.call(executor,historyTimeout,()->repository.continuity(session.guardianId(),session.childId(),
                goal.goalSelectionId(),started,deadline.limit(historyTimeout)));
        Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("ageYears",account.ageYears());
        var cleanGoal=original.mapContent(text->(String)sanitizer.sanitize(Map.of("text",text),aliases.aliases(),LunaPurpose.SCENARIO_GENERATION).payload().get("text"));
        Map<String,Object> semanticGoal=new LinkedHashMap<>();
        semanticGoal.put("kind",cleanGoal.kind());semanticGoal.put("skillRef",cleanGoal.skillRef());
        if(cleanGoal.candidate()){
            semanticGoal.put("targetExpression",cleanGoal.targetExpression());semanticGoal.put("fourElements",cleanGoal.fourElements());
        }
        payload.put("goal",semanticGoal);
        payload.put("practiceContinuity",continuity.supportHistory());
        // FT-1023-g(2026-10-24): 원래 대화 요약을 없애 아이 발화 인용(reflectionContext)은 생성 입력에 넣지 않는다
        String opportunity=original.candidate()?original.fourElements().situation():definition.opportunityCondition();
        String relation=previous==null?goal.relationType():previous.scenario().relationType();
        if(!original.candidate()){
            payload.put("behaviorName",definition.displayName());payload.put("behaviorDefinition",definition.behaviorDefinitionText());
            payload.put("scenarioCondition",definition.scenarioCondition());
        }
        payload.put("opportunityCondition",opportunity);payload.put("relation",relation);
        payload.put("history",history.stream().map(e->Map.of("scenarioText",e.scenarioText(),"triggerScript",e.triggerScript())).toList());
        if(previous!=null)payload.put("sourceScenario",Map.of("scenarioText",previous.scenario().scenarioText(),"triggerScript",previous.scenario().triggerScript()));
        var exposures=history.stream().filter(e->e.skillRef()!=null&&e.graphContext()!=null)
                .map(e->new VariantPlanner.Exposure(e.skillRef(),e.graphContext())).toList();
        var knowledge=graph.open(session.guardianId(),session.childId(),original.skillRef(),exposures,deadline);
        // U14 keeps a readable partner label; U15 plans use closed relationship codes.
        // Resolve only known equivalent labels, never replace a different relationship.
        payload.put("relation",graphRelation(relation,knowledge.context()
                .map(value->value.seed().relationship()).orElse(null)));
        knowledge.context().ifPresent(value->{var seed=value.seed();payload.put("structureContext",Map.of(
                "skillRef",seed.seedId(),"signature",seed.signature(),"invariantSentence",seed.invariantSentence(),
                "preservationRule",seed.preservationRule(),"relationship",seed.relationship(),"situation",seed.situation()));});
        payload.put("baseOnly",true); // 첫 BASE의 실제 좌표를 받은 뒤에만 변형 계획을 정한다.
        // 처음 만드는 장면에만 아이가 자주 막힌, 연습에 고른 적 있는 다른 목표의 씨앗 하나를 소재 참고로 준다. 불변 문장만 주고 보존 조건은 주지 않는다.
        // 목표 씨앗 조회가 이미 실패한 그래프에는 다시 묻지 않는다.
        var reference=previous==null&&references!=null&&!knowledge.fallbackRequired()
                ?references.select(session.guardianId(),session.childId(),original.skillRef(),deadline)
                :java.util.Optional.<kr.mujoong.core.knowledge.ChildKnowledgeService.ReferenceSeed>empty();
        reference.ifPresent(seed->payload.put("referenceContext",Map.of("invariantSentence",seed.invariantSentence())));
        var clean=sanitizer.sanitize(payload,aliases.aliases(),LunaPurpose.SCENARIO_GENERATION).payload();
        String cleanOpportunity=(String)clean.get("opportunityCondition"),cleanRelation=(String)clean.get("relation");
        String sourceText=null;
        if(previous!=null)sourceText=(String)((Map<?,?>)clean.get("sourceScenario")).get("scenarioText");
        var duplicate=new java.util.concurrent.atomic.AtomicBoolean();
        var rules=new ScenarioScriptValidation.Rules(cleanRelation,cleanOpportunity,cleanGoal.targetExpression(),aliases.originalNames(),
                sourceText,fingerprint->{boolean seen=repository.seen(session.childId(),fingerprint);if(seen)duplicate.set(true);return seen;},knowledge);
        ScenarioScriptGenerator.Result result;
        try{result=generator.generate(new ScenarioScriptGenerator.Input(clean,aliases.aliases(),rules,knowledge.context().isPresent(),json.write(cleanGoal)),deadline);}
        catch(ScenarioScriptGenerator.ContentExhausted exhausted){exhausted.retryableDuplicate=duplicate.get();throw exhausted;}
        // 참고 시점은 장면을 저장한 뒤 referenceUsed에서 남긴다. 생성 중 그래프가 실패해 참고를 뺀 드문 경우도 쉬는 기간은 시작된다(참고가 드물어질 뿐이다).
        return result!=null&&reference.isPresent()?result.withReference(reference.get()):result;
    }
    /** 저장까지 끝난 장면에 참고 씨앗이 들어갔으면 그 시점을 남긴다. */
    public void referenceUsed(java.util.UUID guardian,java.util.UUID child,ScenarioScriptGenerator.Result saved){
        if(references!=null&&saved!=null&&saved.reference()!=null)references.used(guardian,child,saved.reference());
    }
    static String graphRelation(String relation,String seedRelationship){
        if(seedRelationship==null||kr.mujoong.core.knowledge.KnowledgeSeed.RELATIONSHIPS.contains(relation))return relation;
        boolean equivalent=switch(relation){
            case "친구", "같이 놀 친구" -> java.util.Set.of("FRIEND_CLOSE","FRIEND_CASUAL").contains(seedRelationship);
            case "친한 친구" -> "FRIEND_CLOSE".equals(seedRelationship);
            case "반 친구", "같은 반 친구" -> "CLASSMATE".equals(seedRelationship);
            case "선생님", "교사" -> "TEACHER".equals(seedRelationship);
            case "가족" -> "FAMILY".equals(seedRelationship);
            default -> unnamedPartner(relation);
        };
        return equivalent?seedRelationship:relation;
    }
    /** 사람을 정하지 않는 상대 표현. 이 표현은 다른 관계가 아니므로 씨앗의 관계 코드로 좌표를 맞춘다(FT-1030-h). */
    private static final java.util.regex.Pattern ROLE_WORDS=java.util.regex.Pattern.compile(
            "친구|선생님|교사|가족|엄마|아빠|부모|형|누나|언니|오빠|동생|선배|후배|팀|모둠|동아리|어른|낯선");
    /**
     * 목표 후보의 상대가 "대화하는 상대" · "이야기를 나누는 상대"처럼 '상대'로 끝나고 누구인지 정하지 않으면, 정해진 관계 코드로 바꿔도 다른
     * 사람이 되지 않는다. 그대로 두면 변형 계획이 관계 좌표를 몰라 늘 비었다(2026-10-30 실키, ScenarioGraphFallback_PLAN_EMPTY).
     */
    static boolean unnamedPartner(String relation){
        String text=relation.strip();
        return text.endsWith("상대")&&!ROLE_WORDS.matcher(text).find();
    }
}
