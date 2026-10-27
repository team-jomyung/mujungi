package kr.mujoong.realtime.session;

import kr.mujoong.contract.context.ConversationGuide;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.EndRequestReason;
import kr.mujoong.contract.status.FailureCode;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.ws.message.CloseTransport;
import kr.mujoong.contract.ws.message.DeviceState;
import kr.mujoong.contract.ws.message.OutputBegin;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.SessionState;
import kr.mujoong.contract.ws.message.StartSession;
import kr.mujoong.contract.ws.message.View;
import kr.mujoong.realtime.common.collect.BoundedCollections;
import kr.mujoong.realtime.common.scaling.SingleInstanceAssumption;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.config.RealtimeTimingProperties;
import kr.mujoong.realtime.dialog.DialogInstructions;
import kr.mujoong.realtime.dialog.InstructionTemplates;
import kr.mujoong.realtime.dialog.JoinTracker;
import kr.mujoong.realtime.dialog.StopIntentJudge;
import kr.mujoong.realtime.judgment.JudgeOutcome;
import kr.mujoong.realtime.judgment.JudgmentOutcomeKind;
import kr.mujoong.realtime.judgment.JudgmentOwner;
import kr.mujoong.realtime.judgment.JudgmentPoint;
import kr.mujoong.realtime.judgment.JudgmentTrigger;
import kr.mujoong.realtime.judgment.JudgmentTurn;
import kr.mujoong.realtime.judgment.NextAction;
import kr.mujoong.realtime.gate.ApprovedPlayback;
import kr.mujoong.realtime.gate.ClosingSequence;
import kr.mujoong.realtime.gate.GateDecision;
import kr.mujoong.realtime.gate.DiscardDetail;
import kr.mujoong.realtime.gate.OutputGate;
import kr.mujoong.realtime.gate.OutputGeneration;
import kr.mujoong.realtime.gate.SegmentBuffer;
import kr.mujoong.realtime.gate.SeedDictionary;
import kr.mujoong.realtime.gate.TimedOutputBuffer;
import kr.mujoong.realtime.gateway.DeviceLink;
import kr.mujoong.realtime.gateway.DeviceStatusPort;
import kr.mujoong.realtime.gateway.OutputBundle;
import kr.mujoong.realtime.guardrail.GuardrailRequest;
import kr.mujoong.realtime.guardrail.GuardrailSupport;
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
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import static kr.mujoong.realtime.session.SessionContext.START_PHASE;

/**
 * 세션 하나의 직렬 실행기.
 *
 * <p>세션 상태 · 시계 · 구간 버퍼 · 게이트 판정은 이 실행기 한 스레드만 바꾼다(세션 안 잠금 없음). 반복마다 안전 편지함을 먼저 비운다. 전이는
 * {@link SessionTransitions}의 짧은 트랜잭션이고 조건부 UPDATE의 승자만 부수 효과(기기 명령 · 시계)를 낸다. 시계 만기는 세대가 맞을 때만,
 * 경과를 다시 재서(지연 재무장) 판정한다. 전이가 DB 장애로 실패하면 그 메시지를 {@code mujoong.session.timer-tick} 뒤 다시 처리하고,
 * 다시 처리에서 커밋은 됐는데 응답을 잃은 전이를 DB에서 받아들이면 그 상태의 시한 · 부수 효과를 한 번 건다. 그 밖의 예외가 반복
 * 최상위까지 오면 APP_ERROR 한 줄을 남기고 FAILED(SERVER_RESTART)를 시도해 이기면 기기 명령을 낸 뒤 정리한다. 출력
 * 세대 번호는 STOP_OUTPUT · CLOSING 진입 · 종료 전이 · Core 취소 · 연결 교체 · 끊김마다 오르고, 봉인한 구간과 송신 프레임마다
 * 비교한다(SD-17). 실행기 스레드는 공급자 쪽 생산자로서 자기 편지함을 기다리지 않는다.
 *
 * <p>U3(docs/units/u3/design.md 4 · 5절): 같은 기기의 새 연결이 HELLO_OK를 받으면 {@link LinkReplaced}로 세션을 다시 묶고(끊김 구간 닫기 ·
 * SESSION_STATE 재송신 · ACK를 못 받은 opId 명령 재전달, 이어가기가 아니면 START_SESSION부터), 묶인 연결이 끊기면 끊김 구간을
 * 연다. T19 표시 상태 {@link #view}를 기억해 HELLO_OK.session에 싣고, DEVICE_STATE의 마이크 끊김 · 플레이어 오류와
 * PLAYBACK_MARK(STOPPED · LOCAL_VOICE_ACTIVITY) 끼어들기를 세션 이벤트로 남긴다. 세션 전이 표는 U1 그대로다.
 */
@SingleInstanceAssumption(si = {"SI-2", "SI-9"},
        reason = "세션 상태 · 편지함 · 출력 세대와 세션당 공급자 연결을 이 프로세스의 실행기 하나가 쥐어 세션을 다른 인스턴스로 옮길 수 없다")
public final class SessionExecutor {

