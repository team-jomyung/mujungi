package kr.mujoong.realtime.practiceflow;

import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.minimalScript;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.packageJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.VoiceSessionClosed;
import kr.mujoong.realtime.guardrail.GuardrailRequest;
import kr.mujoong.realtime.internalhttp.InternalHttpClient;
import kr.mujoong.realtime.judgment.JudgmentPoint;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.FactKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.LiveTiming;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneRunRecord;
import kr.mujoong.realtime.session.ActivitySessionRepository;
import kr.mujoong.realtime.session.ChildContext;
import kr.mujoong.realtime.session.ConsentSnapshot;
import kr.mujoong.realtime.session.NewActivitySession;
import kr.mujoong.realtime.session.NoOpPracticeSessionExtension;
import kr.mujoong.realtime.session.SessionEvent;
import kr.mujoong.realtime.session.SessionMailbox;
import kr.mujoong.realtime.testing.FakeGuardrailClient;
import kr.mujoong.realtime.testing.FakeJudgeClient;
import kr.mujoong.realtime.testing.InternalApi;
import kr.mujoong.realtime.testing.RealtimeDb;
import kr.mujoong.realtime.testing.SessionHarness;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 새 연습 흐름 끝-끝 최소 범위 (U12 FR8.1, 계획 열린 질문 6): AC1.2.3 "그거 줘 → 어느 색? → 파란색 → 친구 반응" 한 장면을 실행부({@code SessionExecutor}) ·
 * 라우터 · 상태 기계 · 운영 기록기(실제 DB V7 표)로 시작부터 {@code record.practice}까지 본다. 공급자 · 제브 · 판정은 모두 시험이 손으로 푸는
 * 가짜이다(R-F1). 이 시험의 성공은 테스트 대역 · 합성 대화의 성공이며 실제 API · 실제 기기 성공이 아니다(R-M11). 기기 시뮬레이터 · {@code verify.sh}
 * 단계 확장은 하지 않고 후속 과제다.
 */
@Tag("practice-flow")
@Tag("db")
class PracticeFlowIT {

    private static final UUID GOAL = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID SCENARIO = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final String INTRO = "친구가 상자 두 개를 들고 있어.";
    private static final String OPPORTUNITY = "친구가 말했어. 그거 줘.";
    private static final String REASK = "어느 색을 말하는 건지 잘 모르겠어";
    private static final String REACTION = "아, 파란 상자였구나! 여기 있어.";
    private static final String MARKER = "표식QX93";

    private static RealtimeDb db;

    private final SessionHarness h = new SessionHarness();
    private final FakeJudgeClient judge = h.useJudge();
    private final FakeGuardrailClient guard = h.useGuardrail();
    private final List<Object> settled = new ArrayList<>();
    private final List<RuntimeException> failures = new ArrayList<>();
    private PracticeFlowRepository repository;

    @BeforeAll
    static void openDatabase() {
        db = RealtimeDb.open();
    }

    @AfterAll
    static void closeDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        db.wipe();
        repository = new PracticeFlowRepository(db.jdbc());
        ActivitySessionRepository sessions = new ActivitySessionRepository(db.jdbc());
        NewActivitySession row = new NewActivitySession(h.sessionId, h.guardianId, UUID.randomUUID(), h.deviceId,
                ActivityType.PRACTICE, UUID.randomUUID().toString(), new ConsentSnapshot("1.0", true, true, false, false),
                new ChildContext("하늘", 8, List.of("공룡")), null, null);
        assertThat(db.inTransaction(() -> sessions.insert(row, h.clock.now()))).isTrue();

