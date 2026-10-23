package kr.mujoong.realtime.testing;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import kr.mujoong.realtime.voice.ProviderPort;
import kr.mujoong.realtime.voice.ProviderRequestFactory;

/**
 * 시험용 공급자 입구(realtime {@link ProviderPort} 구현이라 이 앱의 src/test에 둔다). 연결 거부 · started 없음 · 정상을 고르고, 열린
 * 세션으로 공급자 이벤트를 흘려 넣으며 받은 요청을 기록한다. {@link #signalClosedOnClose}를 켜면 세션 닫기가 실제
 * {@code ProviderClient}처럼 닫기를 부른 스레드에서 리스너에 Closed를 한 번 알린다(리뷰 F2 시험).
 */
public final class FakeProviderPort implements ProviderPort {

    /** 연결 시나리오. */
    public enum Mode {
        /** 열리고 session.start에 session.started로 답한다. */
        OK,
        /** 연결 거부. */
        REFUSE,
        /** 열리지만 session.started를 보내지 않는다. */
        NO_STARTED
    }

    private final URI endpoint;
    private final List<FakeSession> sessions = new ArrayList<>();
    private volatile Mode mode = Mode.OK;
    private volatile boolean signalClosedOnClose;
    private volatile Duration newSpeechMargin;

    /** 이후 여는 세션이 실행기의 new-speech-margin으로 지시 응답을 흉내 내게 한다(시간 없는 출력 → 확인 사건 · 새 말 · 채움). */
    public void newSpeechMargin(Duration margin) {
        this.newSpeechMargin = margin;
    }

    public FakeProviderPort() {
        this(URI.create("ws://127.0.0.1:9/fake-provider"));
    }

    public FakeProviderPort(URI endpoint) {
        this.endpoint = endpoint;
    }

    public void mode(Mode value) {
        this.mode = value;
    }

    /** 이후 여는 세션의 닫기가 닫기를 부른 스레드에서 리스너에 Closed를 알리는가(실제 클라이언트의 reader.signalClosed 흉내). */
    public void signalClosedOnClose(boolean value) {
        this.signalClosedOnClose = value;
    }

    @Override
    public URI endpoint() {
        return endpoint;
    }

    @Override
    public ProviderSession open(Listener listener, Duration timeout) throws ProviderUnavailableException {
        if (mode == Mode.REFUSE) {
            throw new ProviderUnavailableException(ProviderUnavailableException.Reason.CONNECT_FAILED);
        }
        FakeSession session = new FakeSession(listener, mode, signalClosedOnClose);
        session.newSpeechMargin(newSpeechMargin);
        synchronized (sessions) {
            sessions.add(session);
        }
        return session;
    }

    /** 마지막으로 연 세션. */
    public FakeSession last() {
        synchronized (sessions) {
            if (sessions.isEmpty()) {
                throw new IllegalStateException("열린 공급자 세션이 없다");
            }
            return sessions.get(sessions.size() - 1);
        }
    }

    public int openedCount() {
        synchronized (sessions) {
            return sessions.size();
        }
    }

    /** 열린 공급자 세션 하나. */
    public static final class FakeSession implements ProviderSession {

        private final Listener listener;
        private final Mode mode;
        private final boolean signalOnClose;
        private final AtomicBoolean closedSignalled = new AtomicBoolean();
        private final List<Object> requests = new ArrayList<>();
        private volatile boolean open = true;
        private volatile boolean failOnClose;
        private volatile Duration newSpeechMargin;
        private volatile long audioBytes;
        private volatile int acked;
        private volatile boolean autoAck;
        private volatile long ackStartMs;
        private volatile long ackEndMs;

        private FakeSession(Listener listener, Mode mode, boolean signalOnClose) {
            this.listener = listener;
            this.mode = mode;
            this.signalOnClose = signalOnClose;
        }

        @Override
        public void start(ProviderRequestFactory.SessionStart request) {
            record(request);
            if (mode == Mode.OK) {
                listener.onEvent(new ProviderEvent.Started("prov-session-1"));
            }
        }

        @Override
        public void sendInputAudio(ProviderRequestFactory.InputAudioAppend request) throws ProviderUnavailableException {
            if (!open) {
                throw new ProviderUnavailableException(ProviderUnavailableException.Reason.CLOSED);
            }
            record(request);
        }

        /** U11: 지시문 추가({@code session.instructions.append})를 기록한다. */
        @Override
        public void appendInstructions(ProviderRequestFactory.InstructionsAppend request)
                throws ProviderUnavailableException {
            if (!open) {
                throw new ProviderUnavailableException(ProviderUnavailableException.Reason.CLOSED);
            }
            record(request);
            if (autoAck) {
                // 공급자처럼 확인 사건을 곧바로 돌려준다(시간은 호출자가 정한 오디오 시계 위 값)
                listener.onEvent(new ProviderEvent.InstructionsAppended(request.eventId(), ackStartMs, ackEndMs));
            }
        }

