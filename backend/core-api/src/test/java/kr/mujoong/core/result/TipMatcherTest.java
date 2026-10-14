package kr.mujoong.core.result;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import kr.mujoong.core.knowledge.SupportLibrary;
import kr.mujoong.core.result.check.ForbiddenDictionary;
import org.junit.jupiter.api.*;
@Tag("u6")
class TipMatcherTest {
    SupportLibrary library=mock(SupportLibrary.class);TipMatcher matcher=new TipMatcher(library,new ForbiddenDictionary());
    @Test void keepsApprovedTextUnchanged(){var tip=new SupportLibrary.Tip(UUID.randomUUID(),"기다리기","말이 끝나면","잠시 기다려 주세요","아이 반응에 따라 달라요");when(library.approvedByBehaviorKeys(Set.of("key"))).thenReturn(List.of(tip));assertThat(matcher.match(Set.of("key")).tips()).containsExactly(tip);}
    @Test void noMatchRequiresMoreObservation(){assertThat(matcher.match(Set.of()).noTipReason()).isEqualTo("NEED_MORE_OBSERVATION");}
    @Test void missingTitleIsAllowed(){when(library.approvedByBehaviorKeys(any())).thenReturn(List.of(new SupportLibrary.Tip(UUID.randomUUID(),null,"말이 끝나면","잠시 기다려 주세요","아이마다 달라요")));assertThat(matcher.match(Set.of("key")).tips()).hasSize(1);}
    @Test void forbiddenTitleHidesWholeTip(){when(library.approvedByBehaviorKeys(any())).thenReturn(List.of(new SupportLibrary.Tip(UUID.randomUUID(),"치료 효과","조건","내용","한계")));assertThat(matcher.match(Set.of("key")).hidden()).isEqualTo(1);}
    @Test void quotedForbiddenContentStillHidden(){when(library.approvedByBehaviorKeys(any())).thenReturn(List.of(new SupportLibrary.Tip(UUID.randomUUID(),null,"조건","\"꼭 해야\" 치료","한계")));assertThat(matcher.match(Set.of("key")).tips()).isEmpty();}
    @Test void onlyRequestedKeysReachLibrary(){matcher.match(Set.of("a","b"));verify(library).approvedByBehaviorKeys(Set.of("a","b"));}
}
