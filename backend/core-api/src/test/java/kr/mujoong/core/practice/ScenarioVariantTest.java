package kr.mujoong.core.practice;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.Duration;
import java.util.*;
import kr.mujoong.core.knowledge.*;
import kr.mujoong.core.textai.ScenarioScript;
import org.junit.jupiter.api.*;

@Tag("scenario-generation") class ScenarioVariantTest {
    final ScenarioGraphTest f=new ScenarioGraphTest();
    final VariantPlanner.Context base=new VariantPlanner.Context("놀이","교실","아이와 친구","FRIEND_CLOSE","FRIEND_PRIVATE");
    VariantPlanner.Plan plan;
    @BeforeEach void setup(){f.setup();plan=new VariantPlanner().plan(f.seed,base,List.of()).orElseThrow();when(f.service.variantPlan(any(),any(),any(),any(),any())).thenReturn(Optional.of(plan));}
    @AfterEach void close(){f.close();}
    ScenarioScript.Scene scene(VariantPlanner.Context c,String distance,String changed){return new ScenarioScript.Scene("s","VARIANT","친구가 쉬려 한다","쉴래","친구의 말을 듣자",List.of(),List.of(),c.relationship(),c.place(),c.topic(),new ScenarioScript.Preservation("seed:S1",f.seed.invariantSentence(),f.seed.preservationRule(),distance,changed,c.participants(),c.situation()),null);}
    ScenarioScript.Scene original(){return scene(base,"SAME_CONTEXT","none");}
    ScenarioScript.Scene variant(){return scene(plan.context(),plan.grade(),plan.changedField());}
    @Test void acceptsExactPlanAndPreservedSkill(){assertThat(f.open().valid(original(),variant())).isTrue();}
    @Test void unchangedVariantIsNotAPlan(){assertThat(f.open().valid(original(),original())).isFalse();}
    @Test void wrongTransferDistanceFails(){assertThat(f.open().valid(original(),scene(plan.context(),"NEAR",plan.changedField()))).isFalse();}
    @Test void wrongChangedAxisFails(){assertThat(f.open().valid(original(),scene(plan.context(),plan.grade(),"situation"))).isFalse();}
    @Test void missingPlanIsRejected(){when(f.service.variantPlan(any(),any(),any(),any(),any())).thenReturn(Optional.empty());assertThat(f.open().valid(original(),variant())).isFalse();}
    @Test void twoReadsShareBudgetAcrossEveryCorrection(){when(f.service.skillContext(any(),any(),any())).thenAnswer(c->{f.clock.advance(Duration.ofMillis(1500));return Optional.of(new ChildKnowledgeService.SkillContext(f.seed,"v1"));});when(f.service.variantPlan(any(),any(),any(),any(),any())).thenAnswer(c->{f.clock.advance(Duration.ofMillis(500));return Optional.of(plan);});var session=f.open();assertThat(session.valid(original(),variant())).isFalse();assertThat(session.valid(original(),variant())).isFalse();verify(f.service,times(1)).variantPlan(any(),any(),any(),any(),any());}
    @Test void successfulPlanIsCachedButNewBaseCannotResetBudget(){when(f.service.skillContext(any(),any(),any())).thenAnswer(c->{f.clock.advance(Duration.ofMillis(1000));return Optional.of(new ChildKnowledgeService.SkillContext(f.seed,"v1"));});when(f.service.variantPlan(any(),any(),any(),any(),any())).thenAnswer(c->{f.clock.advance(Duration.ofMillis(600));return Optional.of(plan);});var session=f.open();assertThat(session.valid(original(),variant())).isTrue();assertThat(session.valid(original(),variant())).isTrue();var different=scene(new VariantPlanner.Context("그림","교실","아이와 친구","FRIEND_CLOSE","FRIEND_PRIVATE"),"SAME_CONTEXT","none");assertThat(session.valid(different,variant())).isFalse();verify(f.service,times(2)).variantPlan(any(),any(),any(),any(),any());}
}
