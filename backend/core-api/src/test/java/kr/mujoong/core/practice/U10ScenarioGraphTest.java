package kr.mujoong.core.practice;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import kr.mujoong.core.knowledge.*;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.*;

@Tag("u10") class U10ScenarioGraphTest {
    final ChildKnowledgeService service=mock(ChildKnowledgeService.class);
    final MutableClock clock=new MutableClock();
    final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
    final UUID guardian=UUID.randomUUID(),child=UUID.randomUUID();
    final KnowledgeSeed seed=new KnowledgeSeed("seed:S1",KnowledgeSeed.Origin.POC,"놀이",new StructureSignature("RECOGNIZE","의도","상대의 의도 1층","YES","YES"),"친구가 멈추려 한다","family","의도를 유지한다","FRIEND_CLOSE","FRIEND_PRIVATE",true);
    @BeforeEach void setup(){when(service.skillContext(guardian,child,"seed:S1")).thenReturn(Optional.of(new ChildKnowledgeService.SkillContext(seed,"v1")));}
    @AfterEach void close(){executor.shutdownNow();}
    ScenarioGraphContext.Session open(){return new ScenarioGraphContext(service,executor,clock,Duration.ofSeconds(2)).open(guardian,child,"seed:S1",List.of(),new ScenarioDeadline(clock,clock.now().plusSeconds(15)));}
    @Test void unavailableGraphIsEmpty(){when(service.skillContext(any(),any(),any())).thenReturn(Optional.empty());assertThat(open().context()).isEmpty();}
    @Test void graphFailureFallsBackWithoutChangingRequestBudget(){when(service.skillContext(any(),any(),any())).thenThrow(new IllegalStateException("unavailable"));assertThat(open().context()).isEmpty();}
    @Test void exactTotalGraphDeadlineDiscardsLateFirstRead(){when(service.skillContext(any(),any(),any())).thenAnswer(c->{clock.advance(Duration.ofSeconds(2));return Optional.of(new ChildKnowledgeService.SkillContext(seed,"v1"));});assertThat(open().context()).isEmpty();}
    @Test void noSkillDoesNotQueryGraph(){var session=new ScenarioGraphContext(service,executor,clock,Duration.ofSeconds(2)).open(guardian,child,"NONE",List.of(),new ScenarioDeadline(clock,clock.now().plusSeconds(15)));assertThat(session.context()).isEmpty();verifyNoInteractions(service);}
    @Test void validContextRetainsVersion(){assertThat(open().context().orElseThrow().graphVersion()).isEqualTo("v1");}
    @Test void outerDeadlineStillFailsInsteadOfStartingAI(){when(service.skillContext(any(),any(),any())).thenAnswer(c->{clock.advance(Duration.ofSeconds(15));return Optional.empty();});assertThatThrownBy(this::open).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);}
    @Test void absentPreservationNeverInventsContext(){var session=open();assertThat(session.valid(U10ScenarioGenerationTest.base(),U10ScenarioGenerationTest.base())).isFalse();verify(service,never()).variantPlan(any(),any(),any(),any(),any());}
    @Test void graphBaseWithoutPreservationIsInvalid(){assertThat(open().validBase(U10ScenarioGenerationTest.base())).isFalse();}
    @Test void absentGraphDoesNotRequireInventedPreservation(){when(service.skillContext(any(),any(),any())).thenReturn(Optional.empty());assertThat(open().validBase(U10ScenarioGenerationTest.base())).isTrue();}
}
