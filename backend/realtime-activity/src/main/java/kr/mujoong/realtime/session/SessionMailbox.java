package kr.mujoong.realtime.session;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import kr.mujoong.contract.status.EndRequestReason;
import kr.mujoong.contract.ws.message.DeviceReady;
import kr.mujoong.contract.ws.message.DeviceState;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.VoiceSessionClosed;
import kr.mujoong.realtime.gateway.DeviceLink;
import kr.mujoong.realtime.voice.ProviderPort;

/**
 * 세션 실행기의 두 편지함 (U1 3.5 Step 23 · PD-1 표 2.1-B · 2.1-C · 2.1-D · PD-2).
 *
 * <p>안전 편지함(L0H)은 상한이 없고 시계 · 내부 HTTP 명령 · 생명주기 · 연결 끊김이 넣는다. 일반 편지함(L0N)은
 * {@code mujoong.limits.session-control-queue}(1,000)가 상한이고 기기 쪽 생산자는 {@link #offerGeneral}(가득 차면 거짓 → 연결
 * 1011), 공급자 쪽 생산자는 {@link #putGeneral}(자리가 날 때까지 대기)로 넣는다. 꺼낼 때는 늘 안전 편지함을 먼저 비운다. 닫힌 뒤
 * 넣는 메시지는 버린다(BR6.11). 잠금 안에서 블로킹 호출을 하지 않는다(가상 스레드 핀 고정 방지).
 */
public final class SessionMailbox {

    /** 실행기가 처리하는 메시지. */
    public sealed interface Message {
    }

    /** 실행기 첫 메시지: START_SESSION 송신 · ② 무장 · 공급자 연결(R3-1). */
    public record Bootstrap() implements Message {
    }

    /** 시계 만기(타이머 스레드는 이것을 넣기만 한다, RD-3). */
    public record TimerFired(SessionTimers.Kind kind, long generation) implements Message {
    }

    /** 끝내기 · 정리 명령. 결과는 약속으로 돌려준다(호출자는 제한 시간까지 기다린다). */
    public record EndRequested(UUID requestId, EndRequestReason reason, CompletableFuture<EndOutcome> result)
            implements Message {

        public EndRequested {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(result, "result");
        }
    }

    /** 세션에 묶인 기기 연결이 닫혔거나 대체됐다(SD-17 세대 올림). */
    public record ConnectionLost(UUID connectionId) implements Message {
    }

    /**
     * U3-J4 · U3-J5: 같은 기기의 새 연결이 HELLO_OK를 받았다 — 실행기가 이 연결에 세션을 다시 묶고 끊김 구간을 닫은 뒤 상태 · 미확인 명령을 다시
     * 보낸다. {@code resumed}는 기기가 HELLO resume으로 이 세션을 이어가겠다고 했는가(아니면 START_SESSION부터 다시).
     */
    public record LinkReplaced(DeviceLink link, boolean resumed, Instant at) implements Message {

        public LinkReplaced {
            Objects.requireNonNull(link, "link");
            Objects.requireNonNull(at, "at");
        }
    }

    /** U3-J9: 묶인 기기의 DEVICE_STATE(마이크 · 소리 · 보임 · 켜짐 유지, 바뀐 필드만). */
    public record DeviceStateReceived(UUID connectionId, DeviceState state, Instant receivedAt) implements Message {
    }

    /**
     * 앱 종료(RD-13 T3 · BR6.13): 공급자 연결을 닫고 기록기 마지막 플러시를 신호한다. 세션은 닫지 않는다. 실행기가 처리를 마치면(실패해도)
     * {@code handled}를 끝내고, 처리하지 못하고 사라지면 {@link #closeAndRelease}가 끝낸다 — 정상 종료는 이 약속을 기다린 뒤 폴러를 멈춘다
     * (3.6 수정 J304).
     */
    public record ServerShutdown(CompletableFuture<Void> handled) implements Message {

        public ServerShutdown {
            Objects.requireNonNull(handled, "handled");
        }

        public ServerShutdown() {
            this(new CompletableFuture<>());
        }
    }

    /**
     * U4b(BR4.7 · U4b-J8): 같은 기기에 새 세션이 시작됐다 — DECLINED 마무리 인사가 남았으면 버리고(기기 명령 없이) 바로 정리 · 정산한다.
     */
    public record DeviceTakenOver(UUID newSessionId) implements Message {
    }

    /**
     * U4b(BR9.1 · U4b-J19): 계정 삭제 강제 정지 — DB 전이 · 이벤트 · 스냅숏 없이 입력 중계 · 원음 기록 · 시계 · 공급자를 닫고(연결이 살아 있으면
     * CLOSE_TRANSPORT(CONSENT_WITHDRAWN)) 정리한다. 끝나면(실행기가 이미 끝났어도) {@code stopped}를 끝낸다.
     */
    public record DeletionStop(CompletableFuture<Void> stopped) implements Message {

