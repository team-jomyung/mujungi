package kr.mujoong.core.practice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.Duration;
import java.util.*;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.luna.LunaInputSanitizer;
import kr.mujoong.core.practicegoal.FourElements;
import kr.mujoong.core.practicegoal.SavedGoalReader;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

class ScenarioRelationshipTest {
    @Test void readableFriendGoalUsesTheEquivalentGraphCoordinate(){
        assertThat(StructuredScenarioFlow.graphRelation("친구","FRIEND_CLOSE")).isEqualTo("FRIEND_CLOSE");
        assertThat(StructuredScenarioFlow.graphRelation("친구","FRIEND_CASUAL")).isEqualTo("FRIEND_CASUAL");
        assertThat(StructuredScenarioFlow.graphRelation("같이 놀 친구","FRIEND_CASUAL")).isEqualTo("FRIEND_CASUAL");
    }
    @Test void graphCannotReplaceTheGoalWithADifferentPartner(){
        assertThat(StructuredScenarioFlow.graphRelation("친구","TEACHER")).isEqualTo("친구");
        assertThat(StructuredScenarioFlow.graphRelation("같이 놀 친구","TEACHER")).isEqualTo("같이 놀 친구");
        assertThat(StructuredScenarioFlow.graphRelation("선생님","FRIEND_CLOSE")).isEqualTo("선생님");
        assertThat(StructuredScenarioFlow.graphRelation("CLASSMATE","FRIEND_CLOSE")).isEqualTo("CLASSMATE");
        assertThat(StructuredScenarioFlow.graphRelation("친한 친구","FRIEND_CASUAL")).isEqualTo("친한 친구");
        assertThat(StructuredScenarioFlow.graphRelation("낯선 사람","FRIEND_CLOSE")).isEqualTo("낯선 사람");
    }
    // FT-1030-h: 누구인지 정하지 않은 '…상대' 표현은 씨앗의 관계 코드로 좌표를 맞춘다. 특정 사람을 가리키면 지금처럼 바꾸지 않는다
    @Test void anUnnamedPartnerTakesTheSeedRelationshipCode(){
        assertThat(StructuredScenarioFlow.graphRelation("대화하는 상대","FRIEND_CASUAL")).isEqualTo("FRIEND_CASUAL");
        assertThat(StructuredScenarioFlow.graphRelation("이야기를 나누는 상대","CLASSMATE")).isEqualTo("CLASSMATE");
        assertThat(StructuredScenarioFlow.graphRelation("응답을 기다리는 대화 상대","TEACHER")).isEqualTo("TEACHER");
        assertThat(StructuredScenarioFlow.graphRelation("친구인 대화 상대","TEACHER")).isEqualTo("친구인 대화 상대");
        assertThat(StructuredScenarioFlow.graphRelation("선생님 같은 상대","FRIEND_CLOSE")).isEqualTo("선생님 같은 상대");
        assertThat(StructuredScenarioFlow.graphRelation("대화하는 상대",null)).isEqualTo("대화하는 상대");
    }
    @Test void withoutGraphTheOriginalRelationshipIsPreserved(){
        assertThat(StructuredScenarioFlow.graphRelation("친구",null)).isEqualTo("친구");
        assertThat(StructuredScenarioFlow.graphRelation("같이 놀 친구",null)).isEqualTo("같이 놀 친구");
    }
    @Test void providerPayloadAndValidationUseTheSameCodeWithoutChangingSavedGoal(){
        var f=new ScenarioGraphTest();f.setup();
        try{
            var generator=mock(ScenarioScriptGenerator.class);
            var privacy=mock(ScenarioPrivacyContextPort.class);
            when(privacy.require(f.guardian,f.child)).thenReturn(new ScenarioPrivacyContextPort.Context("합성아이"));
            var repository=mock(ScenarioRepository.class);
            when(repository.recentExposed(any(),any(),any(),any())).thenReturn(List.of());
            when(repository.continuity(any(),any(),any(),any(),any())).thenReturn(new PracticeContinuity.Context(List.of(),List.of()));
            var goal=new GoalContext(SavedGoalReader.SavedGoalView.Kind.CANDIDATE,"candidate",1,"쉬고 싶은지 물어보기",
                    new FourElements("함께 놀다가 친구가 쉬려는 상황","친구","뜻 확인","쉬고 싶어?"),"seed:S1",null,null);
            var snapshot=new PracticeSessionContextPort.GoalSnapshot(UUID.randomUUID(),goal.carrierKey(),1,"친구",goal);
            var session=new PracticeSessionContextPort.SessionContext(UUID.randomUUID(),f.guardian,f.child,true,List.of(snapshot));
            var flow=new StructuredScenarioFlow(generator,privacy,new LunaInputSanitizer(JsonCodec.shared()),
                    new ScenarioGraphContext(f.service,f.executor,f.clock,Duration.ofSeconds(2)),repository,f.executor,Duration.ofMillis(500),JsonCodec.shared());
            flow.generate(session,snapshot,null,null,new PracticeAccountPort.AccountContext(f.guardian,f.child,10,List.of()),
                    f.clock.now(),new ScenarioDeadline(f.clock,f.clock.now().plusSeconds(15)));
            var sent=ArgumentCaptor.forClass(ScenarioScriptGenerator.Input.class);
            verify(generator).generate(sent.capture(),any());
            assertThat(sent.getValue().payload().get("relation")).isEqualTo("FRIEND_CLOSE");
            assertThat(sent.getValue().rules().relation()).isEqualTo("FRIEND_CLOSE");
            assertThat(sent.getValue().graphContextUsed()).isTrue();
            assertThat(goal.fourElements().partner()).isEqualTo("친구");
        }finally{f.close();}
    }
}
