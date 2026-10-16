package kr.mujoong.core.practicegoal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import kr.mujoong.core.consent.ConsentPolicyService;
import kr.mujoong.core.practice.PracticeAccountPort;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Tag("u14")
class SavedGoalReaderTest {
    UUID guardian=UUID.randomUUID(),child=UUID.randomUUID(),id=UUID.randomUUID();
    GoalCandidateRepository goals=mock(GoalCandidateRepository.class);PracticeAccountPort accounts=mock(PracticeAccountPort.class);
    ConsentPolicyService consent=mock(ConsentPolicyService.class);NamedParameterJdbcTemplate jdbc=mock(NamedParameterJdbcTemplate.class);
    PinnedSavedGoalReader reader=new PinnedSavedGoalReader(goals,accounts,consent,jdbc);
    @BeforeEach void setup(){when(accounts.requireChild(child,false)).thenReturn(new PracticeAccountPort.AccountContext(guardian,child,null,List.of()));when(consent.check(guardian)).thenReturn(new ConsentPolicyService.GateResult(true,null,new ConsentPolicyService.Snapshot("v1",true,true,true,true)));}
    @Test void returnsPinnedCandidate(){var view=new SavedGoalReader.SavedGoalView(id,child,SavedGoalReader.SavedGoalView.Kind.CANDIDATE,"ref",1,"물어보기",null,"NONE",null,null);when(goals.saved(child,id)).thenReturn(Optional.of(view));assertThat(reader.find(child,id)).contains(view);verifyNoInteractions(jdbc);}
    @Test void nullChildDoesNotRead(){assertThat(reader.find(null,id)).isEmpty();verifyNoInteractions(goals);}
    @Test void nullSelectionDoesNotRead(){assertThat(reader.find(child,null)).isEmpty();verifyNoInteractions(goals);}
    @Test void withdrawnConsentReturnsEmpty(){when(consent.check(guardian)).thenReturn(new ConsentPolicyService.GateResult(false,null,new ConsentPolicyService.Snapshot("v1",false,true,true,true)));assertThat(reader.find(child,id)).isEmpty();verifyNoInteractions(goals);}
    @Test void unavailableChildReturnsEmpty(){when(accounts.requireChild(child,false)).thenThrow(kr.mujoong.core.common.error.CoreApiException.of(kr.mujoong.contract.error.ErrorCode.NOT_FOUND));assertThat(reader.find(child,id)).isEmpty();verifyNoInteractions(goals);}
    @Test void fourElementsRejectEmptyAndLongValues(){assertThatThrownBy(()->new FourElements("", "친구","참여","말하기")).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->new FourElements("놀이","가".repeat(201),"참여","말하기")).isInstanceOf(IllegalArgumentException.class);}
}
