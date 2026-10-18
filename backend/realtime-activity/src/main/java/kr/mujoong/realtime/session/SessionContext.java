package kr.mujoong.realtime.session;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.WsPayload;
import kr.mujoong.contract.ws.message.SessionState;
import kr.mujoong.contract.ws.message.StopOutput;
import kr.mujoong.contract.ws.message.View;
import kr.mujoong.realtime.common.scaling.SingleInstanceAssumption;
import kr.mujoong.realtime.dialog.StopConfirmFlow;
import kr.mujoong.realtime.gate.ApprovedPlayback;
import kr.mujoong.realtime.gate.ClosingSequence;
import kr.mujoong.realtime.gate.OutputGeneration;
import kr.mujoong.realtime.gate.SegmentBuffer;
import kr.mujoong.realtime.gateway.DeviceLink;
import kr.mujoong.realtime.recording.AudioRecordings;
import kr.mujoong.realtime.voice.InputRelay;
import kr.mujoong.realtime.voice.ProviderConnector;
import kr.mujoong.realtime.voice.ProviderPort.ProviderSession;
import kr.mujoong.realtime.log.TranscriptWriter;

/**
 * 세션 하나의 실행기 구역들이 함께 읽고 쓰는 상태를 한곳에 모은 값 상자다.
 *
 * <p>데이터와 접근자, 그리고 이 상자의 상태만 쓰는 기기 명령 송신 · 사건 기록 보조를 가진다. 상태를 바꾸는 규칙은 구역(실행기와 그
 * 협력 객체)이 가진다. 세션 하나는 실행기 스레드
 * 하나가 처리하므로(편지함 모델) 새 잠금을 만들지 않는다. 다른 스레드(조회 · 연결 쪽)가 읽는 {@code status} {@code view}
 * {@code terminated}만 {@code volatile}이다.
 */
@SingleInstanceAssumption(si = {"SI-2", "SI-9"},
        reason = "세션 상태 · 출력 세대 · 편지함과 세션당 공급자 연결을 이 프로세스의 실행기가 이 상자에 쥐어 세션을 다른 인스턴스로 옮길 수 없다")
public final class SessionContext {

    /** 시작 확인이 끝나기 전 상태(PREPARING · DEVICE_ACK). */
    static final Set<SessionStatus> START_PHASE = EnumSet.of(SessionStatus.PREPARING, SessionStatus.DEVICE_ACK);

    // 세션 동안 바뀌지 않는 참조
    private final SessionExecutor.Plan plan;
    private final SessionExecutor.Services services;
    private final SessionMailbox mailbox;
    private final SessionTimers timers;
    private final InputRelay relay;
    private final SegmentBuffer segment = new SegmentBuffer();
    private final ApprovedPlayback playback = new ApprovedPlayback();
    private final ClosingSequence closing = new ClosingSequence();
    private final TranscriptWriter transcripts;
    /** 기기 프레임 세대. 공급자 출력 세대({@link #outputGen()})와 다른 번호다. */
    private final AtomicLong outputGeneration = new AtomicLong();
    /** 응답 id를 대신하는 공급자 출력 세대(실행기 스레드 전용). */
    private final OutputGeneration outputGen;
    /** 공급자로 가는 모든 서버 송신(지시)이 지나는 세션 직렬 큐. */
    private final ProviderSendQueue sendQueue;
    /** ACK를 기다리는 opId 명령(오래된 것부터 잊는다). */
    private final LinkedHashMap<UUID, WsPayload> pendingCommands = new LinkedHashMap<>();
    /** 기기가 STARTED를 알리고 아직 끝 표식이 없는 재생. */
    private final Set<UUID> devicePlaying = new HashSet<>();
    /** 종료 확인 질문 흐름. 확인 중이면 공급자 출력을 막는다. */
    private final StopConfirmFlow stopConfirm;

