package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.SessionStatus;
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
 * 시작 처리기의 U8 고정 · 놓기 경로(U8 W1 · BR2.2 · SD4 · 설계 검토 R-03 · 위험 표 '정산 누락으로 쌓임'). 거절된 시작은 계획을 만들지 않고,
 * 계획을 고정한 뒤 실행기를 띄우지 못하면 계획을 놓아 맵에 남기지 않는지 본다. DB · 실행기는 대역이다.
 */
@Tag("u8")
class U8StartSessionHandlerPathsTest {

    private final LiveContentRegistry content = new LiveContentRegistry(LiveContentResources.loadDefault());
    private final SessionRegistry registry = new SessionRegistry();
    private final TombstoneMap tombstones = mock(TombstoneMap.class);
    private final ActivitySessionRepository sessions = mock(ActivitySessionRepository.class);
    private final DeviceConnectionRepository connections = mock(DeviceConnectionRepository.class);
    private final SocketRegistry sockets = mock(SocketRegistry.class);
    private final DbRetry dbRetry = mock(DbRetry.class);
    private final SessionStateEmitter emitter = mock(SessionStateEmitter.class);
    private final SessionExecutor.Services services = mock(SessionExecutor.Services.class);
    private final StartSessionHandler handler;

    U8StartSessionHandlerPathsTest() {
        SessionExecutor.Settings settings = mock(SessionExecutor.Settings.class);
        when(settings.mailboxCapacity()).thenReturn(16);
        when(services.settings()).thenReturn(settings);
        when(services.events()).thenReturn(TestEventLoggers.strict(new MutableClock(Instant.parse("2026-10-16T00:00:00Z")),
                LogCapture.detached(), RealtimeMetrics.inMemory()));
        handler = new StartSessionHandler(registry, tombstones, sessions, mock(SessionStopExpressionRepository.class), connections,
                sockets, emitter, services, mock(PlatformTransactionManager.class), dbRetry,
                new MutableClock(Instant.parse("2026-10-16T00:00:00Z")), mock(RealtimeProperties.class, RETURNS_DEEP_STUBS), content);
    }

    @Test
    void aTombstonedStartIsRejectedWithoutAPlan() {
        StartSessionHandler.StartCommand command = command();
        when(tombstones.contains(command.sessionId())).thenReturn(true);

        assertThat(handler.start(command, null)).isEqualTo(new StartSessionHandler.Rejected(
                StartSessionHandler.Reason.SESSION_NOT_ACTIVE));
        assertThat(content.plan(command.sessionId())).isEmpty();
    }

    @Test
    void aDeviceThatIsNotConnectedRejectsTheStartLeavesNoPlanAndFreesTheRegistration() {
        StartSessionHandler.StartCommand command = command();
        when(sessions.find(command.sessionId())).thenReturn(Optional.empty());
        when(connections.find(command.deviceId())).thenReturn(Optional.empty());

        assertThat(handler.start(command, null)).isEqualTo(new StartSessionHandler.Rejected(
                StartSessionHandler.Reason.DEVICE_NOT_CONNECTED));
        assertThat(content.pinnedCount()).isZero();
        assertThat(registry.register(command.sessionId(), () -> new SessionMailbox(16)).created()).as("등록을 지웠다").isTrue();
    }

    @Test
    void anExistingRowAnswersItsStatusWithoutPinning() {
        StartSessionHandler.StartCommand command = command();
        ActivitySessionRow row = mock(ActivitySessionRow.class);
        when(row.status()).thenReturn(SessionStatus.ACTIVE);
        when(sessions.find(command.sessionId())).thenReturn(Optional.of(row));

        assertThat(handler.start(command, null)).isEqualTo(new StartSessionHandler.Accepted(SessionStatus.ACTIVE));
        assertThat(content.pinnedCount()).isZero();
    }

    @Test
    void whenTheExecutorCannotStartAfterPinningThePlanIsReleased() {
        StartSessionHandler.StartCommand command = command();
        connected(command);
        ActivitySessionRow row = mock(ActivitySessionRow.class);
        when(dbRetry.execute(eq(DbRetry.Idempotency.IDEMPOTENT), any())).thenReturn(Optional.of(row));
        // 설정 대역의 lifecycle()이 비어 있어 실행기 생성이 실패한다(고정 뒤 · 활성화 전 실패)

        assertThatThrownBy(() -> handler.start(command, "0123456789abcdef0123456789abcdef"))
                .isInstanceOf(RuntimeException.class);

        verify(emitter).committed(any(), eq(row), any(), any());
        assertThat(content.pinnedCount()).as("시작 실패에서 놓는다").isZero();
        assertThat(content.matchApproved(command.sessionId(), "잠깐 쉬고 싶구나.").kind()).isEqualTo(MatchKind.UNKNOWN);
    }

    @Test
    void aLostInsertRaceAnswersInProgressWithoutPinning() {
        StartSessionHandler.StartCommand command = command();
        connected(command);
        when(dbRetry.execute(eq(DbRetry.Idempotency.IDEMPOTENT), any())).thenReturn(Optional.empty());

        assertThat(handler.start(command, null)).isEqualTo(new StartSessionHandler.Rejected(
                StartSessionHandler.Reason.SESSION_IN_PROGRESS));
        verify(emitter, never()).committed(any(), any(), any(), any());
        assertThat(content.pinnedCount()).isZero();
    }

    // ------------------------------------------------------------------ 도움

    private void connected(StartSessionHandler.StartCommand command) {
        UUID connection = UUID.randomUUID();
        kr.mujoong.realtime.gateway.DeviceConnectionRow row = mock(kr.mujoong.realtime.gateway.DeviceConnectionRow.class);
        when(row.state()).thenReturn(kr.mujoong.contract.status.ConnectionState.CONNECTED);
        when(row.connectionId()).thenReturn(connection);
        when(connections.find(command.deviceId())).thenReturn(Optional.of(row));
        when(sockets.isLive(command.deviceId(), connection)).thenReturn(true);
        when(sessions.find(command.sessionId())).thenReturn(Optional.empty());
        when(sessions.findInProgress(command.guardianId(), command.deviceId())).thenReturn(List.of());
    }

    private static StartSessionHandler.StartCommand command() {
        return new StartSessionHandler.StartCommand(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), ActivityType.FREE_TALK, new ConsentSnapshot("1.0", true, true, true, true),
                new ChildContext("하늘", 8, List.of("공룡")), List.of(new StartSessionHandler.StopExpressionItem("무중아 그만할래", true)),
                Map.of("CLOSING", new StartSessionHandler.ApprovedContentItem(UUID.randomUUID(), "안녕!", "/media/approved/closing-v1.wav"),
                        "WAITING_FILLER", new StartSessionHandler.ApprovedContentItem(UUID.randomUUID(), "음.",
                                "/media/approved/waiting-filler-v1.wav")),
                Map.of("D_REST_ACK", new StartSessionHandler.ApprovedSpeechItem(UUID.randomUUID(), "잠깐 쉬고 싶구나.")));
    }
}
