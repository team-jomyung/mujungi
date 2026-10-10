package kr.mujoong.realtime.testing;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import kr.mujoong.realtime.voice.ProviderPort;
import kr.mujoong.realtime.voice.ProviderRequestFactory;

/**
 * 시험용 공급자 입구(realtime {@link ProviderPort} 구현이라 이 앱의 src/test에 둔다). 연결 거부 · started 없음 · 정상을 고르고, 열린
 * 세션으로 공급자 이벤트를 흘려 넣으며 받은 요청을 기록한다.
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

    public FakeProviderPort() {
        this(URI.create("ws://127.0.0.1:9/fake-provider"));
    }

    public FakeProviderPort(URI endpoint) {
        this.endpoint = endpoint;
    }

    public void mode(Mode value) {
        this.mode = value;
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
        FakeSession session = new FakeSession(listener, mode);
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
        private final List<Object> requests = new ArrayList<>();
        private volatile boolean open = true;

        private FakeSession(Listener listener, Mode mode) {
            this.listener = listener;
            this.mode = mode;
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

        @Override
        public void close(ProviderRequestFactory.SessionClose request) {
            record(request);
            open = false;
        }

        @Override
        public void close() {
            open = false;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        /** 공급자 이벤트를 흘려 넣는다(리더 스레드 흉내). */
        public void emit(ProviderEvent event) {
            listener.onEvent(event);
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
    }
}