    // 다른 스레드가 읽는 상태
    private volatile SessionStatus status = SessionStatus.PREPARING;
    private volatile View view = View.STANDBY;
    private volatile boolean terminated;

    // 연결 · 기기 시작 확인
    private DeviceLink link;
    private UUID openGapId;
    private UUID startOpId;
    private Instant startAttemptAt;
    private boolean startSent;
    private boolean ackReceived;
    private boolean deviceReady;
    private boolean deviceUnavailable;

    // 재생 표식을 기다리는 안내
    private UUID joinPromptPlaybackId;
    private UUID stopConfirmPlaybackId;

    // 마무리
    private EndReason closingReason;
    private String closingDetail;
    private Instant closingAt;
    private Instant consentWithdrawnAt;
    private AudioRecordings.Session recording;

    // 공급자 연결
    private boolean providerStarted;
    private String providerSessionId;
    private ProviderSession providerSession;
    private ProviderConnector.Attempt providerAttempt;
    /** 실행기가 스스로 공급자를 닫았다(뒤따르는 끊김 알림은 공급자 장애가 아니다). */
    private boolean providerClosing;
    /** 실행기가 닫은 공급자의 닫힘 알림을 이미 처리했다(정산 창을 더 기다리지 않는다). */
    private boolean providerClosedSignal;

    // 종료 뒤 정산
    private boolean settling;
    private Instant sessionEndedAt;

