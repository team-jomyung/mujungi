package kr.mujoong.realtime.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.WsPayload;
import kr.mujoong.contract.ws.message.CloseTransport;
import kr.mujoong.contract.ws.message.HelloOk;
import kr.mujoong.contract.ws.message.SessionState;
import kr.mujoong.contract.ws.message.View;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.session.SessionExecutor;
import kr.mujoong.realtime.session.SessionMailbox;
import kr.mujoong.realtime.session.SessionRegistry;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * HELLO_OK 인계의 리뷰 반영 4 시험: HELLO_OK.session을 실은 뒤 세션 실행기가 이미 끝나 편지함이 닫혔으면(LinkReplaced를 넣지 못함) 인계가
 * 새 연결을 대기로 돌려보낸다 — 기기가 '듣는 중'에 멈추지 않는다.
 */
@Tag("voice-session")
class ConnectionHandoverTest {

    private final UUID deviceId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final SessionRegistry sessions = mock(SessionRegistry.class);
    private final SessionExecutor executor = mock(SessionExecutor.class);
    private final DeviceConnection connection = mock(DeviceConnection.class);
    private final ConnectionHandover handover = new ConnectionHandover(mock(ConnectionStateWriter.class),
            mock(SocketRegistry.class), sessions, mock(EventLogger.class), new MutableClock(), Duration.ofSeconds(15));

    @Test
    void aClosedMailboxAfterTheSessionInfoSendsCloseTransportAndStandbyToTheResumedDevice() {
        SessionMailbox closed = new SessionMailbox(10);
        closed.close();
        prepare(closed, sessionId);

        assertThat(handover.complete(connection)).isTrue();

        List<WsPayload> sent = sent();
        assertThat(sent).hasSize(3);
        assertThat(sent.get(0)).isInstanceOfSatisfying(HelloOk.class,
                hello -> assertThat(hello.session().resumed()).isTrue());
        assertThat(sent.get(1)).isEqualTo(new CloseTransport(CloseTransport.Reason.DISCONNECT));
        assertThat(sent.get(2)).isEqualTo(new SessionState(View.STANDBY));
    }

    /** 2차 리뷰 5: 그 사이 같은 기기의 새 세션이 이 연결을 묶었으면 뒤늦은 대기 명령으로 새 세션 표시를 덮지 않는다. */
    @Test
    void aConnectionAlreadyBoundToANewSessionIsNotSentBackToStandby() {
        SessionMailbox closed = new SessionMailbox(10);
        closed.close();
        prepare(closed, sessionId);
        when(connection.boundSessionId()).thenReturn(UUID.randomUUID());

        assertThat(handover.complete(connection)).isTrue();

        assertThat(sent()).singleElement().isInstanceOf(HelloOk.class);
    }

    @Test
    void anOpenMailboxGetsTheRelinkAndTheConnectionGetsOnlyHelloOk() {
        SessionMailbox open = new SessionMailbox(10);
        prepare(open, null);

        assertThat(handover.complete(connection)).isTrue();

        assertThat(sent()).singleElement().isInstanceOf(HelloOk.class);
        assertThat(open.poll()).isInstanceOfSatisfying(SessionMailbox.LinkReplaced.class,
                replaced -> assertThat(replaced.resumed()).isFalse());
    }

    private void prepare(SessionMailbox mailbox, UUID resume) {
        when(connection.deviceId()).thenReturn(deviceId);
        when(connection.connectionId()).thenReturn(UUID.randomUUID());
        when(connection.phase()).thenReturn(DeviceConnection.Phase.AWAIT_HELLO);
        when(connection.advance(DeviceConnection.Phase.AWAIT_HELLO, DeviceConnection.Phase.READY)).thenReturn(true);
        when(connection.resumeSessionId()).thenReturn(resume);
        when(connection.isOpen()).thenReturn(true);
        when(connection.sendControl(any(), any(), any())).thenReturn(true);
        when(executor.sessionId()).thenReturn(sessionId);
        when(executor.view()).thenReturn(View.LISTENING);
        when(executor.status()).thenReturn(SessionStatus.ABORTED);
        when(executor.mailbox()).thenReturn(mailbox);
        when(sessions.liveExecutorForDevice(deviceId)).thenReturn(Optional.of(executor));
    }

    private List<WsPayload> sent() {
        ArgumentCaptor<WsPayload> payloads = ArgumentCaptor.forClass(WsPayload.class);
        verify(connection, org.mockito.Mockito.atLeastOnce()).sendControl(payloads.capture(), any(), any());
        return payloads.getAllValues();
    }
}
