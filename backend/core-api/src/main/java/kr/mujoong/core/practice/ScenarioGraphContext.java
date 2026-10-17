package kr.mujoong.core.practice;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.knowledge.ChildKnowledgeService;
import kr.mujoong.core.knowledge.VariantPlanner;
import kr.mujoong.core.textai.ScenarioScript.Scene;

/** 두 U15 조회의 소비 시간을 합산한다. 모델 응답을 기다린 시간으로 새 그래프 예산을 만들지 않는다. */
public final class ScenarioGraphContext {
    private final ChildKnowledgeService knowledge;
    private final ExecutorService executor;
    private final MujoongClock clock;
    private final Duration budget;
    public ScenarioGraphContext(ChildKnowledgeService knowledge,ExecutorService executor,MujoongClock clock,Duration budget){
        this.knowledge=knowledge;this.executor=executor;this.clock=clock;this.budget=budget;
    }
    public Session open(UUID guardian,UUID child,String skill,List<VariantPlanner.Exposure> history,ScenarioDeadline deadline){
        return new Session(guardian,child,skill,history,deadline);
    }
    public final class Session implements ScenarioScriptValidation.VariantCheck {
        private final UUID guardian,child;
        private final String skill;
        private final List<VariantPlanner.Exposure> history;
        private final ScenarioDeadline deadline;
        private Duration remaining=budget;
        private final Optional<ChildKnowledgeService.SkillContext> context;
        private Session(UUID guardian,UUID child,String skill,List<VariantPlanner.Exposure> history,ScenarioDeadline deadline){
            this.guardian=guardian;this.child=child;this.skill=skill;this.history=List.copyOf(history);this.deadline=deadline;
            context=skill==null||"NONE".equals(skill)?Optional.empty():read(()->knowledge.skillContext(guardian,child,skill));
        }
        public Optional<ChildKnowledgeService.SkillContext> context(){return context;}
        private <T>Optional<T> read(java.util.concurrent.Callable<Optional<T>> operation){
            if(remaining.isNegative()||remaining.isZero())return Optional.empty();
            long start=clock.monotonicNanos();
            try{var result=deadline.call(executor,remaining,operation);return clock.elapsedSince(start).compareTo(remaining)>=0?Optional.empty():result;}
            catch(RuntimeException unavailable){deadline.check();return Optional.empty();}
            finally{remaining=remaining.minus(clock.elapsedSince(start));}
        }
        @Override public boolean valid(Scene base,Scene variant){
            if(context.isEmpty()||base.preservation()==null||variant.preservation()==null)return false;
            var p=base.preservation();var v=variant.preservation();var seed=context.get().seed();
            if(!skill.equals(p.skillRef())||!skill.equals(v.skillRef())
                    ||!seed.invariantSentence().equals(p.invariantSentence())||!seed.invariantSentence().equals(v.invariantSentence())
                    ||!seed.preservationRule().equals(p.preservationRule())||!seed.preservationRule().equals(v.preservationRule()))return false;
            var source=coordinates(base);
            var plan=read(()->knowledge.variantPlan(guardian,child,skill,source,history));
            if(plan.isEmpty())return false;
            var expected=plan.get();
            return expected.signature().equals(seed.signature())&&expected.recoverable()&&"LOW".equals(expected.misreadCost())
                    &&expected.grade().equals(v.transferDistance())&&expected.changedField().equals(v.changedElement())
                    &&expected.context().equals(coordinates(variant));
        }
    }
    static VariantPlanner.Context coordinates(Scene scene){
        return new VariantPlanner.Context(scene.topic(),scene.place(),scene.preservation().participants(),scene.relation(),scene.preservation().situation());
    }
}