    SessionContext(SessionExecutor.Plan plan, SessionExecutor.Services services, SessionMailbox mailbox) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.services = Objects.requireNonNull(services, "services");
        this.mailbox = Objects.requireNonNull(mailbox, "mailbox");
        this.timers = new SessionTimers(services.clocks(), mailbox::putSafety);
        this.outputGen = new OutputGeneration(services.settings().segmentQuiet(), services.settings().newSpeechMargin());
        this.sendQueue = new ProviderSendQueue(services.launcher(), plan.sessionId().toString(),
                failure -> services.events().appError(EventLog.Component.VOICE_RELAY, failure));
        this.relay = new InputRelay(plan.sessionId(), services.settings().inputQueueFrames(), services.requests(),
                services.clock(), services.metrics());
        this.transcripts = new TranscriptWriter(plan.sessionId(), 0, services.recorder(), services.latency());
        this.stopConfirm = new StopConfirmFlow(services.settings().lifecycle().stopConfirmWait());
    }

    SessionExecutor.Plan plan() {
        return plan;
    }

    SessionExecutor.Services services() {
        return services;
    }

    SessionMailbox mailbox() {
        return mailbox;
    }

    SessionTimers timers() {
        return timers;
    }

    InputRelay relay() {
        return relay;
    }

    SegmentBuffer segment() {
        return segment;
    }

    ApprovedPlayback playback() {
        return playback;
    }

    ClosingSequence closing() {
        return closing;
    }

    TranscriptWriter transcripts() {
        return transcripts;
    }

    AtomicLong outputGeneration() {
        return outputGeneration;
    }

    OutputGeneration outputGen() {
        return outputGen;
    }

    ProviderSendQueue sendQueue() {
        return sendQueue;
    }

    LinkedHashMap<UUID, WsPayload> pendingCommands() {
        return pendingCommands;
    }

    Set<UUID> devicePlaying() {
        return devicePlaying;
    }

    StopConfirmFlow stopConfirm() {
        return stopConfirm;
    }

    SessionStatus status() {
        return status;
    }

    void status(SessionStatus status) {
        this.status = status;
    }

    View view() {
        return view;
    }

    void view(View view) {
        this.view = view;
    }

    boolean terminated() {
        return terminated;
    }

    void terminated(boolean terminated) {
        this.terminated = terminated;
    }

    DeviceLink link() {
        return link;
    }

    void link(DeviceLink link) {
        this.link = link;
    }

    UUID openGapId() {
        return openGapId;
    }

    void openGapId(UUID openGapId) {
        this.openGapId = openGapId;
    }

    UUID startOpId() {
        return startOpId;
    }

    void startOpId(UUID startOpId) {
        this.startOpId = startOpId;
    }

    Instant startAttemptAt() {
        return startAttemptAt;
    }

    void startAttemptAt(Instant startAttemptAt) {
        this.startAttemptAt = startAttemptAt;
    }

    boolean startSent() {
        return startSent;
    }

    void startSent(boolean startSent) {
        this.startSent = startSent;
    }

    boolean ackReceived() {
        return ackReceived;
    }

    void ackReceived(boolean ackReceived) {
        this.ackReceived = ackReceived;
    }

    boolean deviceReady() {
        return deviceReady;
    }

    void deviceReady(boolean deviceReady) {
        this.deviceReady = deviceReady;
    }

    boolean deviceUnavailable() {
        return deviceUnavailable;
    }

    void deviceUnavailable(boolean deviceUnavailable) {
        this.deviceUnavailable = deviceUnavailable;
    }

    /** 지금 재생 중인 참여 안내의 playbackId(끝 표식이 창을 연다, 없으면 null). */
    UUID joinPromptPlaybackId() {
        return joinPromptPlaybackId;
    }

    void joinPromptPlaybackId(UUID joinPromptPlaybackId) {
        this.joinPromptPlaybackId = joinPromptPlaybackId;
    }

    /** 지금 재생 중인 종료 확인 질문의 playbackId(끝 표식이 창을 연다, 없으면 null). */
    UUID stopConfirmPlaybackId() {
        return stopConfirmPlaybackId;
    }

    void stopConfirmPlaybackId(UUID stopConfirmPlaybackId) {
        this.stopConfirmPlaybackId = stopConfirmPlaybackId;
    }

    /** CLOSING에 들어갈 때 쓴 마무리 사유. */
    EndReason closingReason() {
        return closingReason;
    }

    void closingReason(EndReason closingReason) {
        this.closingReason = closingReason;
    }

    /** CLOSING에 들어갈 때 쓴 마무리 세부(시한이 지났을 때의 end_detail). */
    String closingDetail() {
        return closingDetail;
    }

    void closingDetail(String closingDetail) {
        this.closingDetail = closingDetail;
    }

    Instant closingAt() {
        return closingAt;
    }

    void closingAt(Instant closingAt) {
        this.closingAt = closingAt;
    }

    /** 동의 철회 끝내기를 받은 시각(이후 아이 전사 · 원음을 저장하지 않는다). 받지 않았으면 null. */
    Instant consentWithdrawnAt() {
        return consentWithdrawnAt;
    }

    void consentWithdrawnAt(Instant consentWithdrawnAt) {
        this.consentWithdrawnAt = consentWithdrawnAt;
    }

    /** 동의된 세션의 원음 기록(동의가 없으면 null). */
    AudioRecordings.Session recording() {
        return recording;
    }

    void recording(AudioRecordings.Session recording) {
        this.recording = recording;
    }

    boolean providerStarted() {
        return providerStarted;
    }

    void providerStarted(boolean providerStarted) {
        this.providerStarted = providerStarted;
    }

    String providerSessionId() {
        return providerSessionId;
    }

    void providerSessionId(String providerSessionId) {
        this.providerSessionId = providerSessionId;
    }

    ProviderSession providerSession() {
        return providerSession;
    }

    void providerSession(ProviderSession providerSession) {
        this.providerSession = providerSession;
    }

    ProviderConnector.Attempt providerAttempt() {
        return providerAttempt;
    }

    void providerAttempt(ProviderConnector.Attempt providerAttempt) {
        this.providerAttempt = providerAttempt;
    }

    boolean providerClosing() {
        return providerClosing;
    }

    void providerClosing(boolean providerClosing) {
        this.providerClosing = providerClosing;
    }

    boolean providerClosedSignal() {
        return providerClosedSignal;
    }

    void providerClosedSignal(boolean providerClosedSignal) {
        this.providerClosedSignal = providerClosedSignal;
    }

    /** 종료 뒤 정산 창 중인가(전이 0회, 늦은 아이 전사만 받는다). */
    boolean settling() {
        return settling;
    }

    void settling(boolean settling) {
        this.settling = settling;
    }

    /** 정산 기준 ended_at. */
    Instant sessionEndedAt() {
        return sessionEndedAt;
    }

    void sessionEndedAt(Instant sessionEndedAt) {
        this.sessionEndedAt = sessionEndedAt;
    }

    // ---- 기기 연결 · 명령 송신 · 사건 기록(이 상자의 상태만 쓰는 보조)

    /** ACK를 기다리는 opId 명령의 상한(U3-J7). 넘치면 가장 오래된 것부터 잊는다. */
    static final int MAX_PENDING_COMMANDS = 16;

    boolean fromBoundConnection(UUID connectionId) {
        DeviceLink current = link;
        return current != null && current.connectionId().equals(connectionId);
    }

    void markUnresponsive() {
        DeviceLink current = link;
        if (current != null) {
            services.deviceStatus().markUnresponsive(plan.deviceId(), current.connectionId());
        }
    }

    boolean sendControl(WsPayload payload) {
        if (payload instanceof SessionState state) {
            // U3-J23: 연결이 없어도 표시 상태는 기억한다(다음 HELLO_OK.session.view)
            view(state.view());
        }
        DeviceLink current = link;
        if (current == null || !current.isOpen()) {
            return false;
        }
        if (payload instanceof StopOutput) {
            bumpGeneration();
        }
        UUID opId = null;
        if (payload.messageType().isOpIdRequired()) {
            opId = UUID.randomUUID();
            remember(opId, payload);
        }
        return current.sendControl(payload, opId, plan.sessionId());
    }

    /** ACK를 기다리는 명령으로 둔다(U3-J7, 상한을 넘으면 가장 오래된 것부터 잊는다). */
    void remember(UUID opId, WsPayload payload) {
        if (pendingCommands.size() >= MAX_PENDING_COMMANDS) {
            UUID eldest = pendingCommands.keySet().iterator().next();
            pendingCommands.remove(eldest);
        }
        pendingCommands.put(opId, payload);
    }

    /** U3 세션 이벤트 한 줄(payload는 식별자 · 숫자 · 불리언뿐, 전사 · 원음 없음). */
    void putEvent(String type, String payloadJson, Instant at) {
        services.recorder().putEvent(new SessionEvent(UUID.randomUUID(), plan.sessionId(), type, null, payloadJson, at));
    }

    void bumpGeneration() {
        outputGeneration.incrementAndGet();
        DeviceLink current = link;
        if (current != null) {
            current.clearAudio();
        }
    }

    void annotateQuietly(SessionStatus expected, SessionChange change) {
        try {
            services.transitions().annotate(plan.sessionId(), expected, change);
        } catch (RuntimeException e) {
            services.events().appError(EventLog.Component.SESSION_LIFECYCLE, e);
        }
    }

    /**
     * 이 세션이 묶은 연결이면 묶음을 푼다. 같은 기기의 새 세션이 이미 그 연결을 묶었으면 건드리지 않는다 — 풀면 새 세션의 입력 프레임 경로가
     * 끊긴다(2차 리뷰 5 · 9 조사 중 찾음). 확인과 풀기는 연결의 비교 후 풀기 한 번이다(3차 리뷰 4: 확인한 뒤 풀면 그 사이 새 세션이 묶은 것을
     * 지운다).
     */
    void unbind(DeviceLink target) {
        if (target != null) {
            target.unbindSessionIf(plan.sessionId());
        }
    }

    /** 정리 단계 하나: 예외는 앱 오류 한 줄로 남기고 다음 단계로 간다. */
    void quietly(Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            services.events().appError(EventLog.Component.SESSION_LIFECYCLE, e);
        }
    }
}
