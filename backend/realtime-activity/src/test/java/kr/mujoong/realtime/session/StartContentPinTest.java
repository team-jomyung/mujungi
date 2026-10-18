package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.realtime.common.db.DbRetry;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.content.LiveContentRegistry;
import kr.mujoong.realtime.content.LiveContentRegistry.MatchKind;
import kr.mujoong.realtime.content.LiveContentResources;
import kr.mujoong.realtime.gateway.DeviceConnectionRepository;
import kr.mujoong.realtime.gateway.SocketRegistry;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.testing.TestEventLoggers;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 시작 처리기의 세션 콘텐츠 계획 고정(U8 W1 · BR1.2 · BR2.2 · SD4 · NFR3.6). approvedContent와 code가 겹치는 approvedSpeech 항목은 버리고
 * APP_ERROR(errorKind {@value StartSessionHandler#APPROVED_SPEECH_DUPLICATE_CODE}) 한 줄만 남기며, 그 줄에 문구 원문이 없는지 본다.
 */
@Tag("live-content")
class StartContentPinTest {

    private static final String CLOSING_SPEECH = "몰래-섞인-마무리-문구-7Q";
    private static final String REST_ACK = "잠깐 쉬고 싶구나. 천천히 해도 괜찮아.";

    private final LogCapture logs = LogCapture.detached();
    private final LiveContentRegistry registry = new LiveContentRegistry(LiveContentResources.loadDefault());
    private final StartSessionHandler handler = handler();

    @Test
    void aCodeAlsoInApprovedContentIsDroppedFromApprovedSpeechAndOnlyTheErrorKindIsLogged() {
        UUID restVersion = UUID.randomUUID();
        StartSessionHandler.StartCommand command = command(Map.of(
                "CLOSING", new StartSessionHandler.ApprovedSpeechItem(UUID.randomUUID(), CLOSING_SPEECH),
                "D_REST_ACK", new StartSessionHandler.ApprovedSpeechItem(restVersion, REST_ACK)));

        handler.pinContent(command);

        LiveContentRegistry.SessionContentPlan plan = registry.plan(command.sessionId()).orElseThrow();
        assertThat(plan.approvedSpeech()).containsOnlyKeys("D_REST_ACK");
        assertThat(plan.approvedContentVersionIds()).containsEntry("D_REST_ACK", restVersion)
                .containsEntry("CLOSING", command.approvedContent().get("CLOSING").contentVersionId());
        assertThat(registry.matchApproved(command.sessionId(), CLOSING_SPEECH).kind()).as("버린 쪽은 원문 일치에 쓰지 않는다")
                .isEqualTo(MatchKind.NOT_MATCHED);
        assertThat(logs.events(EventCode.APP_ERROR)).singleElement()
                .satisfies(event -> assertThat(LogCapture.attrs(event))
                        .containsEntry("errorKind", StartSessionHandler.APPROVED_SPEECH_DUPLICATE_CODE));
        assertThat(logs.lines()).noneMatch(line -> line.contains(CLOSING_SPEECH) || line.contains("쉬고"));
    }

    @Test
    void withoutDuplicatesNothingIsLoggedAndBothMapsArePinned() {
        StartSessionHandler.StartCommand command = command(
                Map.of("D_REST_ACK", new StartSessionHandler.ApprovedSpeechItem(UUID.randomUUID(), REST_ACK)));

        handler.pinContent(command);

        assertThat(registry.matchApproved(command.sessionId(), REST_ACK).kind()).isEqualTo(MatchKind.MATCHED);
        assertThat(logs.lines()).isEmpty();
    }

    @Test
    void anOldPackageWithoutApprovedSpeechIsPinnedWithAnEmptySpeechMap() {
        StartSessionHandler.StartCommand old = command(Map.of());

        handler.pinContent(old);

        assertThat(registry.plan(old.sessionId())).get().satisfies(plan -> {
            assertThat(plan.approvedSpeech()).isEmpty();
            assertThat(plan.judgmentSetVersion()).isEqualTo("judgment-v1");
        });
        assertThat(registry.matchApproved(old.sessionId(), REST_ACK).kind()).isEqualTo(MatchKind.NOT_MATCHED);
        assertThat(logs.lines()).isEmpty();
    }

    // ------------------------------------------------------------------ 도움

    private StartSessionHandler handler() {
        SessionExecutor.Services services = mock(SessionExecutor.Services.class);
        when(services.events()).thenReturn(TestEventLoggers.strict(new MutableClock(Instant.parse("2026-10-16T00:00:00Z")), logs,
                RealtimeMetrics.inMemory()));
        return new StartSessionHandler(mock(SessionRegistry.class), mock(TombstoneMap.class), mock(ActivitySessionRepository.class),
                mock(SessionStopExpressionRepository.class), mock(DeviceConnectionRepository.class), mock(SocketRegistry.class),
                mock(SessionStateEmitter.class), services, mock(PlatformTransactionManager.class), mock(DbRetry.class),
                new MutableClock(Instant.parse("2026-10-16T00:00:00Z")), mock(RealtimeProperties.class, RETURNS_DEEP_STUBS),
                registry);
    }

    private static StartSessionHandler.StartCommand command(Map<String, StartSessionHandler.ApprovedSpeechItem> speech) {
        Map<String, StartSessionHandler.ApprovedContentItem> content = new LinkedHashMap<>();
        content.put("CLOSING", new StartSessionHandler.ApprovedContentItem(UUID.randomUUID(), "안녕!", "/media/approved/closing-v1.wav"));
        content.put("WAITING_FILLER", new StartSessionHandler.ApprovedContentItem(UUID.randomUUID(), "음.",
                "/media/approved/waiting-filler-v1.wav"));
        return new StartSessionHandler.StartCommand(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), ActivityType.FREE_TALK, new ConsentSnapshot("1.0", true, true, true, true),
                new ChildContext("하늘", 8, List.of("공룡")), List.of(new StartSessionHandler.StopExpressionItem("무중아 그만할래", true)),
                content, new LinkedHashMap<>(speech));
    }
}