        @Override
        public void close(ProviderRequestFactory.SessionClose request) {
            if (failOnClose) {
                throw new IllegalStateException("시험용 공급자 닫기 실패");
            }
            record(request);
            open = false;
            signalClosed();
        }

        /** 이후 지시 추가마다 확인 사건({@code session.instructions.appended}, 주어진 시간)을 곧바로 돌려주게 한다. */
        public void autoAck(long startMs, long endMs) {
            this.ackStartMs = startMs;
            this.ackEndMs = endMs;
            this.autoAck = true;
        }

        /** 이후 닫기 요청(session.close)이 예외를 던지게 한다(정리 단계 실패 시험, 리뷰 반영 7). */
        public void failOnClose(boolean value) {
            this.failOnClose = value;
        }

        @Override
        public void close() {
            open = false;
            signalClosed();
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        /**
         * 공급자 이벤트를 흘려 넣는다(리더 스레드 흉내). 출력 음성은 오디오 시계(누적 바이트 / 48 ms)에 센다. 확인 사건을 아직 안 보낸 지시가 있는데
         * <b>시간 없는</b> 출력(옛 시험이 흘리는 전사 · 음성)이 오면, 공급자가 지시에 응답하는 것으로 보고 먼저 확인 사건 · 새 말 시작(빈 전사) ·
         * 낡은 꼬리 흉내 채움 음성 · 빈 음성 조각을 보내 실행기가 받는 단계로 가게 한다(설계 개정 A4). 시간이 있는 출력은 시험이 직접 시간표를
         * 만드는 것이라 건드리지 않는다.
         */
        public void emit(ProviderEvent event) {
            if (newSpeechMargin != null && untimedOutput(event) && hasUnackedInstruction()) {
                handshake();
            }
            if (event instanceof ProviderEvent.OutputAudioDelta delta) {
                audioBytes += delta.audio().length;
            }
            listener.onEvent(event);
        }

        private static boolean untimedOutput(ProviderEvent event) {
            return event instanceof ProviderEvent.OutputAudioDelta
                    || (event instanceof ProviderEvent.OutputTranscriptDelta transcript && transcript.startMs() < 0);
        }

        /** 지시 응답 시작 동작: 확인 사건 → 새 말 시작 전사(빈 글) → 채움 음성 → 빈 음성 조각(받는 단계로 간다). */
        private void handshake() {
            List<ProviderRequestFactory.InstructionsAppend> sent = requests(ProviderRequestFactory.InstructionsAppend.class);
            String id = sent.get(sent.size() - 1).eventId();
            acked = sent.size();
            long clock = audioClockMs();
            var latest = sent.get(sent.size() - 1);
            if (latest.type().equals("session.commentary.append") || latest.content().contains("<approved_speech>")) {
                listener.onEvent(new ProviderEvent.InstructionsAppended(id, clock, clock));
                return;
            }
            long ackEnd = clock + 100L;
            listener.onEvent(new ProviderEvent.InstructionsAppended(id, clock, ackEnd));
            listener.onEvent(new ProviderEvent.OutputTranscriptDelta("", ackEnd, ackEnd));
            byte[] filler = new byte[(int) ((100L + newSpeechMargin.toMillis()) * 48L)];
            audioBytes += filler.length;
            listener.onEvent(new ProviderEvent.OutputAudioDelta(filler));
            listener.onEvent(new ProviderEvent.OutputAudioDelta(new byte[0]));
        }

        /** 지금까지 흘려 넣은 출력 음성의 누적 ms(공급자의 세션 오디오 시계). */
        public long audioClockMs() {
            return audioBytes / 48L;
        }

        /** 확인 사건을 아직 안 보낸 지시가 있는가. */
        /** 실행기의 new-speech-margin을 알려 주면 시간 없는 출력이 지시 응답으로 이어진다(꺼 두려면 호출하지 않는다). */
        public void newSpeechMargin(Duration margin) {
            this.newSpeechMargin = margin;
        }

        public synchronized boolean hasUnackedInstruction() {
            return acked < requests(ProviderRequestFactory.InstructionsAppend.class).size();
        }

        public synchronized List<Object> requests() {
            return List.copyOf(requests);
        }

        /** 받은 요청 중 그 종류만. */
        public synchronized <T> List<T> requests(Class<T> type) {
            return requests.stream().filter(type::isInstance).map(type::cast).toList();
        }

        private synchronized void record(Object request) {
            requests.add(request);
        }

        private void signalClosed() {
            if (signalOnClose && closedSignalled.compareAndSet(false, true)) {
                listener.onEvent(new ProviderEvent.Closed());
            }
        }
    }
}