        h.activityType = ActivityType.PRACTICE;
        h.practiceJson = packageJson(GOAL, minimalScript(SCENARIO));
        h.settlement = (sessionId, practice, cutoffAt) -> settled.add(practice);
        PracticeScriptRequester requester = new PracticeScriptRequester(mock(InternalHttpClient.class), Runnable::run,
                Duration.ofSeconds(1));
        h.practice = session -> new PracticeFlowRouter(NoOpPracticeSessionExtension.FACTORY.create(session),
                () -> new PracticeConversation(session, h.guardianId, h.clock, PracticeFlowTestKit.defaults(), requester,
                        new PracticeFlowRecorder(repository, db.transactions()), failures::add));
    }

    @AfterEach
    void tearDown() {
        db.wipe();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aReaskThatResolvesTheSceneRunsFromStartToTheSettledPracticeRecord() {
        h.activate();
        // 도입과 기회 문장: 대본의 승인 문장이 원문 그대로 재생되고(제브 호출 없음) 재생이 끝나면 다음으로 간다
        playApproved(INTRO);
        assertThat(repository.findSceneRuns(h.sessionId)).as("도입 재생 성공 뒤에야 장면 행이 생긴다").hasSize(1);
        playApproved(OPPORTUNITY);
        assertThat(guard.count()).isZero();

        // 아이가 "그거 줘" → J2 일부 전달 → 친구가 한 번 되묻는다(제브 PRACTICE_PEER 검사 뒤 재생)
        h.childSays("child-1", "그거 줘 " + MARKER);
        assertThat(judge.last().request().point()).isEqualTo(JudgmentPoint.J2);
        judge.confirm("PARTIAL", 0.8, "FULL", 0.1);
        h.drain();
        h.settleSend();
        h.speak(REASK, 4800);
        h.advance(Duration.ofMillis(801));
        assertThat(guard.last().request().mode()).isEqualTo(GuardrailRequest.Mode.PRACTICE_PEER);
        assertThat(h.link.outputs()).hasSize(2);
        guard.allow();
        h.drain();
        assertThat(h.link.outputs()).hasSize(3);
        complete(h.link.outputs().getLast().playbackId());

        // 아이가 "파란색" → J3 충분 → 친구가 역할 안에서 한 번 반응(D_CONTINUE, 제브 PRACTICE_PEER 검사 뒤 재생) → 정리(변형 없음) → 연습 종료
        h.childSays("child-2", "파란색");
        assertThat(judge.last().request().point()).isEqualTo(JudgmentPoint.J3);
        judge.confirm("DELIVERED", 0.9, "STILL_SHORT", 0.05);
        h.drain();
        assertThat(h.executor.status()).as("친구 반응이 끝나기 전에는 닫지 않는다").isEqualTo(SessionStatus.ACTIVE);
        h.settleSend();
        h.speak(REACTION, 4800);
        h.advance(Duration.ofMillis(801));
        assertThat(guard.last().request().mode()).isEqualTo(GuardrailRequest.Mode.PRACTICE_PEER);
        guard.allow();
        h.drain();
        assertThat(h.link.outputs()).as("도입 · 기회 문장 · 되묻기 · 친구 반응").hasSize(4);
        complete(h.link.outputs().getLast().playbackId());
        assertThat(h.executor.status()).isEqualTo(SessionStatus.CLOSING);
        assertThat(h.transitions.find(h.sessionId).orElseThrow().endDetail()).isEqualTo("PRACTICE_COMPLETED");

        // 종료 뒤 정산: 같은 값이 표에도, 정산 입구에도 있다
        h.mailbox.offerGeneral(new SessionMailbox.VoiceClosed(h.link.connectionId(),
                new VoiceSessionClosed(h.clock.now(), h.clock.now()), h.clock.now()));
        h.drain();
        h.advance(SessionHarness.LIFECYCLE.recordSettle());
        assertThat(settled).hasSize(1);
        Map<String, Object> practice = (Map<String, Object>) settled.getFirst();
        String json = JsonCodec.shared().write(practice);
        assertThat(InternalApi.schemaErrors("practice-record-v2.schema.json", json)).isEmpty();
        assertThat(practice).containsEntry("flowVersion", "practice-conversation-v1");
        assertThat((List<Object>) practice.get("scenes")).hasSize(1);

        SceneRunRecord stored = repository.findSceneRuns(h.sessionId).getFirst();
        assertThat(stored.goalRef()).isEqualTo(GOAL);
        assertThat(stored.scenarioRef()).isEqualTo(SCENARIO);
        assertThat(stored.liveTiming()).isEqualTo(LiveTiming.AFTER_REASK);
        assertThat(stored.facts()).extracting(PracticeFlowTypes.Fact::kind).contains(FactKind.REASK_RESOLVED);
        assertThat(repository.findHelps(h.sessionId)).as("집중 도움 0건").isEmpty();
        assertThat(PracticeFlowRecordAssembler.assemble(repository.findSceneRuns(h.sessionId))).isEqualTo(practice);
        assertThat(failures).isEmpty();

        // 표식: 아이가 한 말은 기록 · 정산 값 · 앱 오류 · 이벤트 · 제브 요청 밖의 어디에도 나가지 않는다(대화 전사 행은 별도 기능)
        h.recorder.tick();
        assertThat(json).doesNotContain(MARKER);
        assertThat(h.store.sessionEvents).extracting(SessionEvent::getPayloadJson).noneMatch(payload -> payload != null && payload.contains(MARKER));
        assertThat(h.logs.anyLineContains(MARKER)).isFalse();
        assertThat(h.store.guardrails.toString()).doesNotContain(MARKER);
    }

    @Test
    void aBlockedReaskSendsZeroBytesAndTheSceneIsWrappedWithoutStoppingThePractice() {
        h.activate();
        playApproved(INTRO);
        playApproved(OPPORTUNITY);
        h.childSays("child-1", "그거 줘");
        judge.confirm("PARTIAL", 0.8, "FULL", 0.1);
        h.drain();
        h.settleSend();
        h.speak(REASK, 4800);
        h.advance(Duration.ofMillis(801));

        guard.block();
        h.drain();
        h.settleSend();
        h.speak("다시 한 번 물어볼게", 4800);
        h.advance(Duration.ofMillis(801));
        guard.block();
        h.drain();

        assertThat(h.link.outputs()).as("막힌 되묻기는 0바이트").hasSize(2);
        assertThat(h.executor.status()).as("두 번째 막힘은 장면을 정리하고 연습은 이어서 끝난다").isEqualTo(SessionStatus.CLOSING);
        SceneRunRecord wrapped = repository.findSceneRuns(h.sessionId).getFirst();
        assertThat(wrapped.facts()).extracting(PracticeFlowTypes.Fact::kind).as("막힘은 재생 실패 사실이 아니다")
                .doesNotContain(FactKind.PLAYBACK_FAILED);
        assertThat(wrapped.liveTiming()).isEqualTo(LiveTiming.NONE);
        assertThat(wrapped.endedAt()).isNotNull();
        assertThat(failures).isEmpty();
    }

    private void playApproved(String text) {
        h.settleSend();
        h.speak(text, 4800);
        h.advance(Duration.ofMillis(801));
        assertThat(h.link.outputs()).isNotEmpty();
        complete(h.link.outputs().getLast().playbackId());
    }

    private void complete(UUID playbackId) {
        for (PlaybackMark.Status status : List.of(PlaybackMark.Status.STARTED, PlaybackMark.Status.COMPLETED)) {
            h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                    new PlaybackMark(playbackId, status, null, h.clock.now(), null, null), h.clock.now()));
            h.drain();
        }
    }
}