        public DeletionStop {
            Objects.requireNonNull(stopped, "stopped");
        }

        public DeletionStop() {
            this(new CompletableFuture<>());
        }
    }

    /** U4b(BR10.5): 연습 확장점 사건(재생 완료 · 비동기 결과). 확장점이 다른 스레드에서 post해도 실행기 스레드에서 전달된다. */
    public record PracticeEvent(PracticeSessionExtension.ExtensionEvent event) implements Message {

        public PracticeEvent {
            Objects.requireNonNull(event, "event");
        }
    }

    /** U4b(BR10.4): 연습 확장점의 이름 붙은 시계 만기(타이머 스레드는 넣기만 한다, 세대로 낡은 만기를 버린다). */
    public record PracticeTimerFired(String name, long generation) implements Message {
    }

    /**
     * U11 BR1.8: 판정 결과(Luna 완료 콜백 스레드가 일반 편지함에 넣는다). 안전 편지함을 쓰지 않아 앞서 도착한 출력 조각보다 먼저 처리되지 않는다.
     * 차례 id가 다르거나 턴이 닫혔으면 실행기가 버린다(BR5.2).
     */
    public record JudgmentArrived(UUID turnId, kr.mujoong.realtime.judgment.JudgeOutcome outcome) implements Message {

        public JudgmentArrived {
            Objects.requireNonNull(turnId, "turnId");
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    /** U11 설계 개정 A4: 판정 뒤 지시의 새 말이 시한 안에 시작하지 않았거나 송신이 실패했다(큐 스레드에서도 넣는다). */
    public record JudgmentInstructionFailed(UUID turnId) implements Message {

        public JudgmentInstructionFailed {
            Objects.requireNonNull(turnId, "turnId");
        }
    }

    /**
     * U13 BR3.1: 제브 검사 결과(완료 콜백 스레드가 일반 편지함에 넣는다). 판정 결과와 같은 길이다 — 안전 편지함은 일반 편지함보다 먼저 비워져, 이미
     * 쌓인 기기 · 공급자 사건이 반영되기 전 상태로 ALLOW가 검증되기 때문이다. 요청 id가 지금 검사표와 다르면 실행기가 버린다.
     */
    public record GuardrailArrived(UUID requestId, kr.mujoong.realtime.guardrail.GuardrailVerdict verdict) implements Message {

        public GuardrailArrived {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(verdict, "verdict");
        }
    }

    /** U13 BR6.6: 제브 재지시의 새 말이 시한 안에 시작하지 않았거나 송신이 실패했다(큐 스레드에서도 넣는다). */
    public record GuardrailRetryNotHeard(UUID retryId) implements Message {

        public GuardrailRetryNotHeard {
            Objects.requireNonNull(retryId, "retryId");
        }
    }

    /** START_SESSION ACK. */
    public record DeviceAck(UUID connectionId, UUID ackOf, Instant receivedAt) implements Message {
    }

    /** DEVICE_READY. */
    public record DeviceReadyReceived(UUID connectionId, DeviceReady ready, Instant receivedAt) implements Message {
    }

    /** PLAYBACK_MARK(수신 핸들러가 찍은 tServerRecv 첨부). */
    public record PlaybackMarkReceived(UUID connectionId, PlaybackMark mark, Instant serverRecvAt) implements Message {
    }

    /** VOICE_SESSION_CLOSED. */
    public record VoiceClosed(UUID connectionId, VoiceSessionClosed closed, Instant receivedAt) implements Message {
    }

    /** 공급자 연결이 열렸다(연결 스레드). */
    public record ProviderConnected(ProviderPort.ProviderSession session) implements Message {
    }

    /** 공급자 연결 · 시작 실패(연결 스레드). */
    public record ProviderFailed(ProviderPort.ProviderUnavailableException.Reason reason) implements Message {
    }

    /** 공급자 이벤트(리더, 읽은 순서 그대로). */
    public record ProviderEventReceived(ProviderPort.ProviderEvent event, Instant receivedAt, long receivedNanos)
            implements Message {
    }

    /** 끝내기 · 정리 명령의 결과(J54 응답표 · J111). */
    public enum EndOutcome {
        /** 202 {status: CLOSING}. */
        CLOSING,
        /** 202 {} (CORE_ABANDONED 정리). */
        ACCEPTED_EMPTY,
        /** 409 SESSION_NOT_ACTIVE. */
        NOT_ACTIVE,
        /** 응답표 밖 사유(U4b BR1.5: GUARDIAN_STOP · CONSENT_WITHDRAWN · CORE_ABANDONED 밖): 400 VALIDATION_FAILED. */
        UNSUPPORTED
    }

    /** 실행기가 명령을 처리하지 못하고 사라졌다(호출자는 DB 행으로 답한다). */
    public static final class SessionGone extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public SessionGone() {
            super("세션 실행기가 명령을 처리하기 전에 끝났다", null, false, false);
        }
    }

