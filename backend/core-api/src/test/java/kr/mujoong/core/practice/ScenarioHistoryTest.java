package kr.mujoong.core.practice;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import kr.mujoong.core.textai.ScenarioScriptProvider;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

@Tag("scenario-generation") class ScenarioHistoryTest {
    StructuredFixture f;
    @BeforeEach void open(){f=new StructuredFixture();}
    @AfterEach void close(){f.close();}
    List<?> history(ScenarioScriptProvider.Call c){return (List<?>)c.input().get("history");}
    @Test void originalSendsActualExposureToBothCalls(){
        var first=f.legacy.service.generate(f.legacy.original());f.legacy.db.inTx(s->f.legacy.repository.expose(first.scenarioId(),f.legacy.session,f.legacy.clock.now()));
        f.legacy.session=UUID.randomUUID();f.legacy.current.set(new PracticeSessionContextPort.SessionContext(f.legacy.session,f.legacy.guardian,f.legacy.child,true,f.legacy.snapshot.goals()));
        clearInvocations(f.provider);when(f.provider.generate(any())).thenReturn(f.generated("독서"));f.legacy.service.generate(f.legacy.original());
        var calls=ArgumentCaptor.forClass(ScenarioScriptProvider.Call.class);verify(f.provider).generate(calls.capture());verify(f.provider).review(calls.capture());
        assertThat(calls.getAllValues()).allSatisfy(c->assertThat(history(c)).hasSize(1));
    }
    @Test void issuedOriginalIsSeparateSourceAndNotExposureForNew(){
        var first=f.legacy.service.generate(f.legacy.original());clearInvocations(f.provider);when(f.provider.generate(any())).thenReturn(f.generated("독서"));
        f.legacy.service.generate(new ScenarioService.Request(f.legacy.session,f.legacy.goal,"NEW_SCENARIO",first.scenarioId()));
        var c=ArgumentCaptor.forClass(ScenarioScriptProvider.Call.class);verify(f.provider).generate(c.capture());
        assertThat(history(c.getValue())).isEmpty();assertThat(c.getValue().input()).containsKey("sourceScenario");
    }
    @Test void emptyExposureIsExplicitEmptyList(){f.legacy.service.generate(f.legacy.original());var c=ArgumentCaptor.forClass(ScenarioScriptProvider.Call.class);verify(f.provider).generate(c.capture());assertThat(history(c.getValue())).isEmpty();}
    @Test void profileReadFailureDoesNotCallSupplier(){when(f.privacy.require(any(),any())).thenThrow(new IllegalStateException("synthetic unavailable"));assertThatThrownBy(()->f.legacy.service.generate(f.legacy.original())).isInstanceOf(IllegalStateException.class);verifyNoInteractions(f.provider);}
    @Test void callsUseSanitizedGoalHistoryAndInterests(){f.legacy.service.generate(f.legacy.original());var c=ArgumentCaptor.forClass(ScenarioScriptProvider.Call.class);verify(f.provider).generate(c.capture());verify(f.provider).review(c.capture());assertThat(c.getAllValues()).allSatisfy(v->{assertThat(kr.mujoong.contract.json.JsonCodec.shared().write(v.input())).doesNotContain("합성아이");assertThat(v.input()).containsKey("ageYears").containsKey("interests");assertThat(v.aliases()).containsEntry("합성아이","아이");});}
    @Test void historyReadOccursBeforeModelAndPreservesStructuredReplay(){var first=f.legacy.service.generate(f.legacy.original());assertThat(f.legacy.service.generate(f.legacy.original())).isEqualTo(first);verify(f.provider).generate(any());verify(f.provider).review(any());assertThat(first.generation().calls()).isEqualTo(2);}
}
