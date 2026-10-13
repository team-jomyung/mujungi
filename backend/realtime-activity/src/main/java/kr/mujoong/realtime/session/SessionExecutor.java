package kr.mujoong.realtime.session;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.EndRequestReason;
import kr.mujoong.contract.status.FailureCode;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.ws.WsPayload;
import kr.mujoong.contract.ws.message.CloseTransport;
import kr.mujoong.contract.ws.message.DeviceState;
import kr.mujoong.contract.ws.message.OutputBegin;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.SessionState;
import kr.mujoong.contract.ws.message.StartSession;
import kr.mujoong.contract.ws.message.StopOutput;
import kr.mujoong.contract.ws.message.View;
import kr.mujoong.realtime.common.collect.BoundedCollections;
import kr.mujoong.realtime.common.scaling.SingleInstanceAssumption;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.dialog.DialogInstructions;
import kr.mujoong.realtime.dialog.StopIntentJudge;
import kr.mujoong.realtime.gate.ApprovedPlayback;
import kr.mujoong.realtime.gate.ClosingSequence;
import kr.mujoong.realtime.gate.GateDecision;
import kr.mujoong.realtime.gate.OutputGate;
import kr.mujoong.realtime.gate.SegmentBuffer;
import kr.mujoong.realtime.gate.SeedDictionary;
import kr.mujoong.realtime.gateway.DeviceLink;
import kr.mujoong.realtime.gateway.DeviceStatusPort;
import kr.mujoong.realtime.gateway.OutputBundle;
import kr.mujoong.realtime.log.AsyncRecorder;
import kr.mujoong.realtime.log.LatencyRecorder;
import kr.mujoong.realtime.log.SessionGapRepository;
import kr.mujoong.realtime.log.TranscriptWriter;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.recording.AudioRecordings;
import kr.mujoong.realtime.runtime.TaskLauncher;
import kr.mujoong.realtime.runtime.TimerClocks;
import kr.mujoong.realtime.session.SessionMailbox.Bootstrap;
import kr.mujoong.realtime.session.SessionMailbox.ConnectionLost;
import kr.mujoong.realtime.session.SessionMailbox.DeviceAck;
import kr.mujoong.realtime.session.SessionMailbox.DeviceReadyReceived;
import kr.mujoong.realtime.session.SessionMailbox.DeviceStateReceived;
import kr.mujoong.realtime.session.SessionMailbox.EndOutcome;
import kr.mujoong.realtime.session.SessionMailbox.EndRequested;
import kr.mujoong.realtime.session.SessionMailbox.LinkReplaced;
import kr.mujoong.realtime.session.SessionMailbox.Message;
import kr.mujoong.realtime.session.SessionMailbox.PlaybackMarkReceived;
import kr.mujoong.realtime.session.SessionMailbox.ProviderConnected;
import kr.mujoong.realtime.session.SessionMailbox.ProviderEventReceived;
import kr.mujoong.realtime.session.SessionMailbox.ProviderFailed;
import kr.mujoong.realtime.session.SessionMailbox.ServerShutdown;
import kr.mujoong.realtime.session.SessionMailbox.TimerFired;
import kr.mujoong.realtime.session.SessionMailbox.VoiceClosed;
import kr.mujoong.realtime.session.SessionTimers.Kind;
import kr.mujoong.realtime.session.TransitionTable.Trigger;
import kr.mujoong.realtime.voice.InputRelay;
import kr.mujoong.realtime.voice.ProviderConnector;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import kr.mujoong.realtime.voice.ProviderPort.ProviderSession;
import kr.mujoong.realtime.voice.ProviderPort.ProviderUnavailableException;
import kr.mujoong.realtime.voice.ProviderRequestFactory;
import kr.mujoong.realtime.voice.ProviderResponseId;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * 세션 하나의 직렬 실행기 (U1 3.5 Step 23 · PD-1 · PD-3 · BR6.6 ~ 6.12 · BR7.1 ~ 7.5 · SD-17 · RD-1 · RD-3 · R3-1 · R3-6 · R3-8 ·
 * R3-19 · J54 · J126).
 *
 * <p>세션 상태 · 시계 · 구간 버퍼 · 게이트 판정은 이 실행기 한 스레드만 바꾼다(세션 안 잠금 없음). 반복마다 안전 편지함을 먼저 비운다. 전이는
 * {@link SessionTransitions}의 짧은 트랜잭션이고 조건부 UPDATE의 승자만 부수 효과(기기 명령 · 시계)를 낸다. 시계 만기는 세대가 맞을 때만,
 * 경과를 다시 재서(지연 재무장) 판정한다. 전이가 DB 장애로 실패하면 그 메시지를 {@code mujoong.session.timer-tick} 뒤 다시 처리하고,
 * 다시 처리에서 커밋은 됐는데 응답을 잃은 전이를 DB에서 받아들이면 그 상태의 시한 · 부수 효과를 한 번 건다(J291). 그 밖의 예외가 반복
 * 최상위까지 오면 APP_ERROR 한 줄을 남기고 FAILED(SERVER_RESTART)를 시도해 이기면 BR6.8 기기 명령을 낸 뒤 정리한다(J126 · J290). 출력
 * 세대 번호는 STOP_OUTPUT · CLOSING 진입 · 종료 전이 · Core 취소 · 연결 교체 · 끊김마다 오르고, 봉인한 구간과 송신 프레임마다
 * 비교한다(SD-17). 실행기 스레드는 공급자 쪽 생산자로서 자기 편지함을 기다리지 않는다(J289).
 *
 * <p>U3(docs/units/u3/design.md 4 · 5절): 같은 기기의 새 연결이 HELLO_OK를 받으면 {@link LinkReplaced}로 세션을 다시 묶고(끊김 구간 닫기 ·
 * SESSION_STATE 재송신 · ACK를 못 받은 opId 명령 재전달, 이어가기가 아니면 START_SESSION부터, U3-J4 · J5 · J7), 묶인 연결이 끊기면 끊김 구간을
 * 연다(U3-J18). T19 표시 상태 {@link #view()}를 기억해 HELLO_OK.session에 싣고(U3-J23), DEVICE_STATE의 마이크 끊김 · 플레이어 오류와
 * PLAYBACK_MARK(STOPPED · LOCAL_VOICE_ACTIVITY) 끼어들기를 세션 이벤트로 남긴다(U3-J9 · J10). 세션 전이 표는 U1 그대로다(U3-J1).
 */
@SingleInstanceAssumption(si = {"SI-2", "SI-9"},
        reason = "세션 상태 · 편지함 · 출력 세대와 세션당 공급자 연결을 이 프로세스의 실행기 하나가 쥐어 세션을 다른 인스턴스로 옮길 수 없다")
public final class SessionExecutor {

    /** 세션 공통 설정(RealtimeProperties에서 읽는다). */
    public record Settings(
            Duration deviceAckTimeout,
            Duration closingConfirm,
            Duration disconnect,
            Duration segmentQuiet,
            Duration playbackExpiry,
            Duration timerTick,
            int inputQueueFrames,
            int mailboxCapacity,
            int uncertainBelowPercent) {

        public Settings {
            Objects.requireNonNull(deviceAckTimeout, "deviceAckTimeout");
            Objects.requireNonNull(closingConfirm, "closingConfirm");
            Objects.requireNonNull(disconnect, "disconnect");
            Objects.requireNonNull(segmentQuiet, "segmentQuiet");
            Objects.requireNonNull(playbackExpiry, "playbackExpiry");
            Objects.requireNonNull(timerTick, "timerTick");
            if (inputQueueFrames <= 0 || mailboxCapacity <= 0) {
                throw new IllegalArgumentException("대기열 상한은 0보다 커야 한다");
            }
            if (uncertainBelowPercent < 0 || uncertainBelowPercent > MAX_PERCENT) {
                throw new IllegalArgumentException("불확실 기준은 0 ~ 100이다");
            }
        }

        public static Settings from(RealtimeProperties properties) {
            return new Settings(properties.timing().session().deviceAckTimeout(),
                    properties.timing().session().closingConfirm(), properties.timing().device().disconnect(),
                    properties.gate().segmentQuiet(), properties.timing().device().outputQueueMax(),
                    properties.session().timerTick(), properties.limits().sessionInputQueueFrames(),
                    properties.limits().sessionControlQueue(), properties.transcript().uncertainBelowPercent());
        }
    }

    /** 세션들이 함께 쓰는 장치. */
    public record Services(
            SessionTransitions transitions,
            Function<UUID, Optional<? extends DeviceLink>> deviceLinks,
            DeviceStatusPort deviceStatus,
            ProviderConnector connector,
            ProviderRequestFactory requests,
            OutputGate gate,
            AsyncRecorder recorder,
            LatencyRecorder latency,
            TimerClocks clocks,
            TaskLauncher launcher,
            EventLogger events,
            RealtimeMetrics metrics,
            MujoongClock clock,
            Settings settings,
            SessionGapPort gaps,
            AudioRecordings recordings) {

        public Services {
            Objects.requireNonNull(transitions, "transitions");
            Objects.requireNonNull(deviceLinks, "deviceLinks");
            Objects.requireNonNull(deviceStatus, "deviceStatus");
            Objects.requireNonNull(connector, "connector");
            Objects.requireNonNull(requests, "requests");
            Objects.requireNonNull(gate, "gate");
            Objects.requireNonNull(recorder, "recorder");
            Objects.requireNonNull(latency, "latency");
            Objects.requireNonNull(clocks, "clocks");
            Objects.requireNonNull(launcher, "launcher");
            Objects.requireNonNull(events, "events");
            Objects.requireNonNull(metrics, "metrics");
            Objects.requireNonNull(clock, "clock");
            Objects.requireNonNull(settings, "settings");
            Objects.requireNonNull(gaps, "gaps");
            Objects.requireNonNull(recordings, "recordings");
        }
    }

    /** 고정한 승인 문구 하나(contentCode · 버전). */
    public record ApprovedItem(String contentCode, UUID contentVersionId) {
    }

    /**
     * 시작 명령이 고정한 세션 패키지 중 실행기가 쓰는 부분. U3: 원음 기록 동의(consentSnapshot.rawAudio, U3-R9)와 세션의 종료 표현 스냅숏
     * (종료 의도 판정, U3-R12).
     */
    public record Plan(
            UUID sessionId,
            UUID deviceId,
            String traceId,
            ChildContext childContext,
            List<StartSession.ApprovedAudio> approvedAudio,
            ApprovedItem closing,
            ApprovedItem waitingFiller,
            boolean rawAudioConsent,
            List<String> stopExpressions) {

        public Plan {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(deviceId, "deviceId");
            Objects.requireNonNull(childContext, "childContext");
            approvedAudio = List.copyOf(approvedAudio);
            stopExpressions = stopExpressions == null ? List.of() : List.copyOf(stopExpressions);
        }

        @Override
        public String toString() {
            return "Plan[sessionId=" + sessionId + "]";
        }
    }

    private static final java.util.Set<SessionStatus> START_PHASE =
            EnumSet.of(SessionStatus.PREPARING, SessionStatus.DEVICE_ACK);

    /** ACK를 기다리는 opId 명령의 상한(U3-J7). 넘치면 가장 오래된 것부터 잊는다. */
    static final int MAX_PENDING_COMMANDS = 16;
    /** U3 세션 이벤트 종류(payload에 전사 · 원음 없음). */
    static final String EVENT_LINK_REPLACED = "DEVICE_LINK_REPLACED";
    static final String EVENT_BARGE_IN = "BARGE_IN";
    static final String EVENT_MIC_LOST = "DEVICE_MIC_LOST";
    static final String EVENT_MIC_OK = "DEVICE_MIC_OK";
    static final String EVENT_PLAYER_ERROR = "DEVICE_PLAYER_ERROR";
    /** U3-R12: 아이의 명확한 종료 의도(payload는 근거만, 원문 없음). CLOSING(CHILD_STOP) 전이는 U4. */
    static final String EVENT_CHILD_STOP_INTENT = "CHILD_STOP_INTENT";
    /** U3-R12: 종료일 수 있으나 불명확(확인 질문 재생은 U4). */
    static final String EVENT_CHILD_STOP_UNCLEAR = "CHILD_STOP_UNCLEAR";
    /** 리뷰 반영 3: 종료 의도 뒤 아이가 종료가 아닌 말로 대화를 이어 가 출력 막힘을 풀었다(오판 복구). */
    static final String EVENT_CHILD_STOP_LIFTED = "CHILD_STOP_LIFTED";
    /** U3-J13: ACTIVE 중 공급자 끊김의 end_detail. */
    static final String END_DETAIL_PROVIDER_LOST = "PROVIDER_LOST";
    /** 취소 · 교정 · 재생 위치를 기억하는 응답 · 재생 수의 상한(오래된 것부터 잊는다). */
    static final int MAX_TRACKED = 32;
    private static final int MAX_PERCENT = 100;

    private final Plan plan;
    private final Services services;
    private final SessionMailbox mailbox;
    private final SessionTimers timers;
    private final InputRelay relay;
    private final SegmentBuffer segment = new SegmentBuffer();
    private final ApprovedPlayback playback = new ApprovedPlayback();
    private final ClosingSequence closing = new ClosingSequence();
    private final TranscriptWriter transcripts;
    private final AtomicLong outputGeneration = new AtomicLong();
    private final List<Object> pendingRetry = new ArrayList<>();
    private final LinkedHashMap<UUID, WsPayload> pendingCommands = new LinkedHashMap<>();
    private final Runnable onTerminated;

    private volatile SessionStatus status = SessionStatus.PREPARING;
    private volatile View view = View.STANDBY;
    private volatile boolean terminated;
    private UUID openGapId;
    private DeviceState.Mic lastMic;
    private DeviceState.Player lastPlayer;
    private UUID progressPlaybackId;
    private Long progressPlayedToMs;
    /** U3-R5: 취소한 응답(늦게 오는 조각을 받지 않는다). */
    private final Set<String> cancelledResponses = BoundedCollections.set(MAX_TRACKED);
    /** U3-R7: 교정을 이미 보낸 응답(응답당 한 번). */
    private final Set<String> correctedResponses = BoundedCollections.set(MAX_TRACKED);
    /** 기기로 보낸 출력 묶음의 응답(끼어들기 때 그 응답을 취소한다). */
    private final Map<UUID, String> playbackResponses = BoundedCollections.map(MAX_TRACKED);
    /** 기기가 STARTED를 알리고 아직 끝 표식이 없는 재생(생각 중 · 듣는 중 표시와 종료 의도 판정의 '재생 중'). */
    private final Set<UUID> devicePlaying = new HashSet<>();
    /** 종료 의도 판정기(세션 스냅숏의 종료 표현, U3-R12). */
    private final StopIntentJudge stopJudge;
    /** 명확한 종료 의도를 받았다 — 이후 공급자 출력은 받지 않는다(U3-J24, CLOSING(CHILD_STOP)은 U4). */
    private boolean stopIntent;
    /** 실행기가 스스로 공급자를 닫았다(뒤따르는 끊김 알림은 공급자 장애가 아니다). */
    private boolean providerClosing;
    /** 동의된 세션의 원음 기록(동의가 없으면 null, U3-R9). */
    private AudioRecordings.Session recording;
    /** 지금 편지함 메시지를 처리하는 스레드(실행 스레드 또는 시험의 step 호출 스레드). 공급자 리스너가 이 스레드면 기다리지 않는다(J289). */
    private volatile Thread processing;
    private DeviceLink link;
    private UUID startOpId;
    private Instant startAttemptAt;
    private Instant closingAt;
    private boolean startSent;
    private boolean ackReceived;
    private boolean deviceReady;
    private boolean deviceUnavailable;
    private boolean providerStarted;
    private String providerSessionId;
    private ProviderSession providerSession;
    private ProviderConnector.Attempt providerAttempt;
    private Thread thread;
    /** U4b BR1.3: CLOSING에 들어갈 때 쓴 마무리 사유와 세부(30초 시한의 end_detail · STOP_OUTPUT · CLOSE_TRANSPORT 사유). */
    private EndReason closingReason;
    private String closingDetail;
    /** U4b BR1.7: 동의 철회 끝내기를 받은 시각(이후 아이 전사 · 원음을 저장하지 않는다). 받지 않았으면 null. */
    private Instant consentWithdrawnAt;
    /** U4b BR1.7: 동의 철회 뒤 버린 아이 확정 전사 수(사건 payload는 건수만, BR12.1). */
    private int droppedAfterConsent;

    /**
     * @param onTerminated 종료 정리 뒤 레지스트리에서 지우는 함수
     */
    public SessionExecutor(Plan plan, Services services, SessionMailbox mailbox, Runnable onTerminated) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.services = Objects.requireNonNull(services, "services");
        this.mailbox = Objects.requireNonNull(mailbox, "mailbox");
        this.onTerminated = Objects.requireNonNull(onTerminated, "onTerminated");
        this.timers = new SessionTimers(services.clocks(), mailbox::putSafety);
        this.relay = new InputRelay(plan.sessionId(), services.settings().inputQueueFrames(), services.requests(),
                services.clock(), services.metrics());
        this.transcripts = new TranscriptWriter(plan.sessionId(), 0, services.recorder(), services.latency());
        this.stopJudge = new StopIntentJudge(plan.stopExpressions());
    }

    // ------------------------------------------------------------------ 실행

    /** 첫 메시지(Bootstrap)를 안전 편지함 맨 앞 차례로 넣고 실행 스레드를 띄운다. */
    public void start() {
        mailbox.putSafety(new Bootstrap());
        thread = services.launcher().launch("session-" + plan.sessionId(), this::run);
    }

    /** 첫 메시지만 넣는다(시험은 {@link #step()}으로 하나씩 처리한다). */
    public void bootstrapOnly() {
        mailbox.putSafety(new Bootstrap());
    }

    private void run() {
        processing = Thread.currentThread();
        try {
            while (!terminated) {
                Message message = mailbox.take();
                if (message == null) {
                    break;
                }
                process(message);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!terminated) {
                teardown();
            }
        } finally {
            processing = null;
        }
    }

    /** 편지함에서 하나를 꺼내 처리한다(시험). 처리했으면 참. */
    public boolean step() {
        Message message = mailbox.poll();
        if (message == null) {
            return false;
        }
        process(message);
        return true;
    }

    /** 편지함이 빌 때까지 처리한다(시험). 처리한 개수. */
    public int drain() {
        int count = 0;
        while (!terminated && step()) {
            count++;
        }
        return count;
    }

    /** 한 메시지를 처리한다. 최상위 예외 규약(R3-6 · J126)이 여기 있다. */
    void process(Message message) {
        if (terminated) {
            return;
        }
        Thread previous = processing;
        processing = Thread.currentThread();
        try {
            handle(message);
        } catch (DataAccessException | TransactionException e) {
            services.events().appError(EventLog.Component.SESSION_LIFECYCLE, e);
            scheduleRetry(message);
        } catch (RuntimeException e) {
            services.events().appError(EventLog.Component.SESSION_LIFECYCLE, e);
            failFromTopLevel();
        } finally {
            processing = previous;
        }
    }

    // ------------------------------------------------------------------ 외부 입구

    /** 세션 편지함. */
    public SessionMailbox mailbox() {
        return mailbox;
    }

    public UUID sessionId() {
        return plan.sessionId();
    }

    /** 이 세션의 기기(인계가 살아 있는 실행기를 찾는 열쇠, U3-J4). */
    public UUID deviceId() {
        return plan.deviceId();
    }

    /** 지금 상태(실행기 사본). */
    public SessionStatus status() {
        return status;
    }

    /** 마지막으로 기기에 알린 T19 표시 상태(HELLO_OK.session.view, U3-J23). SESSION_STATE를 보낼 때마다 바뀐다. */
    public View view() {
        return view;
    }

    /** ACK를 기다리는 opId 명령 수(시험 · 진단). */
    int pendingCommandCount() {
        return pendingCommands.size();
    }

    /** 열린 끊김 구간이 있는가(시험 · 진단). */
    boolean hasOpenGap() {
        return openGapId != null;
    }

    /** 종료 정리를 마쳤는가. */
    public boolean isTerminated() {
        return terminated;
    }

    /** 실행 스레드(종료 대기 · 진단). 시험 장치로 띄웠으면 null. */
    public Thread thread() {
        return thread;
    }

    /** 지금 출력 세대(SD-17). */
    public long outputGeneration() {
        return outputGeneration.get();
    }

    /** 묶인 기기 연결 id(없으면 null). */
    public UUID boundConnectionId() {
        DeviceLink current = link;
        return current == null ? null : current.connectionId();
    }

    /**
     * 0x01 입력 프레임(WSS 수신 핸들러가 부른다, 기다리지 않는다). 묶인 연결의 프레임만 받고 수락 구간 밖이면 버린다(R3-8).
     *
     * @return 대기열에 넣었으면 참
     */
    public boolean offerAudio(UUID connectionId, long sequence, byte[] pcm, long receivedNanos) {
        DeviceLink current = link;
        if (terminated || current == null || !current.connectionId().equals(connectionId)) {
            return false;
        }
        // 리뷰 반영 1: 순번은 연결마다 다시 센다(새 페이지는 1부터 보낸다)
        return relay.offer(connectionId, sequence, pcm, receivedNanos);
    }

    /** 입력 중계(시험 · 진단). */
    public InputRelay relay() {
        return relay;
    }

    /** 시계(시험). */
    SessionTimers timers() {
        return timers;
    }

    // ------------------------------------------------------------------ 메시지 처리

    private void handle(Message message) {
        switch (message) {
            case Bootstrap ignored -> onBootstrap();
            case TimerFired fired -> onTimer(fired);
            case EndRequested request -> onEndRequested(request);
            case ConnectionLost lost -> onConnectionLost(lost);
            case ServerShutdown shutdown -> {
                // 앱 종료 T3: 공급자 연결을 닫고 기록기를 비운다. 세션 행은 다음 기동 S3a가 닫는다(BR6.13).
                // 끝나면(실패해도) 정상 종료에 알린다 — T4(폴러 정지)는 공급자 닫기 뒤에 시작한다(RD-13 순서, J304)
                try {
                    closeProvider();
                    services.recorder().finish(plan.sessionId());
                } finally {
                    shutdown.handled().complete(null);
                }
            }
            case DeviceAck ack -> onDeviceAck(ack);
            case DeviceReadyReceived ready -> onDeviceReady(ready);
            case LinkReplaced replaced -> onLinkReplaced(replaced);
            case DeviceStateReceived state -> onDeviceState(state);
            case PlaybackMarkReceived mark -> onPlaybackMark(mark);
            case VoiceClosed closed -> onVoiceClosed(closed);
            case ProviderConnected connected -> onProviderConnected(connected);
            case ProviderFailed failed -> onProviderFailed(failed);
            case ProviderEventReceived received -> onProviderEvent(received);
        }
    }

    /** R3-1: START_SESSION을 먼저 보내고 그 시도 시각에 ②를 무장한 뒤 공급자 연결 스레드를 띄운다. */
    private void onBootstrap() {
        services.latency().register(plan.sessionId(), plan.traceId(),
                LatencyRecorder.inputSide(relay::drainRelayInP95Millis, relay::lastVoiceFrameAt));
        link = services.deviceLinks().apply(plan.deviceId()).filter(DeviceLink::isOpen).orElse(null);
        startOpId = UUID.randomUUID();
        startAttemptAt = services.clock().now();
        boolean sent = false;
        if (link != null) {
            link.bindSession(plan.sessionId());
            StartSession startSession = new StartSession(plan.approvedAudio());
            sent = link.sendControl(startSession, startOpId, plan.sessionId());
            if (sent) {
                remember(startOpId, startSession);
            }
        }
        if (!sent) {
            // BR6.6 ⑤: 같은 처리 안에서 PREPARING → FAILED(DEVICE_NO_ACK) + UNRESPONSIVE
            Optional<ActivitySessionRow> failed = transition(SessionStatus.PREPARING, Trigger.START_SEND_FAILED, null);
            if (failed.isPresent()) {
                markUnresponsive();
                teardown();
            } else {
                syncFromDb();
            }
            return;
        }
        startSent = true;
        timers.arm(Kind.DEVICE_ACK, services.settings().deviceAckTimeout());
        providerAttempt = services.connector().connect(plan.sessionId(), plan.childContext(), event -> {
            putFromProvider(new ProviderEventReceived(event, services.clock().now(), services.clock().monotonicNanos()));
        }, new ProviderConnector.Callback() {
            @Override
            public void connected(ProviderSession session) {
                if (!putFromProvider(new ProviderConnected(session))) {
                    session.close();
                }
            }

            @Override
            public void failed(kr.mujoong.realtime.voice.ProviderPort.ProviderUnavailableException.Reason reason) {
                putFromProvider(new ProviderFailed(reason));
            }
        });
    }

    private boolean putFromProvider(Message message) {
        if (Thread.currentThread() == processing) {
            // F2 · J289: 공급자 닫기(closeProvider · 정리)는 리스너의 Closed를 닫기를 부른 스레드에서 바로 부른다. 그 스레드가 이
            // 편지함을 비우는 실행기 자신이면 자리가 날 때까지 기다리지 않는다 — 비울 쪽이 없어 교착한다. 가득 찼으면 버린다(스스로 닫은
            // 공급자의 끊김 알림이라 잃어도 상태가 어긋나지 않는다)
            return mailbox.offerGeneral(message);
        }
        try {
            return mailbox.putGeneral(message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void onDeviceAck(DeviceAck ack) {
        if (!fromBoundConnection(ack.connectionId())) {
            return;
        }
        // U3-J7: ACK가 온 명령은 연결이 바뀌어도 다시 보내지 않는다
        if (ack.ackOf() != null) {
            pendingCommands.remove(ack.ackOf());
        }
        if (!Objects.equals(ack.ackOf(), startOpId) || status != SessionStatus.PREPARING) {
            return;
        }
        Optional<ActivitySessionRow> row = transition(SessionStatus.PREPARING, Trigger.DEVICE_ACK, null);
        if (row.isEmpty()) {
            syncFromDb();
            return;
        }
        ackReceived = true;
        if (deviceUnavailable) {
            failDeviceUnavailable();
            return;
        }
        tryActivate();
    }

    private void onDeviceReady(DeviceReadyReceived ready) {
        if (!fromBoundConnection(ready.connectionId()) || !START_PHASE.contains(status)) {
            return;
        }
        if (!ready.ready().isReady()) {
            deviceUnavailable = true;
            if (status == SessionStatus.DEVICE_ACK) {
                failDeviceUnavailable();
            }
            return;
        }
        deviceReady = true;
        tryActivate();
    }

    private void failDeviceUnavailable() {
        // BR6.8: DEVICE_READY가 사용 불가를 알리면 20초를 기다리지 않고 즉시 FAILED(DEVICE_NO_ACK), UNRESPONSIVE는 쓰지 않는다(J55)
        Optional<ActivitySessionRow> row = transition(SessionStatus.DEVICE_ACK, Trigger.DEVICE_UNAVAILABLE, null);
        if (row.isPresent()) {
            afterFailed();
        } else {
            syncFromDb();
        }
    }

    private void onProviderConnected(ProviderConnected connected) {
        if (status.isTerminal() || (providerAttempt != null && providerAttempt.isCancelled())) {
            connected.session().close();
            return;
        }
        providerSession = connected.session();
        relay.attach(providerSession);
    }

    private void onProviderFailed(ProviderFailed failed) {
        if (!START_PHASE.contains(status)) {
            return;
        }
        multiOriginFail(Trigger.PROVIDER_FAILED, FailureCode.PROVIDER_UNAVAILABLE, SessionTransitions.Origin.REALTIME);
    }

    private void tryActivate() {
        if (status != SessionStatus.DEVICE_ACK || !deviceReady || !providerStarted) {
            return;
        }
        Instant now = services.clock().now();
        SessionChange change = SessionChange.none().withStartedAt(now);
        if (providerSessionId != null && !providerSessionId.isBlank()
                && providerSessionId.length() <= SessionChange.MAX_PROVIDER_SESSION_ID_LENGTH) {
            change = change.withProviderSessionId(providerSessionId);
        }
        Optional<ActivitySessionRow> row = services.transitions().transition(plan.sessionId(), SessionStatus.DEVICE_ACK,
                SessionStatus.ACTIVE, change, SessionTransitions.Origin.REALTIME, plan.traceId());
        if (row.isEmpty()) {
            syncFromDb();
            return;
        }
        status = SessionStatus.ACTIVE;
        enterActive();
    }

    /** ACTIVE 진입의 승자 몫(BR6.8 · BR7.1 · BR6.12 ④): ② 시계 해제 · 입력 중계 시작 · LISTENING · 끊김 시계. */
    private void enterActive() {
        timers.cancel(Kind.DEVICE_ACK);
        relay.attach(providerSession);
        // U3-R9: 동의된 세션만 원음 기록을 연다(동의가 없으면 한 바이트도 디스크에 가지 않는다)
        if (plan.rawAudioConsent() && recording == null) {
            recording = services.recordings().open(plan.sessionId());
            relay.tap(recording::child);
        }
        relay.setAccepting(true);
        relay.start(services.launcher());
        sendControl(new SessionState(View.LISTENING));
        armDisconnect();
    }

    private void onProviderEvent(ProviderEventReceived received) {
        if (status.isTerminal()) {
            return;
        }
        ProviderEvent event = received.event();
        Instant at = received.receivedAt();
        switch (event) {
            case ProviderEvent.Started started -> {
                if (START_PHASE.contains(status)) {
                    providerStarted = true;
                    providerSessionId = started.providerSessionId();
                    tryActivate();
                }
            }
            case ProviderEvent.Error ignored -> providerError();
            case ProviderEvent.Closed ignored -> providerLost();
            case ProviderEvent.SpeechStopped ignored -> {
                services.latency().speechEnd(plan.sessionId(), LatencyRecorder.SpeechEndSource.PROVIDER_EVENT, at);
                thinking();
            }
            case ProviderEvent.InputTranscriptDelta delta -> {
                if (consentWithdrawnAt != null) {
                    // BR1.7: 동의 철회 뒤 도착한 아이 전사는 저장하지 않는다(확정 때 센다)
                    return;
                }
                if (inRelayRange()) {
                    services.latency().inputActivity(plan.sessionId());
                    // BR7.3: 아이 입력 재개는 열린 구간을 확정한다(대화가 넘어갔으니 완결 요청 교정은 하지 않는다)
                    if (segment.isOpen()) {
                        sealAndGate(true);
                    }
                    transcripts.childDelta(delta.itemId(), delta.delta(), at);
                }
            }
            case ProviderEvent.InputTranscriptDone done -> {
                if (consentWithdrawnAt != null) {
                    droppedAfterConsent++;
                    return;
                }
                if (inRelayRange()) {
                    boolean uncertain = uncertain(done);
                    transcripts.childFinal(done.itemId(), done.transcript(), at, uncertain);
                    // 2차 리뷰 2: 종료 의도를 푼 발화의 응답은 막힌 동안 이미 취소됐을 수 있다 — 끝 신호가 오지 않을 '생각 중'을 보내지 않는다
                    if (!judgeStopIntent(done.transcript(), uncertain, at)) {
                        thinking();
                    }
                }
            }
            case ProviderEvent.OutputTranscriptDelta delta -> {
                refuseAfterStopIntent(delta.responseId());
                if (inOutputRange() && !cancelledResponses.contains(delta.responseId())) {
                    beginOutput(delta.responseId(), at);
                    // J294: 음성처럼 구간에 들어간 조각만 조용 시계를 다시 건다(버린 조각은 열린 구간의 조용 시간을 늘리지 않는다)
                    if (segment.addTranscript(delta.responseId(), delta.delta(), at, received.receivedNanos())
                            == SegmentBuffer.Added.ADDED) {
                        timers.arm(Kind.SEGMENT_QUIET, services.settings().segmentQuiet());
                    }
                }
            }
            case ProviderEvent.OutputAudioDelta delta -> {
                refuseAfterStopIntent(delta.responseId());
                if (inOutputRange() && !cancelledResponses.contains(delta.responseId())) {
                    beginOutput(delta.responseId(), at);
                    SegmentBuffer.Added added =
                            segment.addAudio(delta.responseId(), delta.audio(), at, received.receivedNanos());
                    if (added == SegmentBuffer.Added.OVERSIZE) {
                        timers.cancel(Kind.SEGMENT_QUIET);
                        SegmentBuffer.Sealed oversized = segment.takeOversized();
                        discard(oversized, GateDecision.DiscardReason.SEGMENT_OVERSIZE);
                    } else if (added == SegmentBuffer.Added.ADDED) {
                        timers.arm(Kind.SEGMENT_QUIET, services.settings().segmentQuiet());
                    }
                }
            }
            case ProviderEvent.OutputDone done -> {
                // U3-R5: 취소한 응답의 끝 신호는 버린다
                if (!cancelledResponses.contains(done.responseId())) {
                    // J300: 열린 구간이 그 응답이면 output.done 처리 시각이 열린 AI 행의 tTranscriptFinal이다(view_item_ready{ai}의 시작)
                    if (segment.isOpen() && segment.responseId().equals(done.responseId())) {
                        transcripts.aiDone(at);
                    }
                    segment.responseDone(done.responseId());
                }
            }
        }
    }

    /**
     * 출력 조각을 넣기 전: 열린 구간이 다른 응답의 것이면 그 자리에서 봉인해 게이트에 넣는다 — 한 구간은 한 응답이고 섞지 않는다(F1 · J286,
     * 완결이 아닌 구간은 게이트 3단계가 GATE_EMPTY로 폐기한다). 응답의 첫 조각이면 t1을 찍고 AI 행 번호를 정해 확정 전 칸을 넣는다(PD-5 2.4).
     */
    private void beginOutput(String responseId, Instant at) {
        if (segment.holdsOtherThan(responseId)) {
            sealAndGate(true);
        }
        if (!segment.isOpen() && segment.accepts(responseId)) {
            services.latency().providerFirstOut(plan.sessionId(), at);
            transcripts.aiStart(at);
        }
    }

    /**
     * 공급자 {@code session.error}. 시작 전이면 PROVIDER_UNAVAILABLE(contracts/ai-io.md 3절, U1 그대로). 시작 뒤에는 연결이 살아 있을 수
     * 있어 세션을 끝내지 않는다 — PD 2.1-C의 WARN 한 줄만 남기고, 완결되지 않은 구간은 게이트가 폐기해 대기 문구로 잇는다(U1 그대로).
     * 연결이 실제로 끊기면 뒤따르는 끊김 알림이 {@link #providerLost()}로 끝낸다(U3-R8).
     */
    private void providerError() {
        if (START_PHASE.contains(status)) {
            multiOriginFail(Trigger.PROVIDER_FAILED, FailureCode.PROVIDER_UNAVAILABLE, SessionTransitions.Origin.REALTIME);
            return;
        }
        services.events().appError(EventLog.Component.VOICE_RELAY, "ProviderDisconnected", EventCode.Level.WARN);
    }

    /** 공급자 연결 끊김({@code session.closed} · WSS 닫힘 · 전송 오류). */
    private void providerLost() {
        if (providerClosing) {
            // 실행기가 스스로 닫은 공급자의 끊김 알림이다(정리 · 앱 종료 T3)
            return;
        }
        if (START_PHASE.contains(status)) {
            multiOriginFail(Trigger.PROVIDER_FAILED, FailureCode.PROVIDER_UNAVAILABLE, SessionTransitions.Origin.REALTIME);
            return;
        }
        // PD 2.1-C의 WARN 한 줄은 그대로 남긴다
        services.events().appError(EventLog.Component.VOICE_RELAY, "ProviderDisconnected", EventCode.Level.WARN);
        if (status == SessionStatus.ACTIVE) {
            abortProviderLost();
        }
        // CLOSING 중에는 30초 확인 시한이 우선한다(U1 그대로)
    }

    /**
     * U3-R8 · U3-J13 · AC6.6.3 · B18: ACTIVE 중 공급자가 끊기면 음성 모델 없이 대화를 이을 수 없어 기존 전이 ACTIVE → ABORTED(DISCONNECT)로
     * 바로 끝내고 end_detail에 PROVIDER_LOST를 적는다. 연결이 살아 있으면 CLOSE_TRANSPORT(DISCONNECT) + STANDBY.
     */
    private void abortProviderLost() {
        Instant now = services.clock().now();
        Optional<ActivitySessionRow> row = services.transitions().transition(plan.sessionId(), SessionStatus.ACTIVE,
                SessionStatus.ABORTED, TransitionTable.changeFor(Trigger.DISCONNECT, null, now)
                        .withEndDetail(END_DETAIL_PROVIDER_LOST).withProviderClosedAt(now),
                SessionTransitions.Origin.REALTIME, plan.traceId());
        if (row.isEmpty()) {
            syncFromDb();
            return;
        }
        status = SessionStatus.ABORTED;
        closeProvider();
        bumpGeneration();
        DeviceLink current = link;
        if (current != null && current.isOpen()) {
            sendControl(new CloseTransport(CloseTransport.Reason.DISCONNECT));
            sendControl(new SessionState(View.STANDBY));
        }
        teardown();
    }

    /**
     * U3-R12 · AC6.4.4 ~ 6.4.6: ACTIVE의 확정 아이 전사로 종료 의도를 판정한다. STOP이면 대기 · 재생 중 응답을 취소하고(끼어들기와 같은 출력
     * 멈춤) 이후 공급자 출력을 받지 않으며 CHILD_STOP_INTENT를 남긴다. CONFIRM이면 CHILD_STOP_UNCLEAR만 남긴다. 상태 전이 · 마무리 인사 ·
     * 확인 질문 재생은 U4다(U3-J1 · U3-J24).
     *
     * <p>리뷰 반영 3(오판 복구): CLOSING(CHILD_STOP) 전이가 U4라 STOP 뒤에도 세션은 ACTIVE다. 그 뒤 아이가 적극적으로 대화를 이어 가면
     * ("더 얘기하자" · "계속할래" · "그만 안 할래" — 불확실 아님 · 기기 재생 중 아님) 오판으로 보고 출력 막힘을 풀어 새 응답을 다시 보낸다. 남의 말 ·
     * 주제 거부 · 평범한 말은 풀지 않는다(2차 리뷰 6). 이미 취소한 응답은 그대로 버린다(AC6.4.8 '이전 응답이 이어서 재생되지 않음').
     *
     * @return 이번 발화로 종료 의도를 풀었으면 참
     */
    private boolean judgeStopIntent(String transcript, boolean uncertain, Instant at) {
        if (status != SessionStatus.ACTIVE) {
            return false;
        }
        if (stopIntent) {
            if (!uncertain && devicePlaying.isEmpty() && stopJudge.continues(transcript)) {
                stopIntent = false;
                putEvent(EVENT_CHILD_STOP_LIFTED, null, at);
                return true;
            }
            return false;
        }
        StopIntentJudge.Judgment judgment = stopJudge.judge(transcript, !devicePlaying.isEmpty());
        switch (judgment.verdict()) {
            case STOP -> {
                stopIntent = true;
                putEvent(EVENT_CHILD_STOP_INTENT, "{\"basis\":\"" + judgment.basis().name() + "\"}", at);
                stopOutputForIntent(at);
                listeningAgain();
            }
            case CONFIRM -> putEvent(EVENT_CHILD_STOP_UNCLEAR, null, at);
            case NONE -> {
                // 종료 의도 아님
            }
        }
        return false;
    }

    /** 종료 의도: 남은 프레임을 보내지 않고(세대 올림) 만들던 · 재생 중이던 응답을 공급자에서 취소하고 열린 구간을 버린다. */
    private void stopOutputForIntent(Instant at) {
        bumpGeneration();
        timers.cancel(Kind.SEGMENT_QUIET);
        Set<String> targets = new LinkedHashSet<>();
        if (segment.isOpen() && segment.responseId() != null) {
            targets.add(segment.responseId());
        }
        devicePlaying.forEach(playbackId -> {
            String responseId = playbackResponses.get(playbackId);
            if (responseId != null) {
                targets.add(responseId);
            }
        });
        cancelResponses(targets.toArray(String[]::new));
        segment.reset();
        transcripts.aiFinal("", false, at);
    }

    /** 종료 의도 뒤 새 응답의 첫 조각은 공급자에서 바로 취소한다(한 번). 조각은 취소한 응답이라 버려진다. */
    private void refuseAfterStopIntent(String responseId) {
        if (stopIntent && responseId != null && !responseId.isBlank() && !cancelledResponses.contains(responseId)) {
            cancelResponses(responseId);
        }
    }

    /** U3-J6: 아이 말이 끝났다(말 끝 · 전사 확정) — 듣는 중이고 재생이 없으면 '생각 중'. */
    private void thinking() {
        if (status == SessionStatus.ACTIVE && view == View.LISTENING && devicePlaying.isEmpty() && !stopIntent) {
            sendControl(new SessionState(View.THINKING));
        }
    }

    /** U3-J6: 재생이 모두 끝났거나 보낼 응답이 없다 — 생각 중 · 재생 뒤면 '듣는 중'으로 돌아간다. */
    private void listeningAgain() {
        if (status == SessionStatus.ACTIVE && view != View.LISTENING && devicePlaying.isEmpty()
                && !playback.isPlaying()) {
            sendControl(new SessionState(View.LISTENING));
        }
    }

    /** U3-R10 · U3-J17: 확정 전사가 비었거나 공급자 신뢰도가 기준 미만이면 불확실. 신뢰도가 없으면 불확실로 보지 않는다. */
    private boolean uncertain(ProviderEvent.InputTranscriptDone done) {
        if (done.transcript() == null || done.transcript().isBlank()) {
            return true;
        }
        Double confidence = done.confidence();
        return confidence != null && confidence * MAX_PERCENT < services.settings().uncertainBelowPercent();
    }

    // ------------------------------------------------------------------ 게이트

    /**
     * 구간 확정(조용 시간 만기 · 아이 입력 재개) → 검사 → 송신 직전 재확인 → 송신 또는 폐기(PD-3 표 2.3-C).
     *
     * @param movedOn 아이 입력 재개 · 다른 응답 도착으로 확정했는가(대화가 이미 넘어가 완결 요청 교정을 보내지 않는다, U3-R7)
     */
    private void sealAndGate(boolean movedOn) {
        timers.cancel(Kind.SEGMENT_QUIET);
        Instant confirmedAt = services.clock().now();
        long confirmedNanos = services.clock().monotonicNanos();
        SegmentBuffer.Sealed sealed = segment.seal(outputGeneration.get());
        if (sealed == null) {
            return;
        }
        services.latency().segmentConfirmed(plan.sessionId(), confirmedAt, confirmedNanos, sealed.firstOutNanos(),
                sealed.lastOutNanos());
        try {
            GateDecision inspected = services.gate().inspect(sealed);
            if (!inspected.passed()) {
                // 금지 패턴은 대화가 넘어갔어도 교정한다(같은 표현을 되풀이하지 않게)
                discard(sealed, inspected.reason(),
                        !movedOn || inspected.reason() == GateDecision.DiscardReason.GATE_PATTERN);
                return;
            }
            DeviceLink target = link;
            boolean activeAndCurrent = status == SessionStatus.ACTIVE && sealed.generation() == outputGeneration.get()
                    && target != null && target.isOpen();
            OutputGate.Rechecked<DeviceLink.AudioReservation> rechecked = services.gate().recheck(activeAndCurrent,
                    services.recorder().isDegraded(plan.sessionId()),
                    () -> target == null ? Optional.empty() : target.reserveAudio(sealed.totalBytes()));
            if (!rechecked.decision().passed()) {
                discard(sealed, rechecked.decision().reason());
                return;
            }
            send(sealed, target, rechecked.reservation());
        } catch (RuntimeException e) {
            // 게이트 처리 중 예외는 폐기한다(fail-closed, SD-17)
            services.events().appError(EventLog.Component.OUTPUT_GATE, e);
            if (transcripts.hasOpenAi()) {
                discard(sealed, GateDecision.DiscardReason.GATE_EMPTY, !movedOn);
            }
        }
    }

    private void send(SegmentBuffer.Sealed sealed, DeviceLink target, DeviceLink.AudioReservation reservation) {
        Instant passAt = services.clock().now();
        UUID playbackId = UUID.randomUUID();
        services.latency().gatePassed(plan.sessionId(), playbackId, passAt);
        transcripts.aiFinal(sealed.transcript(), true, passAt);
        playbackResponses.put(playbackId, sealed.responseId());
        long generation = sealed.generation();
        // U3-R9 · 리뷰 반영 6: 기기로 실제 보낸 AI 음성 프레임만 기록한다(송신기가 소켓에 쓴 직후 넘긴다). 음성 레인에 못 들어간 묶음 ·
        // 세대가 올라 잘린 나머지 프레임은 기록하지 않는다
        AudioRecordings.Session aiRecording = recording;
        OutputBundle bundle = new OutputBundle(plan.sessionId(), playbackId, OutputBegin.Source.GPT_LIVE,
                OutputBundle.frame(sealed.chunks()), sealed.totalMs(), reservation,
                () -> outputGeneration.get() == generation,
                firstFrameAt -> services.latency().firstFrameSent(plan.sessionId(), playbackId, firstFrameAt),
                aiRecording == null ? frame -> { } : aiRecording::ai);
        if (!target.sendOutput(bundle)) {
            services.metrics().gateDiscard(GateDecision.DiscardReason.OUTBOUND_FULL.name());
        }
    }

    /** 폐기: AI 행 FINAL · false(BR6.15), 그다음 대체 문구 판정(겹침 규칙 R3-19). 교정은 사유에 따라(U3-R7). */
    private void discard(SegmentBuffer.Sealed sealed, GateDecision.DiscardReason reason) {
        discard(sealed, reason, true);
    }

    private void discard(SegmentBuffer.Sealed sealed, GateDecision.DiscardReason reason, boolean correct) {
        services.metrics().gateDiscard(reason.name());
        services.latency().gateDiscarded(plan.sessionId());
        transcripts.aiFinal(sealed == null ? "" : sealed.transcript(), false, services.clock().now());
        if (correct) {
            correctAfterDiscard(sealed, reason);
        }
        if (status != SessionStatus.ACTIVE || playback.isPlaying() || plan.waitingFiller() == null) {
            listeningAgain();
            return;
        }
        UUID playbackId = UUID.randomUUID();
        if (sendControl(new PlayApprovedContent(playbackId, PlayApprovedContent.WAITING_FILLER,
                plan.waitingFiller().contentVersionId()))) {
            playback.started(playbackId, PlayApprovedContent.WAITING_FILLER);
            timers.arm(Kind.PLAYBACK_EXPIRY, services.settings().playbackExpiry());
        }
    }

    /**
     * U3-R7 · U3-J12: ACTIVE에서 GATE_PATTERN · GATE_EMPTY · SEGMENT_OVERSIZE로 폐기했으면 응답당 한 번 고정 교정 문장을 보낸다(걸린 범주의
     * 문장, 범주를 모르거나 미완 · 너무 긴 응답이면 짧은 완결 요청). 폐기한 전사 · 걸린 패턴은 보내지 않는다.
     */
    private void correctAfterDiscard(SegmentBuffer.Sealed sealed, GateDecision.DiscardReason reason) {
        if (status != SessionStatus.ACTIVE || sealed == null || sealed.responseId() == null) {
            return;
        }
        // 폐기 사유가 미완 · 너무 김이어도 버린 구간에 금지 패턴이 있으면 그 범주의 문장을 보낸다 — 교정은 응답당 한 번이라 전사만 든 구간이
        // 먼저 미완으로 폐기되면 금지 패턴 교정을 다시 보낼 기회가 없다(2차 리뷰 9 조사 중 찾음)
        DialogInstructions.Correction correction = switch (reason) {
            case GATE_PATTERN, GATE_EMPTY, SEGMENT_OVERSIZE -> services.gate().matchedCategory(sealed)
                    .map(SessionExecutor::correctionFor).orElse(DialogInstructions.Correction.INCOMPLETE);
            default -> null;
        };
        if (correction == null || !correctedResponses.add(sealed.responseId())) {
            return;
        }
        ProviderRequestFactory.InstructionsCorrect request = services.requests().instructionsCorrect(correction);
        toProvider("provider-correct-", session -> session.correctInstructions(request));
    }

    static DialogInstructions.Correction correctionFor(SeedDictionary.Category category) {
        return switch (category) {
            case DIAGNOSIS -> DialogInstructions.Correction.DIAGNOSIS;
            case TREATMENT -> DialogInstructions.Correction.TREATMENT;
            case SCORE -> DialogInstructions.Correction.SCORE;
            case CAUSE -> DialogInstructions.Correction.CAUSE;
            case GENERALIZATION -> DialogInstructions.Correction.GENERALIZATION;
            case EMOTION_CLAIM -> DialogInstructions.Correction.EMOTION_CLAIM;
            case PRESSURE -> DialogInstructions.Correction.PRESSURE;
        };
    }

    /** 응답을 취소한다(끼어들기 · 종료 의도, U3-R5 · U3-J11). 늦게 오는 그 응답의 조각은 받지 않는다. id를 모르면 지금 응답을 멈춘다. */
    private void cancelResponses(String... responseIds) {
        Set<String> targets = new LinkedHashSet<>();
        for (String id : responseIds) {
            if (id != null && !id.isBlank()) {
                targets.add(id);
            }
        }
        if (targets.isEmpty()) {
            ProviderRequestFactory.ResponseCancel request = services.requests().responseCancel(null);
            toProvider("provider-cancel-", session -> session.cancelResponse(request));
            return;
        }
        for (String id : targets) {
            cancelledResponses.add(id);
            ProviderRequestFactory.ResponseCancel request =
                    services.requests().responseCancel(ProviderResponseId.of(id).orElse(null));
            toProvider("provider-cancel-", session -> session.cancelResponse(request));
        }
    }

    /** 공급자 송신 하나(보조 작업). 실행기 스레드는 공급자 소켓 쓰기를 기다리지 않는다. 끊김은 리더가 따로 알린다. */
    private void toProvider(String name, ProviderCall call) {
        ProviderSession session = providerSession;
        if (session == null || !session.isOpen()) {
            return;
        }
        services.launcher().launch(name + plan.sessionId(), () -> {
            try {
                call.send(session);
            } catch (ProviderUnavailableException e) {
                // 공급자 끊김은 리더의 Closed로 따로 온다
            } catch (RuntimeException e) {
                services.events().appError(EventLog.Component.VOICE_RELAY, e);
            }
        });
    }

    /** 공급자 송신 한 번. */
    @FunctionalInterface
    private interface ProviderCall {
        void send(ProviderSession session) throws ProviderUnavailableException;
    }

    private boolean inRelayRange() {
        return status == SessionStatus.ACTIVE
                || (status == SessionStatus.CLOSING && closing.step() != ClosingSequence.Step.TRANSPORT_CLOSED);
    }

    private boolean inOutputRange() {
        return status == SessionStatus.ACTIVE || status == SessionStatus.CLOSING;
    }

    // ------------------------------------------------------------------ 기기 표식 · 끝내기

    private void onPlaybackMark(PlaybackMarkReceived received) {
        if (status.isTerminal() || !fromBoundConnection(received.connectionId())) {
            return;
        }
        PlaybackMark mark = received.mark();
        DeviceLink current = link;
        switch (mark.status()) {
            case STARTED -> {
                devicePlaying.add(mark.playbackId());
                services.latency().playbackStarted(plan.sessionId(), mark.playbackId(), mark.observedAt(),
                        received.serverRecvAt(), current == null ? null : current.clockOffsetMs(),
                        current == null ? null : current.rttMs());
            }
            case PROGRESS -> {
                // U3-J10: 재생 진행(1초마다)은 끼어들기 · 멈춤 시점의 재생 위치 근거로만 둔다
                progressPlaybackId = mark.playbackId();
                progressPlayedToMs = mark.playedRange() == null ? null : mark.playedRange().toMs();
            }
            case COMPLETED, STOPPED, FAILED -> {
                if (mark.status() == PlaybackMark.Status.STOPPED) {
                    logStopped(mark, received.serverRecvAt(), current);
                    if (mark.stopCause() == PlaybackMark.StopCause.LOCAL_VOICE_ACTIVITY) {
                        onBargeIn(mark, received.serverRecvAt());
                    }
                }
                if (playback.clearIfMatches(mark.playbackId())) {
                    timers.cancel(Kind.PLAYBACK_EXPIRY);
                }
                if (status == SessionStatus.CLOSING && closing.onPlaybackMark(closingDevice(), mark.playbackId(),
                        mark.status())) {
                    // R3-8: CLOSE_TRANSPORT 송신까지만 입력을 중계한다
                    relay.setAccepting(false);
                    annotateQuietly(SessionStatus.CLOSING,
                            SessionChange.none().withTransportClosedAt(services.clock().now()));
                }
                devicePlaying.remove(mark.playbackId());
                // U3-J6: 재생이 모두 끝나면 다시 '듣는 중'
                listeningAgain();
            }
            default -> {
                // UNRECOGNIZED는 판단에 쓰지 않는다(J123)
            }
        }
    }

    /**
     * 끼어들기(U3-R5 · U3-J10 · AC6.2.12): 기기가 아이 음성으로 재생을 멈췄다. 남은 음성 프레임을 보내지 않고(세대 올림) 공급자 응답을 취소하고
     * 열린 출력 구간은 버리며 재생 위치와 함께 BARGE_IN을 남긴다. '듣는 중' 복귀는 끝 표식 처리가 한다(U3-J6).
     */
    private void onBargeIn(PlaybackMark mark, Instant at) {
        bumpGeneration();
        timers.cancel(Kind.SEGMENT_QUIET);
        String playing = playbackResponses.get(mark.playbackId());
        String open = segment.isOpen() ? segment.responseId() : null;
        // U3-R5 · U3-J11: 재생 중이던 응답과 만들던 응답을 공급자에서도 멈춘다(남은 조각은 받지 않는다)
        cancelResponses(playing, open);
        segment.reset();
        transcripts.aiFinal("", false, at);
        Long playedTo = mark.playedRange() != null ? mark.playedRange().toMs()
                : mark.playbackId().equals(progressPlaybackId) ? progressPlayedToMs : null;
        putEvent(EVENT_BARGE_IN, "{\"playbackId\":\"" + mark.playbackId() + "\""
                + (playedTo == null ? "" : ",\"playedToMs\":" + playedTo) + "}", at);
    }

    /** U3-J9: 묶인 기기의 마이크 끊김 · 복구와 플레이어 오류를 세션 이벤트로 남긴다(바뀔 때 한 번). */
    private void onDeviceState(DeviceStateReceived received) {
        if (status.isTerminal() || !fromBoundConnection(received.connectionId())) {
            return;
        }
        DeviceState state = received.state();
        if (state.mic() != null && state.mic() != DeviceState.Mic.UNRECOGNIZED && state.mic() != lastMic) {
            if (state.mic() == DeviceState.Mic.LOST) {
                putEvent(EVENT_MIC_LOST, null, received.receivedAt());
            } else if (lastMic == DeviceState.Mic.LOST) {
                putEvent(EVENT_MIC_OK, null, received.receivedAt());
            }
            lastMic = state.mic();
        }
        if (state.player() != null && state.player() != DeviceState.Player.UNRECOGNIZED && state.player() != lastPlayer) {
            if (state.player() == DeviceState.Player.ERROR) {
                putEvent(EVENT_PLAYER_ERROR, null, received.receivedAt());
            }
            lastPlayer = state.player();
        }
    }

    /**
     * U3-J4 · J5 · J7: 같은 기기의 새 연결이 HELLO_OK를 받았다(HELLO_OK는 이미 나갔다). 세션을 새 연결에 묶고 끊김 구간을 닫은 뒤, 이어가기면
     * SESSION_STATE(지금 표시 상태)와 ACK를 못 받은 명령을, 이어가기가 아니면 START_SESSION(같은 opId · 같은 승인 음성)부터 다시 보낸다. 낡은
     * STOP_OUTPUT은 다시 보내지 않는다(새 연결의 음성 레인은 비어 있다).
     *
     * <p>리뷰 반영: 세션이 이미 끝났으면 새 연결을 대기로 돌려보낸다(4, {@link #standDown}). CLOSING 중 세션을 모르는 기기(resume 없음)에는
     * START_SESSION으로 입력 창을 다시 열게 하지 않고 CLOSE_TRANSPORT를 보낸다(5). 다시 보낸 승인 문구 재생은 재생 추적에 다시 넣는다(9).
     */
    private void onLinkReplaced(LinkReplaced replaced) {
        if (status.isTerminal()) {
            standDown(replaced.link(), plan.sessionId(), status, replaced.resumed());
            return;
        }
        DeviceLink next = replaced.link();
        DeviceLink previous = link;
        if (previous != null && previous.connectionId().equals(next.connectionId())) {
            return;
        }
        unbind(previous);
        link = next;
        next.bindSession(plan.sessionId());
        // SD-17: 연결 교체는 세대를 올린다(옛 묶음의 남은 프레임이 새 연결로 가지 않게)
        bumpGeneration();
        playback.clear();
        devicePlaying.clear();
        timers.cancel(Kind.PLAYBACK_EXPIRY);
        Instant now = services.clock().now();
        if (openGapId != null) {
            services.gaps().close(openGapId, now);
            openGapId = null;
        }
        putEvent(EVENT_LINK_REPLACED, "{\"resumed\":" + replaced.resumed() + "}", now);
        if (START_PHASE.contains(status)) {
            // 시작 단계: 기기가 이어가기를 안 했거나 START_SESSION의 ACK가 아직 없으면 같은 opId로 다시 보낸다(기기는 같은 opId를 다시 실행하지
            // 않고 ACK만 한다, U3-J8)
            if (startSent && (!replaced.resumed() || pendingCommands.containsKey(startOpId))) {
                next.sendControl(new StartSession(plan.approvedAudio()), startOpId, plan.sessionId());
            }
            return;
        }
        if (status == SessionStatus.CLOSING && !replaced.resumed()) {
            closeForUnknownDevice(now);
            return;
        }
        if (!replaced.resumed()) {
            // U3-J5: 기기가 세션을 모른다(새로 켠 페이지) — 같은 승인 음성으로 START_SESSION부터. 늦은 DEVICE_READY는 무시된다
            next.sendControl(new StartSession(plan.approvedAudio()), startOpId, plan.sessionId());
        }
        sendControl(new SessionState(view));
        keepOnlyLatestPendingPlayback();
        for (Map.Entry<UUID, WsPayload> pending : List.copyOf(pendingCommands.entrySet())) {
            WsPayload payload = pending.getValue();
            if (payload instanceof StopOutput || payload instanceof StartSession) {
                continue;
            }
            if (next.sendControl(payload, pending.getKey(), plan.sessionId())
                    && payload instanceof PlayApprovedContent play) {
                // 리뷰 반영 9: 같은 playbackId로 다시 보낸 승인 문구는 다시 재생될 것이라 추적한다(끝 표식 · 20초 만기가 지운다). 추적하지
                // 않으면 그 사이 대기 문구가 겹쳐 나가고 '듣는 중'이 재생 중에 나간다
                playback.started(play.playbackId(), play.contentCode());
                timers.arm(Kind.PLAYBACK_EXPIRY, services.settings().playbackExpiry());
            }
        }
        if (status == SessionStatus.ACTIVE) {
            armDisconnect();
        }
    }

    /**
     * 2차 리뷰 4: 재생 추적({@link ApprovedPlayback})은 한 칸이라 ACK 없는 승인 문구 재생 명령이 둘 이상이면 앞 것의 끝 표식이 추적을 지우지 못해
     * '듣는 중' 복귀가 늦는다. 새 연결에는 가장 최근 재생 명령 하나만 다시 보내고 앞 것은 미확인 목록에서 뺀다 — 끊기기 전 출력은 잇지 않는다는
     * 규칙(편차 (ab))과 같은 쪽이고, CLOSING의 마무리 문구는 늘 가장 최근 재생 명령이라(진입 때 STOP_OUTPUT 뒤 한 번) 남는다.
     */
    private void keepOnlyLatestPendingPlayback() {
        UUID latest = null;
        for (Map.Entry<UUID, WsPayload> pending : pendingCommands.entrySet()) {
            if (pending.getValue() instanceof PlayApprovedContent) {
                latest = pending.getKey();
            }
        }
        UUID keep = latest;
        pendingCommands.entrySet().removeIf(pending -> pending.getValue() instanceof PlayApprovedContent
                && !pending.getKey().equals(keep));
    }

    /**
     * 리뷰 반영 5: CLOSING 중 세션을 모르는 기기가 붙었다(CLOSE_TRANSPORT를 처리하고 세션을 잊은 뒤 끊긴 기기 · 새로 켠 페이지). START_SESSION을
     * 다시 보내면 기기가 마이크 입력 창을 다시 열어 30초 시한까지 열린 채 ENDED_UNCONFIRMED로 끝난다. 그래서 마무리 표시를 알리고
     * CLOSE_TRANSPORT를 (다시) 보내 VOICE_SESSION_CLOSED로 끝나게 한다. 아직 마무리 문구 차례였으면 세션을 모르는 기기가 재생하지 못하므로
     * 건너뛰고 지금 닫는다 — 입력 중계도 이때 멈춘다(R3-8). 옛 연결의 마무리 문구 · 닫기 명령은 다시 보내지 않는다.
     */
    private void closeForUnknownDevice(Instant now) {
        pendingCommands.values().removeIf(payload -> payload instanceof PlayApprovedContent
                || payload instanceof CloseTransport || payload instanceof StartSession);
        sendControl(new SessionState(View.CLOSING));
        if (closing.closeNow(closingDevice())) {
            relay.setAccepting(false);
            annotateQuietly(SessionStatus.CLOSING, SessionChange.none().withTransportClosedAt(now));
        }
    }

    /**
     * 리뷰 반영 4: 끝난 세션의 HELLO_OK.session을 받은 새 연결을 대기로 돌려보낸다. 이어간 기기(resumed)는 입력 창을 이미 다시 열었으므로
     * CLOSE_TRANSPORT로 닫게 하고(그 ACK · VOICE_SESSION_CLOSED는 세션이 없어 버려진다), 그다음 SESSION_STATE{STANDBY}. 연결이 닫혔으면 아무것도
     * 보내지 않는다. 실행기가 LinkReplaced를 처리하기 전에 끝났을 때(정리의 남은 편지 · 인계가 닫힌 편지함을 만났을 때) 쓴다.
     *
     * @param ended 세션의 끝 상태(닫기 사유를 고른다)
     */
    public static void standDown(DeviceLink next, UUID sessionId, SessionStatus ended, boolean resumed) {
        if (next == null || !next.isOpen()) {
            return;
        }
        UUID bound = next.boundSessionId();
        if (bound != null && !bound.equals(sessionId)) {
            // 2차 리뷰 5: 그 사이 같은 기기의 새 세션이 이 연결을 묶었다 — 뒤늦은 대기 명령으로 새 세션 표시를 덮지 않는다
            return;
        }
        if (resumed) {
            next.sendControl(new CloseTransport(closeReasonFor(ended)), UUID.randomUUID(), sessionId);
        }
        next.sendControl(new SessionState(View.STANDBY), null, sessionId);
    }

    private static CloseTransport.Reason closeReasonFor(SessionStatus ended) {
        return switch (ended) {
            case ENDED, ENDED_UNCONFIRMED -> CloseTransport.Reason.ENDED;
            case ABORTED -> CloseTransport.Reason.DISCONNECT;
            case DECLINED -> CloseTransport.Reason.DECLINED;
            default -> CloseTransport.Reason.FAILED;
        };
    }

    private void logStopped(PlaybackMark mark, Instant serverRecvAt, DeviceLink current) {
        services.events().log(EventCode.PLAYBACK_MARK_STOPPED, EventLog.Component.SESSION_LIFECYCLE, plan.sessionId(),
                plan.traceId(), entry -> {
                    entry.attr(AttrKey.STOP_CAUSE, mark.stopCause()).attr(AttrKey.T_DEVICE_OBSERVED, mark.observedAt())
                            .attr(AttrKey.T_SERVER_RECV, serverRecvAt);
                    if (current != null && current.clockOffsetMs() != null) {
                        entry.attr(AttrKey.CLOCK_OFFSET_MS, current.clockOffsetMs().longValue());
                    }
                    if (current != null && current.rttMs() != null) {
                        entry.attr(AttrKey.RTT_MS, current.rttMs().longValue());
                    }
                });
    }

    private void onVoiceClosed(VoiceClosed closed) {
        if (status != SessionStatus.CLOSING || !fromBoundConnection(closed.connectionId())) {
            return;
        }
        Instant now = services.clock().now();
        closeProvider();
        SessionChange change = TransitionTable.changeFor(Trigger.VOICE_CLOSED, null, now).withProviderClosedAt(now)
                .withOutputStoppedAt(closed.closed().outputStoppedAt());
        Optional<ActivitySessionRow> row = services.transitions().transition(plan.sessionId(), SessionStatus.CLOSING,
                SessionStatus.ENDED, change, SessionTransitions.Origin.REALTIME, plan.traceId());
        if (row.isEmpty()) {
            syncFromDb();
            return;
        }
        status = SessionStatus.ENDED;
        bumpGeneration();
        sendControl(new SessionState(View.STANDBY));
        teardown();
    }

    private void onEndRequested(EndRequested request) {
        EndOutcome outcome = switch (request.reason()) {
            case GUARDIAN_STOP -> guardianStop(request.requestId());
            case CONSENT_WITHDRAWN -> consentWithdrawn(request.requestId());
            case CORE_ABANDONED -> coreAbandoned();
            default -> EndOutcome.UNSUPPORTED;
        };
        request.result().complete(outcome);
    }

    /**
     * J54 · U4b 응답표(BR1.5 · BR1.6): WAITING_CHILD · ACTIVE → CLOSING(GUARDIAN_STOP, 첫 수락), CLOSING → 같은 답(재처리 없음), 시작 단계 ·
     * 종료 → 409.
     */
    private EndOutcome guardianStop(UUID requestId) {
        if (status == SessionStatus.CLOSING) {
            return EndOutcome.CLOSING;
        }
        if (!Trigger.GUARDIAN_STOP.accepts(status)) {
            return EndOutcome.NOT_ACTIVE;
        }
        beginClosing(Trigger.GUARDIAN_STOP, requestId);
        return status == SessionStatus.CLOSING ? EndOutcome.CLOSING : EndOutcome.NOT_ACTIVE;
    }

    /**
     * U4b 응답표의 동의 철회 행(BR1.5 · BR1.7 · U4b-R9 · U4b-J9): 시작 단계 → FAILED(CONSENT_WITHDRAWN) + 202 {}, WAITING_CHILD · ACTIVE →
     * CLOSING(CONSENT_WITHDRAWN) + 202 {CLOSING}, CLOSING → 202 {CLOSING}(사유는 처음 것), 종료 → 409. 받은 순간부터 아이 전사 · 원음을 저장하지
     * 않는다(반복 요청은 같은 응답만, BR1.6).
     */
    private EndOutcome consentWithdrawn(UUID requestId) {
        if (status.isTerminal()) {
            return EndOutcome.NOT_ACTIVE;
        }
        blockChildRecording();
        if (status == SessionStatus.CLOSING) {
            return EndOutcome.CLOSING;
        }
        if (START_PHASE.contains(status)) {
            failWithConsentWithdrawn();
            // 시작 단계는 202 {}(다른 전이가 먼저 이겨 종료됐어도 같은 답)
            return status == SessionStatus.CLOSING ? EndOutcome.CLOSING : EndOutcome.ACCEPTED_EMPTY;
        }
        beginClosing(Trigger.CONSENT_WITHDRAWN, requestId);
        return status == SessionStatus.CLOSING ? EndOutcome.CLOSING : EndOutcome.NOT_ACTIVE;
    }

    /** 시작 단계 동의 철회: FAILED(CONSENT_WITHDRAWN). START_SESSION이 나갔고 연결이 살아 있으면 CLOSE_TRANSPORT(CONSENT_WITHDRAWN). */
    private void failWithConsentWithdrawn() {
        ActivitySessionRepository.MultiOriginTransition result = services.transitions().transitionFromAny(
                plan.sessionId(), Trigger.CONSENT_WITHDRAWN_START.origins(), SessionStatus.FAILED,
                read -> TransitionTable.changeFor(Trigger.CONSENT_WITHDRAWN_START, null, services.clock().now())
                        .withTerminationRequestedAt(services.clock().now()),
                SessionTransitions.Origin.REALTIME, plan.traceId());
        if (!result.transitioned()) {
            syncFromDb();
            return;
        }
        afterFailed(CloseTransport.Reason.CONSENT_WITHDRAWN);
    }

    /**
     * BR1.7: 동의 철회를 받은 뒤의 아이 전사 · 원음은 저장하지 않는다. 원음 기록을 닫고(아이 · AI 모두) 이후 아이 전사는 버리며 버린 건수를
     * 센다(사건 payload는 건수만, BR12.1).
     */
    private void blockChildRecording() {
        if (consentWithdrawnAt != null) {
            return;
        }
        consentWithdrawnAt = services.clock().now();
        relay.tap(null);
        AudioRecordings.Session open = recording;
        recording = null;
        if (open != null) {
            quietly(() -> open.close(consentWithdrawnAt));
        }
        transcripts.discardOpenChildren(consentWithdrawnAt);
    }

    /**
     * WAITING_CHILD · ACTIVE → CLOSING 전이(BR1.3): 사건이 정한 사유를 CLOSING에 쓰고 승자만 마무리 순서를 시작한다(BR1.2 · BR4.6).
     *
     * @return 이 호출이 전이를 이겼으면 참(지면 DB 상태로 맞춘다)
     */
    private boolean beginClosing(Trigger trigger, UUID requestId) {
        if (!trigger.accepts(status) || trigger.target() != SessionStatus.CLOSING) {
            return false;
        }
        Instant now = services.clock().now();
        SessionStatus from = status;
        Optional<ActivitySessionRow> row = services.transitions().transition(plan.sessionId(), from,
                SessionStatus.CLOSING, TransitionTable.changeFor(trigger, null, now).withTerminationRequestedAt(now),
                SessionTransitions.Origin.REALTIME, plan.traceId(), transitionExtras());
        if (row.isEmpty()) {
            syncFromDb(requestId);
            return false;
        }
        status = SessionStatus.CLOSING;
        closingReason = row.get().endReason() != null ? row.get().endReason() : trigger.endReason();
        closingDetail = row.get().endDetail();
        enterClosing(now, requestId);
        return true;
    }

    /** 전이와 함께 쓸 값(L2: 없음. 기술 일시정지는 L4가 싣는다). */
    private SessionTransitions.Extras transitionExtras() {
        return SessionTransitions.Extras.none();
    }

    /**
     * CLOSING 진입의 승자 몫(BR6.10 ① ② · BR6.12 ③ · BR4.5 · BR4.6).
     *
     * @param committedAt CLOSING 전이를 이긴 커밋 시각(30초 확인 시한의 시작점)
     * @param requestId END_REQUESTED 기록에 남길 끝내기 요청 id(모르면 null)
     */
    private void enterClosing(Instant committedAt, UUID requestId) {
        Instant now = services.clock().now();
        closingAt = committedAt;
        services.recorder().putEvent(new SessionEvent(UUID.randomUUID(), plan.sessionId(), "END_REQUESTED",
                requestId == null ? null : requestId.toString(), null, committedAt));
        // BR6.10 ① · BR4.5: 서버 쪽 출력 버퍼 취소 → STOP_OUTPUT + SESSION_STATE{CLOSING} → ② 마무리 문구 1회
        bumpGeneration();
        timers.cancel(Kind.SEGMENT_QUIET);
        timers.cancel(Kind.DISCONNECT);
        timers.cancel(Kind.PLAYBACK_EXPIRY);
        segment.reset();
        transcripts.aiFinal("", false, now);
        closing.begin(closingDevice(), plan.closing() == null ? null : plan.closing().contentVersionId(), playback,
                UUID::randomUUID, stopReasonFor(closingReason), closeReasonForClosing(closingReason));
        Duration elapsed = Duration.between(committedAt, now);
        timers.arm(Kind.CLOSING_CONFIRM,
                services.settings().closingConfirm().minus(elapsed.isNegative() ? Duration.ZERO : elapsed));
    }

    /** BR4.6: STOP_OUTPUT 사유 — GUARDIAN_STOP · CHILD_STOP · SILENCE는 같은 값, CONSENT_WITHDRAWN은 BEFORE_CLOSING. */
    static StopOutput.Reason stopReasonFor(EndReason reason) {
        if (reason == null) {
            return StopOutput.Reason.GUARDIAN_STOP;
        }
        return switch (reason) {
            case CHILD_STOP -> StopOutput.Reason.CHILD_STOP;
            case SILENCE -> StopOutput.Reason.SILENCE;
            case CONSENT_WITHDRAWN -> StopOutput.Reason.BEFORE_CLOSING;
            default -> StopOutput.Reason.GUARDIAN_STOP;
        };
    }

    /** BR4.6: CLOSE_TRANSPORT 사유는 마무리 사유(end_reason) 값 그대로다. */
    static CloseTransport.Reason closeReasonForClosing(EndReason reason) {
        if (reason == null) {
            return CloseTransport.Reason.GUARDIAN_STOP;
        }
        return switch (reason) {
            case CHILD_STOP -> CloseTransport.Reason.CHILD_STOP;
            case SILENCE -> CloseTransport.Reason.SILENCE;
            case CONSENT_WITHDRAWN -> CloseTransport.Reason.CONSENT_WITHDRAWN;
            default -> CloseTransport.Reason.GUARDIAN_STOP;
        };
    }

    /** BR6.7 (나) ~ (라) · BR1.9: PREPARING · DEVICE_ACK · WAITING_CHILD · ACTIVE → FAILED(실패 코드 없음), CLOSING · 종료는 그대로 202 {}. */
    private EndOutcome coreAbandoned() {
        if (status == SessionStatus.CLOSING || status.isTerminal()) {
            return EndOutcome.ACCEPTED_EMPTY;
        }
        multiOriginFail(Trigger.CORE_ABANDONED, null, SessionTransitions.Origin.CORE_ABANDONED);
        return EndOutcome.ACCEPTED_EMPTY;
    }

    private void onConnectionLost(ConnectionLost lost) {
        if (!fromBoundConnection(lost.connectionId())) {
            return;
        }
        // SD-17: 연결 교체 · 끊김마다 세대를 올린다. 세션 전이는 ④ 시계(ACTIVE) · ② · ③ 시한이 정한다
        bumpGeneration();
        playback.clear();
        devicePlaying.clear();
        timers.cancel(Kind.PLAYBACK_EXPIRY);
        // U3-J18: 진행 중 세션의 끊김 구간을 연다(새 연결이 다시 묶이면 닫고, ABORTED로 끝나면 열린 채 남는다)
        if ((status == SessionStatus.ACTIVE || status == SessionStatus.CLOSING) && openGapId == null) {
            openGapId = services.gaps().open(plan.sessionId(), SessionGapRepository.Kind.DISCONNECT, services.clock().now());
        }
    }

    // ------------------------------------------------------------------ 시계

    private void onTimer(TimerFired fired) {
        if (!timers.consume(fired)) {
            return;
        }
        runTimer(fired.kind());
    }

    private void runTimer(Kind kind) {
        switch (kind) {
            case DEVICE_ACK -> onStartTimeout();
            case CLOSING_CONFIRM -> onClosingTimeout();
            case DISCONNECT -> onDisconnectCheck();
            case SEGMENT_QUIET -> {
                if (inOutputRange()) {
                    sealAndGate(false);
                }
            }
            case PLAYBACK_EXPIRY -> playback.clear();
            case REDELIVER -> redeliver();
        }
    }

    /** BR6.12 ②: 송신 시도 시각부터 20초. ACK · READY 빠짐 → DEVICE_NO_ACK + UNRESPONSIVE, 아니면 PROVIDER_UNAVAILABLE(J59 (h)). */
    private void onStartTimeout() {
        if (!START_PHASE.contains(status)) {
            return;
        }
        Duration threshold = services.settings().deviceAckTimeout();
        Duration elapsed = Duration.between(startAttemptAt, services.clock().now());
        if (elapsed.compareTo(threshold) < 0) {
            timers.arm(Kind.DEVICE_ACK, threshold.minus(elapsed));
            return;
        }
        FailureCode code = TransitionTable.startTimeoutCode(ackReceived, deviceReady);
        ActivitySessionRepository.MultiOriginTransition result = services.transitions().transitionFromAny(
                plan.sessionId(), Trigger.START_TIMEOUT.origins(), SessionStatus.FAILED,
                read -> TransitionTable.changeFor(Trigger.START_TIMEOUT, code, services.clock().now()),
                SessionTransitions.Origin.REALTIME, plan.traceId());
        if (!result.transitioned()) {
            syncFromDb();
            return;
        }
        status = SessionStatus.FAILED;
        timeoutFired("DEVICE_ACK", threshold, elapsed);
        if (code == FailureCode.DEVICE_NO_ACK) {
            markUnresponsive();
        }
        afterFailed();
    }

    /** BR6.12 ③: CLOSING 진입부터 30초. ENDED_UNCONFIRMED + UNRESPONSIVE. */
    private void onClosingTimeout() {
        if (status != SessionStatus.CLOSING) {
            return;
        }
        Duration threshold = services.settings().closingConfirm();
        Duration elapsed = Duration.between(closingAt, services.clock().now());
        if (elapsed.compareTo(threshold) < 0) {
            timers.arm(Kind.CLOSING_CONFIRM, threshold.minus(elapsed));
            return;
        }
        Instant now = services.clock().now();
        closeProvider();
        // U4b BR1.4: UNCONFIRMED로 덮고 처음 사유(와 세부)를 end_detail에 둔다
        Optional<ActivitySessionRow> row = services.transitions().transition(plan.sessionId(), SessionStatus.CLOSING,
                SessionStatus.ENDED_UNCONFIRMED,
                TransitionTable.closingTimeoutChange(closingReason, closingDetail, now).withProviderClosedAt(now),
                SessionTransitions.Origin.REALTIME, plan.traceId());
        if (row.isEmpty()) {
            syncFromDb();
            return;
        }
        status = SessionStatus.ENDED_UNCONFIRMED;
        timeoutFired("CLOSING_CONFIRM", threshold, elapsed);
        markUnresponsive();
        bumpGeneration();
        sendControl(new SessionState(View.STANDBY));
        teardown();
    }

    /** BR6.12 ④: 묶인 연결의 마지막 기기 메시지부터 40초. 메시지마다 다시 걸지 않고 만기 때 남은 시간으로 다시 건다. */
    private void onDisconnectCheck() {
        if (status != SessionStatus.ACTIVE) {
            return;
        }
        Duration threshold = services.settings().disconnect();
        Instant last = link == null ? startAttemptAt : link.lastMessageAt();
        Duration elapsed = Duration.between(last, services.clock().now());
        if (elapsed.compareTo(threshold) < 0) {
            timers.arm(Kind.DISCONNECT, threshold.minus(elapsed));
            return;
        }
        Instant now = services.clock().now();
        Optional<ActivitySessionRow> row = services.transitions().transition(plan.sessionId(), SessionStatus.ACTIVE,
                SessionStatus.ABORTED, TransitionTable.changeFor(Trigger.DISCONNECT, null, now).withProviderClosedAt(now),
                SessionTransitions.Origin.REALTIME, plan.traceId());
        if (row.isEmpty()) {
            // J292: 진 끊김 시계는 공급자를 닫지 않는다 — 행이 CLOSING이면 CLOSING이 끊김보다 우선하고(BR6.12 ③) 공급자는
            // VOICE_SESSION_CLOSED · 30초 시한까지 열어 둔다(BR6.10 ④)
            syncFromDb();
            return;
        }
        status = SessionStatus.ABORTED;
        closeProvider();
        timeoutFired("DISCONNECT", threshold, elapsed);
        teardown();
    }

    private void armDisconnect() {
        Duration threshold = services.settings().disconnect();
        Instant last = link == null ? services.clock().now() : link.lastMessageAt();
        Duration elapsed = Duration.between(last, services.clock().now());
        timers.arm(Kind.DISCONNECT, elapsed.isNegative() ? threshold : threshold.minus(elapsed));
    }

    private void timeoutFired(String type, Duration threshold, Duration elapsed) {
        services.metrics().timeoutFired(type);
        services.events().log(EventCode.TIMEOUT_FIRED, EventLog.Component.SESSION_LIFECYCLE, plan.sessionId(),
                plan.traceId(), entry -> entry.attr(AttrKey.TYPE, type).attr(AttrKey.THRESHOLD_MS, threshold.toMillis())
                        .attr(AttrKey.ELAPSED_MS, Math.max(0L, elapsed.toMillis())));
    }

    // ------------------------------------------------------------------ 재전달 · 최상위 예외

    private void scheduleRetry(Message message) {
        if (message instanceof TimerFired fired) {
            pendingRetry.add(fired.kind());
        } else {
            pendingRetry.add(message);
        }
        timers.arm(Kind.REDELIVER, services.settings().timerTick());
    }

    private void redeliver() {
        List<Object> retry = new ArrayList<>(pendingRetry);
        pendingRetry.clear();
        for (Object item : retry) {
            if (terminated) {
                return;
            }
            try {
                if (item instanceof Kind kind) {
                    runTimer(kind);
                } else {
                    handle((Message) item);
                }
            } catch (DataAccessException | TransactionException e) {
                services.events().appError(EventLog.Component.SESSION_LIFECYCLE, e);
                pendingRetry.add(item);
            }
        }
        if (!pendingRetry.isEmpty()) {
            timers.arm(Kind.REDELIVER, services.settings().timerTick());
        }
    }

    /**
     * R3-6 · J126: 최상위 예외 → FAILED(SERVER_RESTART) 시도 → 정리. 전이가 실패하면 고아 세션 점검이 닫는다. 전이를 이기면 BR6.8대로
     * START_SESSION이 나갔고 연결이 살아 있을 때 CLOSE_TRANSPORT(FAILED) + STANDBY를 보낸다(F4 · J290). '재시작 제외'는 보낼 연결이 없는
     * 기동 정리(S3a) 경우이고, 실행기 예외는 연결이 살아 있을 수 있다. 전이를 이기지 못하면(승자 하나) 기기 명령 없이 정리한다.
     */
    private void failFromTopLevel() {
        try {
            if (!status.isTerminal()) {
                ActivitySessionRepository.MultiOriginTransition result = services.transitions().transitionFromAny(
                        plan.sessionId(), Trigger.SERVER_RESTART.origins(), SessionStatus.FAILED,
                        read -> TransitionTable.changeFor(Trigger.SERVER_RESTART, null, services.clock().now()),
                        SessionTransitions.Origin.REALTIME, plan.traceId());
                if (result.transitioned()) {
                    afterFailed();
                }
            }
        } catch (RuntimeException e) {
            services.events().appError(EventLog.Component.SESSION_LIFECYCLE, e);
        } finally {
            teardown();
        }
    }

    // ------------------------------------------------------------------ 전이 보조

    private Optional<ActivitySessionRow> transition(SessionStatus from, Trigger trigger, FailureCode code) {
        Optional<ActivitySessionRow> row = services.transitions().transition(plan.sessionId(), from, trigger.target(),
                TransitionTable.changeFor(trigger, code, services.clock().now()), SessionTransitions.Origin.REALTIME,
                plan.traceId());
        row.ifPresent(value -> status = value.status());
        return row;
    }

    /** 출발 상태가 둘 이상인 → FAILED(FOR UPDATE로 읽은 상태). 이기면 CLOSE_TRANSPORT(FAILED) 규칙과 정리를 한다. */
    private void multiOriginFail(Trigger trigger, FailureCode code, SessionTransitions.Origin origin) {
        ActivitySessionRepository.MultiOriginTransition result = services.transitions().transitionFromAny(
                plan.sessionId(), trigger.origins(), SessionStatus.FAILED,
                read -> TransitionTable.changeFor(trigger, code, services.clock().now()), origin, plan.traceId());
        if (!result.transitioned()) {
            syncFromDb();
            return;
        }
        status = SessionStatus.FAILED;
        afterFailed();
    }

    /** BR6.8: FAILED로 갈 때 START_SESSION이 나갔고 연결이 살아 있으면 CLOSE_TRANSPORT(FAILED) + SESSION_STATE{STANDBY}. */
    private void afterFailed() {
        afterFailed(CloseTransport.Reason.FAILED);
    }

    /**
     * BR6.8 · U4b BR1.5: FAILED로 갈 때 START_SESSION이 나갔고 연결이 살아 있으면 CLOSE_TRANSPORT(사유) + SESSION_STATE{STANDBY}. 시작 단계 동의
     * 철회는 사유가 CONSENT_WITHDRAWN이다.
     */
    private void afterFailed(CloseTransport.Reason reason) {
        status = SessionStatus.FAILED;
        bumpGeneration();
        if (startSent && link != null && link.isOpen()) {
            sendControl(new CloseTransport(reason));
            sendControl(new SessionState(View.STANDBY));
        }
        teardown();
    }

    /** 진 전이 뒤 DB의 지금 상태로 맞춘다. 종료 상태면 정리한다. */
    private void syncFromDb() {
        syncFromDb(null);
    }

    /**
     * 진 전이 뒤 DB의 지금 상태로 맞춘다. 종료 상태면 정리하고, 메모리와 다른 비종료 상태를 받아들였으면 그 상태의 시한 · 부수 효과를 한
     * 번 건다(F5 · J291).
     *
     * @param endRequestId CLOSING을 받아들일 때 END_REQUESTED에 남길 끝내기 요청 id(끝내기 처리 중이 아니면 null, J293)
     */
    private void syncFromDb(UUID endRequestId) {
        Optional<ActivitySessionRow> row = services.transitions().find(plan.sessionId());
        if (row.isEmpty()) {
            teardown();
            return;
        }
        SessionStatus before = status;
        status = row.get().status();
        if (status.isTerminal()) {
            teardown();
            return;
        }
        if (status != before) {
            adopt(row.get(), endRequestId);
        }
    }

    /**
     * F5 · J291: 살아 있는 실행기의 세션 행은 이 실행기만 전이한다(고아 점검 · 기동 정리는 실행기 없는 세션만 다룬다). 그래서 DB가 메모리보다
     * 앞선 비종료 상태면 커밋은 됐는데 응답을 잃은 이 실행기의 전이이고, 그 승자 몫은 아직 실행되지 않았다. 상태가 바뀔 때 한 번만 부르므로
     * 중복 실행이 없다.
     */
    private void adopt(ActivitySessionRow row, UUID endRequestId) {
        switch (status) {
            case DEVICE_ACK -> {
                // PREPARING → DEVICE_ACK(ACK): 그 사이 처리한 READY · session.started로 ACTIVE 조건을 다시 본다
                ackReceived = true;
                if (deviceUnavailable) {
                    failDeviceUnavailable();
                } else {
                    tryActivate();
                }
            }
            case ACTIVE -> enterActive();
            case CLOSING -> {
                closingReason = row.endReason();
                closingDetail = row.endDetail();
                enterClosing(
                        row.terminationRequestedAt() == null ? services.clock().now() : row.terminationRequestedAt(),
                        endRequestId);
            }
            default -> {
                // PREPARING으로 되돌아가는 일은 없다(종료 상태는 위에서 정리했다)
            }
        }
    }

    private void annotateQuietly(SessionStatus expected, SessionChange change) {
        try {
            services.transitions().annotate(plan.sessionId(), expected, change);
        } catch (RuntimeException e) {
            services.events().appError(EventLog.Component.SESSION_LIFECYCLE, e);
        }
    }

    private void markUnresponsive() {
        DeviceLink current = link;
        if (current != null) {
            services.deviceStatus().markUnresponsive(plan.deviceId(), current.connectionId());
        }
    }

    private boolean fromBoundConnection(UUID connectionId) {
        DeviceLink current = link;
        return current != null && current.connectionId().equals(connectionId);
    }

    private boolean sendControl(WsPayload payload) {
        if (payload instanceof SessionState state) {
            // U3-J23: 연결이 없어도 표시 상태는 기억한다(다음 HELLO_OK.session.view)
            view = state.view();
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
    private void remember(UUID opId, WsPayload payload) {
        if (pendingCommands.size() >= MAX_PENDING_COMMANDS) {
            UUID eldest = pendingCommands.keySet().iterator().next();
            pendingCommands.remove(eldest);
        }
        pendingCommands.put(opId, payload);
    }

    /** U3 세션 이벤트 한 줄(payload는 식별자 · 숫자 · 불리언뿐, 전사 · 원음 없음). */
    private void putEvent(String type, String payloadJson, Instant at) {
        services.recorder().putEvent(new SessionEvent(UUID.randomUUID(), plan.sessionId(), type, null, payloadJson, at));
    }

    private ClosingSequence.Device closingDevice() {
        return new ClosingSequence.Device() {
            @Override
            public boolean stopOutput(StopOutput.Reason reason) {
                return sendControl(new StopOutput(reason));
            }

            @Override
            public boolean sessionState(View view) {
                return sendControl(new SessionState(view));
            }

            @Override
            public boolean playApproved(UUID playbackId, String contentCode, UUID contentVersionId) {
                return sendControl(new PlayApprovedContent(playbackId, contentCode, contentVersionId));
            }

            @Override
            public boolean closeTransport(CloseTransport.Reason reason) {
                return sendControl(new CloseTransport(reason));
            }
        };
    }

    private void bumpGeneration() {
        outputGeneration.incrementAndGet();
        DeviceLink current = link;
        if (current != null) {
            current.clearAudio();
        }
    }

    private void closeProvider() {
        providerClosing = true;
        ProviderConnector.Attempt attempt = providerAttempt;
        if (attempt != null) {
            attempt.cancel();
        }
        ProviderSession session = providerSession;
        if (session != null && session.isOpen()) {
            session.close(services.requests().sessionClose());
        }
    }

    /**
     * 종료 정리(PD-1 생명주기 3): 입력 수락 끝 · 공급자 정리 · 구간 버림 · 열린 행 닫기 · 기록기 마지막 플러시 · 원음 기록 닫기 · 레지스트리 제거.
     * 리뷰 반영 7: 단계마다 예외를 따로 받아(앱 오류 한 줄) 앞 단계가 실패해도 뒤 단계 — 특히 원음 파일 핸들과 메타의 ended_at — 를 마친다.
     * 리뷰 반영 4: 처리하지 못하고 남은 연결 교체(LinkReplaced)는 그 새 연결을 대기로 돌려보낸다.
     */
    private void teardown() {
        if (terminated) {
            return;
        }
        terminated = true;
        outputGeneration.incrementAndGet();
        Instant now = services.clock().now();
        try {
            quietly(timers::cancelAll);
            quietly(relay::stop);
            quietly(this::closeProvider);
            quietly(() -> {
                ProviderSession session = providerSession;
                if (session != null) {
                    session.close();
                }
            });
            quietly(segment::reset);
            quietly(playback::clear);
            quietly(() -> transcripts.closeAll(now));
            quietly(() -> services.latency().sessionEnded(plan.sessionId()));
            quietly(() -> services.recorder().finish(plan.sessionId()));
            quietly(() -> unbind(link));
            pendingCommands.clear();
            quietly(() -> {
                if (recording != null) {
                    recording.close(now);
                }
            });
        } finally {
            for (Message left : mailbox.closeAndRelease()) {
                if (left instanceof LinkReplaced replaced) {
                    quietly(() -> standDown(replaced.link(), plan.sessionId(), status, replaced.resumed()));
                }
            }
            onTerminated.run();
        }
    }

    /**
     * 이 세션이 묶은 연결이면 묶음을 푼다. 같은 기기의 새 세션이 이미 그 연결을 묶었으면 건드리지 않는다 — 풀면 새 세션의 입력 프레임 경로가
     * 끊긴다(2차 리뷰 5 · 9 조사 중 찾음). 확인과 풀기는 연결의 비교 후 풀기 한 번이다(3차 리뷰 4: 확인한 뒤 풀면 그 사이 새 세션이 묶은 것을
     * 지운다).
     */
    private void unbind(DeviceLink target) {
        if (target != null) {
            target.unbindSessionIf(plan.sessionId());
        }
    }

    /** 정리 단계 하나: 예외는 앱 오류 한 줄로 남기고 다음 단계로 간다. */
    private void quietly(Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            services.events().appError(EventLog.Component.SESSION_LIFECYCLE, e);
        }
    }

    @Override
    public String toString() {
        return "SessionExecutor[sessionId=" + plan.sessionId() + ", status=" + status + "]";
    }
}
