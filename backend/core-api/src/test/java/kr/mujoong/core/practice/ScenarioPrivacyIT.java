package kr.mujoong.core.practice;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.core.child.ChildProfileService;
import kr.mujoong.core.consent.ConsentPolicyService;
import org.junit.jupiter.api.*;

/** 프로필·계정·동의 사이의 내부 포트 결합 시험. 실제 HTTP/DB는 후속 API 통합 시험에서 확인한다. */
@Tag("scenario-generation")
class ScenarioPrivacyIT {
    UUID g,c;PracticeAccountPort accounts;ConsentPolicyService consent;ChildProfileService profiles;ScenarioPrivacyContextAdapter adapter;
    @BeforeEach void open(){g=UUID.randomUUID();c=UUID.randomUUID();accounts=mock(PracticeAccountPort.class);consent=mock(ConsentPolicyService.class);profiles=mock(ChildProfileService.class);adapter=new ScenarioPrivacyContextAdapter(accounts,consent,profiles);when(accounts.requireActive(g,false)).thenReturn(new PracticeAccountPort.AccountContext(g,c,8,List.of()));when(consent.check(g)).thenReturn(new ConsentPolicyService.GateResult(true,null,null));when(profiles.find(g)).thenReturn(Optional.of(new ChildProfileService.ChildView(c,"합성아이",8,List.of())));}
    @Test void ownedConsentProfileProducesPrivateAlias(){assertThat(adapter.require(g,c).aliases()).containsExactlyEntriesOf(java.util.Map.of("합성아이","아이"));verify(profiles).find(g);}
    @Test void differentAccountChildStopsBeforeProfile(){assertThatThrownBy(()->adapter.require(g,UUID.randomUUID())).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);verifyNoInteractions(profiles);}
    @Test void revokedConsentStopsBeforeProfile(){when(consent.check(g)).thenReturn(new ConsentPolicyService.GateResult(false,null,null));assertThatThrownBy(()->adapter.require(g,c)).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);verifyNoInteractions(profiles);}
    @Test void missingProfileDoesNotReturnEmptyAliases(){when(profiles.find(g)).thenReturn(Optional.empty());assertThatThrownBy(()->adapter.require(g,c)).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);}
    @Test void failedProfileReadDoesNotBecomeEmptyAliases(){when(profiles.find(g)).thenThrow(new IllegalStateException("synthetic unavailable"));assertThatThrownBy(()->adapter.require(g,c)).isInstanceOf(IllegalStateException.class);}
    @Test void differentProfileChildFails(){when(profiles.find(g)).thenReturn(Optional.of(new ChildProfileService.ChildView(UUID.randomUUID(),"합성아이",8,List.of())));assertThatThrownBy(()->adapter.require(g,c)).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);}
    @Test void blankDisplayNameFails(){when(profiles.find(g)).thenReturn(Optional.of(new ChildProfileService.ChildView(c," ",8,List.of())));assertThatThrownBy(()->adapter.require(g,c)).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);}
}
