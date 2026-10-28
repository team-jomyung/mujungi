package kr.mujoong.core.practice;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.*;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.knowledge.ChildKnowledgeService.ReferenceSeed;
import kr.mujoong.core.luna.LunaInputSanitizer;
import kr.mujoong.core.practicegoal.FourElements;
import kr.mujoong.core.practicegoal.SavedGoalReader;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

/** 처음 만드는 연습 장면에만 참고 씨앗의 불변 문장을 넣고, 저장까지 끝난 장면에 대해서만 참고 시점을 남긴다. */
@Tag("scenario-generation")
class ReferenceSeedFlowTest {
    private static final Duration WEEK = Duration.ofDays(7);
    private static final ReferenceSeed REFERENCE = new ReferenceSeed("seed:S2", "불편을 말했지만 원한 건 행동이었다");
    final ScenarioGraphTest f = new ScenarioGraphTest();
    final ScenarioScriptGenerator generator = mock(ScenarioScriptGenerator.class);
    final ScenarioRepository repository = mock(ScenarioRepository.class);
    PracticeSessionContextPort.SessionContext session;
    PracticeSessionContextPort.GoalSnapshot snapshot;
    StructuredScenarioFlow flow;

    @BeforeEach void setup() {
        f.setup();
        when(f.service.enabled()).thenReturn(true);
        when(repository.recentExposed(any(), any(), any(), any())).thenReturn(List.of());
        when(repository.continuity(any(), any(), any(), any(), any())).thenReturn(new PracticeContinuity.Context(List.of(), List.of()));
        var goal = new GoalContext(SavedGoalReader.SavedGoalView.Kind.CANDIDATE, "candidate", 1, "쉬고 싶은지 물어보기",
                new FourElements("함께 놀다가 친구가 쉬려는 상황", "친구", "뜻 확인", "쉬고 싶어?"), "seed:S1", null, null);
        snapshot = new PracticeSessionContextPort.GoalSnapshot(UUID.randomUUID(), goal.carrierKey(), 1, "친구", goal);
        session = new PracticeSessionContextPort.SessionContext(UUID.randomUUID(), f.guardian, f.child, true, List.of(snapshot));
        when(f.service.referenceSeed(eq(f.guardian), eq(f.child), eq("seed:S1"), any(), eq(WEEK))).thenReturn(Optional.of(REFERENCE));
        when(generator.generate(any(), any())).thenReturn(new ScenarioScriptGenerator.Result(null, null));
        var privacy = mock(ScenarioPrivacyContextPort.class);
        when(privacy.require(f.guardian, f.child)).thenReturn(new ScenarioPrivacyContextPort.Context("합성아이"));
        flow = new StructuredScenarioFlow(generator, privacy, new LunaInputSanitizer(JsonCodec.shared()),
                new ScenarioGraphContext(f.service, f.executor, f.clock, Duration.ofSeconds(2)), repository, f.executor,
                Duration.ofMillis(500), JsonCodec.shared())
                .references(new ReferenceSeedSelector(f.service, child -> Set.of("seed:S2"), f.executor, Duration.ofSeconds(2), WEEK));
    }
    @AfterEach void close() { f.close(); }

    ScenarioScriptGenerator.Result generate(ScenarioRepository.Stored previous) {
        return flow.generate(session, snapshot, null, previous, new PracticeAccountPort.AccountContext(f.guardian, f.child, 10, List.of()),
                f.clock.now(), new ScenarioDeadline(f.clock, f.clock.now().plusSeconds(15)));
    }
    Map<String, Object> sentPayload() {
        var sent = ArgumentCaptor.forClass(ScenarioScriptGenerator.Input.class);
        verify(generator).generate(sent.capture(), any());
        return sent.getValue().payload();
    }

    @Test void theFirstSceneCarriesOnlyTheReferenceInvariantSentenceAndMarksItOnlyAfterSaving() {
        var result = generate(null);

        assertThat(sentPayload().get("referenceContext")).isEqualTo(Map.of("invariantSentence", REFERENCE.invariantSentence()));
        assertThat(sentPayload()).containsKey("structureContext");
        assertThat(result.reference()).isEqualTo(REFERENCE);
        verify(f.service, never()).requestReferenceMark(any(), any(), any());

        flow.referenceUsed(f.guardian, f.child, result);
        verify(f.service).requestReferenceMark(f.guardian, f.child, "seed:S2");
    }

    @Test void aRejectedSceneCarriesNoReferenceToMark() {
        when(generator.generate(any(), any())).thenThrow(new ScenarioScriptGenerator.ContentExhausted());

        assertThatThrownBy(() -> generate(null)).isInstanceOf(ScenarioScriptGenerator.ContentExhausted.class);
        verify(f.service, never()).requestReferenceMark(any(), any(), any());
    }

    @Test void aNewScenarioForAPreviousSceneDoesNotLookUpAReference() {
        var previous = new ScenarioRepository.Stored(new Scenario(UUID.randomUUID(), "ORIGINAL", "친구", "기회", "이전 상황", "상대역", "시작", null),
                f.guardian, f.child, session.sessionId(), snapshot.goalSelectionId(), snapshot.behaviorKey(), 1, "ISSUED");
        var result = generate(previous);

        assertThat(sentPayload()).doesNotContainKey("referenceContext");
        assertThat(result.reference()).isNull();
        verify(f.service, never()).referenceSeed(any(), any(), any(), any(), any());
    }

    @Test void aFailedGoalSeedReadSkipsTheReferenceLookup() {
        when(f.service.skillContext(any(), any(), any())).thenReturn(Optional.empty());
        generate(null);

        assertThat(sentPayload()).doesNotContainKey("referenceContext");
        verify(f.service, never()).referenceSeed(any(), any(), any(), any(), any());
    }

    @Test void noReferenceKeepsTheScenePayloadAsBefore() {
        when(f.service.referenceSeed(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        var result = generate(null);

        assertThat(sentPayload()).doesNotContainKey("referenceContext");
        flow.referenceUsed(f.guardian, f.child, result);
        verify(f.service, never()).requestReferenceMark(any(), any(), any());
    }
}
