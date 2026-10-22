package kr.mujoong.realtime.voice;

import kr.mujoong.contract.context.ConversationGuide;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.runtime.TaskLauncher;
import kr.mujoong.realtime.session.ChildContext;
import kr.mujoong.realtime.voice.ProviderPort.Listener;
import kr.mujoong.realtime.voice.ProviderPort.ProviderSession;
import kr.mujoong.realtime.voice.ProviderPort.ProviderUnavailableException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 공급자 연결 스레드 (U1 3.5 Step 25 · R3-1 · PD-1 표 2.1-A · SD-20 · J109 · J141 · RD-5 · NFR4.2).
 *
 * <p>세션 실행기가 아니라 세션마다 별도 가상 스레드가 연결을 연다. 순서: ① 주소가 허용 목록(J109 · J141) 밖이면 연결하지 않고 실패를
 * 알린다 ② {@link ProviderRequestFactory}로 {@code session.start} 요청을 만든다(같은 팩토리가 유일한 경로, SD-20) ③ {@code
 * mujoong.provider.connect-timeout}(10초) 안에 연결을 연다 ④ 결과(Connected)를 먼저 알리고 ⑤ {@code session.start}를 보낸다 — 그래서
 * session.started가 Connected보다 먼저 처리되지 않는다. 연결이 걸려 있는 동안에도 실행기는 취소 · 안전 타이머를 처리한다. 취소되면
 * 스레드를 끊고 늦게 열린 연결은 바로 닫는다.
 */
@Component
public class ProviderConnector {

    /** 연결 결과 수신자(세션 일반 편지함에 넣는다). */
    public interface Callback {

        /** 연결이 열렸다. 이 호출이 끝난 뒤 session.start를 보낸다. */
        void connected(ProviderSession session);

        /** 연결 · 시작 실패(PROVIDER_UNAVAILABLE). */
        void failed(ProviderUnavailableException.Reason reason);
    }

    /** 진행 중 연결 시도. */
    public static final class Attempt {

        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile Thread thread;

        /** 취소한다(연결 중이면 스레드를 끊는다). 두 번 불러도 된다. */
        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                Thread running = thread;
                if (running != null) {
                    running.interrupt();
                }
            }
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        /** 연결 스레드가 끝날 때까지 기다린다(시험 · 정리). */
        public boolean await(Duration timeout) throws InterruptedException {
            Thread running = thread;
            return running == null || running.join(timeout);
        }
    }

    private final ProviderPort port;
    private final ProviderRequestFactory factory;
    private final Duration connectTimeout;
    private final TaskLauncher launcher;
    private final String[] activeProfiles;

    @Autowired
    public ProviderConnector(ProviderPort port, ProviderRequestFactory factory, RealtimeProperties properties,
            Environment environment, TaskLauncher launcher) {
        this(port, factory, properties.provider().connectTimeout(), launcher, environment.getActiveProfiles());
    }

    public ProviderConnector(ProviderPort port, ProviderRequestFactory factory, Duration connectTimeout,
            TaskLauncher launcher, String... activeProfiles) {
        this.port = Objects.requireNonNull(port, "port");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.activeProfiles = activeProfiles.clone();
    }

    /** 연결 스레드를 띄운다. */
    public Attempt connect(UUID sessionId, ChildContext childContext, Listener listener, Callback callback) {
        return connect(sessionId, childContext, null, listener, callback);
    }

    public Attempt connect(UUID sessionId, ChildContext childContext, ConversationGuide guide, Listener listener, Callback callback) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(childContext, "childContext");
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(callback, "callback");
        Attempt attempt = new Attempt();
        attempt.thread = launcher.launch("provider-connect-" + sessionId,
                () -> run(attempt, childContext, guide, listener, callback));
        if (attempt.isCancelled() && attempt.thread != null) {
            attempt.thread.interrupt();
        }
        return attempt;
    }

    private void run(Attempt attempt, ChildContext childContext, ConversationGuide guide, Listener listener, Callback callback) {
        if (!ProviderHosts.isConnectable(port.endpoint(), activeProfiles)) {
            callback.failed(ProviderUnavailableException.Reason.HOST_NOT_ALLOWED);
            return;
        }
        ProviderRequestFactory.SessionStart request = guide == null ? factory.sessionStart(childContext) : factory.sessionStart(childContext, ProviderRequestFactory.AUDIO_RATE, guide);
        ProviderSession session = null;
        try {
            session = port.open(listener, connectTimeout);
            if (attempt.isCancelled()) {
                session.close();
                return;
            }
            callback.connected(session);
            session.start(request);
        } catch (ProviderUnavailableException e) {
            if (!attempt.isCancelled()) {
                callback.failed(e.reason());
            }
        } catch (InterruptedException e) {
            if (session != null) {
                session.close();
            }
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            if (session != null) {
                session.close();
            }
            if (!attempt.isCancelled()) {
                callback.failed(ProviderUnavailableException.Reason.CONNECT_FAILED);
            }
        }
    }
}