    /**
     * 닫고 남은 끝내기 명령의 약속을 {@link SessionGone}으로 끝낸다. 남은 종료 신호의 약속은 그냥 끝낸다(기다리는 정상 종료가 멈추지 않게,
     * J304). 나머지 메시지는 처리하지 않는다(BR6.11).
     *
     * @return 처리하지 않은 나머지 메시지(정리가 남은 연결 교체를 대기로 돌려보낸다, 리뷰 반영 4)
     */
    public java.util.List<Message> closeAndRelease() {
        close();
        java.util.List<Message> left = new java.util.ArrayList<>();
        for (Message message : clear()) {
            if (message instanceof EndRequested request) {
                request.result().completeExceptionally(new SessionGone());
            } else if (message instanceof ServerShutdown shutdown) {
                shutdown.handled().complete(null);
            } else if (message instanceof DeletionStop stop) {
                stop.stopped().complete(null);
            } else {
                left.add(message);
            }
        }
        return java.util.List.copyOf(left);
    }

    private final int generalCapacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    private final ArrayDeque<Message> safety = new ArrayDeque<>();
    private final ArrayDeque<Message> general = new ArrayDeque<>();
    private boolean closed;

    public SessionMailbox(int generalCapacity) {
        if (generalCapacity <= 0) {
            throw new IllegalArgumentException("generalCapacity는 0보다 커야 한다");
        }
        this.generalCapacity = generalCapacity;
    }

    /** 안전 편지함에 넣는다(기다리지 않는다). 닫혔으면 거짓. */
    public boolean putSafety(Message message) {
        Objects.requireNonNull(message, "message");
        lock.lock();
        try {
            if (closed) {
                return false;
            }
            safety.addLast(message);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** 일반 편지함에 넣는다(기기 쪽, 기다리지 않는다). 가득 찼거나 닫혔으면 거짓. */
    public boolean offerGeneral(Message message) {
        Objects.requireNonNull(message, "message");
        lock.lock();
        try {
            if (closed || general.size() >= generalCapacity) {
                return false;
            }
            general.addLast(message);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** 일반 편지함에 넣는다(공급자 쪽, 자리가 날 때까지 기다린다). 닫혔으면 거짓. */
    public boolean putGeneral(Message message) throws InterruptedException {
        Objects.requireNonNull(message, "message");
        lock.lock();
        try {
            while (!closed && general.size() >= generalCapacity) {
                notFull.await();
            }
            if (closed) {
                return false;
            }
            general.addLast(message);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** 다음 메시지(안전 먼저). 닫혔고 비었으면 null. */
    public Message take() throws InterruptedException {
        lock.lock();
        try {
            while (!closed && safety.isEmpty() && general.isEmpty()) {
                notEmpty.await();
            }
            return next();
        } finally {
            lock.unlock();
        }
    }

    /** 다음 메시지를 기다리지 않고 꺼낸다(시험의 단계 실행). 없으면 null. */
    public Message poll() {
        lock.lock();
        try {
            return next();
        } finally {
            lock.unlock();
        }
    }

    /** 최대 {@code timeoutMillis} 동안 기다려 꺼낸다. 없으면 null. */
    public Message poll(long timeoutMillis) throws InterruptedException {
        lock.lock();
        try {
            long nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (!closed && safety.isEmpty() && general.isEmpty() && nanos > 0) {
                nanos = notEmpty.awaitNanos(nanos);
            }
            return next();
        } finally {
            lock.unlock();
        }
    }

    /** 닫는다. 남은 메시지는 그대로 꺼낼 수 있고 새 메시지는 버린다. 기다리던 생산자 · 소비자를 깨운다. */
    public void close() {
        lock.lock();
        try {
            closed = true;
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** 남은 메시지를 모두 꺼내 돌려준다(종료 정리가 끝내기 명령의 약속을 채운다). */
    public java.util.List<Message> clear() {
        lock.lock();
        try {
            java.util.List<Message> drained = new java.util.ArrayList<>(safety);
            drained.addAll(general);
            safety.clear();
            general.clear();
            notFull.signalAll();
            return drained;
        } finally {
            lock.unlock();
        }
    }

    public boolean isClosed() {
        lock.lock();
        try {
            return closed;
        } finally {
            lock.unlock();
        }
    }

    /** 일반 편지함 깊이(게이지). */
    public int generalSize() {
        lock.lock();
        try {
            return general.size();
        } finally {
            lock.unlock();
        }
    }

    /** 안전 편지함 깊이. */
    public int safetySize() {
        lock.lock();
        try {
            return safety.size();
        } finally {
            lock.unlock();
        }
    }

    private Message next() {
        Message message = safety.pollFirst();
        if (message == null) {
            message = general.pollFirst();
            if (message != null) {
                notFull.signal();
            }
        }
        return message;
    }
}