    /**
     * 세션 공통 설정(RealtimeProperties에서 읽는다).
     *
     * @param inputTranscriptQuiet 입력 전사 조각이 이만큼 오지 않으면 그 묶음을 확정 전사로 본다({@code mujoong.timing.provider.input-transcript-quiet}, 설계 개정 A7)
     * @param newSpeechMargin 지시 뒤 새 말의 전사 시작에 더하는 여유({@code mujoong.timing.provider.new-speech-margin}, 설계 개정 A4)
     * @param instructionWait 지시를 보낸 뒤 새 말이 시작하기를 기다리는 시한({@code mujoong.timing.judgment.response-ready}, 지시 송신 시각부터, 설계 개정 A3)
     * @param joinSelfStartWait 참여 수락 때 보관한 응답이 비어 있으면 첫 응답 지시 전에 모델이 스스로 말을 시작하기를 기다리는 시간
     *     ({@code mujoong.timing.provider.join-self-start-wait}). 0이면 기다리지 않고 바로 지시한다
     * @param responseStallWait 자유대화 응답 정체 안전망 대기({@code mujoong.timing.provider.response-stall-wait}, JIRA-270). 0이면 끈다
     */
    public record Settings(
            Duration deviceAckTimeout,
            Duration closingConfirm,
            Duration disconnect,
            Duration segmentQuiet,
            Duration playbackExpiry,
            Duration timerTick,
            int inputQueueFrames,
            int mailboxCapacity,
            int uncertainBelowPercent,
            Lifecycle lifecycle,
            Duration inputTranscriptQuiet,
            Duration newSpeechMargin,
            Duration instructionWait,
            Duration joinSelfStartWait,
            Duration responseStallWait) {

        /** 응답 정체 안전망 없이(0) 만든다(안전망 전 모양, 시험 · 기존 호출자). */
        public Settings(Duration deviceAckTimeout, Duration closingConfirm, Duration disconnect, Duration segmentQuiet,
                Duration playbackExpiry, Duration timerTick, int inputQueueFrames, int mailboxCapacity,
                int uncertainBelowPercent, Lifecycle lifecycle, Duration inputTranscriptQuiet, Duration newSpeechMargin,
                Duration instructionWait, Duration joinSelfStartWait) {
            this(deviceAckTimeout, closingConfirm, disconnect, segmentQuiet, playbackExpiry, timerTick, inputQueueFrames,
                    mailboxCapacity, uncertainBelowPercent, lifecycle, inputTranscriptQuiet, newSpeechMargin, instructionWait,
                    joinSelfStartWait, Duration.ZERO);
        }

        /**
         * U4b 세션 생애 시간 값(contracts/timing.md, 모두 설정 키에서 온다).
         *
         * @param joinWait 처음 시작 안내 뒤 참여 창(join-wait)
         * @param joinWaitAfterReprompt 재안내 · 되묻기 · 대안 뒤 참여 창(join-wait-after-reprompt)
         * @param silenceWarning 공백 경고 누적(silence-warning)
         * @param silenceEnd 공백 종료 누적(silence-end)
         * @param recordSettle 종료 뒤 정산 창 상한(record-settle)
         * @param gapDisplay 마지막 기기 메시지 뒤 기술 일시정지(device.gap-display)
         */
        public record Lifecycle(Duration joinWait, Duration joinWaitAfterReprompt, Duration silenceWarning,
                Duration silenceEnd, Duration recordSettle, Duration gapDisplay) {

            public Lifecycle {
                Objects.requireNonNull(joinWait, "joinWait");
                Objects.requireNonNull(joinWaitAfterReprompt, "joinWaitAfterReprompt");
                Objects.requireNonNull(silenceWarning, "silenceWarning");
                Objects.requireNonNull(silenceEnd, "silenceEnd");
                Objects.requireNonNull(recordSettle, "recordSettle");
                Objects.requireNonNull(gapDisplay, "gapDisplay");
            }

            public static Lifecycle from(RealtimeProperties properties) {
                RealtimeTimingProperties.SessionTiming session =properties.timing().session();
                return new Lifecycle(session.joinWait(), session.joinWaitAfterReprompt(), session.silenceWarning(),
                        session.silenceEnd(), session.recordSettle(),
                        properties.timing().device().gapDisplay());
            }
        }

        public Settings {
            Objects.requireNonNull(lifecycle, "lifecycle");
            Objects.requireNonNull(inputTranscriptQuiet, "inputTranscriptQuiet");
            Objects.requireNonNull(newSpeechMargin, "newSpeechMargin");
            Objects.requireNonNull(instructionWait, "instructionWait");
            if (Objects.requireNonNull(joinSelfStartWait, "joinSelfStartWait").isNegative()) {
                throw new IllegalArgumentException("joinSelfStartWait는 음수일 수 없다");
            }
            if (Objects.requireNonNull(responseStallWait, "responseStallWait").isNegative()) {
                throw new IllegalArgumentException("responseStallWait는 음수일 수 없다");
            }
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
                    properties.limits().sessionControlQueue(), properties.transcript().uncertainBelowPercent(),
                    Lifecycle.from(properties), properties.timing().provider().inputTranscriptQuiet(),
                    properties.timing().provider().newSpeechMargin(), properties.timing().judgment().responseReady(),
                    properties.timing().provider().joinSelfStartWait(), properties.timing().provider().responseStallWait());
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
            AudioRecordings recordings,
            SessionSettlement settlement,
            PracticeSessionExtension.Factory practice,
            JudgmentSupport judgment,
            GuardrailSupport guardrail,
            NoticeAssetCatalog notices) {

        public Services(SessionTransitions transitions, Function<UUID, Optional<? extends DeviceLink>> deviceLinks,
                DeviceStatusPort deviceStatus, ProviderConnector connector, ProviderRequestFactory requests,
                OutputGate gate, AsyncRecorder recorder, LatencyRecorder latency, TimerClocks clocks,
                TaskLauncher launcher, EventLogger events, RealtimeMetrics metrics, MujoongClock clock, Settings settings,
                SessionGapPort gaps, AudioRecordings recordings, SessionSettlement settlement,
                PracticeSessionExtension.Factory practice, JudgmentSupport judgment, GuardrailSupport guardrail) {
            this(transitions, deviceLinks, deviceStatus, connector, requests, gate, recorder, latency, clocks, launcher,
                    events, metrics, clock, settings, gaps, recordings, settlement, practice, judgment, guardrail,
                    new NoticeAssetCatalog());
        }

        /** 제브 장치 없음(U13 전 모양, 제브 단계를 건너뛰고 지금 흐름이 그대로 돈다 — {@link GuardrailSupport#DISABLED}). */
        public Services(SessionTransitions transitions, Function<UUID, Optional<? extends DeviceLink>> deviceLinks,
                DeviceStatusPort deviceStatus, ProviderConnector connector, ProviderRequestFactory requests,
                OutputGate gate, AsyncRecorder recorder, LatencyRecorder latency, TimerClocks clocks,
                TaskLauncher launcher, EventLogger events, RealtimeMetrics metrics, MujoongClock clock, Settings settings,
                SessionGapPort gaps, AudioRecordings recordings, SessionSettlement settlement,
                PracticeSessionExtension.Factory practice, JudgmentSupport judgment) {
            this(transitions, deviceLinks, deviceStatus, connector, requests, gate, recorder, latency, clocks, launcher,
                    events, metrics, clock, settings, gaps, recordings, settlement, practice, judgment,
                    GuardrailSupport.DISABLED);
        }

        /** 판정 장치 없음(U11 전 모양, 판정 턴을 열지 않는다). */
        public Services(SessionTransitions transitions, Function<UUID, Optional<? extends DeviceLink>> deviceLinks,
                DeviceStatusPort deviceStatus, ProviderConnector connector, ProviderRequestFactory requests,
                OutputGate gate, AsyncRecorder recorder, LatencyRecorder latency, TimerClocks clocks,
                TaskLauncher launcher, EventLogger events, RealtimeMetrics metrics, MujoongClock clock, Settings settings,
                SessionGapPort gaps, AudioRecordings recordings, SessionSettlement settlement,
                PracticeSessionExtension.Factory practice) {
            this(transitions, deviceLinks, deviceStatus, connector, requests, gate, recorder, latency, clocks, launcher,
                    events, metrics, clock, settings, gaps, recordings, settlement, practice, JudgmentSupport.NONE);
        }

        /** U1 · U3 모양(정산 없음 — 종료 상태로 가면 정산 창 없이 곧바로 정리한다, 연습 확장점은 무동작). */
        public Services(SessionTransitions transitions, Function<UUID, Optional<? extends DeviceLink>> deviceLinks,
                DeviceStatusPort deviceStatus, ProviderConnector connector, ProviderRequestFactory requests,
                OutputGate gate, AsyncRecorder recorder, LatencyRecorder latency, TimerClocks clocks,
                TaskLauncher launcher, EventLogger events, RealtimeMetrics metrics, MujoongClock clock, Settings settings,
                SessionGapPort gaps, AudioRecordings recordings) {
            this(transitions, deviceLinks, deviceStatus, connector, requests, gate, recorder, latency, clocks, launcher,
                    events, metrics, clock, settings, gaps, recordings, SessionSettlement.NONE);
        }

        /** 연습 확장점은 무동작(U5b 전). */
        public Services(SessionTransitions transitions, Function<UUID, Optional<? extends DeviceLink>> deviceLinks,
                DeviceStatusPort deviceStatus, ProviderConnector connector, ProviderRequestFactory requests,
                OutputGate gate, AsyncRecorder recorder, LatencyRecorder latency, TimerClocks clocks,
                TaskLauncher launcher, EventLogger events, RealtimeMetrics metrics, MujoongClock clock, Settings settings,
                SessionGapPort gaps, AudioRecordings recordings, SessionSettlement settlement) {
            this(transitions, deviceLinks, deviceStatus, connector, requests, gate, recorder, latency, clocks, launcher,
                    events, metrics, clock, settings, gaps, recordings, settlement, NoOpPracticeSessionExtension.FACTORY);
        }

        public Services {
            Objects.requireNonNull(settlement, "settlement");
            Objects.requireNonNull(practice, "practice");
            Objects.requireNonNull(judgment, "judgment");
            Objects.requireNonNull(guardrail, "guardrail");
            Objects.requireNonNull(notices, "notices");
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

    /**
     * 고정한 승인 문구 하나(contentCode · 버전 · 원문). 원문은 승인 문구 말풍선 행에 쓰고 로그 · 문자열 표현에 넣지 않는다. 원문을 모르면
     * null이고 그때는 말풍선 행을 남기지 않는다.
     */
    public record ApprovedItem(String contentCode, UUID contentVersionId, String text) {

        public ApprovedItem(String contentCode, UUID contentVersionId) {
            this(contentCode, contentVersionId, null);
        }

        @Override
        public String toString() {
            return "ApprovedItem[contentCode=" + contentCode + "]";
        }
    }

    /** U4b 승인 문구 코드(시작 패키지 approvedContent의 선택 키, 설계 8절). */
    public static final String INTRO_FREE_TALK = "INTRO_FREE_TALK";
    public static final String INTRO_PRACTICE = "INTRO_PRACTICE";
    public static final String RE_PROMPT = "RE_PROMPT";
    public static final String DECLINE_ALTERNATIVE = "DECLINE_ALTERNATIVE";
    public static final String SILENCE_WARNING = "SILENCE_WARNING";

    /**
     * 시작 명령이 고정한 세션 패키지 중 실행기가 쓰는 부분. U3: 원음 기록 동의(consentSnapshot.rawAudio)와 세션의 종료 표현 스냅숏
     * (종료 의도 판정). U4b: 활동 종류(시작 안내 선택 · 연습 확장점), 승인 문구 전체(선택 코드 포함, 원문은 말풍선용),
     * 시작 패키지의 연습 조각(JSON 원문, 확장점에만 넘긴다 · 없으면 null).
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
            List<String> stopExpressions,
            ActivityType activityType,
            Map<String, ApprovedItem> approved,
            String practiceJson,
            UUID guardianId,
            Map<String, String> approvedSpeech, ConversationGuide conversationGuide) {
        public Plan(UUID sessionId, UUID deviceId, String traceId, ChildContext childContext,
                List<StartSession.ApprovedAudio> approvedAudio, ApprovedItem closing, ApprovedItem waitingFiller,
                boolean rawAudioConsent, List<String> stopExpressions, ActivityType activityType,
                Map<String, ApprovedItem> approved, String practiceJson, UUID guardianId, Map<String,String> approvedSpeech) {
            this(sessionId,deviceId,traceId,childContext,approvedAudio,closing,waitingFiller,rawAudioConsent,stopExpressions,activityType,approved,practiceJson,guardianId,approvedSpeech,null);
        }

        /**
         * U12 이전 모양(승인 발화 문장 지도 없음 — 연습 시작 패키지의 approvedSpeech를 확장에 싣지 않는다). 기존 호출처 · 시험은 이 모양을 그대로
         * 쓴다.
         */
        public Plan(UUID sessionId, UUID deviceId, String traceId, ChildContext childContext,
                List<StartSession.ApprovedAudio> approvedAudio, ApprovedItem closing, ApprovedItem waitingFiller,
                boolean rawAudioConsent, List<String> stopExpressions, ActivityType activityType,
                Map<String, ApprovedItem> approved, String practiceJson, UUID guardianId) {
            this(sessionId, deviceId, traceId, childContext, approvedAudio, closing, waitingFiller, rawAudioConsent,
                    stopExpressions, activityType, approved, practiceJson, guardianId, Map.of());
        }

        /** U4b 모양(보호자 id 없음 — 판정 기록을 남기지 않는다). */
        public Plan(UUID sessionId, UUID deviceId, String traceId, ChildContext childContext,
                List<StartSession.ApprovedAudio> approvedAudio, ApprovedItem closing, ApprovedItem waitingFiller,
                boolean rawAudioConsent, List<String> stopExpressions, ActivityType activityType,
                Map<String, ApprovedItem> approved, String practiceJson) {
            this(sessionId, deviceId, traceId, childContext, approvedAudio, closing, waitingFiller, rawAudioConsent,
                    stopExpressions, activityType, approved, practiceJson, null, Map.of());
        }

        public Plan {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(deviceId, "deviceId");
            Objects.requireNonNull(childContext, "childContext");
            approvedAudio = List.copyOf(approvedAudio);
            stopExpressions = stopExpressions == null ? List.of() : List.copyOf(stopExpressions);
            activityType = activityType == null ? ActivityType.FREE_TALK : activityType;
            Map<String, ApprovedItem> items = new LinkedHashMap<>();
            if (approved != null) {
                items.putAll(approved);
            }
            if (closing != null) {
                items.putIfAbsent(closing.contentCode(), closing);
            }
            if (waitingFiller != null) {
                items.putIfAbsent(waitingFiller.contentCode(), waitingFiller);
            }
            approved = Map.copyOf(items);
            approvedSpeech = approvedSpeech == null ? Map.of() : Map.copyOf(approvedSpeech);
        }

        /** U1 · U3 모양(자유대화, 승인 문구는 마무리 · 대기 문구뿐, 연습 조각 없음). */
        public Plan(UUID sessionId, UUID deviceId, String traceId, ChildContext childContext,
                List<StartSession.ApprovedAudio> approvedAudio, ApprovedItem closing, ApprovedItem waitingFiller,
                boolean rawAudioConsent, List<String> stopExpressions) {
            this(sessionId, deviceId, traceId, childContext, approvedAudio, closing, waitingFiller, rawAudioConsent,
                    stopExpressions, ActivityType.FREE_TALK, Map.of(), null, null, Map.of());
        }

        /** 그 코드의 승인 문구(패키지에 없으면 빈 값). */
        public Optional<ApprovedItem> approvedItem(String contentCode) {
            return Optional.ofNullable(approved.get(contentCode));
        }

        @Override
        public String toString() {
            return "Plan[sessionId=" + sessionId + ", activityType=" + activityType + "]";
        }
    }

    /** 아이의 명확한 종료 의도(payload는 근거만, 원문 없음). CLOSING(CHILD_STOP) 전이는 U4. */
    static final String EVENT_CHILD_STOP_INTENT = "CHILD_STOP_INTENT";
    /** 종료일 수 있으나 불명확(확인 질문 재생은 U4). */
    static final String EVENT_CHILD_STOP_UNCLEAR = "CHILD_STOP_UNCLEAR";
    /** 리뷰 반영 3: 종료 의도 뒤 아이가 종료가 아닌 말로 대화를 이어 가 출력 막힘을 풀었다(오판 복구). */
    static final String EVENT_CHILD_STOP_LIFTED = "CHILD_STOP_LIFTED";
    /** U4b 세션 사건 종류(payload는 코드 · 횟수 · 분류 이름 · 시각뿐, entities SessionEvent). */
    static final String EVENT_APPROVED_CONTENT_MISSING = "APPROVED_CONTENT_MISSING";
    static final String EVENT_PRACTICE_EXTENSION_FAILED = "PRACTICE_EXTENSION_FAILED";
    /** ACTIVE 중 공급자 끊김의 end_detail. */
    static final String END_DETAIL_PROVIDER_LOST = "PROVIDER_LOST";
    /** 취소 · 교정 · 재생 위치를 기억하는 응답 · 재생 수의 상한(오래된 것부터 잊는다). */
    static final int MAX_TRACKED = 32;
    private static final int MAX_PERCENT = 100;

    /** 구역들이 함께 읽고 쓰는 세션 상태(불변 참조 · 연결 · 시작 확인 · 마무리 · 공급자 연결 · 정산). */
    private final SessionContext ctx;
    /** 통과한 구간 없이 연달아 보낼 수 있는 게이트 교정 지시 수(안전 상한, 시간 값이 아니다). */
    private static final int MAX_CORRECTIONS_IN_A_ROW = 2;
    /** U12: 실행부가 만드는 역할 발화 슬롯 · 알림 발화의 재생 증거 표지용 장면 식별자(대본 장면을 모르므로 고정 값, 제브 요청 · 기록에 쓰이지 않는다). */
    private static final UUID NOTICE_SCENARIO_ID = new UUID(0L, 0L);
    /** 지시마다 올리는 번호(공급자가 확인 사건의 client_event_id로 되돌려 주는 event_id를 만든다). */
    private long instructionSeq;
    /** 가장 최근 지시가 새 말을 못 들려주고 시한이 지났을 때(NEW_SPEECH_NOT_OBSERVED) 부를 일(연습 발화는 확장에 실패를 알린다). */
    private Runnable instructionNotHeard;
    /** 게이트를 통과한 구간 없이 연달아 보낸 교정 지시 수(교정 지시가 곧 발화라 모델이 계속 어기면 끝없이 이어지는 것을 막는다). */
    private int correctionsInARow;
    /** 참여 수락 뒤 모델이 스스로 첫 응답을 시작하기를 기다리는 세대(-1이면 기다리지 않는다). */
    private long joinSelfStartGeneration = -1L;
    /** JIRA-270: 미루는 말 판단에 쓰는 응답 전사 끝부분 길이(글자 수). */
    private static final int RESPONSE_TAIL_CHARS = 64;
    /** JIRA-270: 이번 아이 턴에서 응답 정체 이어 말하기 지시를 이미 보냈는가(아이의 새 확정 전사가 지운다). 턴마다 한 번만 보낸다. */
    private boolean stallNudgeUsed;
    /** JIRA-270: 지금 받는 응답의 전사 끝부분(미루는 말 판단용, 원문은 로그에 남기지 않는다). 응답이 끝나거나 세대가 오르면 비운다. */
    private final StringBuilder responseTail = new StringBuilder();
    /** JIRA-261: 다음 지시를 끊김 확인 뒤 조각 없는 세대에서 보낸다(첫 조각을 새 응답으로 받는다). {@link #onJoinSelfStartExpired}만 켠다. */
    private boolean instructionAfterQuietGap;
    /** 지난 편지함 처리 끝의 출력 보류 상태(true → false가 되면 세대를 올린다). */
    private boolean lastHeld;
    /** 공급자 리더가 일반 편지함에 넣었고 실행기가 아직 처리하지 않은 출력 조각 수(끊김 만기가 쌓인 조각을 앞지르지 못하게 한다). */
    private final java.util.concurrent.atomic.AtomicInteger pendingOutputChunks =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 설계 개정 A7: 입력 전사 조각을 묶는 중인 발화(공급자는 전사 완료 사건을 주지 않는다). */
    private final StringBuilder inputGroup = new StringBuilder();
    private String inputGroupId;
    private boolean inputGroupDuringIntroduction;
    private long inputGroupSeq;
    private final List<Object> pendingRetry = new ArrayList<>();

    /** 기기 표식 · 끝내기 구역 협력 객체(실행기 스레드 전용). */
    private final EndingFlow ending;
    /** 참여 확인 구역 협력 객체(참여 판단 상태를 쥔다, 실행기 스레드 전용). */
    private final JoinFlow joinFlow;
    /** 시계 구역 협력 객체(실행기 스레드 전용). */
    private final SessionClocks clocks;
    /** 공백 시계 · 기술 일시정지 · 진행 조회 협력 객체(진행 조회 값만 다른 스레드가 읽는다). */
    private final IdleClock idle;
    /** U11 판정 턴 협력 객체(열린 턴 · 최근 전사 · 기록, 실행기 스레드 전용). */
    private final JudgmentCoordinator judgment;
    /** U13 제브 게이트 협력 객체(검사 상태 · 검사표 · 대기열 · 턴 재지시, 실행기 스레드 전용). 여기에는 호출 지점과 아래 카운터만 둔다. */
    private final GuardrailCoordinator guardrail;
    private final TimedOutputBuffer timedOutput = new TimedOutputBuffer();
    /** 입력 묶음을 처음 연 횟수(단조 증가). 검사 요청 뒤 늘었으면 아이가 새로 말을 시작한 것이다(같은 묶음의 꼬리 조각은 세지 않는다). */
    private long inputGroupOpens;
    /** U11 W2: 지금 처리 중인 아이 확정 전사의 대화 기록 순번(판정 요청 · 기록용, 원문 아님). */
    private int currentTranscriptSeq;
    /** 확장이 요청한 '생각 중' 유지(닫힐 때 듣는 중으로 되돌리지 않는다). */
    private boolean practiceThinking;
    /** 종료 의도 판정기(세션 스냅숏의 종료 표현). */
    private final StopIntentJudge stopJudge;
    /** 지금 편지함 메시지를 처리하는 스레드(실행 스레드 또는 시험의 step 호출 스레드). 공급자 리스너가 이 스레드면 기다리지 않는다. */
    private volatile Thread processing;
    private Thread thread;
    /** 동의 철회 뒤 버린 아이 확정 전사 수(사건 payload는 건수만). */
    private int droppedAfterConsent;
    /** U4b 연습 확장점(PRACTICE 세션만, 예외가 나면 null로 두고 자유대화처럼 잇는다). */
    private PracticeSessionExtension practice;
    /** 안내 경계 이전에 열린 발화의 꼬리는 조용 경계까지 참여 입력으로 재사용하지 않는다. */
    private boolean participationInputDraining;
    /** 수락을 일으킨 참여 대답을 대화 행으로 남길 때 쓰는 항목 키(세션에서 한 번만 쓴다). */
    private static final String ACCEPTED_REPLY_ITEM = "join-accepted-reply";
    /** 이번 참여 창에서 이미 사유 코드를 남긴 탈락 사유(창이 열리면 비운다). */
    /**
     * 전사 먼저 방출 경로(자유대화): 전사 조각이 도착하면 음성이 다 오길 기다리지 않고 바로 검사하고, 통과한 조각의 끝 위치까지 음성을 도착하는 대로
     * 내보낸다. 연습 흐름이 출력을 쥔 응답은 기존 방식(음성이 다 온 단위만 검사)을 쓴다.
     */
    private boolean progressiveResponse;
    /** 검사 중인 전사 단위 → 그 전사가 끝나는 PCM 위치(통과하면 이 위치까지 음성을 낸다). 비교는 객체 동일성이다. */
    private final java.util.IdentityHashMap<SegmentBuffer.Sealed, Long> textUnitEnds = new java.util.IdentityHashMap<>();
    /** 통과한 단위들의 끝 위치 중 가장 먼 곳(음성은 여기까지만 낸다). */
    private long approvedEndByte;
    private final StringBuilder progressiveSubtitle = new StringBuilder();
    /** 이 응답의 첫 음성 묶음을 이미 보냈는가(지연 기록은 응답의 첫 묶음만 쥔다). */
    private boolean progressiveSoundStarted;
    /** 시간 정보가 있는 출력 전사를 이 세션에서 한 번이라도 받았는가(공급자가 시간 정보를 주는지 알아보는 표지). */
    private boolean timedTranscriptsSeen;
    /** 진단: 아이 입력 전사 조각을 마지막으로 받은 시각(기기로 보내는 음성이 아이가 말하기 전에 이미 끝난 응답의 것인지 가린다). */
    private Instant lastChildInputAt;
    /** 진단: 단위별 경로에 공급자 음성(무음 포함)이 마지막으로 들어온 시각. */
    private Instant timedAudioLastAt;
    /**
     * 참여 확인 중(안내가 끝난 뒤) 공급자가 스스로 시작한 응답을 버리지 않고 보관한 것. 참여가 수락되면 이것을 첫 응답으로 다시 흘려 넣고, 수락 지시를 따로 보내지
     * 않는다(GPT-Live는 아이 말이 끝나면 스스로 응답을 시작하므로, 보관하지 않으면 첫 발화에 대한 응답이 사라진다).
     */
    private final java.util.ArrayList<HeldOutput> joinHeldOutput = new java.util.ArrayList<>();
    /** 진단(D): 지금 출력 세대에서 보류 · 낡은 꼬리로 버린 조각을 봤는가(세대를 올릴 때 사유 코드로 한 번씩 남긴다, 원문 없음). */
    private boolean discardedHeldSeen;
    private boolean discardedStaleSeen;
    /** 진단(D): 마지막 지시 뒤 받은 출력 조각 수(새 말이 관측되지 않았을 때 공급자가 침묵했는지 서버가 버렸는지 가른다). */
    private int chunksSinceInstruction;
    private long joinHeldClockBase = -1L;
    private int joinHeldBytes;
    private int joinHeldChars;
    private boolean joinHeldOverflow;
    private boolean joinWindowOpened;

    /** 보관한 공급자 출력 한 조각(전사 · 음성 · 무음). */
    private record HeldOutput(String transcript, long startMs, long endMs, byte[] audio, int silenceBytes) { }
    private boolean firstUnitLogged;
    /** 응답은 끝났지만 검사 중인 단위가 남아 단위 버퍼를 아직 비우지 못했다. */
    private boolean timedOutputResetPending;
    private final java.util.EnumSet<ParticipationInputBoundary.Verdict> loggedParticipationDrops =
            java.util.EnumSet.noneOf(ParticipationInputBoundary.Verdict.class);
    private final ParticipationInputBoundary participationInputs = new ParticipationInputBoundary();
    /** 안전 편지함의 완료 표식이 이미 수신한 일반 전사/음성을 앞질러도 경계가 뒤로 가지 않는다. */
    private final java.util.concurrent.atomic.AtomicLong receivedProviderAudioBytes = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong receivedInputEndMs = new java.util.concurrent.atomic.AtomicLong(-1L);
    private final GreetingHandoff greetingHandoff = new GreetingHandoff();
    /** 확장점 예외가 났다(정산 시점을 건너뛰고 practice는 null). */
    private boolean practiceFailed;
    /** 확장점이 공급자 출력을 막았다. */
    private boolean practiceHold;
    /** 확장점 시계(이름 → 예약). 기술 일시정지 동안 남은 시간만 들고 멈춘다. */
    private final Map<String, PracticeTimer> practiceTimers = new HashMap<>();
    private long practiceTimerGeneration;
    /** 확장점이 요청한 재생(playbackId → 승인 문구 코드). 끝 표식이 PLAYBACK_DONE이 된다. */
    private final Map<UUID, String> practicePlaybacks = new HashMap<>();
    private volatile kr.mujoong.realtime.voice.PracticeSpeech pendingPracticeSpeech;
    private record DeferredPracticeInstruction(OutputGeneration.BumpReason reason, ProviderSendQueue.Job job,
            UUID speechId, Instant deadline) {}
    private DeferredPracticeInstruction deferredPracticeInstruction;
    private boolean practiceCollecting;
    private final Set<UUID> practicePlaybackStarted = new HashSet<>();
    private final Map<UUID, kr.mujoong.realtime.voice.PracticeSpeech> practiceAudio = new HashMap<>();
    private kr.mujoong.realtime.voice.PracticeSpeech streamedPractice;
    private boolean streamedPracticeEnded;
    /** 단위별 경로의 생성형 연습 발화({@link #streamedPractice})에서 단위 하나라도 기기 송신에 넘겼는가(FT-1023-e: 재생이 시작된 발화). */
    private boolean streamedPracticeSent;
    /** 조각 단위로 내보내는 승인 낭독(B1)에서 지금까지 원문 앞부분으로 확인하고 보낸 전사. 응답이 끝나면 원문 전체와 대조한다. */
    private final StringBuilder streamedApprovedText = new StringBuilder();
    private final java.util.ArrayDeque<PracticeSessionExtension.ExtensionEvent> pausedPracticeEvents = new java.util.ArrayDeque<>();
    private Instant practicePauseStartedAt;
    private record PracticePause(Instant from, Instant to) { }
    private final java.util.ArrayList<PracticePause> practicePauses = new java.util.ArrayList<>();
    /** 정산 창 안에 확장점에 전달한 사건 수. */
    private int practiceEventsInSettle;
    private final PracticeSessionExtension.PracticeOutput practiceOutput = new PracticeOutputPort();
    /** 정산 창에서 버린 늦은 아이 전사 수. */
    private int droppedLate;
    /** 빠진 승인 문구를 이미 남긴 코드(세션당 코드별 1회). */
    private final Set<String> missingReported = new HashSet<>();

    /**
     * @param onTerminated 종료 정리 뒤 레지스트리에서 지우는 함수
     */
    public SessionExecutor(Plan initialPlan, Services initialServices, SessionMailbox initialMailbox,
            Runnable onTerminated) {
        this.ctx = new SessionContext(initialPlan, initialServices, initialMailbox);
        Objects.requireNonNull(onTerminated, "onTerminated");
        this.stopJudge = new StopIntentJudge(ctx.plan().stopExpressions());
        this.judgment = new JudgmentCoordinator(ctx.services().judgment(), ctx.plan(),
                ctx.services().settings().instructionWait(), new JudgmentEnv());
        this.guardrail = new GuardrailCoordinator(ctx.services().guardrail(), ctx.plan(), new GuardrailEnv());
        this.idle = new IdleClock(ctx, new IdleEnv());
        this.ending = new EndingFlow(ctx, idle, judgment, guardrail, onTerminated, new EndingEnv());
        this.joinFlow = new JoinFlow(ctx, idle, judgment, ending, new JoinEnv());
        this.clocks = new SessionClocks(ctx, idle, ending, new ClocksEnv());
        // 연습 확장점은 PRACTICE 세션에서만 부른다(자유대화는 부르지 않는다)
        this.practice = ctx.plan().activityType() == ActivityType.PRACTICE
                ? Objects.requireNonNull(ctx.services().practice().create(ctx.plan().sessionId()), "practice extension")
                : null;
    }

    /** 진행 조회 값(다른 스레드에서 읽는다). */
    public IdleClock.LiveState liveState() {
        return idle.liveState();
    }
    public GuardrailProjection guardrailProjection() { return ctx.guardrailProjection(); }

    // ------------------------------------------------------------------ 실행

    /** 첫 메시지(Bootstrap)를 안전 편지함 맨 앞 차례로 넣고 실행 스레드를 띄운다. */
    public void start() {
        ctx.mailbox().putSafety(new Bootstrap());
        thread = ctx.services().launcher().launch("session-" + ctx.plan().sessionId(), this::run);
    }

    /** 첫 메시지만 넣는다(시험은 {@link #step()}으로 하나씩 처리한다). */
    public void bootstrapOnly() {
        ctx.mailbox().putSafety(new Bootstrap());
    }

    private void run() {
        processing = Thread.currentThread();
        try {
            while (!ctx.terminated()) {
                Message message = ctx.mailbox().take();
                if (message == null) {
                    break;
                }
                process(message);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!ctx.terminated()) {
                ending.teardown();
            }
        } finally {
            processing = null;
        }
    }

    /** 편지함에서 하나를 꺼내 처리한다(시험). 처리했으면 참. */
    public boolean step() {
        Message message = ctx.mailbox().poll();
        if (message == null) {
            return false;
        }
        process(message);
        return true;
    }

    /** 편지함이 빌 때까지 처리한다(시험). 처리한 개수. */
    public int drain() {
        int count = 0;
        while (!ctx.terminated() && step()) {
            count++;
        }
        return count;
    }

    /** 한 메시지를 처리한다. 최상위 예외 규약이 여기 있다. */
    void process(Message message) {
        if (ctx.terminated()) {
            return;
        }
        Thread previous = processing;
        processing = Thread.currentThread();
        try {
            long generationBefore = ctx.outputGen().generation();
            handle(message);
            watchHold(generationBefore);
            idle.afterMessage();
        } catch (DataAccessException | TransactionException e) {
            ctx.services().events().appError(EventLog.Component.SESSION_LIFECYCLE, e);
            scheduleRetry(message);
        } catch (RuntimeException e) {
            ctx.services().events().appError(EventLog.Component.SESSION_LIFECYCLE, e);
            failFromTopLevel();
        } finally {
            if (ctx.status().isTerminal()) {
                participationInputs.clear();
                greetingHandoff.clear();
            }
            processing = previous;
        }
    }

    // ------------------------------------------------------------------ 외부 입구

    /** 세션 편지함. */
    public SessionMailbox mailbox() {
        return ctx.mailbox();
    }

    public UUID sessionId() {
        return ctx.plan().sessionId();
    }

    /** 이 세션의 기기(인계가 살아 있는 실행기를 찾는 열쇠). */
    public UUID deviceId() {
        return ctx.plan().deviceId();
    }

    /** 지금 상태(실행기 사본). */
    public SessionStatus status() {
        return ctx.status();
    }

    /** 마지막으로 기기에 알린 T19 표시 상태(HELLO_OK.session.view). SESSION_STATE를 보낼 때마다 바뀐다. */
    public View view() {
        return ctx.view();
    }

    /** ACK를 기다리는 opId 명령 수(시험 · 진단). */
    int pendingCommandCount() {
        return ctx.pendingCommands().size();
    }

    /** 열린 끊김 구간이 있는가(시험 · 진단). */
    boolean hasOpenGap() {
        return ctx.openGapId() != null;
    }

    /** 종료 정리를 마쳤는가. */
    public boolean isTerminated() {
        return ctx.terminated();
    }

    /** 실행 스레드(종료 대기 · 진단). 시험 장치로 띄웠으면 null. */
    public Thread thread() {
        return thread;
    }

    /** 지금 출력 세대(SD-17). */
    public long outputGeneration() {
        return ctx.outputGeneration().get();
    }

    /** 묶인 기기 연결 id(없으면 null). */
    public UUID boundConnectionId() {
        DeviceLink current = ctx.link();
        return current == null ? null : current.connectionId();
    }

    /**
     * 0x01 입력 프레임(WSS 수신 핸들러가 부른다, 기다리지 않는다). 묶인 연결의 프레임만 받고 수락 구간 밖이면 버린다(R3-8).
     *
     * @return 대기열에 넣었으면 참
     */
    public boolean offerAudio(UUID connectionId, long sequence, byte[] pcm, long receivedNanos) {
        DeviceLink current = ctx.link();
        if (ctx.terminated() || current == null || !current.connectionId().equals(connectionId)) {
            return false;
        }
        // 리뷰 반영 1: 순번은 연결마다 다시 센다(새 페이지는 1부터 보낸다)
        return ctx.relay().offer(connectionId, sequence, pcm, receivedNanos);
    }

    /** 입력 중계(시험 · 진단). */
    public InputRelay relay() {
        return ctx.relay();
    }

    /** 시계(시험). */
    SessionTimers timers() {
        return ctx.timers();
    }

    /** 제브 게이트 협력 객체(시험). */
    GuardrailCoordinator guardrail() {
        return guardrail;
    }

    // ------------------------------------------------------------------ 메시지 처리

    private void handle(Message message) {
        switch (message) {
            case Bootstrap ignored -> onBootstrap();
            case TimerFired fired -> clocks.onTimer(fired);
            case EndRequested request -> ending.onEndRequested(request);
            case ConnectionLost lost -> ending.onConnectionLost(lost);
            case SessionMailbox.JudgmentArrived arrived -> judgment.onArrived(arrived);
            case SessionMailbox.JudgmentInstructionFailed failed -> judgment.onInstructionFailed(failed);
            case SessionMailbox.GuardrailArrived arrived -> guardrail.onArrived(arrived);
            case SessionMailbox.GuardrailRetryNotHeard notHeard -> guardrail.onRetryNotHeard(notHeard);
            case ServerShutdown shutdown -> {
                judgment.onExit();
                // 앱 종료 T3: 공급자 연결을 닫고 기록기를 비운다. 세션 행은 다음 기동 S3a가 닫는다.
                // 끝나면(실패해도) 정상 종료에 알린다 — T4(폴러 정지)는 공급자 닫기 뒤에 시작한다(RD-13 순서)
                try {
                    ending.closeProvider();
                    ctx.services().recorder().finish(ctx.plan().sessionId());
                } finally {
                    shutdown.handled().complete(null);
                }
            }
            case SessionMailbox.DeletionStop stop -> clocks.onDeletionStop(stop);
            case SessionMailbox.PracticeEvent event -> onPracticeEvent(event.event());
            case SessionMailbox.PracticeTimerFired fired -> onPracticeTimer(fired);
            case SessionMailbox.DeviceTakenOver ignored -> {
                if (ending.farewellPending()) {
                    ending.finishFarewell(false);
                }
            }
            case DeviceAck ack -> onDeviceAck(ack);
            case DeviceReadyReceived ready -> onDeviceReady(ready);
            case LinkReplaced replaced -> ending.onLinkReplaced(replaced);
            case DeviceStateReceived state -> ending.onDeviceState(state);
            case PlaybackMarkReceived mark -> {
                if (ctx.fromBoundConnection(mark.connectionId()) && ctx.status() == SessionStatus.ACTIVE
                        && mark.mark().status() == kr.mujoong.contract.ws.message.PlaybackMark.Status.COMPLETED) {
                    greetingHandoff.completed(mark.mark().playbackId(), ctx.outputGeneration().get(), mark.connectionId(),
                            mark.serverRecvAt());
                } else if (ctx.fromBoundConnection(mark.connectionId())
                        && (mark.mark().status() == kr.mujoong.contract.ws.message.PlaybackMark.Status.FAILED
                            || mark.mark().status() == kr.mujoong.contract.ws.message.PlaybackMark.Status.STOPPED)) {
                    greetingHandoff.failed(mark.mark().playbackId());
                }
                if (!joinFlow.onPlaybackMark(mark)) ending.onPlaybackMark(mark);
            }
            case VoiceClosed closed -> ending.onVoiceClosed(closed);
            case ProviderConnected connected -> onProviderConnected(connected);
            case ProviderFailed failed -> onProviderFailed(failed);
            case ProviderEventReceived received -> onProviderEvent(received);
        }
    }

    /** R3-1: START_SESSION을 먼저 보내고 그 시도 시각에 ②를 무장한 뒤 공급자 연결 스레드를 띄운다. */
    private void onBootstrap() {
        ctx.services().latency().register(ctx.plan().sessionId(), ctx.plan().traceId(),
                LatencyRecorder.inputSide(ctx.relay()::drainRelayInP95Millis, ctx.relay()::lastVoiceFrameAt));
        ctx.link(ctx.services().deviceLinks().apply(ctx.plan().deviceId()).filter(DeviceLink::isOpen).orElse(null));
        ctx.startOpId(UUID.randomUUID());
        ctx.startAttemptAt(ctx.services().clock().now());
        boolean sent = false;
        if (ctx.link() != null) {
            ctx.link().bindSession(ctx.plan().sessionId());
            StartSession startSession = new StartSession(ctx.plan().approvedAudio());
            sent = ctx.link().sendControl(startSession, ctx.startOpId(), ctx.plan().sessionId());
            if (sent) {
                ctx.remember(ctx.startOpId(), startSession);
            }
        }
        if (!sent) {
            // ⑤: 같은 처리 안에서 PREPARING → FAILED(DEVICE_NO_ACK) + UNRESPONSIVE
            Optional<ActivitySessionRow> failed = ending.transition(SessionStatus.PREPARING, Trigger.START_SEND_FAILED, null);
            if (failed.isPresent()) {
                ctx.markUnresponsive();
                ending.afterTerminal();
            } else {
                ending.syncFromDb();
            }
            return;
        }
        ctx.startSent(true);
        ctx.timers().arm(Kind.DEVICE_ACK, ctx.services().settings().deviceAckTimeout());
        ctx.providerAttempt(connectProvider(ctx.plan().sessionId(), ctx.plan().childContext(), event -> {
            if (event instanceof ProviderEvent.OutputAudioDelta audio) receivedProviderAudioBytes.addAndGet(audio.audio().length);
            else if (event instanceof ProviderEvent.OutputSilenceDelta silence) receivedProviderAudioBytes.addAndGet(silence.bytes());
            else if (event instanceof ProviderEvent.InputTranscriptDelta input && input.startMs() >= 0 && input.endMs() >= input.startMs()) {
                receivedInputEndMs.accumulateAndGet(input.endMs(), Math::max);
            }
            boolean output = isOutputChunk(event);
            if (output) {
                pendingOutputChunks.incrementAndGet();
            }
            if (!putFromProvider(new ProviderEventReceived(event, ctx.services().clock().now(), ctx.services().clock().monotonicNanos()))
                    && output) {
                pendingOutputChunks.decrementAndGet();
            }
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
        }, ctx.servicePolicy()));
    }

    private static boolean isOutputChunk(ProviderEvent event) {
        return event instanceof ProviderEvent.OutputTranscriptDelta || event instanceof ProviderEvent.OutputAudioDelta;
    }

    private boolean putFromProvider(Message message) {
        if (Thread.currentThread() == processing) {
            // F2 · 공급자 닫기(closeProvider · 정리)는 리스너의 Closed를 닫기를 부른 스레드에서 바로 부른다. 그 스레드가 이
            // 편지함을 비우는 실행기 자신이면 자리가 날 때까지 기다리지 않는다 — 비울 쪽이 없어 교착한다. 가득 찼으면 버린다(스스로 닫은
            // 공급자의 끊김 알림이라 잃어도 상태가 어긋나지 않는다)
            return ctx.mailbox().offerGeneral(message);
        }
        try {
            return ctx.mailbox().putGeneral(message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void onDeviceAck(DeviceAck ack) {
        if (!ctx.fromBoundConnection(ack.connectionId())) {
            return;
        }
        // ACK가 온 명령은 연결이 바뀌어도 다시 보내지 않는다
        if (ack.ackOf() != null) {
            ctx.pendingCommands().remove(ack.ackOf());
        }
        if (!Objects.equals(ack.ackOf(), ctx.startOpId()) || ctx.status() != SessionStatus.PREPARING) {
            return;
        }
        Optional<ActivitySessionRow> row = ending.transition(SessionStatus.PREPARING, Trigger.DEVICE_ACK, null);
        if (row.isEmpty()) {
            ending.syncFromDb();
            return;
        }
        ctx.ackReceived(true);
        if (ctx.deviceUnavailable()) {
            failDeviceUnavailable();
            return;
        }
        tryActivate();
    }

    private void onDeviceReady(DeviceReadyReceived ready) {
        if (!ctx.fromBoundConnection(ready.connectionId()) || !START_PHASE.contains(ctx.status())) {
            return;
        }
        if (!ready.ready().isReady()) {
            ctx.deviceUnavailable(true);
            if (ctx.status() == SessionStatus.DEVICE_ACK) {
                failDeviceUnavailable();
            }
            return;
        }
        ctx.deviceReady(true);
        tryActivate();
    }

    private void failDeviceUnavailable() {
        // DEVICE_READY가 사용 불가를 알리면 20초를 기다리지 않고 즉시 FAILED(DEVICE_NO_ACK), UNRESPONSIVE는 쓰지 않는다
        Optional<ActivitySessionRow> row = ending.transition(SessionStatus.DEVICE_ACK, Trigger.DEVICE_UNAVAILABLE, null);
        if (row.isPresent()) {
            ending.afterFailed();
        } else {
            ending.syncFromDb();
        }
    }

    private void onProviderConnected(ProviderConnected connected) {
        if (ctx.status().isTerminal() || (ctx.providerAttempt() != null && ctx.providerAttempt().isCancelled())) {
            connected.session().close();
            return;
        }
        ctx.providerSession(connected.session());
        ctx.relay().attach(ctx.providerSession());
    }

    private void onProviderFailed(ProviderFailed failed) {
        if (!START_PHASE.contains(ctx.status())) {
            return;
        }
        ending.multiOriginFail(Trigger.PROVIDER_FAILED, FailureCode.PROVIDER_UNAVAILABLE, SessionTransitions.Origin.REALTIME);
    }

    /**
     * U4b: ACK · DEVICE_READY(OK) · 공급자 session.started가 모두 오면 ACTIVE가 아니라 WAITING_CHILD(참여 확인)로 간다.
     */
    private void tryActivate() {
        if (ctx.status() != SessionStatus.DEVICE_ACK || !ctx.deviceReady() || !ctx.providerStarted()) {
            return;
        }
        SessionChange change = SessionChange.none();
        if (ctx.providerSessionId() != null && !ctx.providerSessionId().isBlank()
                && ctx.providerSessionId().length() <= SessionChange.MAX_PROVIDER_SESSION_ID_LENGTH) {
            change = change.withProviderSessionId(ctx.providerSessionId());
        }
        Optional<ActivitySessionRow> row = ctx.services().transitions().transition(ctx.plan().sessionId(), SessionStatus.DEVICE_ACK,
                SessionStatus.WAITING_CHILD, change, SessionTransitions.Origin.REALTIME, ctx.plan().traceId());
        if (row.isEmpty()) {
            ending.syncFromDb();
            return;
        }
        ending.changeStatus(SessionStatus.WAITING_CHILD, ctx.services().clock().now());
        enterWaitingChild();
    }

    /**
     * WAITING_CHILD 진입의 승자 몫: ② 시계 해제 · 입력 중계 시작(원음 기록 없음) · LISTENING · 끊김 시계 · 시작 안내(안내 1번째).
     */
    private void enterWaitingChild() {
        ctx.timers().cancel(Kind.DEVICE_ACK);
        ctx.relay().attach(ctx.providerSession());
        ctx.relay().setAccepting(true);
        ctx.relay().start(ctx.services().launcher());
        ctx.sendControl(new SessionState(View.LISTENING));
        clocks.armDisconnect();
        joinFlow.begin();
    }

    /** ACTIVE 진입의 승자 몫(④): ② 시계 해제 · 원음 기록 시작 · LISTENING · 끊김 시계. */
    private void enterActive() {
        ctx.timers().cancel(Kind.DEVICE_ACK);
        ctx.timers().cancel(Kind.JOIN_WINDOW);
        ctx.joinPromptPlaybackId(null);
        ctx.relay().attach(ctx.providerSession());
        // 동의된 세션만, ACTIVE부터 원음 기록을 연다(참여 확인 전 아이 소리는 남기지 않는다)
        if (ctx.plan().rawAudioConsent() && ctx.recording() == null && ctx.consentWithdrawnAt() == null) {
            ctx.recording(ctx.services().recordings().open(ctx.plan().sessionId()));
            ctx.relay().tap(ctx.recording()::child);
        }
        ctx.relay().setMuted(false);
        ctx.relay().holdQuiet(ctx.services().settings().segmentQuiet());
        ctx.relay().setAccepting(true);
        ctx.relay().start(ctx.services().launcher());
        if (ctx.view() != View.LISTENING) {
            ctx.sendControl(new SessionState(View.LISTENING));
        }
        clocks.armDisconnect();
    }

    /** ACTIVE 전이 승자의 뒷일: 진입 몫과 발언권 반환(응답 생성 요청 1회). */
    private void afterActiveWon() {
        participationInputs.clear();
        if (ctx.plan().activityType() == ActivityType.FREE_TALK && ctx.noticeDelivery() != null
                && ctx.noticeDelivery().state() == NoticeDelivery.State.COMPLETED) greetingHandoff.arm();
        enterActive();
        Instant now = ctx.services().clock().now();
        idle.resetSilence(now);
        // 안내 뒤 아이 첫 발화에 공급자가 스스로 시작해 둔 응답이 있으면 그것이 첫 응답이다(따로 지시하면 이미 응답한 모델이 응답하지 않는다)
        boolean replayed = practice == null || !practice.controlsOutput() ? replayJoinHeldOutput() : false;
        clearJoinHeldOutput();
        if (!replayed && (practice == null || !practice.controlsOutput())) {
            if (ctx.plan().activityType() == ActivityType.FREE_TALK && !ctx.services().settings().joinSelfStartWait().isZero()) {
                awaitJoinSelfStart();
            } else {
                requestResponse(InstructionTemplates.ResponseStart.FIRST);
            }
        }
        // PRACTICE면 확장점 'ACTIVE 진입'
        if (practice != null) {
            PracticeSessionExtension.PracticeContext context = new PracticeSessionExtension.PracticeContext(
                    ctx.plan().sessionId(), ctx.plan().practiceJson(), ctx.plan().approved().keySet(), ctx.plan().rawAudioConsent(), now,
                    ctx.plan().approvedSpeech());
            practiceSignal(() -> practice.onActive(context, practiceOutput), true);
        }
    }

    /**
     * 연습 확장점의 정산 시점과 practice 값: 정산 창이 끝난 뒤 정산 시점을 한 번 부르고 practice 값을 받는다. 확장점이 없거나 예외가
     * 났으면 null이다.
     */
    private Object practiceRecordForSettle(Instant cutoff) {
        PracticeSessionExtension current = practice;
        if (current == null || practiceFailed) {
            return null;
        }
        cancelPracticeTimers();
        PracticeSessionExtension.EndContext end = new PracticeSessionExtension.EndContext(ctx.plan().sessionId(), ctx.status(),
                closingReasonOrRow(), ctx.closingDetail(), ctx.sessionEndedAt(), practiceEventsInSettle);
        try {
            current.onSettle(end);
            return current.practiceRecord();
        } catch (RuntimeException e) {
            if (current.controlsOutput()) throw e;
            practiceFailure("EXCEPTION", e);
            return null;
        }
    }

    /** 정산 시점의 종료 사유(행이 정한 값, 모르면 CLOSING 사유). */
    private EndReason closingReasonOrRow() {
        try {
            return ctx.services().transitions().find(ctx.plan().sessionId()).map(ActivitySessionRow::endReason).orElse(ctx.closingReason());
        } catch (RuntimeException e) {
            return ctx.closingReason();
        }
    }

    // ------------------------------------------------------------------ 연습 확장점

    /**
     * 확장점 시점 하나를 부르고 돌려받은 신호를 처리한다. 예외는 PRACTICE_EXTENSION_FAILED에 남기고 자유대화처럼 잇는다.
     *
     * @param signalsAllowed 신호를 처리하는가(CLOSING · 정산 창의 사건은 전달하되 신호를 무시한다)
     */
    private void practiceSignal(java.util.function.Supplier<PracticeSessionExtension.Signal> call,
            boolean signalsAllowed) {
        PracticeSessionExtension.Signal signal;
        try {
            signal = call.get();
            idle.publishLive(ctx.services().clock().now());
        } catch (RuntimeException e) {
            practiceFailure("EXCEPTION", e);
            return;
        }
        if (!signalsAllowed || signal == null || signal == PracticeSessionExtension.Signal.NONE) {
            return;
        }
        if (ctx.status() != SessionStatus.ACTIVE) {
            ctx.putEvent(EVENT_PRACTICE_EXTENSION_FAILED, "{\"reason\":\"SIGNAL_NOT_ACTIVE\",\"signal\":\"" + signal.name()
                    + "\"}", ctx.services().clock().now());
            return;
        }
        if (signal == PracticeSessionExtension.Signal.LAST_GOAL) {
            // FD-Q6: 연습 완료는 CHILD_STOP + end_detail PRACTICE_COMPLETED
            stopOutputForIntent(ctx.services().clock().now());
            ending.beginClosing(Trigger.PRACTICE_COMPLETED, null);
        }
        // NEXT_GOAL은 계속한다(다음 목표 진행은 확장점이 출력 능력으로 한다)
    }

    /** 확장점 예외: 남기고 확장점을 끈다 — 그 세션은 자유대화처럼 잇고 정산 시점은 건너뛰며 practice는 null이다. */
    private void practiceFailure(String reason, RuntimeException cause) {
        ctx.putEvent(EVENT_PRACTICE_EXTENSION_FAILED, "{\"reason\":\"" + reason + "\"}", ctx.services().clock().now());
        ctx.services().events().appError(EventLog.Component.SESSION_LIFECYCLE, cause);
        if (practice != null && practice.controlsOutput()) {
            stopPracticeProgress();
            practiceHold = true;
            cancelPracticeTimers();
            if (ctx.status() == SessionStatus.ACTIVE) {
                ending.multiOriginFail(Trigger.SERVER_RESTART, FailureCode.SERVER_RESTART, SessionTransitions.Origin.REALTIME);
            }
            return;
        }
        practiceFailed = true;
        practice = null;
        practiceHold = false;
        cancelPracticeTimers();
        practicePlaybacks.clear();
    }

    /**
     * 확장점 사건 전달: ACTIVE면 전달하고 신호를 처리한다. CLOSING · 정산 창이면 전달하되 신호는 무시한다(정산 창 사건 수를
     * 센다). 정산 뒤 · 그 밖 상태는 무시한다.
     */
    private void onPracticeEvent(PracticeSessionExtension.ExtensionEvent event) {
        if (event.name().equals("practice-request-sent") && event.value() instanceof kr.mujoong.realtime.voice.PracticeSpeech speech) {
            if (speech.equals(pendingPracticeSpeech) && practiceActive()) {
                ctx.timers().cancel(Kind.PRACTICE_SEND_WAIT);
                practiceAudioStage("REQUEST_SENT");
                if (!practiceCollecting) ctx.timers().arm(Kind.INSTRUCTION_WAIT, ctx.services().settings().instructionWait());
            }
            return;
        }
        if (event.name().equals("u5b-voice-failed") && event.value() instanceof kr.mujoong.realtime.voice.PracticeSpeech speech) {
            if (speech.equals(pendingPracticeSpeech)) {
                pendingPracticeSpeech = null; practiceHold = true;
                bumpOutput(OutputGeneration.BumpReason.PRACTICE_DISCARD);
                recoverPracticeAudio(speech);
            }
            return;
        }
        PracticeSessionExtension current = practice;
        if (current == null || ctx.terminated()) {
            return;
        }
        if (ctx.status() == SessionStatus.ACTIVE && practiceProgressPaused() && current.controlsOutput()) {
            pausedPracticeEvents.addLast(event);
            return;
        }
        if (ctx.status() == SessionStatus.ACTIVE) {
            var delivered = withPracticePause(event);
            practiceSignal(() -> current.onExtensionEvent(delivered, practiceOutput), true);
            return;
        }
        if (ctx.status() == SessionStatus.CLOSING || ctx.settling()) {
            if (ctx.settling()) {
                practiceEventsInSettle++;
            }
            practiceSignal(() -> current.onExtensionEvent(event, practiceOutput), false);
            if (ctx.settling() && current.controlsOutput()) ending.finishSettle();
        }
    }

    private PracticeSessionExtension.ExtensionEvent withPracticePause(PracticeSessionExtension.ExtensionEvent event) {
        if (!(event.value() instanceof PracticeSessionExtension.PlaybackFact fact) || fact.receivedAt() == null) return event;
        Duration paused = fact.pausedFor();
        for (PracticePause interval : practicePauses) {
            Instant from = fact.receivedAt().isAfter(interval.from()) ? fact.receivedAt() : interval.from();
            if (from.isBefore(interval.to())) paused = paused.plus(Duration.between(from, interval.to()));
        }
        return new PracticeSessionExtension.ExtensionEvent(event.kind(), event.name(), event.correlationId(),
                new PracticeSessionExtension.PlaybackFact(fact.speech(), fact.status(), fact.text(), fact.receivedAt(), paused));
    }

    private void practicePlayback(kr.mujoong.realtime.voice.PracticeSpeech speech,
            kr.mujoong.realtime.practicelegacy.PracticePorts.Playback fact) {
        practicePlayback(speech, fact, ctx.services().clock().now());
    }

    private void practicePlayback(kr.mujoong.realtime.voice.PracticeSpeech speech,
            kr.mujoong.realtime.practicelegacy.PracticePorts.Playback fact, Instant receivedAt) {
        if (speech.equals(streamedPractice) && fact != kr.mujoong.realtime.practicelegacy.PracticePorts.Playback.STARTED) {
            // 단위별로 나가는 생성형 발화의 STARTED는 확장에 그대로 넘긴다(FT-1023-e: 확장이 '재생 중'을 알아야 한다 — 확장은 중복 STARTED를
            // 무시한다). 단위 하나의 COMPLETED는 발화의 끝이 아니다: 응답이 끝나고 보낸 음성이 모두 끝났을 때 한 번 COMPLETED로 닫는다
            if (fact == kr.mujoong.realtime.practicelegacy.PracticePorts.Playback.COMPLETED) {
                finishStreamedPractice();
                return;
            }
            streamedPractice = null;
            pendingPracticeSpeech = null;
            practiceHold = true;
            for (var entry : List.copyOf(practiceAudio.entrySet())) {
                if (speech.equals(entry.getValue())) {
                    practiceAudio.remove(entry.getKey());
                    cancelPracticeTimer("u5b-audio-" + entry.getKey());
                }
            }
            bumpOutput(OutputGeneration.BumpReason.PRACTICE_DISCARD);
        }
        ctx.mailbox().putSafety(new SessionMailbox.PracticeEvent(new PracticeSessionExtension.ExtensionEvent(
                PracticeSessionExtension.ExtensionEvent.Kind.PLAYBACK_FACT, "u5b-playback", speech.correlationId(),
                new PracticeSessionExtension.PlaybackFact(speech, fact, speech.text(), receivedAt))));
    }

    private void finishStreamedPractice() {
        var speech = streamedPractice;
        if (speech == null || !streamedPracticeEnded || guardrail.isChecking() || guardrail.hasQueued()
                || practiceAudio.containsValue(speech)) return;
        streamedPractice = null;
        pendingPracticeSpeech = null;
        practiceCollecting = false;
        ctx.timers().cancel(Kind.PRACTICE_COLLECT_MAX);
        practiceHold = true;
        practicePlayback(speech, kr.mujoong.realtime.practicelegacy.PracticePorts.Playback.COMPLETED);
    }

    /** FT-1023-e: 재생이 시작된 생성형 연습 발화의 뒤 단위를 버리고 나간 부분까지 재생됨으로 닫았다(앱 로그 INFO 사유 코드). */
    static final String REASON_TAIL_UNIT_DROPPED = "PracticeTailUnitDroppedAfterPlayback";
    /** FT-1023-e: 재생이 시작된 생성형 연습 발화의 남은 전사가 음성과 맞춰지지 못한 채 시한이 지나 나간 부분까지 재생됨으로 닫았다(INFO). */
    static final String REASON_TAIL_TEXT_UNALIGNED = "PracticeTailTextUnalignedAfterPlayback";

    /**
     * FT-1023-e: 단위별 경로로 나가던 생성형 연습 발화({@link #streamedPractice})가 이미 단위 하나라도 기기로 나갔으면, 그 발화의 남은 출력을 받지 않고
     * 나간 부분까지 '재생됨'으로 닫는다. 이미 보낸 음성은 끊지 않는다(출력 세대 · 출력 권한을 바꾸지 않는다). 보낸 음성이 아직 재생 중이거나 앞 단위가 제브
     * 검사 중이면 그 끝({@link #finishStreamedPractice})에서 COMPLETED 한 번으로 닫힌다. 재생이 시작되지 않은 발화는 건드리지 않고 거짓을
     * 돌려준다(지금처럼 재요청 1회 → 실패).
     *
     * @param reasonCode 앱 로그 사유 코드(INFO, 원문 없음)
     * @return 닫는 길로 넘겼으면 참
     */
    private boolean closeSentStreamedPractice(String reasonCode) {
        var speech = streamedPractice;
        if (speech == null || !streamedPracticeSent || !speech.equals(pendingPracticeSpeech)) return false;
        ctx.services().events().appError(EventLog.Component.OUTPUT_GATE, reasonCode, EventCode.Level.INFO);
        // 이 응답의 뒤따르는 조각은 받지 않는다(onOutputChunk의 streamedPracticeEnded 길). 맞춰지길 기다리던 전사 · 음성도 버린다
        streamedPracticeEnded = true;
        timedOutput.reset();
        finishStreamedPractice();
        return true;
    }

    /** B1: 조각 단위 승인 낭독에서 원문과 다른 단위가 와서 버리고 나간 부분까지 재생됨으로 닫았다(INFO, 원문 없음). */
    static final String REASON_APPROVED_UNIT_MISMATCH = "PracticeApprovedUnitMismatchAfterPlayback";
    /** B1: 조각 단위 승인 낭독이 원문 끝까지 오지 않은 채 응답이 끝나 나간 부분까지 재생됨으로 닫았다(INFO, 원문 없음). */
    static final String REASON_APPROVED_TAIL_MISSING = "PracticeApprovedTailMissingAfterPlayback";

    /** 들려주지 않은 승인 낭독이 원문과 달랐다: 출력을 버리고 확장에 원문 불일치를 알린다(확장이 같은 원문 재요청 · 복구 낭독을 정한다). */
    private void rejectApprovedReading(kr.mujoong.realtime.voice.PracticeSpeech rejectedSpeech) {
        pendingPracticeSpeech = null;
        if (rejectedSpeech.equals(streamedPractice)) streamedPractice = null;
        practiceHold = true;
        bumpOutput(OutputGeneration.BumpReason.PRACTICE_DISCARD);
        if (practice.handlesAudioRecovery()) {
            ctx.mailbox().putSafety(new SessionMailbox.PracticeEvent(new PracticeSessionExtension.ExtensionEvent(
                    PracticeSessionExtension.ExtensionEvent.Kind.ASYNC_RESULT, "approved-text-mismatch",
                    rejectedSpeech.correlationId(), rejectedSpeech)));
        } else {
            practicePlayback(rejectedSpeech, kr.mujoong.realtime.practicelegacy.PracticePorts.Playback.FAILED);
        }
    }

    private void stopPracticeProgress() {
        if (practice == null || !practice.controlsOutput()) return;
        practice.onStopping();
        streamedPractice = null;
        pendingPracticeSpeech = null;
        practiceAudio.clear();
        practicePlaybackStarted.clear();
        pausedPracticeEvents.clear();
        practicePauseStartedAt = null;
        practicePauses.clear();
        cancelPracticeTimers();
        practiceHold = true;
    }

    private void onPracticeTimer(SessionMailbox.PracticeTimerFired fired) {
        PracticeTimer timer = practiceTimers.get(fired.name());
        if (timer == null || timer.handle() == null || timer.generation() != fired.generation()) {
            return;
        }
        practiceTimers.remove(fired.name());
        if (fired.name().startsWith("u5b-audio-")) {
            UUID playbackId = UUID.fromString(fired.name().substring("u5b-audio-".length()));
            var speech = practiceAudio.remove(playbackId);
            practicePlaybackStarted.remove(playbackId);
            if (speech != null) {
                practiceAudioStage("PLAYBACK_TIMEOUT");
                practicePlayback(speech, kr.mujoong.realtime.practicelegacy.PracticePorts.Playback.FAILED);
            }
            return;
        }
        onPracticeEvent(new PracticeSessionExtension.ExtensionEvent(
                PracticeSessionExtension.ExtensionEvent.Kind.TIMER_FIRED, fired.name(), null, null));
    }

    /** 확장점 시계 하나(만료 예약 또는 일시정지 중 남은 시간). */
    private record PracticeTimer(long generation, kr.mujoong.contract.time.TimerDriver.TimerHandle handle,
            Instant deadline, Duration remaining) {
    }

    private void schedulePracticeTimer(String name, Duration delay) {
        cancelPracticeTimer(name);
        long generation = ++practiceTimerGeneration;
        Duration nonNegative = delay.isNegative() ? Duration.ZERO : delay;
        if (practiceProgressPaused()) {
            practiceTimers.put(name, new PracticeTimer(generation, null, null, nonNegative));
            return;
        }
        kr.mujoong.contract.time.TimerDriver.TimerHandle handle = ctx.services().clocks().safety().schedule(nonNegative,
                () -> ctx.mailbox().putSafety(new SessionMailbox.PracticeTimerFired(name, generation)));
        practiceTimers.put(name, new PracticeTimer(generation, handle, ctx.services().clock().now().plus(nonNegative), null));
    }

    private void cancelPracticeTimer(String name) {
        PracticeTimer previous = practiceTimers.remove(name);
        if (previous != null && previous.handle() != null) {
            previous.handle().cancel();
        }
    }

    private void cancelPracticeTimers() {
        for (String name : List.copyOf(practiceTimers.keySet())) {
            cancelPracticeTimer(name);
        }
    }

    private boolean practiceProgressPaused() {
        return idle.technicalPause() != null;
    }

    private void interruptPracticeSpeech(Instant now) {
        if (practice == null || !practice.controlsOutput()) return;
        var interrupted = new LinkedHashSet<kr.mujoong.realtime.voice.PracticeSpeech>(practiceAudio.values());
        if (pendingPracticeSpeech != null) interrupted.add(pendingPracticeSpeech);
        pendingPracticeSpeech = null;
        practiceHold = true;
        for (UUID playbackId : List.copyOf(practiceAudio.keySet())) cancelPracticeTimer("u5b-audio-" + playbackId);
        practiceAudio.clear();
        interrupted.forEach(speech -> practicePlayback(speech,
                kr.mujoong.realtime.practicelegacy.PracticePorts.Playback.INTERRUPTED, now));
    }

    /** 기술 일시정지 동안 남은 반응 시간을 보존한다. */
    private void pausePracticeTimers(Instant now) {
        if (practice != null && practice.controlsOutput() && practicePauseStartedAt == null) practicePauseStartedAt = now;
        for (Map.Entry<String, PracticeTimer> entry : List.copyOf(practiceTimers.entrySet())) {
            PracticeTimer timer = entry.getValue();
            if (timer.handle() == null) {
                continue;
            }
            timer.handle().cancel();
            Duration left = Duration.between(now, timer.deadline());
            practiceTimers.put(entry.getKey(), new PracticeTimer(++practiceTimerGeneration, null, null,
                    left.isNegative() ? Duration.ZERO : left));
        }
    }

    /** 일시정지가 끝나면 확장점 시계를 남은 시간부터 다시 건다. */
    private void resumePracticeTimers(Instant now) {
        if (practiceProgressPaused()) return;
        if (practicePauseStartedAt != null) {
            practicePauses.add(new PracticePause(practicePauseStartedAt, now));
            practicePauseStartedAt = null;
        }
        for (Map.Entry<String, PracticeTimer> entry : List.copyOf(practiceTimers.entrySet())) {
            PracticeTimer timer = entry.getValue();
            if (timer.handle() != null) {
                continue;
            }
            String name = entry.getKey();
            long generation = timer.generation();
            kr.mujoong.contract.time.TimerDriver.TimerHandle handle = ctx.services().clocks().safety().schedule(
                    timer.remaining(), () -> ctx.mailbox().putSafety(new SessionMailbox.PracticeTimerFired(name, generation)));
            practiceTimers.put(name, new PracticeTimer(generation, handle, now.plus(timer.remaining()), null));
        }
        while (!pausedPracticeEvents.isEmpty()) {
            ctx.mailbox().putSafety(new SessionMailbox.PracticeEvent(pausedPracticeEvents.removeFirst()));
        }
    }

    /**
     * W4: 확장이 요청한 판정. 거절 조건은 열린 턴 · 지점 밖 · 최근 확정 전사 4개 밖 · 기준 모양 틀림 · 공급자 없음이다(ACTIVE가 아니면
     * 호출 앞에서 PRACTICE_EXTENSION_FAILED로 거절한다). 다음 행동은 확장이 정하므로 실행부는 지시를 내지 않는다.
     */
    private boolean requestExtensionJudgment(JudgmentPoint point, long transcriptSeq, UUID correlationId,
            Map<String, Object> criteria) {
        if (!practiceActive()) {
            return refusePractice("requestJudgment", "NOT_ACTIVE");
        }
        if (point == null || correlationId == null || point == JudgmentPoint.J1 || point == JudgmentPoint.J6
                || transcriptSeq < 0 || transcriptSeq > Integer.MAX_VALUE) {
            return false;
        }
        JudgmentCoordinator.Recent target = judgment.findRecent(transcriptSeq);
        if (target == null) {
            return false;
        }
        kr.mujoong.realtime.judgment.JudgeRequest.Criteria shaped = null;
        if (point == JudgmentPoint.J2 || point == JudgmentPoint.J3 || point == JudgmentPoint.J5) {
            try {
                shaped = new kr.mujoong.realtime.judgment.JudgeRequest.Criteria(
                        (String) (criteria == null ? null : criteria.get("coreFunction")),
                        (String) (criteria == null ? null : criteria.get("judgmentCriteria")));
            } catch (RuntimeException invalid) {
                return false;
            }
        }
        JudgmentCoordinator.Readiness ready = judgment.preflight(target.text());
        if (ready != JudgmentCoordinator.Readiness.READY) {
            judgment.recordSkipped(ready, point, JudgmentTrigger.EXTENSION_REQUEST, target.seq(), NextAction.DELEGATED_TO_EXTENSION);
            return false;
        }
        judgment.open(new JudgmentCoordinator.Open(point, JudgmentTrigger.EXTENSION_REQUEST, JudgmentOwner.EXTENSION, target.seq(),
                target.text(), target.at(), shaped,
                practice != null && practice.controlsOutput()
                        ? judgment.dialogueBefore(target.seq(), target.at()) : judgment.recentBefore(target.seq()),
                (turn, outcome) -> JudgmentTurn.Decision.close(), closure -> afterExtensionJudgment(closure, correlationId)));
        return true;
    }

    /** 확장 소유 판정이 닫혔다: 결과(확률 없음) 또는 보류 · 장애 사건, 모은 출력 전사가 있으면 그 사건도 확장에 보낸다. */
    private JudgmentCoordinator.Handled afterExtensionJudgment(JudgmentTurn.Closure closure, UUID correlationId) {
        JudgeOutcome outcome = closure.outcome();
        PracticeSessionExtension.ExtensionEvent.Kind kind;
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("point", closure.point().name());
        switch (outcome.kind()) {
            case CONFIRMED -> {
                kind = PracticeSessionExtension.ExtensionEvent.Kind.JUDGMENT_RESULT;
                value.put("firstOption", outcome.firstOption());
                value.put("secondOption", outcome.secondOption());
            }
            case ABSTAINED -> {
                kind = PracticeSessionExtension.ExtensionEvent.Kind.JUDGMENT_ABSTAINED;
                value.put("abstainReason", outcome.abstainReason().name());
            }
            case FAULT_CANCELLED -> {
                kind = PracticeSessionExtension.ExtensionEvent.Kind.JUDGMENT_ABSTAINED;
                value.put("abstainReason", "FAULT");
            }
            default -> {
                return JudgmentCoordinator.Handled.NOTHING;
            }
        }
        ctx.mailbox().putSafety(new SessionMailbox.PracticeEvent(
                new PracticeSessionExtension.ExtensionEvent(kind, "judgment", correlationId, value)));
        if (!closure.heldOutputTranscript().isEmpty()) {
            ctx.mailbox().putSafety(new SessionMailbox.PracticeEvent(new PracticeSessionExtension.ExtensionEvent(
                    PracticeSessionExtension.ExtensionEvent.Kind.HELD_OUTPUT_TRANSCRIPT, "judgment", correlationId,
                    Map.of("text", closure.heldOutputTranscript()))));
        }
        return new JudgmentCoordinator.Handled(NextAction.DELEGATED_TO_EXTENSION, true);
    }

    /** 판정 협력 객체가 실행기에 닿는 자리(U11). */
    private final class JudgmentEnv implements JudgmentCoordinator.Env {

        @Override
        public SessionStatus status() {
            return ctx.status();
        }

        @Override
        public Instant now() {
            return ctx.services().clock().now();
        }

        @Override
        public void bumpForJudgment() {
            bumpOutput(OutputGeneration.BumpReason.JUDGMENT_TURN);
        }

        @Override
        public void showThinking() {
            if (ctx.view() == View.LISTENING && ctx.devicePlaying().isEmpty()) {
                ctx.sendControl(new SessionState(View.THINKING));
            }
        }

        @Override
        public void restoreListening() {
            if (ctx.status() == SessionStatus.ACTIVE && !ctx.segment().isOpen() && !ctx.transcripts().hasOpenAi()) {
                listeningAgain();
            }
        }

        @Override
        public boolean sendInstruction(UUID turnId, InstructionTemplates.NextAction action) {
            return SessionExecutor.this.sendInstruction(OutputGeneration.BumpReason.SERVER_SEND, "provider-judgment-",
                    id -> ctx.services().requests().instructionsAppend(id, action.content(ctx.plan().activityType())),
                    () -> ctx.mailbox().putSafety(new SessionMailbox.JudgmentInstructionFailed(turnId)), true,
                    action == InstructionTemplates.NextAction.REST_ACK_ONLY && ctx.plan().activityType() == ActivityType.FREE_TALK
                            ? kr.mujoong.realtime.gate.OutputAuthority.Purpose.REST_ACK
                            : kr.mujoong.realtime.gate.OutputAuthority.Purpose.NORMAL);
        }

        @Override
        public void armTimer(JudgmentTurn.TimerKind kind, Duration delay) {
            ctx.timers().arm(timerKind(kind), delay);
        }

        @Override
        public void cancelTimer(JudgmentTurn.TimerKind kind) {
            ctx.timers().cancel(timerKind(kind));
        }

        @Override
        public boolean postFromProvider(SessionMailbox.Message message) {
            return SessionExecutor.this.putFromProvider(message);
        }

        @Override
        public void lateDropped(String reasonCode) {
            ctx.services().events().appError(EventLog.Component.SESSION_LIFECYCLE, reasonCode, EventCode.Level.INFO);
        }

        private Kind timerKind(JudgmentTurn.TimerKind kind) {
            return switch (kind) {
                case LIVE -> Kind.JUDGMENT_LIVE;
                case READY -> Kind.JUDGMENT_READY;
                case INSTRUCTION -> Kind.JUDGMENT_INSTRUCTION;
            };
        }
    }

    /** 참여 확인 협력 객체가 실행기에 남은 로직으로 되돌아오는 자리. */
    private final class JoinEnv implements JoinFlow.Env {

        @Override
        public void reportMissing(String contentCode, Instant at) {
            SessionExecutor.this.reportMissing(contentCode, at);
        }

        @Override
        public void approvedBubble(ApprovedItem item, Instant at) {
            SessionExecutor.this.approvedBubble(item, at);
        }

        @Override
        public void afterActiveWon() {
            SessionExecutor.this.afterActiveWon();
        }

        @Override
        public void acceptedReply(String transcript, Instant at) {
            if (ctx.consentWithdrawnAt() != null || transcript == null || transcript.isBlank()) {
                return;
            }
            // 보호자 화면의 순서는 AI 안내 → 수락한 아이 말 → 첫 응답이다(번호는 여기서 정해지고 첫 응답 행은 이 뒤에 만든다)
            ctx.transcripts().childFinal(ACCEPTED_REPLY_ITEM, transcript, at);
        }

        @Override
        public void participationWindowOpened() {
            judgment.onTranscript(false);
            loggedParticipationDrops.clear();
            clearJoinHeldOutput();
            joinWindowOpened = true;
            participationInputs.open(Math.max(ctx.outputGen().audioClockMs(),
                    receivedProviderAudioBytes.get() / OutputGeneration.PCM_BYTES_PER_MILLISECOND), receivedInputEndMs.get());
            participationInputDraining = inputGroupId != null;
            clearInputGroup();
            ctx.inputBeganAt(null);
            if (participationInputDraining) {
                ctx.timers().arm(Kind.INPUT_TRANSCRIPT_QUIET, ctx.services().settings().inputTranscriptQuiet());
            }
        }
    }

    /** 기기 표식 · 끝내기 협력 객체가 실행기에 남은 로직으로 되돌아오는 자리. */
    private final class EndingEnv implements EndingFlow.Env {

        @Override
        public long bumpOutput(OutputGeneration.BumpReason reason) {
            return SessionExecutor.this.bumpOutput(reason);
        }

        @Override
        public void adopt(ActivitySessionRow row, UUID endRequestId) {
            SessionExecutor.this.adopt(row, endRequestId);
        }

        @Override
        public void listeningAgain() {
            SessionExecutor.this.listeningAgain();
        }

        @Override
        public void approvedBubble(ApprovedItem item, Instant at) {
            SessionExecutor.this.approvedBubble(item, at);
        }

        @Override
        public void onJoinPromptPlayed(Instant at) {
            joinFlow.onJoinPromptPlayed(at);
        }

        @Override
        public void armDisconnect() {
            clocks.armDisconnect();
        }

        @Override
        public void stopPracticeProgress() {
            SessionExecutor.this.stopPracticeProgress();
        }

        @Override
        public void cancelPracticeTimer(String name) {
            SessionExecutor.this.cancelPracticeTimer(name);
        }

        @Override
        public void practicePlayback(kr.mujoong.realtime.voice.PracticeSpeech speech,
                kr.mujoong.realtime.practicelegacy.PracticePorts.Playback fact, Instant receivedAt) {
            if (fact == kr.mujoong.realtime.practicelegacy.PracticePorts.Playback.STARTED) {
                for (var entry : practiceAudio.entrySet()) if (entry.getValue().equals(speech)
                        && practicePlaybackStarted.add(entry.getKey())) {
                    Duration playbackBound = ctx.services().settings().playbackExpiry();
                    if (practice != null && practice.handlesAudioRecovery())
                        playbackBound = playbackBound.plus(ctx.services().settings().segmentQuiet());
                    schedulePracticeTimer("u5b-audio-" + entry.getKey(), playbackBound);
                    practiceAudioStage("PLAYING");
                }
            } else {
                practicePlaybackStarted.removeIf(id -> !practiceAudio.containsKey(id));
                practiceAudioStage("PLAYBACK_ENDED");
            }
            SessionExecutor.this.practicePlayback(speech, fact, receivedAt);
        }

        @Override
        public Map<UUID, kr.mujoong.realtime.voice.PracticeSpeech> practiceAudio() {
            return practiceAudio;
        }

        @Override
        public Map<UUID, String> practicePlaybacks() {
            return practicePlaybacks;
        }

        @Override
        public void cancelPracticeTimers() {
            SessionExecutor.this.cancelPracticeTimers();
        }

        @Override
        public void guardrailTurnBoundary() {
            guardrail.onTurnBoundary();
        }

        @Override
        public boolean inputGroupOpen() {
            return inputGroupId != null;
        }

        @Override
        public void flushInputGroupInSettle() {
            SessionExecutor.this.flushInputGroupInSettle();
        }

        @Override
        public Duration practiceSettlementDelay() {
            if (practice != null && practice.controlsOutput()) {
                return practice.settlementDelay(ctx.services().clock().now());
            }
            return Duration.ZERO;
        }

        @Override
        public Object practiceRecordForSettle(Instant cutoff) {
            return SessionExecutor.this.practiceRecordForSettle(cutoff);
        }
    }

    /** 공백 시계 · 기술 일시정지 협력 객체가 실행기에 남은 로직으로 되돌아오는 자리. */
    private final class IdleEnv implements IdleClock.Env {

        @Override
        public void reportMissing(String contentCode, Instant at) {
            SessionExecutor.this.reportMissing(contentCode, at);
        }

        @Override
        public void approvedBubble(ApprovedItem item, Instant at) {
            SessionExecutor.this.approvedBubble(item, at);
        }

        @Override
        public boolean beginClosing(Trigger trigger, UUID requestId) {
            return ending.beginClosing(trigger, requestId);
        }

        @Override
        public PracticeSessionExtension.Progress practiceProgress() {
            return practice == null ? null : practice.progress();
        }

        @Override
        public boolean practiceControlsOutput() {
            return practice != null && practice.controlsOutput();
        }

        @Override
        public void judgmentOnFault() {
            judgment.onFault();
        }

        @Override
        public void pausePracticeTimers(Instant now) {
            SessionExecutor.this.pausePracticeTimers(now);
        }

        @Override
        public void resumePracticeTimers(Instant now) {
            SessionExecutor.this.resumePracticeTimers(now);
        }

        @Override
        public void interruptPracticeSpeech(Instant now) {
            SessionExecutor.this.interruptPracticeSpeech(now);
        }

        @Override
        public void stopOutputForIntent(Instant at) {
            SessionExecutor.this.stopOutputForIntent(at);
        }

        @Override
        public boolean hasJoin() {
            return joinFlow.hasJoin();
        }

        @Override
        public void resumeJoinWindow(Duration paused) {
            joinFlow.resumeJoinWindow(paused);
        }
    }

    /** 시계 구역 협력 객체가 실행기에 남은 로직으로 되돌아오는 자리. */
    private final class ClocksEnv implements SessionClocks.Env {

        @Override
        public void onOutputQuiet() {
            SessionExecutor.this.onOutputQuiet();
        }

        @Override
        public void onPracticeOutputDrain() { SessionExecutor.this.onPracticeOutputDrain(); }
        @Override public void onPracticeSendWait() { SessionExecutor.this.onPracticeSendWait(); }
        @Override public void onPracticeCollectMax() { SessionExecutor.this.onPracticeCollectMax(); }

        @Override
        public void onInstructionWaitExpired() {
            SessionExecutor.this.onInstructionWaitExpired();
        }

        @Override
        public void onJoinSelfStartExpired() {
            SessionExecutor.this.onJoinSelfStartExpired();
        }

        @Override
        public void onResponseStallExpired() {
            SessionExecutor.this.onResponseStallExpired();
        }

        @Override
        public void onInputTranscriptQuiet() {
            SessionExecutor.this.onInputTranscriptQuiet();
        }

        @Override
        public void onJudgmentTimer(JudgmentTurn.TimerKind kind) {
            judgment.onTimer(kind);
        }

        @Override
        public void onJudgmentExit() {
            judgment.onExit();
        }

        @Override
        public void onGuardrailCheckTimer() {
            guardrail.onCheckTimerFired();
        }

        @Override
        public void onJoinPromptPlayed(Instant at) {
            joinFlow.onJoinPromptPlayed(at);
        }

        @Override
        public void redeliver() {
            SessionExecutor.this.redeliver();
        }

        @Override
        public void onJoinWindow() {
            joinFlow.onJoinWindow();
        }
    }

    /** 제브 게이트 협력 객체가 실행기에 닿는 자리(U13). */
    private final class GuardrailEnv implements GuardrailCoordinator.Env {
        @Override
        public void invalidateStreaming() {
            bumpOutput(OutputGeneration.BumpReason.GUARDRAIL_RETRY);
        }

        @Override
        public SessionStatus status() {
            return ctx.status();
        }

        @Override
        public boolean terminated() {
            return ctx.terminated();
        }

        @Override
        public Instant now() {
            return ctx.services().clock().now();
        }

        @Override
        public UUID connectionId() {
            DeviceLink current = ctx.link();
            return current != null && current.isOpen() ? current.connectionId() : null;
        }

        @Override
        public long deviceGeneration() {
            return ctx.outputGeneration().get();
        }

        @Override
        public long providerGeneration() {
            return ctx.outputGen().generation();
        }

        @Override
        public boolean outputHeld() {
            return SessionExecutor.this.outputHeld();
        }

        @Override
        public kr.mujoong.realtime.gate.OutputAuthority outputAuthority() { return ctx.outputAuthority(); }
        @Override public String servicePolicyVersion() {
            return ctx.servicePolicy() == null ? "practice" : ctx.servicePolicy().policyVersion();
        }

        @Override
        public long inputGroupOpenCount() {
            return inputGroupOpens;
        }

        @Override
        public UUID practiceSpeechId() {
            var speech = pendingPracticeSpeech;
            return speech == null ? null : speech.correlationId();
        }

        @Override
        public TranscriptWriter.Held holdAiRow() {
            return ctx.transcripts().aiHold();
        }

        @Override
        public boolean recheckAndSend(SegmentBuffer.Sealed sealed, TranscriptWriter.Held held, boolean ownsLatency) {
            return SessionExecutor.this.recheckAndSend(sealed, held, ownsLatency);
        }

        @Override
        public void discardForRetry(SegmentBuffer.Sealed sealed, TranscriptWriter.Held held,
                GateDecision.DiscardReason reason, boolean ownsLatency) {
            recordDiscard(sealed, held, reason, ownsLatency);
        }

        @Override
        public void abandon(SegmentBuffer.Sealed sealed, TranscriptWriter.Held held, GateDecision.DiscardReason reason) {
            discard(sealed, reason, false, held, true);
        }

        @Override
        public void discardSilent(SegmentBuffer.Sealed sealed, TranscriptWriter.Held held,
                GateDecision.DiscardReason reasonOrNull) {
            logOutputDiscard(sealed, held, reasonOrNull, reasonOrNull == null ? DiscardDetail.GUARDRAIL_VOIDED
                    : defaultDiscardDetail(reasonOrNull), null);
            if (reasonOrNull != null) {
                ctx.services().metrics().gateDiscard(reasonOrNull.name());
            }
            closeAiRow(sealed, held);
        }

        @Override
        public void finishAbandoned() {
            // 재지시가 소리로 이어지지 않으면 열린 생성형 슬롯을 실패로 한 번 닫는다
            closeGeneratedSlot(null, kr.mujoong.realtime.practicelegacy.PracticePorts.Playback.FAILED);
            finishDiscard();
        }

        @Override
        public void practiceVoided(UUID practiceSpeechId) {
            // 무효로 닫힌 검사의 생성형 슬롯이 아직 열려 있으면 INTERRUPTED로 한 번 닫는다
            closeGeneratedSlot(practiceSpeechId, kr.mujoong.realtime.practicelegacy.PracticePorts.Playback.INTERRUPTED);
        }

        @Override
        public void listeningAgain() {
            SessionExecutor.this.listeningAgain();
        }

        @Override
        public boolean sendReplay(GuardrailCoordinator.ReplayableInstruction instruction, UUID retryId) {
            return SessionExecutor.this.sendInstruction(OutputGeneration.BumpReason.GUARDRAIL_RETRY, instruction.name(),
                    instruction.build(), () -> ctx.mailbox().putSafety(new SessionMailbox.GuardrailRetryNotHeard(retryId)), false);
        }

        @Override
        public boolean sendRetryFreeTurn(UUID retryId) {
            return SessionExecutor.this.sendInstruction(OutputGeneration.BumpReason.GUARDRAIL_RETRY,
                    "provider-guardrail-retry-",
                    id -> ctx.services().requests().instructionsAppend(id, InstructionTemplates.Retry.RETRY_FREE_TURN.content(ctx.plan().activityType())),
                    () -> ctx.mailbox().putSafety(new SessionMailbox.GuardrailRetryNotHeard(retryId)), false);
        }

        @Override
        public boolean postFromProvider(SessionMailbox.Message message) {
            return SessionExecutor.this.putFromProvider(message);
        }

        @Override
        public RealtimeMetrics metrics() {
            return ctx.services().metrics();
        }

        @Override
        public void armCheckTimer(Duration delay) {
            ctx.timers().arm(Kind.GUARDRAIL_CHECK, delay);
        }

        @Override
        public void cancelCheckTimer() {
            ctx.timers().cancel(Kind.GUARDRAIL_CHECK);
        }

        @Override
        public void markCheckedTurn(String mode) {
            ctx.services().latency().guardrailChecked(ctx.plan().sessionId(), mode);
        }

        @Override
        public void practiceBlocked(PracticeSessionExtension.GuardrailBlockedEvent event) {
            // 연습 확장점 알림: 안전 편지함을 거쳐 실행기 스레드에서 전달한다. 운영에서 연습 모드 검사를 시작하는 것은 U12의 생성형 슬롯(speakGenerated)이다
            ctx.mailbox().putSafety(new SessionMailbox.PracticeEvent(new PracticeSessionExtension.ExtensionEvent(
                    PracticeSessionExtension.ExtensionEvent.Kind.GUARDRAIL_BLOCKED, "guardrail-blocked",
                    event.correlationId(), event)));
        }

        @Override
        public void appError(String reasonCode, EventCode.Level level) {
            ctx.services().events().appError(EventLog.Component.OUTPUT_GATE, reasonCode, level);
        }

        @Override
        public void appError(RuntimeException error) {
            ctx.services().events().appError(EventLog.Component.OUTPUT_GATE, error);
        }
    }

    /**
     * 열린 생성형 슬롯(PracticeSpeech.generated)을 닫고 종료 사실을 확장에 한 번 보낸다. 슬롯이 없거나 생성형이 아니거나 식별자가
     * 다르면 아무것도 하지 않는다(승인 발화 슬롯의 기존 길은 건드리지 않는다).
     *
     * @param correlationId 닫을 슬롯의 식별자(null이면 지금 열린 생성형 슬롯)
     */
    private void closeGeneratedSlot(UUID correlationId, kr.mujoong.realtime.practicelegacy.PracticePorts.Playback fact) {
        var slot = pendingPracticeSpeech;
        // B1: 단위별 경로로 나가던 승인 낭독도 같은 길로 닫는다(경로가 깨지면 재생 실패)
        if (slot == null || (!slot.generated() && !slot.equals(streamedPractice))
                || (correlationId != null && !slot.correlationId().equals(correlationId))) {
            return;
        }
        pendingPracticeSpeech = null;
        practiceHold = true;
        practicePlayback(slot, fact);
    }

    /** 열린 생성형 슬롯의 역할에 맞는 제브 연습 모드. */
    private static GuardrailRequest.Mode practiceGuardrailMode(kr.mujoong.realtime.voice.PracticeSpeech slot) {
        return "COACH".equals(slot.partnerRole()) ? GuardrailRequest.Mode.PRACTICE_COACH : GuardrailRequest.Mode.PRACTICE_PEER;
    }

    /** 출력 능력 거절: ACTIVE가 아니거나 능력 밖 요청이면 남기고 거짓. */
    private boolean refusePractice(String capability, String reason) {
        ctx.putEvent(EVENT_PRACTICE_EXTENSION_FAILED, "{\"reason\":\"" + reason + "\",\"capability\":\"" + capability
                + "\"}", ctx.services().clock().now());
        return false;
    }

    /** 출력 능력을 쓸 수 있는가(ACTIVE이고 정산 전, 확장점이 살아 있다). */
    private boolean practiceActive() {
        return ctx.status() == SessionStatus.ACTIVE && !ctx.settling() && !ctx.terminated() && practice != null;
    }

    /** 실행기가 확장점에 주는 출력 능력. post 밖의 능력은 실행기 스레드에서만 부른다. */
    private final class PracticeOutputPort implements PracticeSessionExtension.PracticeOutput {

        @Override public boolean speak(kr.mujoong.realtime.voice.PracticeSpeech speech) {
            if (!practiceActive() || ctx.providerSession() == null || !ctx.providerSession().isOpen()) return false;
            ctx.bumpGeneration();
            ctx.transcripts().aiFinal("", false, ctx.services().clock().now());
            pendingPracticeSpeech = speech;
            practiceHold = false;
            // 설계 개정 A2: 승인된 발화 읽기 지시를 세대를 올리며 직렬 큐로 보낸다(응답 요청 type이 없어 지시가 발화를 시작한다). 새 말이 시한
            // 안에 시작하지 않거나 송신이 실패하면 지금처럼 확장에 실패를 알린다
            kr.mujoong.realtime.voice.PracticeInstruction instruction =
                    kr.mujoong.realtime.voice.PracticeInstruction.forSpeech(speech);
            return sendInstruction(OutputGeneration.BumpReason.SERVER_SEND, "provider-practice-speech-",
                    id -> ctx.services().requests().practiceInstruction(id, instruction),
                    () -> practiceOutput.post("u5b-voice-failed", speech));
        }

        @Override public boolean cancelSpeech(UUID correlationId) {
            if (pendingPracticeSpeech != null && pendingPracticeSpeech.correlationId().equals(correlationId)) {
                pendingPracticeSpeech = null; practiceHold = true;
                stopOutputForIntent(ctx.services().clock().now());
            }
            for (var entry : List.copyOf(practiceAudio.entrySet())) {
                if (entry.getValue().correlationId().equals(correlationId)) {
                    practiceAudio.remove(entry.getKey()); cancelPracticeTimer("u5b-audio-" + entry.getKey());
                    stopOutputForIntent(ctx.services().clock().now());
                }
            }
            return true;
        }

        @Override
        public UUID sessionId() {
            return ctx.plan().sessionId();
        }

        @Override
        public boolean playApproved(String contentCode) {
            if (!practiceActive()) {
                return refusePractice("playApproved", "NOT_ACTIVE");
            }
            Instant now = ctx.services().clock().now();
            Optional<ApprovedItem> item = contentCode == null ? Optional.empty() : ctx.plan().approvedItem(contentCode);
            UUID playbackId = UUID.randomUUID();
            if (item.isEmpty()) {
                // 패키지에 없으면 재생만 건너뛰고 곧바로 끝난 것으로 본다
                reportMissing(String.valueOf(contentCode), now);
                ctx.mailbox().putSafety(new SessionMailbox.PracticeEvent(new PracticeSessionExtension.ExtensionEvent(
                        PracticeSessionExtension.ExtensionEvent.Kind.PLAYBACK_DONE, String.valueOf(contentCode),
                        playbackId, null)));
                return true;
            }
            if (!ctx.sendControl(new PlayApprovedContent(playbackId, contentCode, item.get().contentVersionId()))) {
                return refusePractice("playApproved", "SEND_FAILED");
            }
            practicePlaybacks.put(playbackId, contentCode);
            ctx.playback().started(playbackId, contentCode);
            ctx.timers().arm(Kind.PLAYBACK_EXPIRY, ctx.services().settings().playbackExpiry());
            approvedBubble(item.get(), now);
            return true;
        }

        @Override
        public boolean instructProvider(String text) {
            if (!practiceActive()) {
                return refusePractice("instructProvider", "NOT_ACTIVE");
            }
            kr.mujoong.realtime.voice.PracticeInstruction instruction;
            try {
                instruction = new kr.mujoong.realtime.voice.PracticeInstruction(text);
            } catch (RuntimeException e) {
                return refusePractice("instructProvider", "INVALID");
            }
            // 설계 개정 A2: 추가한 지시는 곧 발화를 시작하므로 다른 서버 발화와 같이 세대를 올리고 직렬 큐로 보낸다
            return sendInstruction(OutputGeneration.BumpReason.SERVER_SEND, "provider-practice-",
                    id -> ctx.services().requests().practiceInstruction(id, instruction), null);
        }

        @Override
        public boolean requestResponse() {
            if (!practiceActive()) {
                return refusePractice("requestResponse", "NOT_ACTIVE");
            }
            SessionExecutor.this.requestResponse(InstructionTemplates.ResponseStart.CONTINUE);
            return true;
        }

        @Override
        public boolean cancelResponse() {
            if (!practiceActive()) {
                return refusePractice("cancelResponse", "NOT_ACTIVE");
            }
            // 공급자 응답 취소 대신 출력 세대를 올려 만들던 응답의 남은 조각을 버린다
            bumpOutput(OutputGeneration.BumpReason.PRACTICE_DISCARD);
            return true;
        }

        @Override
        public boolean holdOutput(boolean hold) {
            if (!practiceActive()) {
                return refusePractice("holdOutput", "NOT_ACTIVE");
            }
            practiceHold = hold;
            if (hold) {
                stopOutputForIntent(ctx.services().clock().now());
            }
            return true;
        }

        @Override
        public boolean schedule(String name, Duration delay) {
            if (!practiceActive()) {
                return refusePractice("schedule", "NOT_ACTIVE");
            }
            if (name == null || name.isBlank() || delay == null) {
                return refusePractice("schedule", "INVALID");
            }
            schedulePracticeTimer(name, delay);
            return true;
        }

        @Override
        public boolean cancel(String name) {
            if (!practiceActive()) {
                return refusePractice("cancel", "NOT_ACTIVE");
            }
            cancelPracticeTimer(name);
            return true;
        }

        @Override
        public boolean requestJudgment(JudgmentPoint point, long transcriptSeq, UUID correlationId, Map<String, Object> criteria) {
            return requestExtensionJudgment(point, transcriptSeq, correlationId, criteria);
        }

        @Override
        public boolean announceRoleSwitch(String toRole) {
            if (!practiceActive()) {
                return refusePractice("announceRoleSwitch", "NOT_ACTIVE");
            }
            // 시작 패키지 approvedSpeech의 승인 문장을 기존 승인 발화 재생(speak) 길로 낸다(원문 일치라 제브 호출 없음). 승인 문장이
            // 없으면 거짓이고 호출한 확장이 생성형 알림으로 낸다
            String code = "COACH".equals(toRole) ? "COACH_SWITCH" : "PEER".equals(toRole) ? "PEER_RETURN" : null;
            String sentence = code == null ? null : ctx.plan().approvedSpeech().get(code);
            if (sentence == null) {
                return false;
            }
            try {
                return speak(kr.mujoong.realtime.voice.PracticeSpeech.scenario(UUID.randomUUID(), NOTICE_SCENARIO_ID,
                        ctx.outputGen().generation(), "COACH".equals(toRole) ? "코치" : "친구", sentence,
                        kr.mujoong.realtime.voice.PracticeSpeech.ReadingCue.ROLE_NOTICE));
            } catch (IllegalArgumentException invalid) {
                return false;
            }
        }

        @Override
        public boolean speakGenerated(UUID correlationId, String role, String instructionText) {
            if (!practiceActive()) {
                return refusePractice("speakGenerated", "NOT_ACTIVE");
            }
            if (ctx.providerSession() == null || !ctx.providerSession().isOpen()) {
                return false;
            }
            kr.mujoong.realtime.voice.PracticeSpeech slot;
            kr.mujoong.realtime.voice.PracticeInstruction instruction;
            try {
                slot = kr.mujoong.realtime.voice.PracticeSpeech.generatedSlot(correlationId, NOTICE_SCENARIO_ID,
                        ctx.outputGen().generation(), role, instructionText);
                instruction = kr.mujoong.realtime.voice.PracticeInstruction.forGeneratedSpeech(instructionText);
            } catch (RuntimeException e) {
                return refusePractice("speakGenerated", "INVALID");
            }
            // 한 발화마다 재지시 수를 새로 센다(앞 발화가 재지시를 썼어도 이 발화는 한 번 다시 검사받는다)
            guardrail.onTurnBoundary();
            ctx.bumpGeneration();
            ctx.transcripts().aiFinal("", false, ctx.services().clock().now());
            pendingPracticeSpeech = slot;
            practiceHold = false;
            // 제브 첫 막힘 뒤 재지시가 같은 역할 지시문을 다시 보내도록 replayable로 보낸다. 새 말 미시작 · 송신 실패는 기존 실패 길이다
            boolean sent = sendInstruction(OutputGeneration.BumpReason.SERVER_SEND, "provider-practice-generated-",
                    id -> ctx.services().requests().practiceInstruction(id, instruction),
                    () -> practiceOutput.post("u5b-voice-failed", slot), true);
            if (!sent) {
                pendingPracticeSpeech = null;
                practiceHold = true;
            }
            return sent;
        }

        @Override
        public boolean managesSpeechTimeouts() { return true; }

        @Override public boolean setThinking(boolean on) {
            if (!practiceActive()) {
                return refusePractice("setThinking", "NOT_ACTIVE");
            }
            practiceThinking = on;
            if (on) {
                if (ctx.view() == View.LISTENING && ctx.devicePlaying().isEmpty()) {
                    ctx.sendControl(new SessionState(View.THINKING));
                }
            } else if (!judgment.isOpen()) {
                listeningAgain();
            }
            return true;
        }

        @Override
        public boolean post(String name, Object value) {
            if (name == null || name.isBlank()) {
                return false;
            }
            return ctx.mailbox().putSafety(new SessionMailbox.PracticeEvent(new PracticeSessionExtension.ExtensionEvent(
                    PracticeSessionExtension.ExtensionEvent.Kind.ASYNC_RESULT, name, null, value)));
        }
    }

    /**
     * 정산 창 안 공급자 사건: 연결 닫힘 알림이면 창을 닫고, 아이 확정 전사는 발화 시작이 ended_at보다 이르면 저장하고 아니면 버린다.
     */
    private void onLateProviderEvent(ProviderEvent event, Instant at) {
        switch (event) {
            case ProviderEvent.Closed ignored -> {
                ctx.providerClosedSignal(true);
                ending.finishSettle();
            }
            case ProviderEvent.InputTranscriptDelta delta -> {
                String groupId = groupInputDelta(delta);
                if (ctx.consentWithdrawnAt() == null && startedBeforeEnd(groupId, at)) {
                    ctx.transcripts().childDelta(groupId, delta.delta(), at);
                }
            }
            case ProviderEvent.InputTranscriptDone done -> {
                clearInputGroup();
                if (ctx.consentWithdrawnAt() == null && startedBeforeEnd(done.itemId(), at)) {
                    ctx.transcripts().childFinal(done.itemId(), done.transcript(), at, uncertain(done));
                } else {
                    droppedLate++;
                }
            }
            default -> {
                // 정산 창의 AI 출력 · 그 밖 사건은 쓰지 않는다
            }
        }
    }

    private boolean startedBeforeEnd(String itemId, Instant at) {
        Instant startedAt = ctx.transcripts().childStartedAt(itemId);
        return (startedAt != null ? startedAt : at).isBefore(ctx.sessionEndedAt());
    }

    /**
     * 발언권을 모델에 돌려줄 때(참여 뒤 첫 응답 · 확인 질문 뒤 이어 가기 · 연습 requestResponse) 지시 하나를 보낸다. 공급자에는 응답 요청
     * type이 없어 지시가 곧 발화다(설계 개정 A2). 세대를 올리고 직렬 큐로 보낸다. 참여 전(그리고 확인 대기 동안) 시작된 자동 응답은 출력 보류가
     * 버린다.
     */
    private void requestResponse(InstructionTemplates.ResponseStart start) {
        sendInstruction(OutputGeneration.BumpReason.SERVER_SEND, "provider-respond-",
                id -> ctx.services().requests().responseStart(id, start, ctx.plan().activityType(),
                        ctx.plan().conversationGuide()), null, true);
    }

    /** 공급자 출력을 받지 않는 때(참여 확인 중 · 확장점이 막은 동안). */
    private boolean outputHeld() {
        return ctx.status() == SessionStatus.WAITING_CHILD || judgment.holdsOutput()
                || (ctx.plan().activityType() == ActivityType.FREE_TALK && ctx.conversationPolicy().resting()
                    && (ctx.outputAuthority() == null || ctx.outputAuthority().purpose()
                        != kr.mujoong.realtime.gate.OutputAuthority.Purpose.REST_ACK))
                || (practiceHold && ctx.status() == SessionStatus.ACTIVE);
    }

    /** 빠진 승인 문구는 세션당 코드별 1회만 남긴다(payload는 코드만). */
    private void reportMissing(String contentCode, Instant at) {
        if (missingReported.add(contentCode)) {
            ctx.putEvent(EVENT_APPROVED_CONTENT_MISSING, "{\"code\":\"" + contentCode + "\"}", at);
        }
    }

    /** 보낸 승인 문구의 AI 말풍선 행(원문을 모르면 남기지 않는다). */
    private void approvedBubble(ApprovedItem item, Instant at) {
        if (item.text() != null && !item.text().isBlank()) {
            if (ctx.plan().activityType() == ActivityType.PRACTICE) {
                ctx.playback().current().ifPresent(playing ->
                        ctx.transcripts().awaitApprovedPlayback(playing.playbackId(), item.text(), at));
            } else {
                ctx.transcripts().aiStart(at);
                ctx.transcripts().aiFinal(item.text(), true, at);
            }
        }
    }

    private Settings.Lifecycle lifecycle() {
        return ctx.services().settings().lifecycle();
    }

    private void onProviderEvent(ProviderEventReceived received) {
        if (isOutputChunk(received.event())) {
            pendingOutputChunks.decrementAndGet();
        }
        if (ctx.status().isTerminal()) {
            if (ctx.settling()) {
                onLateProviderEvent(received.event(), received.receivedAt());
            }
            return;
        }
        ProviderEvent event = received.event();
        Instant at = received.receivedAt();
        switch (event) {
            case ProviderEvent.Started started -> {
                if (START_PHASE.contains(ctx.status())) {
                    ctx.providerStarted(true);
                    ctx.providerSessionId(started.providerSessionId());
                    tryActivate();
                }
            }
            case ProviderEvent.Error error -> providerError(error);
            case ProviderEvent.Closed ignored -> providerLost();
            case ProviderEvent.InputTranscriptDelta delta -> onInputTranscriptDelta(delta, at);
            case ProviderEvent.InputTranscriptDone done -> {
                // 공급자는 이 사건을 보내지 않는다(설계 개정 A7). 대신 보내는 쪽(시험 대역)이 있으면 묶음을 그것으로 확정한 것으로 본다
                clearInputGroup();
                if (ctx.plan().activityType() == ActivityType.FREE_TALK && ctx.status() == SessionStatus.WAITING_CHILD
                        && !participationInputs.acceptsLegacyDone(done.itemId())) {
                    return;
                }
                ctx.inputBeganAt(at);
                onConfirmedChildTranscript(done, at);
            }
            case ProviderEvent.UsageUpdated ignored -> {
                // 사용량 초는 파싱만 한다(실호출 도구 보고서가 기록). 지표 이름은 contracts 표에 없어 이 단위에서 더하지 않는다
            }
            case ProviderEvent.InstructionsAppended appended -> {
                if (ctx.outputGen().onAppended(appended.clientEventId(), appended.endMs())) {
                    judgment.onInstructionAcked();
                }
            }
            case ProviderEvent.OutputTranscriptDelta delta ->
                onOutputChunk(delta.delta(), delta.startMs(), delta.endMs(), null, at, received.receivedNanos());
            case ProviderEvent.OutputAudioDelta delta ->
                onOutputChunk(null, -1L, -1L, delta.audio(), at, received.receivedNanos());
            case ProviderEvent.OutputSilenceDelta silence -> onOutputSilence(silence.bytes(), at, received.receivedNanos());
        }
    }

    /** 공급자가 보낸 무음 조각. 시계에는 더하되 말의 끝으로 세지 않는다. */
    private void onOutputSilence(int bytes, Instant at, long nanos) {
        captureJoinHeldOutput(new HeldOutput(null, -1L, -1L, null, bytes));
        ctx.outputGen().onSilentAudio(bytes);
        if (!outputHeld() && inOutputRange() && ctx.outputGen().phase() == OutputGeneration.Phase.RECEIVING) {
            if (timedOutput.isOpen()) {
                timedOutput.audio(new byte[bytes], ctx.outputGen().audioClockBytes() - bytes);
                timedAudioLastAt = at;
                drainTimedOutput(at, nanos);
            } else ctx.segment().addSilence(bytes);
        }
    }

    /**
     * 참여 확인 중 안내가 끝난 뒤 공급자가 내는 출력을 버리기 전에 보관한다. 응답이 시작되기 전의 무음은 보관하지 않는다. 상한을 넘기면 보관을 포기하고
     * 수락 때 일반 첫 응답 지시로 간다.
     */
    private void captureJoinHeldOutput(HeldOutput event) {
        if (!joinWindowOpened || joinHeldOverflow || ctx.status() != SessionStatus.WAITING_CHILD
                || ctx.plan().activityType() != ActivityType.FREE_TALK || ctx.relay().isMuted()) {
            return;
        }
        boolean silence = event.silenceBytes() > 0;
        if (silence && joinHeldOutput.isEmpty()) {
            return;
        }
        int bytes = silence ? event.silenceBytes() : event.audio() == null ? 0 : event.audio().length;
        int chars = event.transcript() == null ? 0 : event.transcript().length();
        if (joinHeldOutput.size() >= 4_096 || joinHeldBytes + (long) bytes > SegmentBuffer.MAX_BYTES || joinHeldChars + chars > 8_192) {
            joinHeldOverflow = true;
            joinHeldOutput.clear();
            return;
        }
        if (joinHeldOutput.isEmpty()) {
            // 이 조각이 시계에 더해지기 전의 위치(되감을 기준)
            joinHeldClockBase = ctx.outputGen().audioClockBytes();
        }
        joinHeldOutput.add(new HeldOutput(event.transcript(), event.startMs(), event.endMs(),
                event.audio() == null ? null : event.audio().clone(), event.silenceBytes()));
        joinHeldBytes += bytes;
        joinHeldChars += chars;
    }

    private void clearJoinHeldOutput() {
        joinHeldOutput.clear();
        joinHeldClockBase = -1L;
        joinHeldBytes = 0;
        joinHeldChars = 0;
        joinHeldOverflow = false;
    }

    /**
     * 수락 순간 보관한 응답이 비어 있다: 모델이 판정 직후에 스스로 답을 시작하는 중일 수 있다. 바로 첫 응답 지시를 보내면 그 답은 지시 확인
     * 전의 낡은 말로 버려지고 모델은 이미 답하는 중이라 지시에 새로 답하지 않아 아무 말도 들리지 않는다(2026-10-22 15:05 세션). 그래서 지시 없이
     * 세대를 올려 끊김이 확인된 것으로 두고(다음 조각을 새 응답의 시작으로 받는다) join-self-start-wait만큼 기다린 뒤, 그동안 받은 조각이
     * 없을 때만 첫 응답 지시를 보낸다.
     */
    private void awaitJoinSelfStart() {
        bumpOutput(OutputGeneration.BumpReason.HOLD_RELEASED);
        ctx.outputGen().confirmGap();
        joinSelfStartGeneration = ctx.outputGen().generation();
        ctx.services().events().appError(EventLog.Component.OUTPUT_GATE, "JoinSelfStartWait", EventCode.Level.INFO);
        ctx.timers().arm(Kind.JOIN_SELF_START, ctx.services().settings().joinSelfStartWait());
    }

    /** 스스로 시작 대기가 끝났다: 같은 세대에서 받은 조각이 없으면 첫 응답 지시를 보낸다. */
    private void onJoinSelfStartExpired() {
        long waited = joinSelfStartGeneration;
        joinSelfStartGeneration = -1L;
        if (waited < 0 || ctx.status() != SessionStatus.ACTIVE) {
            return;
        }
        if (ctx.outputGen().generation() != waited || ctx.outputGen().phase() != OutputGeneration.Phase.GAP_CONFIRMED) {
            // 모델이 스스로 답을 시작했거나(조각을 받아 RECEIVING) 그사이 다른 일(끼어들기 · 서버 발화)이 세대를 올렸다
            ctx.services().events().appError(EventLog.Component.OUTPUT_GATE, "JoinSelfStartSeen", EventCode.Level.INFO);
            return;
        }
        ctx.services().events().appError(EventLog.Component.OUTPUT_GATE, "JoinSelfStartMissed", EventCode.Level.INFO);
        // JIRA-261: 모델이 대기 직후에 스스로 답을 시작하면 그 조각의 전사 시간선은 지시 확인보다 앞이라 일반 지시 경로가 낡은 말로 버린다
        // (2026-10-23 12:46 세션). 끊김 확인 뒤 지금까지 조각이 없었으므로 이 지시 세대의 첫 조각은 새 응답으로 받는다
        instructionAfterQuietGap = true;
        try {
            requestResponse(InstructionTemplates.ResponseStart.FIRST);
        } finally {
            instructionAfterQuietGap = false;
        }
    }

    /**
     * JIRA-270 응답 정체 안전망을 건다. 자유대화 ACTIVE에서만, 연습이 출력을 쥐지 않았을 때, 이번 아이 턴에 아직 보내지 않았을 때만 건다. 시계는
     * 하나라 다시 걸면 처음부터 잰다.
     */
    private void armResponseStall() {
        Duration wait = ctx.services().settings().responseStallWait();
        if (wait.isZero() || stallNudgeUsed || ctx.plan().activityType() != ActivityType.FREE_TALK
                || ctx.status() != SessionStatus.ACTIVE || (practice != null && practice.controlsOutput())) {
            return;
        }
        ctx.timers().arm(Kind.RESPONSE_STALL, wait);
    }

    private void cancelResponseStall() {
        if (ctx.timers().isArmed(Kind.RESPONSE_STALL)) {
            ctx.timers().cancel(Kind.RESPONSE_STALL);
        }
    }

    /** 받은 응답 전사를 끝부분만 모은다(미루는 말 판단용). */
    private void appendResponseTail(String transcript) {
        if (transcript == null || transcript.isEmpty()) {
            return;
        }
        responseTail.append(transcript);
        int over = responseTail.length() - RESPONSE_TAIL_CHARS;
        if (over > 0) {
            responseTail.delete(0, over);
        }
    }

    /**
     * 응답 하나가 끝났다(조용함으로 봉인): 전사가 "생각해 볼게" 같은 미루는 말로 끝났으면 응답 정체 안전망을 건다. 공급자가 이어 말하면 그 조각이
     * 시계를 끈다.
     */
    private void onResponseEnded() {
        boolean deferral = kr.mujoong.realtime.dialog.DeferralPhrases.endsWithDeferral(responseTail);
        responseTail.setLength(0);
        if (deferral && !stallNudgeUsed && ctx.plan().activityType() == ActivityType.FREE_TALK
                && ctx.status() == SessionStatus.ACTIVE) {
            ctx.services().events().appError(EventLog.Component.OUTPUT_GATE, "ResponseStallDeferralEnd", EventCode.Level.INFO);
            armResponseStall();
        }
    }

    /**
     * 응답 정체 안전망 만기(JIRA-270): 아이 말 확정 뒤(또는 미루는 말로 끝난 응답 뒤) 이 시간 동안 새 출력도 아이 말도 없었다. 보낼 수 있으면 이어
     * 말하기 지시({@link InstructionTemplates.ResponseStart#STALL_RESUME})를 한 번 보낸다. 기기가 아직 재생 중이거나 제브 검사가 남았으면 같은
     * 시간만큼 다시 기다리고, 아이가 말하는 중 · 출력 보류 · 판정 · 다른 지시를 기다리는 중 · ACTIVE가 아니면 보내지 않는다.
     */
    private void onResponseStallExpired() {
        if (stallNudgeUsed || ctx.status() != SessionStatus.ACTIVE || ctx.terminated()
                || ctx.plan().activityType() != ActivityType.FREE_TALK || (practice != null && practice.controlsOutput())
                || outputHeld() || judgment.isOpen() || inputGroupId != null || joinSelfStartGeneration >= 0
                || ctx.outputGen().awaitingNewSpeech() || ctx.segment().isOpen() || timedOutput.isOpen()
                || ctx.transcripts().hasOpenAi()) {
            return;
        }
        Instant now = ctx.services().clock().now();
        Instant lastDelta = ctx.outputGen().lastDeltaAt();
        boolean outputFlowing = lastDelta != null && now.isBefore(lastDelta.plus(ctx.services().settings().segmentQuiet()));
        if (!ctx.devicePlaying().isEmpty() || ctx.playback().isPlaying() || guardrail.isChecking() || guardrail.hasQueued()
                || outputFlowing) {
            // 앞 응답이 아직 재생 · 검사 중이다: 끝난 뒤 다시 잰다
            ctx.timers().arm(Kind.RESPONSE_STALL, ctx.services().settings().responseStallWait());
            return;
        }
        stallNudgeUsed = true;
        ctx.services().events().appError(EventLog.Component.OUTPUT_GATE, "ResponseStallNudge", EventCode.Level.INFO);
        if (!sendInstruction(OutputGeneration.BumpReason.SERVER_SEND, "provider-respond-",
                id -> ctx.services().requests().responseStart(id, InstructionTemplates.ResponseStart.STALL_RESUME,
                        ctx.plan().activityType(), null), null, true)) {
            ctx.services().events().appError(EventLog.Component.OUTPUT_GATE, "ResponseStallNudgeNotSent", EventCode.Level.INFO);
        }
    }

    /** 진단(D): 지금 세대에서 보류 · 낡은 꼬리로 버린 조각이 있었으면 사유 코드를 한 번씩 남기고 표지를 지운다(원문 없음, R-F14). */
    private void flushDiscardDiagnostics() {
        if (discardedHeldSeen) {
            ctx.services().events().appError(EventLog.Component.OUTPUT_GATE, "OutputDiscardedHeld", EventCode.Level.INFO);
        }
        if (discardedStaleSeen) {
            ctx.services().events().appError(EventLog.Component.OUTPUT_GATE, "OutputDiscardedStale", EventCode.Level.INFO);
        }
        discardedHeldSeen = false;
        discardedStaleSeen = false;
    }

    /**
     * 참여가 수락됐다: 안내 뒤 아이 말에 공급자가 스스로 시작해 둔 응답이 있으면 그것을 첫 응답으로 다시 흘려 넣는다(새 세대 · 새 응답의 시작으로,
     * 오디오 시계는 보관을 시작한 위치로 되감는다). 수락 시점에는 판정 턴이 이미 닫혀 출력 보류가 풀려 있다.
     *
     * @return 다시 흘려 넣었으면 참(이때는 첫 응답 지시를 따로 보내지 않는다)
     */
    private boolean replayJoinHeldOutput() {
        boolean usable = !joinHeldOutput.isEmpty() && !joinHeldOverflow && joinHeldClockBase >= 0
                && ctx.plan().activityType() == ActivityType.FREE_TALK && ctx.status() == SessionStatus.ACTIVE
                && joinHeldClockBase <= ctx.outputGen().audioClockBytes();
        if (!usable) {
            ctx.services().events().appError(EventLog.Component.OUTPUT_GATE,
                    joinHeldOverflow ? "JoinHeldOutputOverflow" : "JoinHeldOutputNone", EventCode.Level.INFO);
            clearJoinHeldOutput();
            return false;
        }
        List<HeldOutput> events = List.copyOf(joinHeldOutput);
        long base = joinHeldClockBase;
        clearJoinHeldOutput();
        ctx.services().events().appError(EventLog.Component.OUTPUT_GATE, "JoinHeldOutputReplayed", EventCode.Level.INFO);
        // 지시 없이 올린 세대로 새 응답을 시작하고, 끊김이 이미 확인된 것으로 둔다
        bumpOutput(OutputGeneration.BumpReason.HOLD_RELEASED);
        ctx.outputGen().confirmGap();
        ctx.outputGen().rewindAudioClock(base);
        Instant now = ctx.services().clock().now();
        long nanos = ctx.services().clock().monotonicNanos();
        for (HeldOutput event : events) {
            if (event.silenceBytes() > 0) {
                onOutputSilence(event.silenceBytes(), now, nanos);
            } else if (event.transcript() != null) {
                onOutputChunk(event.transcript(), event.startMs(), event.endMs(), null, now, nanos);
            } else if (event.audio() != null) {
                onOutputChunk(null, -1L, -1L, event.audio(), now, nanos);
            }
        }
        return true;
    }

    /**
     * 입력 전사 조각 하나(설계 개정 A7). 공급자는 전사 완료 · 말 끝 사건을 주지 않으므로 조각을 묶고, {@code input-transcript-quiet} 동안 조각이
     * 없으면 {@link #onInputTranscriptQuiet}가 그 묶음을 확정 전사로 만든다. 조각의 저장 규칙(동의 철회 · 참여 전 · 출력 범위)은 그대로다.
     */
    private void onInputTranscriptDelta(ProviderEvent.InputTranscriptDelta delta, Instant at) {
        if (ctx.plan().activityType() == ActivityType.FREE_TALK && ctx.status() == SessionStatus.WAITING_CHILD) {
            ParticipationInputBoundary.Verdict verdict = participationInputs.judge(delta);
            if (verdict != ParticipationInputBoundary.Verdict.ACCEPT) {
                // 참여 대답이 인식되지 않는 까닭을 가리려고 창마다 사유별로 한 번만 사유 코드를 남긴다(전사 원문 없음, R-F14)
                if (loggedParticipationDrops.add(verdict)) {
                    ctx.services().events().appError(EventLog.Component.VOICE_RELAY, "ParticipationInputDropped" + verdict.name(),
                            EventCode.Level.INFO);
                }
                return;
            }
        }
        if (participationInputDraining && ctx.status() == SessionStatus.WAITING_CHILD) {
            ctx.timers().arm(Kind.INPUT_TRANSCRIPT_QUIET, ctx.services().settings().inputTranscriptQuiet());
            return;
        }
        groupInputDelta(delta);
        if (ctx.consentWithdrawnAt() != null ||ctx.status() == SessionStatus.WAITING_CHILD) {
            // 동의 철회 뒤 도착한 아이 전사는 저장하지 않는다(확정 때 센다). 참여 전 아이 전사도 저장하지 않는다
            return;
        }
        if (inRelayRange()) {
            ctx.services().latency().inputActivity(ctx.plan().sessionId());
            // JIRA-270: 아이가 말하는 중이다 — 정체 안전망을 끈다(말이 확정되면 다시 건다)
            cancelResponseStall();
            // 아이 입력 재개는 열린 구간을 확정한다(대화가 넘어갔으니 완결 요청 교정은 하지 않는다)
            // 승인 대본은 전부 받은 뒤 원문 일치를 검사한다. 수집 중의 입력 전사만으로
            // 앞 조각을 봉인하면 정상 대본도 불완전/불일치로 폐기돼 연습이 종료된다.
            // 입력 기록·종료 의도 처리는 계속한다. 시작 안내의 일반 입력만 구분하고 역할 대화의 끼어들기는 기존대로 둔다.
            boolean collectingPractice = pendingPracticeSpeech != null;
            boolean yieldingPractice = collectingPractice && pendingPracticeSpeech.generated()
                    && practice != null && practice.canYieldUnplayedSpeech();
            if (yieldingPractice && !ctx.segment().isOpen() && !guardrail.isChecking()) {
                // Child input may also arrive before the provider produces its first chunk.
                yieldUnplayedPracticeToInput(null);
            }
            if (ctx.segment().isOpen() && (!collectingPractice || yieldingPractice) && !inputGroupDuringIntroduction) {
                sealAndGate(true);
            }
            // 아이 입력이 시작되면 모아 둔 AI 전사를 먼저 한 행으로 낸다(보호자 화면의 순서가 말한 순서와 같게)
            ctx.transcripts().endStream(at);
            ctx.transcripts().childDelta(inputGroupId, delta.delta(), at);
        }
    }

    /** 조각을 현재 묶음에 더하고(없으면 묶음을 연다) 조용 시계를 다시 건다. 묶음 id를 돌려준다. */
    private String groupInputDelta(ProviderEvent.InputTranscriptDelta delta) {
        lastChildInputAt = ctx.services().clock().now();
        if (inputGroupId == null) {
            ctx.inputBeganAt(ctx.services().clock().now());
            inputGroupDuringIntroduction = practice != null && practice.collectingIntroduction();
            // Only a conversational input group supersedes a pending guardrail ticket.
            // Stop/rest still invalidate output through their normal status/generation changes.
            if (!inputGroupDuringIntroduction) inputGroupOpens++;
            inputGroupId = delta.itemId() != null ? delta.itemId() : "in-" + (++inputGroupSeq);
            inputGroup.setLength(0);
        }
        inputGroup.append(delta.delta());
        ctx.timers().arm(Kind.INPUT_TRANSCRIPT_QUIET, ctx.services().settings().inputTranscriptQuiet());
        return inputGroupId;
    }

    private void clearInputGroup() {
        ctx.timers().cancel(Kind.INPUT_TRANSCRIPT_QUIET);
        inputGroupId = null;
        inputGroupDuringIntroduction = false;
        inputGroup.setLength(0);
    }

    /** 입력 전사 묶음이 조용해졌다: 확정 전사를 지금 확정 전사 입구({@link ProviderEvent.InputTranscriptDone})와 같은 길로 흘린다. */
    private void onInputTranscriptQuiet() {
        if (participationInputDraining) {
            participationInputDraining = false;
            clearInputGroup();
            ctx.inputBeganAt(null);
            return;
        }
        String id = inputGroupId;
        String text = inputGroup.toString();
        boolean duringIntroduction = inputGroupDuringIntroduction;
        clearInputGroup();
        if (id == null || text.isBlank()) {
            return;
        }
        ProviderEvent.InputTranscriptDone done = new ProviderEvent.InputTranscriptDone(id, text, null);
        Instant at = ctx.services().clock().now();
        if (ctx.status().isTerminal()) {
            if (ctx.settling()) {
                onLateProviderEvent(done, at);
            }
            return;
        }
        onConfirmedChildTranscript(done, at, duringIntroduction);
    }

    /** 정산 창이 끝날 때 묶던 입력 전사가 있으면 정산 창 규칙으로 확정한다(발화 시작이 ended_at보다 이르면 저장). */
    private void flushInputGroupInSettle() {
        String id = inputGroupId;
        String text = inputGroup.toString();
        clearInputGroup();
        if (id != null && !text.isBlank()) {
            onLateProviderEvent(new ProviderEvent.InputTranscriptDone(id, text, null), ctx.services().clock().now());
        }
    }

    /**
     * 확정된 아이 전사 하나(참여 확인 · 종료 의도 · 연습 · 기록). 서버가 입력 전사 조각을 묶어 만든 것이다(설계 개정 A7). 전사 신뢰도는 오지 않으므로
     * 불확실이 아니다.
     */
    private void onConfirmedChildTranscript(ProviderEvent.InputTranscriptDone done, Instant at) {
        onConfirmedChildTranscript(done, at, practice != null && practice.collectingIntroduction());
    }

    private void onConfirmedChildTranscript(ProviderEvent.InputTranscriptDone done, Instant at, boolean duringIntroduction) {
        if (ctx.consentWithdrawnAt() != null) {
            droppedAfterConsent++;
            return;
        }
        if (ctx.status() == SessionStatus.WAITING_CHILD) {
            greetingHandoff.childInput(done.transcript());
            joinFlow.onJoinReply(done.transcript(), at, ctx.inputBeganAt() == null ? at : ctx.inputBeganAt());
            return;
        }
        if (inRelayRange()) {
            // JIRA-262: 비언어 표지([cry 등) · 앞머리 문장부호 잡음을 지운 글로 저장 · 판정 · 제브 문맥 · 종료 판정을 한다(표지만 있던 말은 빈 전사 =
            // 불확실). 참여 확인 대답(위)은 지금 동작을 바꾸지 않으려고 정리하지 않는다
            String cleaned = kr.mujoong.realtime.log.TranscriptMarkers.cleanChild(done.transcript());
            if (!java.util.Objects.equals(cleaned, done.transcript())) {
                done = new ProviderEvent.InputTranscriptDone(done.itemId(), cleaned, done.confidence());
            }
            greetingHandoff.childInput(done.transcript());
            boolean uncertain = uncertain(done);
            java.util.OptionalInt seq = ctx.transcripts().childFinal(done.itemId(), done.transcript(), at, uncertain);
            // 아이의 빈 아닌 확정 전사가 공백 시계를 0으로 한다(빈 전사는 리셋하지 않는다)
            if (ctx.status() == SessionStatus.ACTIVE && done.transcript() != null && !done.transcript().isBlank()) {
                idle.resetSilence(at);
            }
            currentTranscriptSeq = seq.orElse(0);
            // U13 호출 지점 ③: 아이 확정 전사 = 새 턴(재지시 수를 0으로, 마지막 서버 지시를 지운다)
            if (!duringIntroduction) guardrail.onNewTurn(done.transcript());
            if (ctx.status() == SessionStatus.ACTIVE) {
                judgment.remember(currentTranscriptSeq, done.transcript(), at);
                if (judgment.isOpen()) {
                    // 설계 검토 판정 중(지시 단계 포함) 새 확정 전사 — 종료 표현이면 STOPPED, 아니면 SUPERSEDED로 닫고 이 전사를
                    // 아래 지금 흐름으로 처음부터 다시 처리한다(확장 소유 판정 단계는 턴을 그대로 두고 확장에 전달한다)
                    judgment.onTranscript(stopJudge.judge(done.transcript()).verdict() == StopIntentJudge.Verdict.STOP);
                }
            }
            boolean controlledPractice = practice != null && practice.controlsOutput();
            boolean stopHandled = controlledPractice && judgeStopIntent(done.transcript(), uncertain, at);
            if (!stopHandled && !duringIntroduction && ctx.status() == SessionStatus.ACTIVE && practice != null
                    && !(controlledPractice && idle.technicalPause() != null)) {
                PracticeSessionExtension.Utterance utterance = new PracticeSessionExtension.Utterance(
                        seq.orElse(0), at, done.transcript() == null ? "" : done.transcript(), uncertain,
                        !ctx.devicePlaying().isEmpty());
                practiceSignal(() -> practice.onChildUtterance(utterance, practiceOutput), true);
            }
            // 2차 리뷰 2: 종료 의도를 푼 발화의 응답은 막힌 동안 이미 취소됐을 수 있다 — 끝 신호가 오지 않을 '생각 중'을 보내지 않는다
            if (!duringIntroduction && !(controlledPractice ? stopHandled : judgeStopIntent(done.transcript(), uncertain, at))) {
                thinking();
                // JIRA-270: 아이 말 확정 = 새 턴. 응답이 오지 않으면 이어 말하기 지시를 한 번 보낼 수 있게 시계를 건다. 아이가 "잠깐 생각할게"처럼
                // 기다려 달라고 하면 서비스 정책대로 기다린다(걸지 않는다)
                if (ctx.status() == SessionStatus.ACTIVE && done.transcript() != null && !done.transcript().isBlank()) {
                    stallNudgeUsed = false;
                    if (!kr.mujoong.realtime.dialog.DeferralPhrases.endsWithDeferral(done.transcript())) {
                        armResponseStall();
                    } else {
                        cancelResponseStall();
                    }
                }
            }
        }
    }

    /**
     * 출력 조각 하나(음성 또는 전사, U11 W8). 어느 단계 · 보류 여부와 관계없이 마지막 조각 시각을 갱신하고 구간 조용 시계를 다시 건다. 출력
     * 세대가 받는 단계이고 보류가 아니면 구간에 넣는다 — 열린 구간이 없으면 이 조각부터 새 구간(첫 조각이면 t1을 찍고 AI 행 번호를 정해 확정 전
     * 칸을 넣는다, PD-5 2.4). 버리는 조각은 구간을 열지 않는다. 지시를 보낸 세대의 새 말 판단(확인 사건 · 전사 시간선 · 오디오 시계 여유)은
     * {@link OutputGeneration}이 한다(설계 개정 A4). 출력 범위 밖(ACTIVE · CLOSING 아님)에서 온 조각도 '본 조각'으로 센다(구간이 중간부터 시작하면
     * 불완전이다).
     *
     * @param startMs 출력 전사 조각의 전사 시간선 시작(음성이면 -1)
     */
    private void onOutputChunk(String transcript, long startMs, long endMs, byte[] audio, Instant at, long nanos) {
        captureJoinHeldOutput(new HeldOutput(transcript, startMs, endMs, audio, 0));
        if (transcript != null && startMs >= 0 && endMs > startMs) {
            // 이 공급자는 시간 정보가 있는 전사를 준다: 음성으로 시작하는 응답도 단위별 경로로 받는다(아래 열기 조건)
            timedTranscriptsSeen = true;
        }
        boolean drainingPractice = deferredPracticeInstruction != null;
        OutputGeneration.ChunkResult result = transcript != null
                ? ctx.outputGen().onTranscript(at, startMs, outputHeld() || drainingPractice)
                : ctx.outputGen().onAudio(at, audio.length, outputHeld() || drainingPractice);
        chunksSinceInstruction++;
        if (ctx.outputGen().takeOpenStartAccepted()) {
            // JIRA-261: 참여 직후 지시를 보낸 뒤 처음 온 조각을 새 응답으로 받았다(모델이 스스로 시작한 답일 수 있다)
            ctx.services().events().appError(EventLog.Component.OUTPUT_GATE, "JoinSelfStartLateAccepted", EventCode.Level.INFO);
        }
        if (result.verdict() == OutputGeneration.ChunkVerdict.DISCARD_HELD) discardedHeldSeen = true;
        else if (result.verdict() == OutputGeneration.ChunkVerdict.DISCARD) discardedStaleSeen = true;
        // 버리는 조각도 조용 시계를 다시 건다: 조각이 계속 오는 동안은 끊김이 아니다
        ctx.timers().arm(Kind.SEGMENT_QUIET, ctx.services().settings().segmentQuiet());
        if (!ctx.outputGen().awaitingNewSpeech()) {
            ctx.timers().cancel(Kind.INSTRUCTION_WAIT);
        }
        if (drainingPractice) return;
        if (judgment.isOpen()) {
            // 판정 턴 중 음성은 버리고 전사 조각만 모은다(구간 · 대화 기록 · 보호자 중계에 넣지 않는다)
            if (result.verdict() != OutputGeneration.ChunkVerdict.ACCEPT) {
                if (transcript != null) {
                    judgment.holdTranscript(transcript);
                }
                return;
            }
            // 지시 단계에서 받는 첫 조각 = 지시에 따른 새 말의 시작(설계 개정 A4): 턴은 닫히고 이 조각부터 지시 응답이다
            judgment.onNewSpeechStarted();
        }
        if (result.verdict() != OutputGeneration.ChunkVerdict.ACCEPT || !inOutputRange()) {
            return;
        }
        // JIRA-270: 응답이 오고 있다 — 정체 안전망을 끄고 미루는 말 판단용 끝부분을 모은다
        cancelResponseStall();
        appendResponseTail(transcript);
        if (pendingPracticeSpeech != null && practice != null && practice.handlesAudioRecovery() && !practiceCollecting) {
            practiceCollecting = true;
            ctx.timers().cancel(Kind.PRACTICE_SEND_WAIT);
            ctx.timers().cancel(Kind.INSTRUCTION_WAIT);
            // Duration of audio is still capped by SegmentBuffer. This bounds wall time even if chunks trickle forever.
            ctx.timers().arm(Kind.PRACTICE_COLLECT_MAX,
                    ctx.services().settings().playbackExpiry().plus(ctx.services().settings().instructionWait()));
            practiceAudioStage("COLLECTING");
        }
        if (streamedPractice != null && streamedPracticeEnded) return;
        // GPT-Live가 말하는 동안 아이 소리를 모아 두던 기능은 끈다: 모았다가 몰아 보내면 공급자 입력 속도 한도(input_audio_rate_limit_exceeded)에
        // 걸려 아이 말이 버려진다. 아이 소리는 늘 실시간으로 보낸다(InputRelay.setHolding은 쓰지 않는다)
        long gen = ctx.outputGen().generation();
        // Timestamped provider deltas form independent verification units. Untimed providers retain the quiet fallback.
        // B1: 승인 낭독도 다 모으지 않고 단위별 경로로 받는다. 생성형은 단위마다 제브, 승인 낭독은 단위마다 원문 앞부분 대조로 확인해 바로 낸다
        boolean streaming = guardrail.enabled() && ctx.status() == SessionStatus.ACTIVE
                && (practice == null || !practice.controlsOutput() || pendingPracticeSpeech != null);
        if (!timedOutput.isOpen() && !ctx.segment().isOpen()) {
            // 응답이 시작될 때 어느 출력 경로를 타는지와 그 이유를 사유 코드로 남긴다(전사 원문 없음). 응답이 느릴 때 단위별 방출이 안 켜졌는지 가린다
            String path = !guardrail.enabled() ? "GuardrailOff" : ctx.status() != SessionStatus.ACTIVE ? "NotActive"
                    : !streaming ? "PracticeOutput" : transcript == null && !timedTranscriptsSeen ? "FirstChunkIsAudio"
                    : !result.fromStart() ? "NotFromStart" : transcript != null && !(startMs >= 0 && endMs > startMs) ? "NoTimeRange"
                    : practice != null && practice.controlsOutput() ? "UnitsWhole" : "UnitsProgressive";
            ctx.services().events().appError(EventLog.Component.OUTPUT_GATE, "OutputPath" + path, EventCode.Level.INFO);
        }
        // GPT-Live는 아이 말이 끝나면 스스로 응답을 시작해 응답이 전사보다 음성 조각으로 먼저 시작하기도 한다. 시간 정보를 주는 공급자라면 음성으로 시작하는
        // 응답도 단위별 경로로 받는다(전사는 곧 따라오고, 단위는 전사가 와야 만들어진다)
        boolean opensTimed = !ctx.segment().isOpen() && result.fromStart()
                && (transcript != null ? startMs >= 0 && endMs > startMs : timedTranscriptsSeen);
        if (streaming && (timedOutput.isOpen() || opensTimed)) {
            if (!timedOutput.isOpen()) {
                // 여기서 이 응답을 단위별 경로로 받기로 정한다: 첫 조각이 음성 · 빈 전사여도 다음 조각부터 이 경로에 남는다
                timedOutput.open();
                progressiveResponse = practice == null || !practice.controlsOutput();
                instructionNotHeard = null;
                if (!guardrail.isChecking()) ctx.services().latency().providerFirstOut(ctx.plan().sessionId(), at);
                if (pendingPracticeSpeech != null) {
                    streamedPractice = pendingPracticeSpeech;
                    streamedPracticeEnded = false;
                    streamedPracticeSent = false;
                    streamedApprovedText.setLength(0);
                }
            }
            if (transcript != null) timedOutput.transcript(transcript, startMs, endMs, at, nanos);
            else {
                timedOutput.audio(audio, ctx.outputGen().audioClockBytes() - audio.length);
                timedAudioLastAt = at;
            }
            drainTimedOutput(at, nanos);
            return;
        }
        if (!ctx.segment().isOpen()) {
            instructionNotHeard = null;
            if (!guardrail.isChecking()) {
                // 앞 구간의 제브 검사 중에 시작한 구간의 첫 조각은 앞 턴의 지연 기록을 덮지 않는다
                ctx.services().latency().providerFirstOut(ctx.plan().sessionId(), at);
            }
            ctx.transcripts().aiStart(at);
        }
        if (transcript != null) {
            ctx.segment().addTranscript(gen, result.fromStart(), transcript, at, nanos);
            return;
        }
        SegmentBuffer.Added added = ctx.segment().addAudio(gen, result.fromStart(), audio, at, nanos);
        if (added == SegmentBuffer.Added.OVERSIZE) {
            SegmentBuffer.Sealed oversized = ctx.segment().takeOversized();
            discard(oversized, GateDecision.DiscardReason.SEGMENT_OVERSIZE);
        }
    }

    /**
     * 지시 없이 출력 세대를 올린다(끼어들기 · 종료 의도 · 연습 폐기 · 보류 해제). 열린 구간은 '불완전'으로 버리고(AI 행 FINAL ·
     * false), 끊김을 기다리는 단계로 들어가며 조용 시계를 건다. 기기에서 재생 중인 음성을 멈추는 일은 호출자가 지금처럼 {@link SessionContext#bumpGeneration}(기기
     * 프레임 세대)로 한다.
     *
     * @return 새 출력 세대 번호
     */
    private long bumpOutput(OutputGeneration.BumpReason reason) {
        flushDiscardDiagnostics();
        Instant now = ctx.services().clock().now();
        long generation = ctx.outputGen().bump(now, reason);
        responseTail.setLength(0);
        deferredPracticeInstruction = null;
        practiceCollecting = false;
        ctx.timers().cancel(Kind.PRACTICE_COLLECT_MAX);
        ctx.timers().cancel(Kind.PRACTICE_SEND_WAIT);
        ctx.timers().cancel(Kind.PRACTICE_OUTPUT_DRAIN);
        ctx.outputAuthority(newOutputAuthority(kr.mujoong.realtime.gate.OutputAuthority.Purpose.NORMAL));
        instructionNotHeard = null;
        ctx.timers().cancel(Kind.INSTRUCTION_WAIT);
        ctx.segment().reset();
        timedOutput.reset();
        resetProgressive();
        ctx.transcripts().aiFinal("", false, now);
        // 세대를 올리는 모든 경우(끼어들기 · 서버 발화 · 폐기)는 앞 응답의 끝이다: 모아 둔 AI 전사를 한 행으로 닫는다
        ctx.transcripts().endStream(now);
        ctx.relay().setHolding(false);
        ctx.timers().arm(Kind.SEGMENT_QUIET, ctx.services().settings().segmentQuiet());
        return generation;
    }

    /**
     * 구간 조용 시계 만기. 받는 단계에서는 열린 구간을 조용함으로 봉인해 게이트에 넣고, 지시 없이 올린 세대의 버리는 단계에서는 끊김을
     * 확인한다. 지시를 보낸 세대에서 전사만 받고 음성이 여유 시각에 닿지 못한 채 끝났으면 그 구간을 불완전으로 버린다.
     */
    private void onOutputQuiet() {
        if (deferredPracticeInstruction != null) { onPracticeOutputDrain(); return; }
        if (pendingOutputChunks.get() > 0) {
            // 이 만기는 안전 편지함으로 먼저 들어와 일반 편지함에 쌓인 출력 조각을 앞질렀다 — 그 조각이 같은 응답의 나머지일 수 있어 지금 끊김으로 보지
            // 않는다(조각을 처리하면 조용 시계를 다시 건다)
            return;
        }
        Instant now = ctx.services().clock().now();
        // FT-1023-e: 전사 먼저 방출 경로가 다음 조각과 합치려고 붙잡아 둔 말끝 구두점(예: "?")은 응답이 조용해지면 그대로 단위로 낸다
        if (progressiveResponse && timedOutput.hasPendingText() && inOutputRange()) {
            TimedOutputBuffer.TextUnit trailing = timedOutput.pollTrailingText(ctx.outputGen().generation(),
                    ctx.outputGeneration().get(), now, ctx.services().clock().monotonicNanos());
            if (trailing != null) {
                ctx.transcripts().aiStartStreaming(trailing.sealed().firstOutAt());
                textUnitEnds.put(trailing.sealed(), trailing.endByte());
                gateSealed(trailing.sealed(), false, true, now, ctx.services().clock().monotonicNanos());
            }
        }
        // Transcript deltas may lead PCM by up to several seconds. Silence is not an end while aligned audio is pending.
        if (timedOutput.hasPendingText()) {
            Instant deadline = timedOutput.pendingSince().plus(ctx.services().settings().instructionWait());
            if (now.isBefore(deadline)) {
                ctx.timers().arm(Kind.SEGMENT_QUIET, Duration.between(now, deadline));
                return;
            }
            // FT-1023-e: 재생이 시작된 연습 발화면 남은 전사를 버리고 나간 부분까지 재생됨으로 닫은 뒤 아래 조용 처리로 응답을 끝낸다
            if (!closeSentStreamedPractice(REASON_TAIL_TEXT_UNALIGNED)) {
                if (streamedPractice != null) closeGeneratedSlot(streamedPractice.correlationId(),
                        kr.mujoong.realtime.practicelegacy.PracticePorts.Playback.FAILED);
                bumpOutput(OutputGeneration.BumpReason.GATE_CORRECTION);
                return;
            }
        }
        OutputGeneration.QuietResult result = ctx.outputGen().onQuiet(now);
        switch (result.verdict()) {
            case SEAL_RECEIVING -> {
                if (inOutputRange()) {
                    sealAndGate(false);
                    if (progressiveResponse && (guardrail.isChecking() || guardrail.hasQueued() || !textUnitEnds.isEmpty())) {
                        // 검사 중인 단위의 음성이 아직 버퍼에 있다: 마지막 단위가 정리될 때 비운다
                        timedOutputResetPending = true;
                    } else {
                        timedOutput.reset();
                        resetProgressive();
                    }
                    var approved = streamedPractice;
                    if (approved != null && !approved.generated() && approved.equals(pendingPracticeSpeech)
                            && !approved.matches(streamedApprovedText.toString())) {
                        // B1: 응답이 원문 끝까지 오지 않았다. 나간 부분이 있으면 거기까지 재생됨으로, 없으면 원문 불일치로 재요청 길로 보낸다
                        if (streamedPracticeSent) {
                            ctx.services().events().appError(EventLog.Component.OUTPUT_GATE, REASON_APPROVED_TAIL_MISSING,
                                    EventCode.Level.INFO);
                        } else {
                            rejectApprovedReading(approved);
                        }
                    }
                    streamedPracticeEnded = true;
                    finishStreamedPractice();
                    // 응답이 끝났다: 모아 둔 AI 전사를 한 행으로 닫는다(검사 중인 단위가 남았으면 마지막 단위가 정리될 때 닫힌다)
                    ctx.transcripts().endStream(now);
                    ctx.relay().setHolding(false);
                    onResponseEnded();
                }
            }
            case NOT_YET -> ctx.timers().arm(Kind.SEGMENT_QUIET, Duration.between(now, result.waitUntil()));
            case ABANDON_NO_AUDIO -> {
                if (inOutputRange() && ctx.segment().isOpen()) {
                    discard(ctx.segment().sealByGeneration(ctx.outputGeneration().get()), GateDecision.DiscardReason.GATE_EMPTY,
                            true, null, true, DiscardDetail.PROVIDER_NO_AUDIO);
                }
                ctx.timers().arm(Kind.SEGMENT_QUIET, ctx.services().settings().segmentQuiet());
            }
            case GAP_CONFIRMED, NONE -> {
                // 끊김 확인(다음 조각부터 받는다) 또는 할 일 없음
            }
        }
    }

    /**
     * 지시 응답 시한 만기(설계 개정 A3 · 4.6). 확인 사건이 안 왔으면 사유 {@code OutputAckMissing}을 남기고 시한을 한 번 더 걸어 전사로 새 말을
     * 찾아 계속한다. 확인은 왔는데 새 말 전사가 시한 안에 없거나 두 번째 시한도 지났으면 사유 {@code OutputNewSpeechNotObserved}를 남기고 이 세대를
     * 닫는다 — 아이는 아무 소리도 듣지 않는다(안전 쪽 실패). 연습 발화는 확장에 실패를 알린다.
     */
    private void recoverPracticeAudio(kr.mujoong.realtime.voice.PracticeSpeech speech) {
        if (practice != null && practice.handlesAudioRecovery()) {
            ctx.mailbox().putSafety(new SessionMailbox.PracticeEvent(new PracticeSessionExtension.ExtensionEvent(
                    PracticeSessionExtension.ExtensionEvent.Kind.ASYNC_RESULT, "practice-audio-incomplete",
                    speech.correlationId(), speech)));
        } else practicePlayback(speech, kr.mujoong.realtime.practicelegacy.PracticePorts.Playback.FAILED);
    }

    private void practiceAudioStage(String stage) {
        ctx.services().events().log(EventCode.PRACTICE_AUDIO_STAGE, EventLog.Component.OUTPUT_GATE,
                ctx.plan().sessionId(), ctx.plan().traceId(), entry -> entry
                .attr(AttrKey.AUDIO_STAGE, stage).attr(AttrKey.OUTPUT_GENERATION, ctx.outputGen().generation())
                .attr(AttrKey.PRACTICE_PHASE, practice == null ? "NONE" : practice.diagnosticPhase())
                .attr(AttrKey.PRACTICE_SUB, practice == null ? "NONE" : practice.diagnosticSub()));
    }

    private void onPracticeSendWait() {
        if (pendingPracticeSpeech == null || practiceCollecting) return;
        practiceAudioStage("SEND_TIMEOUT");
        var speech = pendingPracticeSpeech; pendingPracticeSpeech = null; practiceHold = true;
        bumpOutput(OutputGeneration.BumpReason.PRACTICE_DISCARD);
        recoverPracticeAudio(speech);
    }

    private void onPracticeCollectMax() {
        if (pendingPracticeSpeech == null || !practiceCollecting) return;
        practiceAudioStage("COLLECTION_TIMEOUT");
        if (ctx.segment().isOpen()) discard(ctx.segment().sealByGeneration(ctx.outputGeneration().get()),
                GateDecision.DiscardReason.GATE_EMPTY, false, null, true, DiscardDetail.PROVIDER_COLLECTION_TIMEOUT);
        else if (streamedPractice != null) {
            logOutputDiscard(null, null, GateDecision.DiscardReason.GATE_EMPTY,
                    DiscardDetail.PROVIDER_COLLECTION_TIMEOUT, streamedPractice);
            practicePlayback(streamedPractice, kr.mujoong.realtime.practicelegacy.PracticePorts.Playback.FAILED);
        }
    }

    private void onPracticeOutputDrain() {
        var deferred = deferredPracticeInstruction;
        if (deferred == null) return;
        if (!practiceActive() || pendingPracticeSpeech == null
                || !deferred.speechId().equals(pendingPracticeSpeech.correlationId())) {
            deferredPracticeInstruction = null;
            return;
        }
        Instant now = ctx.services().clock().now();
        if (!now.isBefore(deferred.deadline())) {
            deferredPracticeInstruction = null;
            practiceAudioStage("DRAIN_TIMEOUT");
            ctx.services().events().appError(EventLog.Component.OUTPUT_GATE, "PracticeOutputDrainTimeout", EventCode.Level.WARN);
            if (deferred.job().onFailure != null) deferred.job().onFailure.run();
            return;
        }
        Instant last = ctx.outputGen().lastDeltaAt();
        Instant ready = last == null ? now : last.plus(ctx.services().settings().segmentQuiet());
        // Let a pending child utterance reach stop/rest classification before starting more audio.
        if (pendingOutputChunks.get() > 0 || inputGroupId != null || now.isBefore(ready)) {
            ctx.timers().arm(Kind.PRACTICE_OUTPUT_DRAIN, now.isBefore(ready)
                    ? Duration.between(now, ready) : ctx.services().settings().segmentQuiet());
            return;
        }
        deferredPracticeInstruction = null;
        chunksSinceInstruction = 0;
        ctx.outputGen().bumpForQuietInstruction(now, deferred.reason(), deferred.job().append.eventId());
        ctx.outputAuthority(newOutputAuthority(ctx.outputAuthority().purpose()));
        ctx.segment().reset();
        timedOutput.reset();
        resetProgressive();
        ctx.transcripts().aiFinal("", false, now);
        ctx.timers().cancel(Kind.SEGMENT_QUIET);
        ctx.timers().arm(Kind.PRACTICE_SEND_WAIT, ctx.services().settings().instructionWait());
        instructionNotHeard = deferred.job().onFailure;
        practiceAudioStage("REQUEST_QUEUED");
        ctx.sendQueue().submit(deferred.job());
    }

    private void onInstructionWaitExpired() {
        switch (ctx.outputGen().onInstructionTimeout()) {
            case ACK_MISSING -> {
                ctx.services().events().appError(EventLog.Component.VOICE_RELAY, "OutputAckMissing", EventCode.Level.WARN);
                ctx.timers().arm(Kind.INSTRUCTION_WAIT, ctx.services().settings().instructionWait());
            }
            case NEW_SPEECH_NOT_OBSERVED -> {
                if (pendingPracticeSpeech != null) practiceAudioStage("FIRST_OUTPUT_TIMEOUT");
                // 진단(D): 지시 뒤 조각이 하나도 없으면 공급자가 침묵한 것이고, 있었으면 모두 지시 전의 낡은 말로 버린 것이다
                ctx.services().events().appError(EventLog.Component.VOICE_RELAY, chunksSinceInstruction == 0
                        ? "OutputNotObservedProviderSilent" : "OutputNotObservedOnlyStaleChunks", EventCode.Level.WARN);
                ctx.services().events().appError(EventLog.Component.VOICE_RELAY, "OutputNewSpeechNotObserved", EventCode.Level.WARN);
                Runnable notHeard = instructionNotHeard;
                instructionNotHeard = null;
                ctx.timers().arm(Kind.SEGMENT_QUIET, ctx.services().settings().segmentQuiet());
                if (notHeard != null) {
                    notHeard.run();
                }
            }
            case NONE -> {
                // 이미 새 말이 시작했거나 다른 세대다
            }
        }
    }

    /**
     * 서버 발화 하나를 보낸다(설계 개정 A2): 공급자에는 응답 요청 type이 없어 지시({@code session.instructions.append}) 하나가 곧 발화다. 출력
     * 세대를 올려 낡은 조각을 바로 버리고(DISCARDING), 지시 응답 시한을 걸고, 직렬 큐로 보낸다. 공급자 연결이 없으면 보내지 않고 거짓.
     *
     * @param build 지시 id를 받아 보낼 지시를 만든다(확인 사건의 {@code client_event_id}와 맞춘다)
     * @param onFailure 송신이 실패하거나 새 말이 시한 안에 시작하지 않았을 때 부를 일(없으면 null)
     */
    private boolean sendInstruction(OutputGeneration.BumpReason reason, String name,
            java.util.function.Function<String, ProviderRequestFactory.InstructionsAppend> build, Runnable onFailure) {
        return sendInstruction(reason, name, build, onFailure, false);
    }

    /**
     * @param replayable 제브 막힘 뒤 같은 지시를 한 번 다시 보낼 수 있게 마지막 서버 지시로 기억하는가(U13 W2b: 판정 뒤 다음 행동 지시 · 응답 시작
     *     지시만 참). 거짓이면 기억을 지운다(다른 종류의 지시 · 재지시 자신)
     */
    private boolean sendInstruction(OutputGeneration.BumpReason reason, String name,
            java.util.function.Function<String, ProviderRequestFactory.InstructionsAppend> build, Runnable onFailure,
            boolean replayable) {
        return sendInstruction(reason, name, build, onFailure, replayable, kr.mujoong.realtime.gate.OutputAuthority.Purpose.NORMAL);
    }

    private boolean sendInstruction(OutputGeneration.BumpReason reason, String name,
            java.util.function.Function<String, ProviderRequestFactory.InstructionsAppend> build, Runnable onFailure,
            boolean replayable, kr.mujoong.realtime.gate.OutputAuthority.Purpose purpose) {
        if (ctx.plan().activityType() == ActivityType.FREE_TALK && ctx.conversationPolicy().resting()
                && (purpose != kr.mujoong.realtime.gate.OutputAuthority.Purpose.REST_ACK
                    || !ctx.conversationPolicy().claimRestAck())) return false;
        ProviderSession session = ctx.providerSession();
        if (session == null || !session.isOpen()) {
            return false;
        }
        String eventId = "mj-" + (++instructionSeq);
        ProviderRequestFactory.InstructionsAppend request = build.apply(eventId);
        Instant now = ctx.services().clock().now();
        flushDiscardDiagnostics();
        chunksSinceInstruction = 0;
        if (pendingPracticeSpeech != null && practice != null && practice.controlsOutput()) {
            // An append ACK is context delivery, not the first audio sample. Wait for old speech
            // to finish BEFORE the request, then collect the full next utterance without chopping it.
            bumpOutput(OutputGeneration.BumpReason.PRACTICE_DISCARD);
            var speechRequest = pendingPracticeSpeech;
            deferredPracticeInstruction = new DeferredPracticeInstruction(reason,
                    new ProviderSendQueue.Job(name, session, request, onFailure,
                            () -> practiceOutput.post("practice-request-sent", speechRequest),
                            () -> speechRequest.equals(pendingPracticeSpeech)), pendingPracticeSpeech.correlationId(),
                    now.plus(ctx.services().settings().instructionWait()));
            ctx.outputAuthority(newOutputAuthority(purpose));
            streamedPracticeEnded = false;
            guardrail.onServerInstruction(replayable ? new GuardrailCoordinator.ReplayableInstruction(name, build) : null);
            practiceAudioStage("DRAINING");
            cancelResponseStall();
            onPracticeOutputDrain();
            return true;
        }
        if (instructionAfterQuietGap && ctx.outputGen().phase() == OutputGeneration.Phase.GAP_CONFIRMED) {
            ctx.outputGen().bumpForInstructionAfterQuietGap(now, reason, eventId);
        } else {
            ctx.outputGen().bumpForInstruction(now, reason, eventId);
        }
        streamedPracticeEnded = false;
        responseTail.setLength(0);
        // 서버가 발화를 지시했다: 그 응답은 지시 응답 시한(INSTRUCTION_WAIT)이 지킨다
        cancelResponseStall();
        ctx.outputAuthority(newOutputAuthority(purpose));
        ctx.segment().reset();
        timedOutput.reset();
        resetProgressive();
        ctx.transcripts().aiFinal("", false, now);
        ctx.timers().cancel(Kind.SEGMENT_QUIET);
        ctx.timers().arm(Kind.INSTRUCTION_WAIT, ctx.services().settings().instructionWait());
        instructionNotHeard = onFailure;
        guardrail.onServerInstruction(replayable ? new GuardrailCoordinator.ReplayableInstruction(name, build) : null);
        ctx.sendQueue().submit(new ProviderSendQueue.Job(name, session, request, onFailure));
        return true;
    }

    /**
     * 출력 보류가 풀렸으면(참 → 거짓) 세대를 올린다: 보류 중 시작된 자동 응답의 꼬리가 풀린 직후 기기로 가지 않게 한다. 이번 편지함
     * 처리에서 이미 세대를 올렸으면(같은 풀림에서 서버 발화가 올렸다) 또 올리지 않는다.
     */
    private void watchHold(long generationBefore) {
        boolean held = outputHeld();
        boolean released = lastHeld && !held;
        lastHeld = held;
        if (released && ctx.outputGen().generation() == generationBefore && inOutputRange() && !ctx.terminated()) {
            bumpOutput(OutputGeneration.BumpReason.HOLD_RELEASED);
        }
    }

    /**
     * 공급자 {@code session.error}. 시작 전이면 PROVIDER_UNAVAILABLE(contracts/ai-io.md 3절, U1 그대로). 시작 뒤에는 연결이 살아 있을 수
     * 있어 세션을 끝내지 않는다 — PD 2.1-C의 WARN 한 줄만 남기고, 완결되지 않은 구간은 게이트가 폐기해 대기 문구로 잇는다(U1 그대로).
     * 연결이 실제로 끊기면 뒤따르는 끊김 알림이 {@link #providerLost}로 끝낸다.
     */
    private void providerError(ProviderEvent.Error error) {
        ctx.services().events().appError(EventLog.Component.VOICE_RELAY, error.diagnosticKind(), EventCode.Level.WARN);
        if (START_PHASE.contains(ctx.status())) {
            ending.multiOriginFail(Trigger.PROVIDER_FAILED, FailureCode.PROVIDER_UNAVAILABLE, SessionTransitions.Origin.REALTIME);
            return;
        }
        String code = error.code();
        if (code != null && code.startsWith("input_")) {
            // 입력 쪽 오류(예: input_audio_rate_limit_exceeded)는 지금 나오는 AI 응답과 관계없다: 응답을 버리지 않는다
            return;
        }
        // 응답 도중 오류면 열린 구간은 끝까지 오지 않은 응답이다: 응답 끝 사건이 없으니 완결로 볼 수 없어 불완전으로 닫아 폐기하고 대기 문구로 잇는다
        if (inOutputRange() && ctx.segment().isOpen()) {
            discard(ctx.segment().sealByGeneration(ctx.outputGeneration().get()), GateDecision.DiscardReason.GATE_EMPTY,
                    true, null, true, DiscardDetail.PROVIDER_ERROR);
        } else if (inOutputRange() && timedOutput.isOpen()) {
            // 단위별 검사 경로는 구간 버퍼가 아니라 단위 버퍼에 쌓는다: 아직 검사를 통과하지 못한 나머지(대기 단위 · 검사 중인 단위의 늦은 ALLOW)도
            // 끝 신호가 없는 응답의 일부라 세대를 올려 무효화하고, 이미 나간 단위는 그대로 둔 채 대기 문구로 잇는다
            bumpOutput(OutputGeneration.BumpReason.GATE_CORRECTION);
            discard(null, GateDecision.DiscardReason.GATE_EMPTY, false);
        }
    }

    private static final java.util.regex.Pattern PROVIDER_ERROR_CODE = java.util.regex.Pattern.compile("^[a-z][a-z0-9_]{1,60}$");

    private static String safeProviderErrorCode(String code) {
        return code != null && PROVIDER_ERROR_CODE.matcher(code).matches() ? code : "unknown";
    }

    /** 공급자 연결 끊김({@code session.closed} · WSS 닫힘 · 전송 오류). */
    private void providerLost() {
        if (ctx.providerClosing()) {
            // 실행기가 스스로 닫은 공급자의 끊김 알림이다(정리 · 앱 종료 T3)
            ctx.providerClosedSignal(true);
            return;
        }
        if (START_PHASE.contains(ctx.status())) {
            ending.multiOriginFail(Trigger.PROVIDER_FAILED, FailureCode.PROVIDER_UNAVAILABLE, SessionTransitions.Origin.REALTIME);
            return;
        }
        // PD 2.1-C의 WARN 한 줄은 그대로 남긴다
        ctx.services().events().appError(EventLog.Component.VOICE_RELAY, "ProviderDisconnected", EventCode.Level.WARN);
        judgment.onFault();
        if (Trigger.DISCONNECT.accepts(ctx.status())) {
            abortProviderLost();
        }
        // CLOSING 중에는 30초 확인 시한이 우선한다(U1 그대로)
    }

    /**
     * B18: ACTIVE 중 공급자가 끊기면 음성 모델 없이 대화를 이을 수 없어 기존 전이 ACTIVE → ABORTED(DISCONNECT)로
     * 바로 끝내고 end_detail에 PROVIDER_LOST를 적는다. 연결이 살아 있으면 CLOSE_TRANSPORT(DISCONNECT) + STANDBY.
     */
    private void abortProviderLost() {
        judgment.onFault();
        Instant now = ctx.services().clock().now();
        Optional<ActivitySessionRow> row = ctx.services().transitions().transition(ctx.plan().sessionId(), ctx.status(),
                SessionStatus.ABORTED, TransitionTable.changeFor(Trigger.DISCONNECT, null, now)
                        .withEndDetail(END_DETAIL_PROVIDER_LOST).withProviderClosedAt(now),
                SessionTransitions.Origin.REALTIME, ctx.plan().traceId(), ending.transitionExtras());
        if (row.isEmpty()) {
            ending.syncFromDb();
            return;
        }
        ending.changeStatus(SessionStatus.ABORTED, ctx.services().clock().now());
        ending.closeProvider();
        ctx.bumpGeneration();
        DeviceLink current = ctx.link();
        if (current != null && current.isOpen()) {
            ctx.sendControl(new CloseTransport(CloseTransport.Reason.DISCONNECT));
            ctx.sendControl(new SessionState(View.STANDBY));
        }
        ending.afterTerminal();
    }

    /**
     * U4b: ACTIVE의 확정 아이 전사로 종료 의도를 판정한다(기기 재생 중에 확정된 전사도 판정한다, 2026-10-22 결정).
     * 등록 종료 표현(STOP)이면 곧바로 CLOSING(CHILD_STOP)이고, 그 밖이면 대화를 잇는다(FT-1023-a). 불명확한 종료 의도로 확인 질문(STOP_CONFIRM)을 여는 흐름과
     * U3의 '막고 ACTIVE 유지 → 적극적 계속이면 해제'(CHILD_STOP_LIFTED) 경로는 없앴다(contracts/deviations.md FT-1022-b · FT-1022-c).
     *
     * @return 이번 발화를 종료 의도 흐름이 맡았으면 참('생각 중'을 보내지 않는다)
     */
    private boolean judgeStopIntent(String transcript, boolean uncertain, Instant at) {
        if (ctx.status() != SessionStatus.ACTIVE) {
            return false;
        }
        if (ctx.plan().activityType() == ActivityType.PRACTICE && stopJudge.requestsRest(transcript)) {
            stopOutputForIntent(at);
            interruptPracticeSpeech(at);
            ctx.mailbox().putSafety(new SessionMailbox.PracticeEvent(new PracticeSessionExtension.ExtensionEvent(
                    PracticeSessionExtension.ExtensionEvent.Kind.REST_REQUESTED, "practice-rest", null, null)));
            return true;
        }
        if (ctx.plan().activityType() == ActivityType.FREE_TALK) {
            if (stopJudge.requestsImmediateMute(transcript)) {
                // "말하지 마"는 지금 말을 멈추라는 뜻이다. 세션은 등록 종료 표현으로만 끝나므로 닫지 않는다(FT-1023-a)
                stopOutputForIntent(at);
                return true;
            }
            if (stopJudge.requestsRest(transcript)) {
                if (ctx.conversationPolicy().rest()) {
                    stopOutputForIntent(at);
                    sendInstruction(OutputGeneration.BumpReason.SERVER_SEND, "provider-rest-",
                            id -> ctx.services().requests().instructionsAppend(id,
                                    InstructionTemplates.NextAction.REST_ACK_ONLY.content(ActivityType.FREE_TALK)), null, false,
                            kr.mujoong.realtime.gate.OutputAuthority.Purpose.REST_ACK);
                }
                return true;
            }
            ctx.conversationPolicy().resumeOnNewInput();
            if (stopJudge.refusesTopic(transcript)) {
                ctx.conversationPolicy().refuseCurrent();
                stopOutputForIntent(at);
                sendInstruction(OutputGeneration.BumpReason.SERVER_SEND, "provider-topic-refusal-",
                        id -> ctx.services().requests().instructionsAppend(id, InstructionTemplates.scoped(
                                "그 이야기는 하지 않겠다는 선택을 짧게 수용하고 새 질문 없이 기다린다. 같은 정보를 표현만 바꾸어 요구하지 않는다.")), null);
                return true;
            }
        }
        StopIntentJudge.Judgment stopJudgment = stopJudge.judge(transcript);
        if (stopJudgment.verdict() != StopIntentJudge.Verdict.STOP) {
            return false;
        }
        ctx.putEvent(EVENT_CHILD_STOP_INTENT, "{\"basis\":\"" + stopJudgment.basis().name() + "\"}", at);
        closeForStop(at);
        return true;
    }

    /** 종료 의도 확정: 출력을 멈추고 CLOSING(CHILD_STOP)으로 간다. */
    private void closeForStop(Instant at) {
        ctx.confirmedStopSeq(currentTranscriptSeq > 0 ? currentTranscriptSeq : null);
        stopOutputForIntent(at);
        ending.beginClosing(Trigger.CHILD_STOP_CONFIRMED, null);
    }

    // ------------------------------------------------------------------ 출력 보조

    /**
     * 종료 의도: 남은 프레임을 보내지 않고(기기 프레임 세대 올림) 출력 세대를 올려 만들던 · 재생 중이던 응답의 남은 조각을 버리고 열린 구간을
     * 버린다. 공급자에는 응답 취소 type이 없어 아무것도 보내지 않는다(편차).
     */
    private void stopOutputForIntent(Instant at) {
        ctx.bumpGeneration();
        bumpOutput(OutputGeneration.BumpReason.STOP_INTENT);
        guardrail.onTurnBoundary();
        ctx.transcripts().aiFinal("", false, at);
    }

    /** 아이 말이 끝났다(말 끝 · 전사 확정) — 듣는 중이고 재생이 없으면 '생각 중'. */
    private void thinking() {
        if (ctx.status() == SessionStatus.ACTIVE && ctx.view() == View.LISTENING && ctx.devicePlaying().isEmpty() && !outputHeld()) {
            ctx.sendControl(new SessionState(View.THINKING));
        }
    }

    /** 재생이 모두 끝났거나 보낼 응답이 없다 — 생각 중 · 재생 뒤면 '듣는 중'으로 돌아간다. */
    private void listeningAgain() {
        if (judgment.isOpen() || practiceThinking || guardrail.holdsThinking()) {
            // 열린 판정 턴(또는 확장이 요청한 생각 중 유지) 동안은 듣는 중으로 되돌리지 않는다.
            // 제브 검사 · 재지시 응답을 기다리는 동안도 같다
            return;
        }
        if (ctx.status() == SessionStatus.ACTIVE && ctx.view() != View.LISTENING && ctx.devicePlaying().isEmpty()
                && !ctx.playback().isPlaying()) {
            ctx.sendControl(new SessionState(View.LISTENING));
        }
    }

    /** 확정 전사가 비었거나 공급자 신뢰도가 기준 미만이면 불확실. 신뢰도가 없으면 불확실로 보지 않는다. */
    private boolean uncertain(ProviderEvent.InputTranscriptDone done) {
        if (done.transcript() == null || done.transcript().isBlank()) {
            return true;
        }
        Double confidence = done.confidence();
        return confidence != null && confidence * MAX_PERCENT < ctx.services().settings().uncertainBelowPercent();
    }

    // ------------------------------------------------------------------ 게이트

    private void yieldUnplayedPracticeToInput(SegmentBuffer.Sealed sealed) {
        guardrail.onDiscardedBeforeCheck();
        if(sealed!=null)recordDiscard(sealed,null,GateDecision.DiscardReason.GATE_EMPTY,true,
                DiscardDetail.INPUT_SUPERSEDED_UNPLAYED);
        else logOutputDiscard(null, null, null, DiscardDetail.INPUT_SUPERSEDED_UNPLAYED, pendingPracticeSpeech);
        var speech=pendingPracticeSpeech;
        pendingPracticeSpeech=null;
        practiceHold=true;
        ctx.timers().cancel(Kind.INSTRUCTION_WAIT);
        instructionNotHeard=null;
        bumpOutput(OutputGeneration.BumpReason.PRACTICE_DISCARD);
        practicePlayback(speech,kr.mujoong.realtime.practicelegacy.PracticePorts.Playback.INTERRUPTED);
    }

    /**
     * 구간 확정(조용 시간 만기 · 아이 입력 재개) → 검사 → 송신 직전 재확인 → 송신 또는 폐기(PD-3 표 2.3-C).
     *
     * @param movedOn 아이 입력 재개 · 다른 응답 도착으로 확정했는가(대화가 이미 넘어가 완결 요청 교정을 보내지 않는다)
     */
    private void sealAndGate(boolean movedOn) {
        ctx.timers().cancel(Kind.SEGMENT_QUIET);
        Instant confirmedAt = ctx.services().clock().now();
        long confirmedNanos = ctx.services().clock().monotonicNanos();
        // 아이 입력 재개로 닫을 때는 조각이 이미 끊김 시간만큼 끊겨 있었어야 '완전'이다(응답이 이어지던 중이면 불완전)
        Instant lastOutAt = ctx.segment().lastOutAt();
        boolean quietEnough = lastOutAt != null && !confirmedAt.isBefore(lastOutAt.plus(ctx.services().settings().segmentQuiet()));
        SegmentBuffer.Sealed sealed = movedOn ? ctx.segment().sealEarly(ctx.outputGeneration().get(), quietEnough)
                : ctx.segment().sealQuiet(ctx.outputGeneration().get());
        if (movedOn && !quietEnough && pendingPracticeSpeech != null && pendingPracticeSpeech.generated()
                && practice != null && practice.canYieldUnplayedSpeech()) {
            yieldUnplayedPracticeToInput(sealed);
            return;
        }
        gateSealed(sealed, movedOn, false, confirmedAt, confirmedNanos);
    }

    /**
     * 전사 먼저 방출 경로에서 유효한 ALLOW(또는 원문 일치)를 받은 단위를 내보낸다. 송신 직전 재확인(ACTIVE · 현재 세대 · 기기 연결 · 출력 권한
     * · 기록기 지연)은 단위마다 하고, 통과하면 보호자 화면 행에 전사를 모으고 이 단위가 끝나는 위치까지 도착한 음성을 바로 낸다. 아직 오지 않은 음성은
     * 도착하는 대로 {@link #pumpApprovedAudio}가 낸다.
     */
    private boolean releaseTextUnit(SegmentBuffer.Sealed sealed, TranscriptWriter.Held held, boolean ownsLatency) {
        Long end = textUnitEnds.remove(sealed);
        if (end == null || duplicateGreeting(sealed)) {
            discard(sealed, GateDecision.DiscardReason.GATE_PATTERN, false, held, ownsLatency);
            return false;
        }
        DeviceLink target = ctx.link();
        if (ctx.outputAuthority() == null) ctx.outputAuthority(newOutputAuthority(
                kr.mujoong.realtime.gate.OutputAuthority.Purpose.NORMAL));
        boolean activeAndCurrent = ctx.status() == SessionStatus.ACTIVE && sealed.generation() == ctx.outputGeneration().get()
                && target != null && target.isOpen() && hasOutputAuthority(ctx.outputAuthority(), sealed);
        OutputGate.Rechecked<Boolean> rechecked = ctx.services().gate().recheck(activeAndCurrent,
                ctx.services().recorder().isDegraded(ctx.plan().sessionId()), () -> Optional.of(Boolean.TRUE));
        if (!rechecked.decision().passed()) {
            discard(sealed, rechecked.decision().reason(), true, held, ownsLatency);
            return false;
        }
        Instant passAt = ctx.services().clock().now();
        correctionsInARow = 0;
        approvedEndByte = Math.max(approvedEndByte, end);
        appendProgressiveSubtitle(sealed.transcript());
        if (held != null) {
            ctx.transcripts().aiFinal(held, sealed.transcript(), true, passAt);
        } else {
            ctx.transcripts().aiFinal(sealed.transcript(), true, passAt);
        }
        pumpApprovedAudio();
        maybeFinishTimedOutput();
        return true;
    }

    /** 통과한 단위들의 끝 위치까지 도착한 음성을 한 묶음으로 기기에 보낸다(도착한 만큼만, 더 오면 다음에 이어 낸다). */
    /** 진단: 이 비율(%) 이상이 디지털 무음 표본이면 '대부분 무음' 묶음으로 본다(시간 값이 아니다). */
    private static final int MOSTLY_SILENT_PERCENT = 90;

    /**
     * 진단(2026-10-23 현장 시험): 기기가 "무중이가 말하는 중"인데 소리가 나지 않는 경우를 가린다. 기기로 보내는 AI 음성 묶음 하나가 디지털 무음(표본 값
     * 0)으로만 되어 있으면 {@code OutputBundleAllSilent}, {@value #MOSTLY_SILENT_PERCENT}% 이상이면 {@code OutputBundleMostlySilent}를 남긴다.
     * 응답의 첫 묶음이면 이름에 {@code First}가 붙는다(예: {@code OutputBundleFirstAllSilent}). 묶음의 공급자 음성이 아이의 마지막 입력 조각보다
     * 먼저 끝났으면(아이가 말하는 중에 앞 응답이 늦게 나감) {@code OutputBundleAfterChildInput}을 남긴다. 사유 코드만 남기고 원문 · 음성은 남기지 않는다(R-F14).
     *
     * @param audioLastAt 묶음에 든 공급자 음성이 마지막으로 들어온 시각(모르면 null)
     */
    private void logBundleSilence(List<byte[]> chunks, boolean firstOfResponse, Instant audioLastAt) {
        long samples = 0;
        long silent = 0;
        for (byte[] chunk : chunks) {
            for (int i = 0; i + 1 < chunk.length; i += 2) {
                samples++;
                if (chunk[i] == 0 && chunk[i + 1] == 0) {
                    silent++;
                }
            }
        }
        String where = firstOfResponse ? "First" : "";
        if (samples > 0 && silent == samples) {
            ctx.services().events().appError(EventLog.Component.OUTPUT_GATE, "OutputBundle" + where + "AllSilent", EventCode.Level.INFO);
        } else if (samples > 0 && silent * 100 >= samples * MOSTLY_SILENT_PERCENT) {
            ctx.services().events().appError(EventLog.Component.OUTPUT_GATE, "OutputBundle" + where + "MostlySilent",
                    EventCode.Level.INFO);
        }
        if (lastChildInputAt != null && audioLastAt != null && audioLastAt.isBefore(lastChildInputAt)) {
            ctx.services().events().appError(EventLog.Component.OUTPUT_GATE, "OutputBundleAfterChildInput", EventCode.Level.INFO);
        }
    }

    /** 나눠 보내기에서 게이트를 통과했지만 아직 묶음에 싣지 않은 글. 다음 묶음의 OUTPUT_BEGIN.text로 한 번 나간다. */
    private void appendProgressiveSubtitle(String transcript) {
        if (transcript == null || transcript.isBlank()) {
            return;
        }
        if (!progressiveSubtitle.isEmpty()) {
            progressiveSubtitle.append(' ');
        }
        progressiveSubtitle.append(transcript.strip());
    }

    private void pumpApprovedAudio() {
        if (!progressiveResponse || approvedEndByte <= 0) {
            return;
        }
        DeviceLink target = ctx.link();
        var authority = ctx.outputAuthority();
        long generation = ctx.outputGeneration().get();
        if (ctx.status() != SessionStatus.ACTIVE || target == null || !target.isOpen() || authority == null
                || !hasOutputAuthority(authority, null)) {
            return;
        }
        List<byte[]> chunks = timedOutput.drainAudio(approvedEndByte);
        if (chunks.isEmpty()) {
            return;
        }
        int total = 0;
        for (byte[] chunk : chunks) {
            total += chunk.length;
        }
        if (total == 0 || total % 2 != 0) {
            return;
        }
        Optional<DeviceLink.AudioReservation> reservation = target.reserveAudio(total);
        if (reservation.isEmpty()) {
            ctx.services().metrics().gateDiscard(GateDecision.DiscardReason.OUTBOUND_FULL.name());
            return;
        }
        Instant now = ctx.services().clock().now();
        UUID playbackId = UUID.randomUUID();
        boolean first = !progressiveSoundStarted;
        progressiveSoundStarted = true;
        logBundleSilence(chunks, first, timedAudioLastAt);
        greetingHandoff.sent(playbackId, generation, target.connectionId(), now.plus(ctx.services().settings().playbackExpiry()));
        if (first) {
            ctx.services().latency().gatePassed(ctx.plan().sessionId(), playbackId, now);
        }
        AudioRecordings.Session aiRecording = ctx.recording();
        OutputBundle bundle = new OutputBundle(ctx.plan().sessionId(), playbackId, OutputBegin.Source.GPT_LIVE,
                OutputBundle.frame(chunks), total / 48L, reservation.get(),
                () -> ctx.outputGeneration().get() == generation && ctx.outputAuthority() == authority
                        && hasOutputAuthority(authority, null),
                firstFrameAt -> {
                    greetingHandoff.frameSent(playbackId);
                    if (first) {
                        ctx.services().latency().firstFrameSent(ctx.plan().sessionId(), playbackId, firstFrameAt);
                    }
                },
                aiRecording == null ? frame -> { } : aiRecording::ai, progressiveSubtitle.toString());
        progressiveSubtitle.setLength(0);
        if (!target.sendOutput(bundle)) {
            greetingHandoff.failed(playbackId);
            ctx.services().metrics().gateDiscard(GateDecision.DiscardReason.OUTBOUND_FULL.name());
        }
    }

    /** 응답은 끝났고 검사 중인 단위도 없으면 단위 버퍼를 비운다(검사가 남아 있으면 마지막 단위가 정리될 때). */
    private void maybeFinishTimedOutput() {
        if (timedOutputResetPending && !guardrail.isChecking() && !guardrail.hasQueued() && textUnitEnds.isEmpty()) {
            timedOutput.reset();
            resetProgressive();
        }
    }

    private void resetProgressive() {
        textUnitEnds.clear();
        approvedEndByte = 0;
        progressiveSubtitle.setLength(0);
        progressiveSoundStarted = false;
        firstUnitLogged = false;
        timedOutputResetPending = false;
        progressiveResponse = false;
    }

    private void drainTimedOutput(Instant at, long nanos) {
        if (progressiveResponse) {
            TimedOutputBuffer.TextUnit text;
            while ((text = timedOutput.pollText(ctx.outputGen().generation(), ctx.outputGeneration().get(), at, nanos)) != null) {
                if (!firstUnitLogged) {
                    // 첫 전사 조각이 들어온 시각(OutputPath 로그)과 이 로그의 시각 차이가 새 말 음성이 시작되기까지의 기다림이다
                    firstUnitLogged = true;
                    ctx.services().events().appError(EventLog.Component.OUTPUT_GATE, "OutputFirstUnitFormed", EventCode.Level.INFO);
                }
                ctx.transcripts().aiStartStreaming(text.sealed().firstOutAt());
                textUnitEnds.put(text.sealed(), text.endByte());
                gateSealed(text.sealed(), false, true, at, nanos);
            }
            pumpApprovedAudio();
            if (timedOutput.failed()) {
                ctx.services().events().appError(EventLog.Component.OUTPUT_GATE, "OutputUnitsFailed" + timedOutput.failReason(),
                        EventCode.Level.INFO);
                bumpOutput(OutputGeneration.BumpReason.GATE_CORRECTION);
            }
            return;
        }
        SegmentBuffer.Sealed unit;
        while ((unit = timedOutput.poll(ctx.outputGen().generation(), ctx.outputGeneration().get(), at, nanos)) != null) {
            ctx.transcripts().aiStartStreaming(unit.firstOutAt());
            gateSealed(unit, false, true, at, nanos);
        }
        if (timedOutput.failed()) {
            if (streamedPractice != null) closeGeneratedSlot(streamedPractice.correlationId(),
                    kr.mujoong.realtime.practicelegacy.PracticePorts.Playback.FAILED);
            // Invalid alignment or an excessive backlog invalidates the remaining provider generation.
            bumpOutput(OutputGeneration.BumpReason.GATE_CORRECTION);
        }
    }

    private void gateSealed(SegmentBuffer.Sealed sealed, boolean movedOn, boolean streaming,
            Instant confirmedAt, long confirmedNanos) {
        if (sealed == null) return;
        if (!guardrail.isChecking()) {
            // 앞 구간의 제브 검사 중에 봉인된 구간은 앞 턴의 구간 확정 시각 · 지연 기록을 덮지 않는다
            ctx.services().latency().segmentConfirmed(ctx.plan().sessionId(), confirmedAt, confirmedNanos, sealed.firstOutNanos(),
                    sealed.lastOutNanos());
        }
        // U11 W8-5: 공급자 출력 끝(tTranscriptFinal)은 구간의 마지막 조각 시각이다(봉인 시각이 아니라 800ms 대기가 지표에 섞이지 않는다)
        ctx.transcripts().aiDone(sealed.lastOutAt());
        if (duplicateGreeting(sealed)) {
            discard(sealed, GateDecision.DiscardReason.GATE_PATTERN, false);
            return;
        }
        try {
            // 단위별 경로(streaming)의 단위는 구두점뿐이어도 통과한다(FT-1023-e). 전체 구간은 지금처럼 빈 전사로 버린다
            OutputGate.Inspection inspection = textUnitEnds.containsKey(sealed)
                    ? ctx.services().gate().inspectTextDetailed(sealed) : ctx.services().gate().inspectDetailed(sealed, streaming);
            GateDecision inspected = inspection.decision();
            if (!inspected.passed()) {
                // 금지 패턴은 대화가 넘어갔어도 교정한다(같은 표현을 되풀이하지 않게)
                discard(sealed, inspected.reason(),
                        !movedOn || inspected.reason() == GateDecision.DiscardReason.GATE_PATTERN,
                        null, true, inspection.detail());
                return;
            }
            boolean practiceControlled = practice != null && practice.controlsOutput();
            var generatedSlot = practiceControlled && pendingPracticeSpeech != null && pendingPracticeSpeech.generated()
                    ? pendingPracticeSpeech : null;
            if (generatedSlot != null) {
                // (기능 설계 3.5): 열린 생성형 슬롯의 새 발화는 제브(연습 모드)가 ALLOW한 것만 재생된다. 제브가 꺼져 있거나 시작이
                // 실패하면 폐기한다(fail-closed). 원문 일치로 제브를 건너뛸 수 없다
                if (!guardrail.enabled() || ctx.status() != SessionStatus.ACTIVE) {
                    discard(sealed, GateDecision.DiscardReason.GATE_PATTERN, false, null, true,
                            !guardrail.enabled() ? DiscardDetail.GUARDRAIL_DISABLED : DiscardDetail.SESSION_INACTIVE);
                    return;
                }
                if (inspection.punctuationOnly()) {
                    releasePunctuationOnly(sealed, practiceGuardrailMode(generatedSlot), generatedSlot.correlationId());
                    return;
                }
                try {
                    guardrail.begin(sealed, practiceGuardrailMode(generatedSlot), generatedSlot.correlationId(), streaming);
                } catch (RuntimeException e) {
                    guardrail.onBeginFailed(sealed, e);
                }
                return;
            }
            if (practiceControlled && pendingPracticeSpeech != null && !pendingPracticeSpeech.generated()
                    && pendingPracticeSpeech.equals(streamedPractice)) {
                // B1: 승인 낭독 단위. 지금까지 보낸 전사 + 이 단위가 원문의 앞부분이면 제브 없이(원문 일치) 바로 낸다
                String unit = java.util.Objects.toString(sealed.transcript(), "");
                if (pendingPracticeSpeech.matchesPrefix(streamedApprovedText + unit)) {
                    streamedApprovedText.append(unit);
                    if (guardrail.enabled()) guardrail.onVerbatimSkipped(GuardrailCoordinator.SOURCE_PRACTICE_SPEECH, unit);
                    recheckAndSend(sealed, null, true);
                    return;
                }
                var rejectedSpeech = pendingPracticeSpeech;
                guardrail.onDiscardedBeforeCheck();
                recordDiscard(sealed, null, GateDecision.DiscardReason.GATE_PATTERN, true, DiscardDetail.APPROVED_TEXT_MISMATCH);
                // 이미 앞부분이 나갔으면 원문과 다른 뒤는 버리고 나간 부분까지 재생됨으로 닫는다. 아무것도 안 나갔으면 지금처럼 재요청 길로 간다
                if (!closeSentStreamedPractice(REASON_APPROVED_UNIT_MISMATCH)) rejectApprovedReading(rejectedSpeech);
                return;
            }
            if (practiceControlled
                    && (pendingPracticeSpeech == null || !pendingPracticeSpeech.matches(sealed.transcript()))) {
                if (pendingPracticeSpeech != null && !pendingPracticeSpeech.generated()) {
                    var rejectedSpeech = pendingPracticeSpeech;
                    guardrail.onDiscardedBeforeCheck();
                    recordDiscard(sealed, null, GateDecision.DiscardReason.GATE_PATTERN, true,
                            DiscardDetail.APPROVED_TEXT_MISMATCH);
                    rejectApprovedReading(rejectedSpeech);
                    return;
                }
                discard(sealed, GateDecision.DiscardReason.GATE_PATTERN, false, null, true,
                        DiscardDetail.NO_OPEN_PRACTICE_SLOT);
                return;
            }
            if (practiceControlled && guardrail.enabled()) {
                // U13 분류 ①: 연습이 출력을 쥐고 승인 발화와 원문이 같으면 제브를 부르지 않고 기존 게이트 검사만 거친 재생이다
                guardrail.onVerbatimSkipped(GuardrailCoordinator.SOURCE_PRACTICE_SPEECH, sealed.transcript());
            } else if (!practiceControlled && guardrail.enabled() && ctx.status() == SessionStatus.ACTIVE
                    && inspection.punctuationOnly()) {
                releasePunctuationOnly(sealed, GuardrailRequest.Mode.FREE_TALK, null);
                return;
            } else if (!practiceControlled && guardrail.enabled() && ctx.status() == SessionStatus.ACTIVE) {
                // U13 분류 ②: 레지스트리가 승인 원문 · 등록 대본과 원문 일치(MATCHED)로 확인한 출력도 제브를 건너뛴다. UNKNOWN ·
                // NOT_MATCHED(일치를 확인할 수 없음)는 새 발화로 검사한다
                Optional<String> verbatim = guardrail.verbatimSource(sealed.transcript());
                if (verbatim.isPresent()) {
                    guardrail.onVerbatimSkipped(verbatim.get(), sealed.transcript());
                } else {
                    // U13 호출 지점 ①: GPT-Live가 그 자리에서 만든 새 발화는 제브가 ALLOW한 것만 recheck → send로 간다. 결과는
                    // GuardrailArrived로 돌아와 이어진다 — 여기서는 기다리지 않는다
                    try {
                        guardrail.begin(sealed, GuardrailRequest.Mode.FREE_TALK, null, streaming);
                    } catch (RuntimeException e) {
                        guardrail.onBeginFailed(sealed, e);
                    }
                    return;
                }
            }
            recheckAndSend(sealed, null, true);
        } catch (RuntimeException e) {
            // 게이트 처리 중 예외는 폐기한다(fail-closed, SD-17)
            ctx.services().events().appError(EventLog.Component.OUTPUT_GATE, e);
            if (ctx.transcripts().hasOpenAi()) {
                discard(sealed, GateDecision.DiscardReason.GATE_EMPTY, !movedOn,
                        null, true, DiscardDetail.GATE_PROCESSING_EXCEPTION);
            }
        }
    }

    /**
     * FT-1023-e: 단위별 경로의 구두점뿐인 단위(말끝 "?" 등)를 낸다. 사전 · 제브가 볼 글이 없어 제브를 부르지 않는다(사유 코드
     * {@link GuardrailCoordinator#SOURCE_PUNCTUATION_ONLY}). 앞 단위가 제브 검사 중이거나 대기 중이면 음성 순서를 지키려고 대기열 뒤에 넣는다 — 차례가
     * 오면 협력 객체가 제브 없이 낸다(앞 대기 단위와 함께 묶이면 그 검사 글에 붙는다).
     */
    private void releasePunctuationOnly(SegmentBuffer.Sealed sealed, GuardrailRequest.Mode mode, UUID practiceSpeechId) {
        if (guardrail.isChecking() || guardrail.hasQueued()) {
            try {
                guardrail.begin(sealed, mode, practiceSpeechId, true);
            } catch (RuntimeException e) {
                guardrail.onBeginFailed(sealed, e);
            }
            return;
        }
        guardrail.onPunctuationOnlySkipped();
        recheckAndSend(sealed, null, true);
    }

    /**
     * 송신 직전 재확인(NOT_ACTIVE · RECORDER_DEGRADED · 출력 레인 예약)을 하고 통과하면 보낸다. 제브 단계가 있으면 유효한 ALLOW가 온 뒤에 불린다
     * (— 출력 레인 예약도 그때 한다. 예약에 실패하면 막힘 흐름이 아니라 지금과 같은 폐기다).
     *
     * @param held 제브 검사를 기다리며 보관한 AI 행(없으면 null — 열린 AI 행을 닫는다)
     * @param ownsLatency 지연 기록의 턴이 이 구간의 것인가(대기열에서 꺼낸 구간은 앞 턴의 기록을 덮지 않는다)
     * @return 기기로 보냈으면 참, recheck에서 폐기했으면 거짓
     */
    private boolean recheckAndSend(SegmentBuffer.Sealed sealed, TranscriptWriter.Held held, boolean ownsLatency) {
        if (textUnitEnds.containsKey(sealed)) {
            return releaseTextUnit(sealed, held, ownsLatency);
        }
        if (duplicateGreeting(sealed)) {
            discard(sealed, GateDecision.DiscardReason.GATE_PATTERN, false, held, ownsLatency);
            return false;
        }
        DeviceLink target = ctx.link();
        if (ctx.outputAuthority() == null) ctx.outputAuthority(newOutputAuthority(
                kr.mujoong.realtime.gate.OutputAuthority.Purpose.NORMAL));
        boolean activeAndCurrent = ctx.status() == SessionStatus.ACTIVE && sealed.generation() == ctx.outputGeneration().get()
                && target != null && target.isOpen() && hasOutputAuthority(ctx.outputAuthority(), sealed);
        OutputGate.Rechecked<DeviceLink.AudioReservation> rechecked = ctx.services().gate().recheck(activeAndCurrent,
                ctx.services().recorder().isDegraded(ctx.plan().sessionId()),
                () -> target == null ? Optional.empty() : target.reserveAudio(sealed.totalBytes()));
        if (!rechecked.decision().passed()) {
            DiscardDetail detail = defaultDiscardDetail(rechecked.decision().reason());
            if (rechecked.decision().reason() == GateDecision.DiscardReason.NOT_ACTIVE) {
                detail = ctx.status() != SessionStatus.ACTIVE ? DiscardDetail.SESSION_INACTIVE
                        : sealed.generation() != ctx.outputGeneration().get() ? DiscardDetail.STALE_GENERATION
                        : DiscardDetail.DEVICE_UNAVAILABLE;
            }
            discard(sealed, rechecked.decision().reason(), true, held, ownsLatency, detail);
            return false;
        }
        send(sealed, target, rechecked.reservation(), held, ownsLatency);
        return true;
    }

    private void send(SegmentBuffer.Sealed sealed, DeviceLink target, DeviceLink.AudioReservation reservation) {
        send(sealed, target, reservation, null, true);
    }

    private void send(SegmentBuffer.Sealed sealed, DeviceLink target, DeviceLink.AudioReservation reservation,
            TranscriptWriter.Held held, boolean ownsLatency) {
        Instant passAt = ctx.services().clock().now();
        correctionsInARow = 0;
        logBundleSilence(sealed.chunks(), ownsLatency, sealed.lastOutAt());
        UUID playbackId = UUID.randomUUID();
        greetingHandoff.sent(playbackId, sealed.generation(), target.connectionId(),
                passAt.plus(ctx.services().settings().playbackExpiry()));
        if (ownsLatency) {
            ctx.services().latency().gatePassed(ctx.plan().sessionId(), playbackId, passAt);
        }
        // 유효한 ALLOW 뒤에도 표시를 보류하고, 기기 STARTED 때 표시한다. 수신용 seq는 유지한다.
        if (ctx.plan().activityType() == ActivityType.PRACTICE) {
            if (held == null) held = ctx.transcripts().aiHold();
            ctx.transcripts().awaitPlayback(playbackId, held, sealed.transcript());
        } else if (held == null) {
            ctx.transcripts().aiFinal(sealed.transcript(), true, passAt);
        } else {
            ctx.transcripts().aiFinal(held, sealed.transcript(), true, passAt);
        }
        var speech = pendingPracticeSpeech;
        if (speech != null) {
            practiceAudioStage("PLAYBACK_QUEUED");
            ctx.timers().cancel(Kind.PRACTICE_SEND_WAIT);
            ctx.timers().cancel(Kind.INSTRUCTION_WAIT);
            if (!speech.equals(streamedPractice)) {
                practiceCollecting = false;
                ctx.timers().cancel(Kind.PRACTICE_COLLECT_MAX);
                pendingPracticeSpeech = null;
                practiceHold = true;
            }
            practiceAudio.put(playbackId, speech);
            schedulePracticeTimer("u5b-audio-" + playbackId, ctx.services().settings().playbackExpiry());
        }
        long generation = sealed.generation();
        var authority = ctx.outputAuthority();
        // 리뷰 반영 6: 기기로 실제 보낸 AI 음성 프레임만 기록한다(송신기가 소켓에 쓴 직후 넘긴다). 음성 레인에 못 들어간 묶음 ·
        // 세대가 올라 잘린 나머지 프레임은 기록하지 않는다
        AudioRecordings.Session aiRecording = ctx.recording();
        OutputBundle bundle = new OutputBundle(ctx.plan().sessionId(), playbackId, OutputBegin.Source.GPT_LIVE,
                OutputBundle.frame(sealed.chunks()), sealed.totalMs(), reservation,
                () -> ctx.outputGeneration().get() == generation && ctx.outputAuthority() == authority
                        && hasOutputAuthority(authority, sealed),
                firstFrameAt -> {
                    greetingHandoff.frameSent(playbackId);
                    ctx.services().latency().firstFrameSent(ctx.plan().sessionId(), playbackId, firstFrameAt);
                },
                aiRecording == null ? frame -> { } : aiRecording::ai, sealed.transcript());
        if (!target.sendOutput(bundle)) {
            logOutputDiscard(sealed, held, GateDecision.DiscardReason.OUTBOUND_FULL,
                    DiscardDetail.AUDIO_SEND_FAILED, speech);
            ctx.transcripts().playbackAbandoned(playbackId, ctx.services().clock().now());
            greetingHandoff.failed(playbackId);
            ctx.services().metrics().gateDiscard(GateDecision.DiscardReason.OUTBOUND_FULL.name());
            if (speech != null) {
                practiceAudio.remove(playbackId); cancelPracticeTimer("u5b-audio-" + playbackId);
                practicePlayback(speech, kr.mujoong.realtime.practicelegacy.PracticePorts.Playback.FAILED);
            }
        } else {
            if (speech != null && speech.equals(streamedPractice)) streamedPracticeSent = true;
            // Only approved output accepted by the device link may ground a subsequent agreement judgment.
            judgment.rememberPartner(sealed.transcript(), passAt, speech != null && "COACH".equals(speech.partnerRole()));
        }
    }

    private kr.mujoong.realtime.gate.OutputAuthority newOutputAuthority(
            kr.mujoong.realtime.gate.OutputAuthority.Purpose purpose) {
        return new kr.mujoong.realtime.gate.OutputAuthority(ctx.plan().sessionId(), ctx.plan().activityType(),
                ctx.outputGeneration().get(), ctx.outputGen().generation(),
                ctx.servicePolicy() == null ? "practice" : ctx.servicePolicy().policyVersion(),
                ctx.services().guardrail().policyVersion(), purpose);
    }

    private boolean duplicateGreeting(SegmentBuffer.Sealed candidate) {
        return ctx.plan().activityType() == ActivityType.FREE_TALK && greetingHandoff.blocks(candidate.transcript(),
                ctx.plan().approvedItem(INTRO_FREE_TALK).map(ApprovedItem::text).orElse(null),
                ctx.plan().approvedItem(RE_PROMPT).map(ApprovedItem::text).orElse(null));
    }

    private boolean hasOutputAuthority(kr.mujoong.realtime.gate.OutputAuthority authority, SegmentBuffer.Sealed candidate) {
        return authority != null && authority.permits(ctx.plan().sessionId(), ctx.plan().activityType(),
                ctx.outputGeneration().get(), ctx.outputGen().generation(),
                ctx.servicePolicy() == null ? "practice" : ctx.servicePolicy().policyVersion(),
                ctx.services().guardrail().policyVersion(), ctx.status() == SessionStatus.ACTIVE && !ctx.terminated(),
                ctx.plan().activityType() == ActivityType.FREE_TALK && ctx.conversationPolicy().resting(), candidate);
    }

    /** 폐기: AI 행 FINAL · false, 그다음 대체 문구 판정(겹침 규칙 R3-19). 교정은 사유에 따라. */
    private void discard(SegmentBuffer.Sealed sealed, GateDecision.DiscardReason reason) {
        discard(sealed, reason, true);
    }

    private void discard(SegmentBuffer.Sealed sealed, GateDecision.DiscardReason reason, boolean correct) {
        discard(sealed, reason, correct, null, true);
    }

    /**
     * @param held 제브 검사를 기다리며 보관한 AI 행(없으면 null — 열린 AI 행을 닫는다)
     * @param ownsLatency 지연 기록의 턴이 이 구간의 것인가
     */
    private void discard(SegmentBuffer.Sealed sealed, GateDecision.DiscardReason reason, boolean correct,
            TranscriptWriter.Held held, boolean ownsLatency) {
        discard(sealed, reason, correct, held, ownsLatency, defaultDiscardDetail(reason));
    }

    private void discard(SegmentBuffer.Sealed sealed, GateDecision.DiscardReason reason, boolean correct,
            TranscriptWriter.Held held, boolean ownsLatency, DiscardDetail detail) {
        boolean textUnit = sealed != null && textUnitEnds.containsKey(sealed);
        long generationBefore = ctx.outputGen().generation();
        discardUnit(sealed, reason, correct, held, ownsLatency, detail);
        if (textUnit && !isGuardrailReason(reason) && ctx.outputGen().generation() == generationBefore) {
            bumpOutput(OutputGeneration.BumpReason.GATE_CORRECTION);
        }
    }

    private void discardUnit(SegmentBuffer.Sealed sealed, GateDecision.DiscardReason reason, boolean correct,
            TranscriptWriter.Held held, boolean ownsLatency, DiscardDetail detail) {
        if (!isGuardrailReason(reason)) {
            // U13 훅: 재지시 응답이 제브에 닿기 전에 어떤 길로든 폐기되면 협력 객체는 RETRYING에서 IDLE로 돌아온다. 마무리는 아래가 한다
            guardrail.onDiscardedBeforeCheck();
        }
        recordDiscard(sealed, held, reason, ownsLatency, detail);
        if (pendingPracticeSpeech != null) {
            // FT-1023-e: 앞 단위가 이미 기기로 나간 생성형 연습 발화의 단위 하나가 비어(GATE_EMPTY) 버려졌다. 같은 지시를 다시 요청하지 않고(아이가
            // 같은 말을 두 번 듣는다) 나간 부분까지 '재생됨'으로 닫는다. 금지 표현 · 제브 · 송신 재확인 폐기는 지금처럼 실패다
            if (reason == GateDecision.DiscardReason.GATE_EMPTY && closeSentStreamedPractice(REASON_TAIL_UNIT_DROPPED)) return;
            var speech = pendingPracticeSpeech; pendingPracticeSpeech = null; practiceHold = true;
            bumpOutput(OutputGeneration.BumpReason.PRACTICE_DISCARD);
            if (reason == GateDecision.DiscardReason.GATE_EMPTY) recoverPracticeAudio(speech);
            else practicePlayback(speech, kr.mujoong.realtime.practicelegacy.PracticePorts.Playback.FAILED);
            return;
        }
        if (correct) {
            correctAfterDiscard(sealed, reason);
        }
        finishDiscard();
    }

    private static boolean isGuardrailReason(GateDecision.DiscardReason reason) {
        return switch (reason) {
            case GUARDRAIL_BLOCK, GUARDRAIL_REVIEW, GUARDRAIL_TIMEOUT, GUARDRAIL_ERROR, GUARDRAIL_QUEUE_FULL -> true;
            default -> false;
        };
    }

    /** 폐기의 기록 부분: 지표 · 지연 기록 · AI 행 FINAL 거짓(보관 행이면 그 손잡이로). */
    private void recordDiscard(SegmentBuffer.Sealed sealed, TranscriptWriter.Held held, GateDecision.DiscardReason reason,
            boolean ownsLatency) {
        recordDiscard(sealed, held, reason, ownsLatency, defaultDiscardDetail(reason));
    }

    private void recordDiscard(SegmentBuffer.Sealed sealed, TranscriptWriter.Held held, GateDecision.DiscardReason reason,
            boolean ownsLatency, DiscardDetail detail) {
        logOutputDiscard(sealed, held, reason, detail, held == null ? pendingPracticeSpeech : null);
        ctx.services().metrics().gateDiscard(reason.name());
        if (ownsLatency) {
            ctx.services().latency().gateDiscarded(ctx.plan().sessionId());
        }
        closeAiRow(sealed, held);
    }

    private static DiscardDetail defaultDiscardDetail(GateDecision.DiscardReason reason) {
        return switch (reason) {
            case GATE_EMPTY -> DiscardDetail.UNCLASSIFIED_GATE_EMPTY;
            case GATE_PATTERN -> DiscardDetail.OUTPUT_POLICY_MISMATCH;
            case NOT_ACTIVE -> DiscardDetail.SESSION_INACTIVE;
            case OUTBOUND_FULL -> DiscardDetail.AUDIO_QUEUE_FULL;
            default -> DiscardDetail.valueOf(reason.name());
        };
    }

    /** One unmerged event per discarded segment. No raw transcript, audio, prompt, or exception message. */
    private void logOutputDiscard(SegmentBuffer.Sealed sealed, TranscriptWriter.Held held,
            GateDecision.DiscardReason reason, DiscardDetail detail,
            kr.mujoong.realtime.voice.PracticeSpeech speech) {
        ctx.services().events().log(EventCode.OUTPUT_DISCARDED, EventLog.Component.OUTPUT_GATE,
                ctx.plan().sessionId(), ctx.plan().traceId(), entry -> {
                    entry.attr(AttrKey.SEQ, ctx.transcripts().diagnosticSequence(held))
                            .attr(AttrKey.DISCARD_REASON, reason == null ? "VOIDED" : reason.name())
                            .attr(AttrKey.DISCARD_DETAIL, detail.name())
                            .attr(AttrKey.CURRENT_DEVICE_GENERATION, ctx.outputGeneration().get())
                            .attr(AttrKey.PRACTICE_PHASE, practice == null ? "NONE" : practice.diagnosticPhase())
                            .attr(AttrKey.PRACTICE_SUB, practice == null ? "NONE" : practice.diagnosticSub());
                    if (sealed != null) {
                        entry.attr(AttrKey.OUTPUT_GENERATION, sealed.outputGen())
                                .attr(AttrKey.DEVICE_GENERATION, sealed.generation())
                                .attr(AttrKey.SEGMENT_COMPLETE, sealed.complete())
                                .attr(AttrKey.AUDIO_BYTES, sealed.totalBytes())
                                .attr(AttrKey.TRANSCRIPT_CHARS, sealed.transcript() == null ? 0
                                        : sealed.transcript().codePointCount(0, sealed.transcript().length()));
                        if (sealed.firstOutAt() != null) entry.attr(AttrKey.T_PROVIDER_FIRST_OUT, sealed.firstOutAt());
                        if (sealed.lastOutAt() != null) entry.attr(AttrKey.T_TRANSCRIPT_FINAL, sealed.lastOutAt());
                    }
                    if (speech != null) {
                        entry.attr(AttrKey.GENERATED_SPEECH, speech.generated());
                        if (!speech.generated()) entry.attr(AttrKey.EXPECTED_CHARS,
                                speech.text().codePointCount(0, speech.text().length()));
                    }
                });
    }

    private void closeAiRow(SegmentBuffer.Sealed sealed, TranscriptWriter.Held held) {
        if (sealed != null) {
            textUnitEnds.remove(sealed);
        }
        String text = sealed == null ? "" : sealed.transcript();
        if (held != null) {
            ctx.transcripts().aiFinal(held, text, false, ctx.services().clock().now());
        } else {
            ctx.transcripts().aiFinal(text, false, ctx.services().clock().now());
        }
        maybeFinishTimedOutput();
    }

    /** 폐기의 마무리: 대기 문구 재생 또는 '듣는 중' 복귀. */
    private void finishDiscard() {
        if (ctx.status() != SessionStatus.ACTIVE || ctx.playback().isPlaying() || ctx.plan().waitingFiller() == null) {
            listeningAgain();
            return;
        }
        UUID playbackId = UUID.randomUUID();
        if (ctx.sendControl(new PlayApprovedContent(playbackId, PlayApprovedContent.WAITING_FILLER,
                ctx.plan().waitingFiller().contentVersionId()))) {
            ctx.playback().started(playbackId, PlayApprovedContent.WAITING_FILLER);
            ctx.timers().arm(Kind.PLAYBACK_EXPIRY, ctx.services().settings().playbackExpiry());
        }
    }

    /**
     * ACTIVE에서 GATE_PATTERN · GATE_EMPTY · SEGMENT_OVERSIZE로 폐기했으면 응답당 한 번 고정 교정 문장을 보낸다(걸린 범주의
     * 문장, 범주를 모르거나 미완 · 너무 긴 응답이면 짧은 완결 요청). 폐기한 전사 · 걸린 패턴은 보내지 않는다.
     */
    private void correctAfterDiscard(SegmentBuffer.Sealed sealed, GateDecision.DiscardReason reason) {
        if (ctx.status() != SessionStatus.ACTIVE || sealed == null) {
            return;
        }
        // 폐기 사유가 미완 · 너무 김이어도 버린 구간에 금지 패턴이 있으면 그 범주의 문장을 보낸다 — 교정은 응답당 한 번이라 전사만 든 구간이
        // 먼저 미완으로 폐기되면 금지 패턴 교정을 다시 보낼 기회가 없다(2차 리뷰 9 조사 중 찾음)
        DialogInstructions.Correction correction = switch (reason) {
            case GATE_PATTERN, GATE_EMPTY, SEGMENT_OVERSIZE -> ctx.services().gate().matchedCategory(sealed)
                    .map(SessionExecutor::correctionFor).orElse(DialogInstructions.Correction.INCOMPLETE);
            default -> null;
        };
        // 세대당 한 번(U11 W8-6): 구간이 속한 세대가 아직 현재이고 이 세대의 교정을 아직 안 보냈을 때만
        if (correction == null || sealed.outputGen() != ctx.outputGen().generation() || !ctx.outputGen().claimCorrection()) {
            return;
        }
        // 교정 연쇄 방지(설계 개정 A2): 교정 지시는 곧 새 발화를 시작하므로, 통과한 구간 없이 연달아 두 번까지만 보낸다
        if (correctionsInARow >= MAX_CORRECTIONS_IN_A_ROW) {
            return;
        }
        DialogInstructions.Correction chosen = correction;
        if (sendInstruction(OutputGeneration.BumpReason.GATE_CORRECTION, "provider-correct-",
                id -> ctx.services().requests().instructionsAppend(id, chosen, ctx.plan().activityType()), null)) {
            correctionsInARow++;
        }
    }

    /** 세션 시작 지시문의 아이 이름을 중립 호칭으로 바꿨다는 사유 코드(JIRA-266, INFO). */
    static final String REASON_CHILD_NAME_REPLACED = "ChildNameProfanityReplaced";

    static DialogInstructions.Correction correctionFor(SeedDictionary.Category category) {
        return switch (category) {
            case DIAGNOSIS -> DialogInstructions.Correction.DIAGNOSIS;
            case TREATMENT -> DialogInstructions.Correction.TREATMENT;
            case SCORE -> DialogInstructions.Correction.SCORE;
            case CAUSE -> DialogInstructions.Correction.CAUSE;
            case GENERALIZATION -> DialogInstructions.Correction.GENERALIZATION;
            case EMOTION_CLAIM -> DialogInstructions.Correction.EMOTION_CLAIM;
            case PRESSURE -> DialogInstructions.Correction.PRESSURE;
            case PROFANITY -> DialogInstructions.Correction.PROFANITY;
        };
    }

    private boolean inRelayRange() {
        return ctx.status() == SessionStatus.ACTIVE
                || (ctx.status() == SessionStatus.CLOSING && ctx.closing().step() != ClosingSequence.Step.TRANSPORT_CLOSED);
    }

    private boolean inOutputRange() {
        return ctx.status() == SessionStatus.ACTIVE || ctx.status() == SessionStatus.CLOSING;
    }

    // ------------------------------------------------------------------ 재전달 · 최상위 예외

    private void scheduleRetry(Message message) {
        if (message instanceof TimerFired fired) {
            pendingRetry.add(fired.kind());
        } else {
            pendingRetry.add(message);
        }
        ctx.timers().arm(Kind.REDELIVER, ctx.services().settings().timerTick());
    }

    private void redeliver() {
        List<Object> retry = new ArrayList<>(pendingRetry);
        pendingRetry.clear();
        for (Object item : retry) {
            if (ctx.terminated()) {
                return;
            }
            try {
                if (item instanceof Kind kind) {
                    clocks.runTimer(kind);
                } else {
                    handle((Message) item);
                }
            } catch (DataAccessException | TransactionException e) {
                ctx.services().events().appError(EventLog.Component.SESSION_LIFECYCLE, e);
                pendingRetry.add(item);
            }
        }
        if (!pendingRetry.isEmpty()) {
            ctx.timers().arm(Kind.REDELIVER, ctx.services().settings().timerTick());
        }
    }

    /**
     * 최상위 예외 → FAILED(SERVER_RESTART) 시도 → 정리. 전이가 실패하면 고아 세션 점검이 닫는다. 전이를 이기면
     * START_SESSION이 나갔고 연결이 살아 있을 때 CLOSE_TRANSPORT(FAILED) + STANDBY를 보낸다(F4). '재시작 제외'는 보낼 연결이 없는
     * 기동 정리(S3a) 경우이고, 실행기 예외는 연결이 살아 있을 수 있다. 전이를 이기지 못하면(승자 하나) 기기 명령 없이 정리한다.
     */
    private void failFromTopLevel() {
        try {
            if (!ctx.status().isTerminal()) {
                ActivitySessionRepository.MultiOriginTransition result = ctx.services().transitions().transitionFromAny(
                        ctx.plan().sessionId(), Trigger.SERVER_RESTART.origins(), SessionStatus.FAILED,
                        read -> TransitionTable.changeFor(Trigger.SERVER_RESTART, null, ctx.services().clock().now()),
                        SessionTransitions.Origin.REALTIME, ctx.plan().traceId());
                if (result.transitioned()) {
                    ending.afterFailed();
                }
            }
        } catch (RuntimeException e) {
            ctx.services().events().appError(EventLog.Component.SESSION_LIFECYCLE, e);
        } finally {
            ending.teardown();
        }
    }

    // ------------------------------------------------------------------ 진 전이 뒤 상태 채택

    /**
     * F5 · 살아 있는 실행기의 세션 행은 이 실행기만 전이한다(고아 점검 · 기동 정리는 실행기 없는 세션만 다룬다). 그래서 DB가 메모리보다
     * 앞선 비종료 상태면 커밋은 됐는데 응답을 잃은 이 실행기의 전이이고, 그 승자 몫은 아직 실행되지 않았다. 상태가 바뀔 때 한 번만 부르므로
     * 중복 실행이 없다.
     */
    private void adopt(ActivitySessionRow row, UUID endRequestId) {
        switch (ctx.status()) {
            case DEVICE_ACK -> {
                // PREPARING → DEVICE_ACK(ACK): 그 사이 처리한 READY · session.started로 ACTIVE 조건을 다시 본다
                ctx.ackReceived(true);
                if (ctx.deviceUnavailable()) {
                    failDeviceUnavailable();
                } else {
                    tryActivate();
                }
            }
            case WAITING_CHILD -> enterWaitingChild();
            case ACTIVE -> afterActiveWon();
            case CLOSING -> {
                ctx.closingReason(row.endReason());
                ctx.closingDetail(row.endDetail());
                ending.enterClosing(
                        row.terminationRequestedAt() == null ? ctx.services().clock().now() : row.terminationRequestedAt(),
                        endRequestId);
            }
            default -> {
                // PREPARING으로 되돌아가는 일은 없다(종료 상태는 위에서 정리했다)
            }
        }
    }

    @Override
    public String toString() {
        return "SessionExecutor[sessionId=" + ctx.plan().sessionId() + ", status=" + ctx.status() + "]";
    }
    private ProviderConnector.Attempt connectProvider(UUID session, ChildContext child,
            kr.mujoong.realtime.voice.ProviderPort.Listener listener, ProviderConnector.Callback callback,
            kr.mujoong.realtime.dialog.PolicyBundle policy) {
        // JIRA-266: 저장된 이름이 욕설 사전에 걸리면 요청 공장이 지시문의 이름을 중립 호칭으로 바꾼다. 여기서는 그 사실만 사유 코드로 남긴다(원문 없음, R-F14)
        if (ProviderRequestFactory.nameNeedsReplacement(child.displayName())) {
            ctx.services().events().appError(EventLog.Component.SESSION_LIFECYCLE, REASON_CHILD_NAME_REPLACED, EventCode.Level.INFO);
        }
        return ctx.services().connector().connect(session, child, listener, callback, policy, ctx.plan().conversationGuide(),
                ctx.plan().activityType() == ActivityType.PRACTICE);
    }

}
