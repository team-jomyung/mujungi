package kr.mujoong.core.practice.start;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.practice.GoalContext;
import kr.mujoong.core.practicegoal.FourElements;
import kr.mujoong.core.practicegoal.SavedGoalReader.SavedGoalView.Kind;
import org.junit.jupiter.api.*;

@Tag("u10")
class U10FrozenGoalTest {
    final UUID g=UUID.randomUUID(),c=UUID.randomUUID();
    static GoalContext candidate(){return new GoalContext(Kind.CANDIDATE,"synthetic-candidate",2,"잠깐 쉬자",new FourElements("친구가 쉬려 해","또래","멈추기","잠깐 쉬자"),"NONE",null,null);}
    WeightedGoalOrder.Selection selection(GoalContext context,int sort,boolean favorite){return new WeightedGoalOrder.Selection(g,c,UUID.randomUUID(),"합성 목표",context.carrierKey(),context.carrierVersion(),context.candidate()?context.criteria():"기준",sort,favorite,context);}
    @Test void candidateCarrierAndFourElementsKeepPinnedVersion(){var context=candidate();assertThat(context.carrierKey()).isEqualTo("candidate:synthetic-candidate");assertThat(context.carrierVersion()).isEqualTo(2);assertThat(context.criteria()).contains("친구가 쉬려 해","또래","멈추기","잠깐 쉬자");}
    @Test void legacyConstructorDefaultsToLegacyContext(){var frozen=new FrozenGoal(UUID.randomUUID(),"목표","ACCEPT_STOP",1,"기준",1,1,1,false,1,1,1);assertThat(frozen.goalContext().kind()).isEqualTo(Kind.LEGACY_BEHAVIOR);}
    @Test void oldJsonWithoutNewFieldReadsAsLegacy(){var old="{\"goalSelectionId\":\""+UUID.randomUUID()+"\",\"displayName\":\"목표\",\"behaviorKey\":\"ACCEPT_STOP\",\"behaviorVersion\":1,\"judgmentCriteriaSnapshot\":\"기준\",\"savedSortOrder\":1,\"selectionRank\":1,\"selectedCount\":1,\"favoriteAtPreparation\":false,\"favoriteMultiplier\":1,\"weight\":1,\"order\":1}";assertThat(JsonCodec.shared().read(old,FrozenGoal.class).goalContext().kind()).isEqualTo(Kind.LEGACY_BEHAVIOR);}
    @Test void mixedKindsCanShareSavedRankWithoutChangingWeights(){var items=List.of(selection(candidate(),1,true),selection(GoalContext.legacy("ACCEPT_STOP",1),1,false));var frozen=new WeightedGoalOrder().freeze(g,c,items,new Random(1));assertThat(new WeightedGoalOrder().validate(frozen)).hasSize(2);assertThat(frozen).extracting(FrozenGoal::savedSortOrder).containsOnly(1);assertThat(frozen).allSatisfy(x->assertThat(x.weight()).isEqualTo((x.favoriteAtPreparation()?2:1)*(x.selectedCount()-x.selectionRank()+1)));}
    @Test void sameKindDuplicateRankStillRejected(){var a=selection(candidate(),1,false);var b=selection(candidate(),1,false);assertThatThrownBy(()->new WeightedGoalOrder().freeze(g,c,List.of(a,b),new Random())).isInstanceOf(IllegalArgumentException.class);}
    @Test void carrierMismatchAndMissingCandidateContextFail(){assertThatThrownBy(()->new FrozenGoal(UUID.randomUUID(),"목표","ACCEPT_STOP",1,"기준",1,1,1,false,1,1,1,candidate())).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->new GoalContext(Kind.CANDIDATE,"x",1,"표현",null,"NONE",null,null)).isInstanceOf(IllegalArgumentException.class);}
    @Test void candidateSnapshotsRoundTripAndAreRedacted(){var frozen=new WeightedGoalOrder().freeze(g,c,List.of(selection(candidate(),1,false)),new Random());var decoded=List.of(JsonCodec.shared().read(JsonCodec.shared().write(frozen),FrozenGoal[].class));assertThat(decoded).isEqualTo(frozen);assertThat(decoded.getFirst().toString()).doesNotContain("잠깐 쉬자");assertThat(candidate().toString()).doesNotContain("잠깐 쉬자");}
    @Test void reuseDoesNotReplacePinnedContextOrReorder(){var frozen=new WeightedGoalOrder().freeze(g,c,List.of(selection(candidate(),1,false)),new Random());assertThat(new WeightedGoalOrder().reuse(frozen,Set.of(frozen.getFirst().goalSelectionId()))).isEqualTo(frozen);}
}
