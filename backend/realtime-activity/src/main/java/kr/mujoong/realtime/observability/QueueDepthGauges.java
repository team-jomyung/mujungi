package kr.mujoong.realtime.observability;

import java.util.Objects;
import kr.mujoong.realtime.gateway.SocketRegistry;
import kr.mujoong.realtime.log.AsyncRecorder;
import kr.mujoong.realtime.observability.RealtimeMetrics.Queue;
import kr.mujoong.realtime.session.SessionRegistry;
import org.springframework.stereotype.Component;

/**
 * {@code mujoong_queue_depth{queue}}의 원천 묶기 (U1 3.6 수정 · OD-5 · NFR7.8 · PD-2 · PD-4 · PD-5 · J298).
 *
 * <p>대기열 네 종류의 앱 전체 깊이(모든 세션 · 연결의 합)를 긁을 때마다 메모리에서 읽는다: session_mailbox = 세션 편지함 안전 + 일반(INIT
 * 포함), output_audio = 연결 음성 레인의 기다리는 · 보내는 중인 묶음, output_control = 연결 제어 레인의 메시지, recorder = 기록기가 들고
 * 있는 칸. 세션 · 연결 id는 라벨이 아니다(NFR9.8). 각 원천은 자기 잠금 안에서 크기만 읽으므로 음성 경로를 막지 않는다.
 */
@Component
public class QueueDepthGauges implements RealtimeMetrics.QueueDepthSource {

    private final SessionRegistry sessions;
    private final SocketRegistry sockets;
    private final AsyncRecorder recorder;

    public QueueDepthGauges(RealtimeMetrics metrics, SessionRegistry sessions, SocketRegistry sockets,
            AsyncRecorder recorder) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.sockets = Objects.requireNonNull(sockets, "sockets");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        Objects.requireNonNull(metrics, "metrics").queueDepthSource(this);
    }

    @Override
    public long depth(Queue queue) {
        return switch (Objects.requireNonNull(queue, "queue")) {
            case SESSION_MAILBOX -> sessions.queuedMessages();
            case OUTPUT_AUDIO -> sockets.queuedAudio();
            case OUTPUT_CONTROL -> sockets.queuedControl();
            case RECORDER -> recorder.heldRowsTotal();
        };
    }
}
