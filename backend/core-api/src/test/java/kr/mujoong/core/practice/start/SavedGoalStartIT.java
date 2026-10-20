package kr.mujoong.core.practice.start;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import kr.mujoong.core.practice.*;
import kr.mujoong.core.practicegoal.*;
import kr.mujoong.core.luna.LunaInputSanitizer;
import kr.mujoong.contract.json.JsonCodec;
import org.junit.jupiter.api.*;

/** 시작 유스케이스의 저장소·버전/권한 포트 결합. 실제 PG 준비 원본은 API 통합 시험에서 확인한다. */
@Tag("scenario-generation")
class SavedGoalStartIT {
    final PracticeStartServiceTest fixture=new PracticeStartServiceTest();
    final UUID guardian=fixture.guardian,child=fixture.child,goal=fixture.goal;
    final kr.mujoong.core.practice.PracticeGoalRepository goals=fixture.goals;
    final kr.mujoong.core.knowledge.PragmaticsKnowledge knowledge=fixture.knowledge;
    final ScenarioService generation=fixture.generation;
    PracticeStartService service;
    PracticeStartRequest request(){return fixture.request();}
    SavedGoalReader reader;ScenarioPrivacyContextPort privacy;
    @BeforeEach void connect(){fixture.setup();service=fixture.service;reader=mock(SavedGoalReader.class);privacy=mock(ScenarioPrivacyContextPort.class);service.connectSavedGoals(reader,privacy,new LunaInputSanitizer(JsonCodec.shared()));when(privacy.require(guardian,child)).thenReturn(new ScenarioPrivacyContextPort.Context("합성아이"));when(goals.preparationMetadata(guardian,child,goal)).thenReturn(Optional.of(new kr.mujoong.core.practicegoal.PracticeGoalRepository.PreparationMetadata("합성아이 목표",true,1)));candidate();}
    void candidate(){when(reader.find(child,goal)).thenReturn(Optional.of(new SavedGoalReader.SavedGoalView(goal,child,SavedGoalReader.SavedGoalView.Kind.CANDIDATE,"candidate-fixed",2,"쉬자",new FourElements("합성아이 친구가 쉬려 해","또래","멈추기","쉬자"),"NONE",null,null)));}
    @Test void candidatePreparesWithoutCatalogRead(){var result=service.prepare(guardian,request());assertThat(result.goals().getFirst().behaviorKey()).isEqualTo("candidate:candidate-fixed");assertThat(result.goals().getFirst().behaviorVersion()).isEqualTo(2);assertThat(result.goals().getFirst().judgmentCriteriaSnapshot()).contains("쉬려 해","멈추기");verifyNoInteractions(knowledge);}
    @Test void newSnapshotAndDisplayNameDoNotPersistOriginalAlias(){var result=service.prepare(guardian,request());assertThat(JsonCodec.shared().write(result.goals())).doesNotContain("합성아이").contains("아이 친구가 쉬려 해","아이 목표");}
    @Test void absentOrWrongChildGoalFailsBeforeGeneration(){when(reader.find(child,goal)).thenReturn(Optional.empty());assertThatThrownBy(()->service.prepare(guardian,request())).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);verifyNoInteractions(generation);}
    @Test void savedLegacyUsesActualPinnedDefinition(){when(reader.find(child,goal)).thenReturn(Optional.of(new SavedGoalReader.SavedGoalView(goal,child,SavedGoalReader.SavedGoalView.Kind.LEGACY_BEHAVIOR,null,null,null,null,null,"ACCEPT_STOP",1)));var result=service.prepare(guardian,request());assertThat(result.goals().getFirst().goalContext().kind()).isEqualTo(SavedGoalReader.SavedGoalView.Kind.LEGACY_BEHAVIOR);verify(knowledge).pinVersion("ACCEPT_STOP",1);}
    @Test void readyPreparationKeepsOldVersionAfterSelectionChange(){var first=service.prepare(guardian,request());when(reader.find(child,goal)).thenReturn(Optional.empty());assertThat(service.prepare(guardian,request())).isEqualTo(first);verify(reader).find(child,goal);}
    @Test void profileFailurePreventsFirstScenario(){when(privacy.require(guardian,child)).thenThrow(new IllegalStateException("synthetic unavailable"));assertThatThrownBy(()->service.prepare(guardian,request())).isInstanceOf(IllegalStateException.class);verifyNoInteractions(generation);}
    @Test void aliasInsideCandidateReferenceCannotChangeOpaqueIdentity(){
        when(privacy.require(guardian,child)).thenReturn(new ScenarioPrivacyContextPort.Context("abc"));
        when(reader.find(child,goal)).thenReturn(Optional.of(new SavedGoalReader.SavedGoalView(goal,child,SavedGoalReader.SavedGoalView.Kind.CANDIDATE,"abc-fixed",2,"abc 쉬자",new FourElements("abc 친구가 쉬려 해","또래","멈추기","abc 쉬자"),"NONE",null,null)));
        var result=service.prepare(guardian,request()).goals().getFirst();
        assertThat(result.behaviorKey()).isEqualTo("candidate:abc-fixed");assertThat(result.goalContext().candidateRef()).isEqualTo("abc-fixed");
        assertThat(result.goalContext().targetExpression()).isEqualTo("아이 쉬자");
    }
    @Test void aliasInsideLegacyKeyDoesNotChangePinnedDefinitionLookup(){
        when(privacy.require(guardian,child)).thenReturn(new ScenarioPrivacyContextPort.Context("ACCEPT"));
        when(reader.find(child,goal)).thenReturn(Optional.of(new SavedGoalReader.SavedGoalView(goal,child,SavedGoalReader.SavedGoalView.Kind.LEGACY_BEHAVIOR,null,null,null,null,null,"ACCEPT_STOP",1)));
        var result=service.prepare(guardian,request()).goals().getFirst();
        assertThat(result.behaviorKey()).isEqualTo("ACCEPT_STOP");verify(knowledge).pinVersion("ACCEPT_STOP",1);
    }
}
