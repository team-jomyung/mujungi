package kr.mujoong.realtime.session;

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
import kr.mujoong.realtime.dialog.InstructionTemplates;
import kr.mujoong.realtime.dialog.JoinTracker;
import kr.mujoong.realtime.dialog.SilenceClock;
import kr.mujoong.realtime.dialog.StopConfirmFlow;
import kr.mujoong.realtime.dialog.StopIntentJudge;
import kr.mujoong.contract.event.payload.SessionStateChanged;
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
import kr.mujoong.realtime.gate.OutputGate;
import kr.mujoong.realtime.gate.OutputGeneration;
import kr.mujoong.realtime.gate.SegmentBuffer;
import kr.mujoong.realtime.gate.SeedDictionary;
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

    /**
     * 세션 공통 설정(RealtimeProperties에서 읽는다).
     *
     * @param inputTranscriptQuiet 입력 전사 조각이 이만큼 오지 않으면 그 묶음을 확정 전사로 본다({@code mujoong.timing.provider.input-transcript-quiet}, 설계 개정 A7)
     * @param newSpeechMargin 지시 뒤 새 말의 전사 시작에 더하는 여유({@code mujoong.timing.provider.new-speech-margin}, 설계 개정 A4)
     * @param instructionWait 지시를 보낸 뒤 새 말이 시작하기를 기다리는 시한({@code mujoong.timing.judgment.response-ready}, 지시 송신 시각부터, 설계 개정 A3)
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
            Duration instructionWait) {

        /**
         * U4b 세션 생애 시간 값(BR11.1 · contracts/timing.md, 모두 설정 키에서 온다).
         *
         * @param joinWait 처음 시작 안내 뒤 참여 창(join-wait)
         * @param joinWaitAfterReprompt 재안내 · 되묻기 · 대안 뒤 참여 창(join-wait-after-reprompt)
         * @param silenceWarning 공백 경고 누적(silence-warning)
         * @param silenceEnd 공백 종료 누적(silence-end)
         * @param stopConfirmWait 종료 확인 질문 창(stop-confirm-wait)
         * @param recordSettle 종료 뒤 정산 창 상한(record-settle)
         * @param gapDisplay 마지막 기기 메시지 뒤 기술 일시정지(device.gap-display)
         */
        public record Lifecycle(Duration joinWait, Duration joinWaitAfterReprompt, Duration silenceWarning,
                Duration silenceEnd, Duration stopConfirmWait, Duration recordSettle, Duration gapDisplay) {

            public Lifecycle {
                Objects.requireNonNull(joinWait, "joinWait");
                Objects.requireNonNull(joinWaitAfterReprompt, "joinWaitAfterReprompt");
                Objects.requireNonNull(silenceWarning, "silenceWarning");
                Objects.requireNonNull(silenceEnd, "silenceEnd");
                Objects.requireNonNull(stopConfirmWait, "stopConfirmWait");
                Objects.requireNonNull(recordSettle, "recordSettle");
                Objects.requireNonNull(gapDisplay, "gapDisplay");
            }

            public static Lifecycle from(RealtimeProperties properties) {
                RealtimeProperties.SessionTiming session = properties.timing().session();
                return new Lifecycle(session.joinWait(), session.joinWaitAfterReprompt(), session.silenceWarning(),
                        session.silenceEnd(), session.stopConfirmWait(), session.recordSettle(),
                        properties.timing().device().gapDisplay());
            }
        }

        public Settings {
            Objects.requireNonNull(lifecycle, "lifecycle");
            Objects.requireNonNull(inputTranscriptQuiet, "inputTranscriptQuiet");
            Objects.requireNonNull(newSpeechMargin, "newSpeechMargin");
            Objects.requireNonNull(instructionWait, "instructionWait");
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
                    properties.timing().provider().newSpeechMargin(), properties.timing().judgment().responseReady());
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
            GuardrailSupport guardrail) {

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
     * 고정한 승인 문구 하나(contentCode · 버전 · 원문). 원문은 승인 문구 말풍선 행(U4b BR3.1)에 쓰고 로그 · 문자열 표현에 넣지 않는다. 원문을 모르면
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

    /** U4b 승인 문구 코드(시작 패키지 approvedContent의 선택 키, U4b-R2 · U4b-R4 · U4b-R6 · 설계 8절). */
    public static final String INTRO_FREE_TALK = "INTRO_FREE_TALK";
    public static final String INTRO_PRACTICE = "INTRO_PRACTICE";
    public static final String RE_PROMPT = "RE_PROMPT";
    public static final String DECLINE_ALTERNATIVE = "DECLINE_ALTERNATIVE";
    public static final String STOP_CONFIRM = "STOP_CONFIRM";
    public static final String SILENCE_WARNING = "SILENCE_WARNING";

    /**
     * 시작 명령이 고정한 세션 패키지 중 실행기가 쓰는 부분. U3: 원음 기록 동의(consentSnapshot.rawAudio, U3-R9)와 세션의 종료 표현 스냅숏
     * (종료 의도 판정, U3-R12). U4b: 활동 종류(시작 안내 선택 · 연습 확장점), 승인 문구 전체(선택 코드 포함, 원문은 말풍선용),
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
            Map<String, String> approvedSpeech) {

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

    /** ACK를 기다리는 opId 명령의 상한(U3-J7). 넘치면 가장 오래된 것부터 잊는다. */
    static final int MAX_PENDING_COMMANDS = 16;
    /** U3-R12: 아이의 명확한 종료 의도(payload는 근거만, 원문 없음). CLOSING(CHILD_STOP) 전이는 U4. */
    static final String EVENT_CHILD_STOP_INTENT = "CHILD_STOP_INTENT";
    /** U3-R12: 종료일 수 있으나 불명확(확인 질문 재생은 U4). */
    static final String EVENT_CHILD_STOP_UNCLEAR = "CHILD_STOP_UNCLEAR";
    /** 리뷰 반영 3: 종료 의도 뒤 아이가 종료가 아닌 말로 대화를 이어 가 출력 막힘을 풀었다(오판 복구). */
    static final String EVENT_CHILD_STOP_LIFTED = "CHILD_STOP_LIFTED";
    /** U4b 세션 사건 종류(payload는 코드 · 횟수 · 분류 이름 · 시각뿐, BR12.1 · entities SessionEvent). */
    static final String EVENT_JOIN_PROMPTED = "JOIN_PROMPTED";
    static final String EVENT_JOIN_RE_PROMPTED = "JOIN_RE_PROMPTED";
    static final String EVENT_JOIN_ALTERNATIVE_OFFERED = "JOIN_ALTERNATIVE_OFFERED";
    static final String EVENT_JOIN_ACCEPTED = "JOIN_ACCEPTED";
    static final String EVENT_JOIN_DECLINED = "JOIN_DECLINED";
    static final String EVENT_APPROVED_CONTENT_MISSING = "APPROVED_CONTENT_MISSING";
    static final String EVENT_STOP_CONFIRM_ASKED = "STOP_CONFIRM_ASKED";
    static final String EVENT_STOP_CONFIRM_RESOLVED = "STOP_CONFIRM_RESOLVED";
    static final String EVENT_SILENCE_WARNED = "SILENCE_WARNED";
    static final String EVENT_SILENCE_RESET = "SILENCE_RESET";
    static final String EVENT_TECHNICAL_PAUSE_STARTED = "TECHNICAL_PAUSE_STARTED";
    static final String EVENT_TECHNICAL_PAUSE_ENDED = "TECHNICAL_PAUSE_ENDED";
    static final String EVENT_PRACTICE_EXTENSION_FAILED = "PRACTICE_EXTENSION_FAILED";
    /** U3-J13: ACTIVE 중 공급자 끊김의 end_detail. */
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
    /** 지난 편지함 처리 끝의 출력 보류 상태(true → false가 되면 세대를 올린다, BR11.13). */
    private boolean lastHeld;
    /** 공급자 리더가 일반 편지함에 넣었고 실행기가 아직 처리하지 않은 출력 조각 수(끊김 만기가 쌓인 조각을 앞지르지 못하게 한다). */
    private final java.util.concurrent.atomic.AtomicInteger pendingOutputChunks =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 설계 개정 A7: 입력 전사 조각을 묶는 중인 발화(공급자는 전사 완료 사건을 주지 않는다). */
    private final StringBuilder inputGroup = new StringBuilder();
    private String inputGroupId;
    private long inputGroupSeq;
    private final List<Object> pendingRetry = new ArrayList<>();
    private final Runnable onTerminated;

    /** 기기 표식 · 끝내기 구역 협력 객체(실행기 스레드 전용). */
    private final EndingFlow ending;
    /** 시계 구역 협력 객체(실행기 스레드 전용). */
    private final SessionClocks clocks;
    /** U11 판정 턴 협력 객체(열린 턴 · 최근 전사 · 기록, 실행기 스레드 전용). */
    private final JudgmentCoordinator judgment;
    /** U13 제브 게이트 협력 객체(검사 상태 · 검사표 · 대기열 · 턴 재지시, 실행기 스레드 전용, NFR8). 여기에는 호출 지점과 아래 카운터만 둔다. */
    private final GuardrailCoordinator guardrail;
    /** U13 BR4.4: 입력 묶음을 처음 연 횟수(단조 증가). 검사 요청 뒤 늘었으면 아이가 새로 말을 시작한 것이다(같은 묶음의 꼬리 조각은 세지 않는다). */
    private long inputGroupOpens;
    /** U11 W2: 지금 처리 중인 아이 확정 전사의 대화 기록 순번(판정 요청 · 기록용, 원문 아님). */
    private int currentTranscriptSeq;
    /** U11 R-14: 새 전사가 J6를 대체할 때 그 전사의 종료 의도 판정(CONFIRM이면 연습 시계를 풀지 않는다). */
    private StopIntentJudge.Verdict supersedeVerdict;
    /** U11 BR8.1: 확장이 요청한 '생각 중' 유지(닫힐 때 듣는 중으로 되돌리지 않는다). */
    private boolean practiceThinking;
    /** 종료 의도 판정기(세션 스냅숏의 종료 표현, U3-R12). */
    private final StopIntentJudge stopJudge;
    /** U4b 공백 시계(ACTIVE에서만, BR5.1 ~ BR5.4). */
    private final SilenceClock silence;
    /** U4b 켜진 기술 일시정지(없으면 null, BR6.1 · BR6.2). */
    private SessionStateChanged.TechnicalPause technicalPause;
    /** U4b 기술 일시정지를 켠 서버 시각(창 · 시계를 멈춘 시간 계산). */
    private Instant pauseStartedAt;
    /** U4b 진행 조회가 읽는 살아 있는 값(실행기 스레드만 바꿔 쓴다, BR7.1). */
    private final java.util.concurrent.atomic.AtomicReference<LiveState> live =
            new java.util.concurrent.atomic.AtomicReference<>(LiveState.NONE);
    /** 지금 편지함 메시지를 처리하는 스레드(실행 스레드 또는 시험의 step 호출 스레드). 공급자 리스너가 이 스레드면 기다리지 않는다(J289). */
    private volatile Thread processing;
    private Thread thread;
    /** U4b BR1.7: 동의 철회 뒤 버린 아이 확정 전사 수(사건 payload는 건수만, BR12.1). */
    private int droppedAfterConsent;
    /** U4b 참여 판단(WAITING_CHILD에 들어갈 때 만든다, BR2.2 ~ BR2.4). */
    private JoinTracker join;
    /** U4b 연습 확장점(PRACTICE 세션만, 예외가 나면 null로 두고 자유대화처럼 잇는다, BR10.1). */
    private PracticeSessionExtension practice;
    /** U4b BR10.1: 확장점 예외가 났다(정산 시점을 건너뛰고 practice는 null). */
    private boolean practiceFailed;
    /** U4b BR10.4: 확장점이 공급자 출력을 막았다. */
    private boolean practiceHold;
    /** U4b BR10.4: 확장점 시계(이름 → 예약). 기술 일시정지 동안 남은 시간만 들고 멈춘다. */
    private final Map<String, PracticeTimer> practiceTimers = new HashMap<>();
    private long practiceTimerGeneration;
    /** U4b BR10.4: 확장점이 요청한 재생(playbackId → 승인 문구 코드). 끝 표식이 PLAYBACK_DONE이 된다. */
    private final Map<UUID, String> practicePlaybacks = new HashMap<>();
    private volatile kr.mujoong.realtime.voice.PracticeSpeech pendingPracticeSpeech;
    private final Map<UUID, kr.mujoong.realtime.voice.PracticeSpeech> practiceAudio = new HashMap<>();
    private final java.util.ArrayDeque<PracticeSessionExtension.ExtensionEvent> pausedPracticeEvents = new java.util.ArrayDeque<>();
    private Instant practicePauseStartedAt;
    private record PracticePause(Instant from, Instant to) { }
    private final java.util.ArrayList<PracticePause> practicePauses = new java.util.ArrayList<>();
    /** U4b BR10.6: 정산 창 안에 확장점에 전달한 사건 수. */
    private int practiceEventsInSettle;
    private final PracticeSessionExtension.PracticeOutput practiceOutput = new PracticeOutputPort();
    /** U4b BR8.6: 정산 창에서 버린 늦은 아이 전사 수. */
    private int droppedLate;
    /** U4b BR3.3: 빠진 승인 문구를 이미 남긴 코드(세션당 코드별 1회). */
    private final Set<String> missingReported = new HashSet<>();

    /**
     * @param onTerminated 종료 정리 뒤 레지스트리에서 지우는 함수
     */
    public SessionExecutor(Plan initialPlan, Services initialServices, SessionMailbox initialMailbox,
            Runnable onTerminated) {
        this.ctx = new SessionContext(initialPlan, initialServices, initialMailbox);
        this.onTerminated = Objects.requireNonNull(onTerminated, "onTerminated");
        this.stopJudge = new StopIntentJudge(ctx.plan().stopExpressions());
        this.judgment = new JudgmentCoordinator(ctx.services().judgment(), ctx.plan(),
                ctx.services().settings().instructionWait(), new JudgmentEnv());
        this.guardrail = new GuardrailCoordinator(ctx.services().guardrail(), ctx.plan(), new GuardrailEnv());
        this.ending = new EndingFlow(ctx, new EndingEnv());
        this.clocks = new SessionClocks(ctx, new ClocksEnv());
        Settings.Lifecycle lifecycle = ctx.services().settings().lifecycle();
        this.silence = new SilenceClock(lifecycle.silenceWarning(), lifecycle.silenceEnd());
        // U4b BR10.1: 연습 확장점은 PRACTICE 세션에서만 부른다(자유대화는 부르지 않는다)
        this.practice = ctx.plan().activityType() == ActivityType.PRACTICE
                ? Objects.requireNonNull(ctx.services().practice().create(ctx.plan().sessionId()), "practice extension")
                : null;
    }

    /**
     * 진행 조회가 읽는 살아 있는 값(U4b BR7.1 · WF10): 기술 일시정지 · 공백 경고. 실행기 스레드가 바뀔 때마다 불변 값 하나로 바꿔 쓰고 조회는 읽기만
     * 한다. 종료 세션은 둘 다 null이다.
     */
    public record LiveState(SessionStateChanged.TechnicalPause technicalPause, SilenceClock.Warning silenceWarning,
            PracticeSessionExtension.Progress practice) {

        public LiveState(SessionStateChanged.TechnicalPause pause, SilenceClock.Warning warning) { this(pause, warning, null); }

        static final LiveState NONE = new LiveState(null, null);
    }

    /** 진행 조회 값(다른 스레드에서 읽는다). */
    public LiveState liveState() {
        return ctx.status().isTerminal() ? LiveState.NONE : live.get();
    }

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
                teardown();
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

    /** 한 메시지를 처리한다. 최상위 예외 규약(R3-6 · J126)이 여기 있다. */
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
            afterMessage();
        } catch (DataAccessException | TransactionException e) {
            ctx.services().events().appError(EventLog.Component.SESSION_LIFECYCLE, e);
            scheduleRetry(message);
        } catch (RuntimeException e) {
            ctx.services().events().appError(EventLog.Component.SESSION_LIFECYCLE, e);
            failFromTopLevel();
        } finally {
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

    /** 이 세션의 기기(인계가 살아 있는 실행기를 찾는 열쇠, U3-J4). */
    public UUID deviceId() {
        return ctx.plan().deviceId();
    }

    /** 지금 상태(실행기 사본). */
    public SessionStatus status() {
        return ctx.status();
    }

    /** 마지막으로 기기에 알린 T19 표시 상태(HELLO_OK.session.view, U3-J23). SESSION_STATE를 보낼 때마다 바뀐다. */
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
                // 앱 종료 T3: 공급자 연결을 닫고 기록기를 비운다. 세션 행은 다음 기동 S3a가 닫는다(BR6.13).
                // 끝나면(실패해도) 정상 종료에 알린다 — T4(폴러 정지)는 공급자 닫기 뒤에 시작한다(RD-13 순서, J304)
                try {
                    closeProvider();
                    ctx.services().recorder().finish(ctx.plan().sessionId());
                } finally {
                    shutdown.handled().complete(null);
                }
            }
            case SessionMailbox.DeletionStop stop -> clocks.onDeletionStop(stop);
            case SessionMailbox.PracticeEvent event -> onPracticeEvent(event.event());
            case SessionMailbox.PracticeTimerFired fired -> onPracticeTimer(fired);
            case SessionMailbox.DeviceTakenOver ignored -> {
                if (clocks.farewellPending()) {
                    finishFarewell(false);
                }
            }
            case DeviceAck ack -> onDeviceAck(ack);
            case DeviceReadyReceived ready -> onDeviceReady(ready);
            case LinkReplaced replaced -> ending.onLinkReplaced(replaced);
            case DeviceStateReceived state -> ending.onDeviceState(state);
            case PlaybackMarkReceived mark -> ending.onPlaybackMark(mark);
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
                remember(ctx.startOpId(), startSession);
            }
        }
        if (!sent) {
            // BR6.6 ⑤: 같은 처리 안에서 PREPARING → FAILED(DEVICE_NO_ACK) + UNRESPONSIVE
            Optional<ActivitySessionRow> failed = transition(SessionStatus.PREPARING, Trigger.START_SEND_FAILED, null);
            if (failed.isPresent()) {
                markUnresponsive();
                afterTerminal();
            } else {
                syncFromDb();
            }
            return;
        }
        ctx.startSent(true);
        ctx.timers().arm(Kind.DEVICE_ACK, ctx.services().settings().deviceAckTimeout());
        ctx.providerAttempt(ctx.services().connector().connect(ctx.plan().sessionId(), ctx.plan().childContext(), event -> {
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
        }));
    }

    private static boolean isOutputChunk(ProviderEvent event) {
        return event instanceof ProviderEvent.OutputTranscriptDelta || event instanceof ProviderEvent.OutputAudioDelta;
    }

    private boolean putFromProvider(Message message) {
        if (Thread.currentThread() == processing) {
            // F2 · J289: 공급자 닫기(closeProvider · 정리)는 리스너의 Closed를 닫기를 부른 스레드에서 바로 부른다. 그 스레드가 이
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
        if (!fromBoundConnection(ack.connectionId())) {
            return;
        }
        // U3-J7: ACK가 온 명령은 연결이 바뀌어도 다시 보내지 않는다
        if (ack.ackOf() != null) {
            ctx.pendingCommands().remove(ack.ackOf());
        }
        if (!Objects.equals(ack.ackOf(), ctx.startOpId()) || ctx.status() != SessionStatus.PREPARING) {
            return;
        }
        Optional<ActivitySessionRow> row = transition(SessionStatus.PREPARING, Trigger.DEVICE_ACK, null);
        if (row.isEmpty()) {
            syncFromDb();
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
        if (!fromBoundConnection(ready.connectionId()) || !START_PHASE.contains(ctx.status())) {
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
        // BR6.8: DEVICE_READY가 사용 불가를 알리면 20초를 기다리지 않고 즉시 FAILED(DEVICE_NO_ACK), UNRESPONSIVE는 쓰지 않는다(J55)
        Optional<ActivitySessionRow> row = transition(SessionStatus.DEVICE_ACK, Trigger.DEVICE_UNAVAILABLE, null);
        if (row.isPresent()) {
            afterFailed();
        } else {
            syncFromDb();
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
        multiOriginFail(Trigger.PROVIDER_FAILED, FailureCode.PROVIDER_UNAVAILABLE, SessionTransitions.Origin.REALTIME);
    }

    /**
     * U4b(BR2.1 · FR1.1): ACK · DEVICE_READY(OK) · 공급자 session.started가 모두 오면 ACTIVE가 아니라 WAITING_CHILD(참여 확인)로 간다.
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
            syncFromDb();
            return;
        }
        changeStatus(SessionStatus.WAITING_CHILD, ctx.services().clock().now());
        enterWaitingChild();
    }

    /**
     * WAITING_CHILD 진입의 승자 몫(BR2.1 · U4b-J2): ② 시계 해제 · 입력 중계 시작(원음 기록 없음) · LISTENING · 끊김 시계 · 시작 안내(안내 1번째).
     */
    private void enterWaitingChild() {
        ctx.timers().cancel(Kind.DEVICE_ACK);
        ctx.relay().attach(ctx.providerSession());
        ctx.relay().setAccepting(true);
        ctx.relay().start(ctx.services().launcher());
        sendControl(new SessionState(View.LISTENING));
        clocks.armDisconnect();
        join = new JoinTracker(lifecycle().joinWait(), lifecycle().joinWaitAfterReprompt());
        putEvent(EVENT_JOIN_PROMPTED, "{\"prompts\":1}", ctx.services().clock().now());
        playJoinPrompt(ctx.plan().activityType() == ActivityType.PRACTICE ? INTRO_PRACTICE : INTRO_FREE_TALK);
    }

    /** ACTIVE 진입의 승자 몫(BR6.8 · BR7.1 · BR6.12 ④ · U4b BR2.6 · BR5.1): ② 시계 해제 · 원음 기록 시작 · LISTENING · 끊김 시계. */
    private void enterActive() {
        ctx.timers().cancel(Kind.DEVICE_ACK);
        ctx.timers().cancel(Kind.JOIN_WINDOW);
        ctx.joinPromptPlaybackId(null);
        ctx.relay().attach(ctx.providerSession());
        // U3-R9 · U4b-J2: 동의된 세션만, ACTIVE부터 원음 기록을 연다(참여 확인 전 아이 소리는 남기지 않는다)
        if (ctx.plan().rawAudioConsent() && ctx.recording() == null && ctx.consentWithdrawnAt() == null) {
            ctx.recording(ctx.services().recordings().open(ctx.plan().sessionId()));
            ctx.relay().tap(ctx.recording()::child);
        }
        ctx.relay().setAccepting(true);
        ctx.relay().start(ctx.services().launcher());
        if (ctx.view() != View.LISTENING) {
            sendControl(new SessionState(View.LISTENING));
        }
        clocks.armDisconnect();
    }

    // ------------------------------------------------------------------ 참여 확인(U4b L3)

    /**
     * 참여 안내 하나를 재생한다(BR2.1 · BR3.1 · BR3.3). 보낸 순간 말풍선 행을 남기고 그 끝 표식이 창을 연다. 패키지에 없으면 재생만 건너뛰고
     * APPROVED_CONTENT_MISSING을 코드별 1회 남긴 뒤 곧바로 재생이 끝난 것으로 본다(시계 · 횟수는 그대로). 보내지 못해도 끝난 것으로 본다.
     */
    private void playJoinPrompt(String contentCode) {
        Instant now = ctx.services().clock().now();
        Optional<ApprovedItem> item = ctx.plan().approvedItem(contentCode);
        if (item.isEmpty()) {
            reportMissing(contentCode, now);
        } else {
            UUID playbackId = UUID.randomUUID();
            if (sendControl(new PlayApprovedContent(playbackId, contentCode, item.get().contentVersionId()))) {
                ctx.joinPromptPlaybackId(playbackId);
                ctx.playback().started(playbackId, contentCode);
                ctx.timers().arm(Kind.PLAYBACK_EXPIRY, ctx.services().settings().playbackExpiry());
                approvedBubble(item.get(), now);
                return;
            }
        }
        ctx.joinPromptPlaybackId(null);
        onJoinPromptPlayed(now);
    }

    /** 참여 안내 재생이 끝났다(서버가 끝 표식을 받은 시각, BR2.2): 창을 연다. */
    private void onJoinPromptPlayed(Instant at) {
        if (ctx.status() != SessionStatus.WAITING_CHILD || join == null || join.isFinished()) {
            return;
        }
        join.promptPlayed(at);
        armJoinWindow();
    }

    private void armJoinWindow() {
        Instant end = join == null ? null : join.windowEnd();
        if (end == null || technicalPause != null) {
            return;
        }
        Duration left = Duration.between(ctx.services().clock().now(), end);
        ctx.timers().arm(Kind.JOIN_WINDOW, left.isNegative() ? Duration.ZERO : left);
    }

    /** 참여 창 시계 만기: 결정표의 무응답 칸(BR2.4). 창이 밀렸으면(일시정지) 남은 시간으로 다시 건다. */
    private void onJoinWindow() {
        if (ctx.status() != SessionStatus.WAITING_CHILD || join == null || join.isFinished()) {
            return;
        }
        if (judgment.openPoint() == JudgmentPoint.J1) {
            // U11 W1 6: J1이 열린 동안의 창 만기는 표시만 하고 턴이 닫힌 뒤 tick을 부른다
            judgment.deferJoinTick();
            return;
        }
        JoinTracker.Action action = join.tick(ctx.services().clock().now());
        if (action == JoinTracker.Action.NONE) {
            armJoinWindow();
            return;
        }
        applyJoinAction(action, null, ctx.services().clock().now());
    }

    /**
     * WAITING_CHILD의 아이 확정 전사(BR2.3 · BR2.5): 저장하지 않고 분류에만 쓴다. 안내를 재생하는 중의 대답도 곧바로 분류한다(R-11 · 5354a32
     * JoinTracker 동작).
     */
    private void onJoinReply(String transcript, Instant at) {
        if (join == null || join.isFinished()) {
            return;
        }
        // U11 BR5.3: J1이 열린 동안 새 확정 전사가 오면 이전 J1을 SUPERSEDED로 닫고 새 전사를 처음부터 다시 처리한다
        judgment.onTranscript(false);
        JoinTracker.Reply reply = JoinTracker.classify(transcript);
        if (reply == JoinTracker.Reply.UNCLEAR && openJoinJudgment(transcript, at)) {
            return;
        }
        applyJoinAction(join.reply(reply, at), reply, at);
    }

    // ------------------------------------------------------------------ U11 판정 턴 (W1 · W2 · W4)

    /**
     * W1: 불명확한 참여 대답은 아직 {@code join.reply}에 넣지 않고 J1을 연다. 열 수 없으면(공급자 없음 · 판독 불가) 기록 한 건만 남기고 지금 흐름으로
     * 간다.
     */
    private boolean openJoinJudgment(String transcript, Instant at) {
        JudgmentCoordinator.Readiness ready = judgment.preflight(transcript);
        if (ready != JudgmentCoordinator.Readiness.READY) {
            judgment.recordSkipped(ready, JudgmentPoint.J1, JudgmentTrigger.JOIN_UNCLEAR, 0, NextAction.REPROMPT_JOIN);
            return false;
        }
        judgment.open(new JudgmentCoordinator.Open(JudgmentPoint.J1, JudgmentTrigger.JOIN_UNCLEAR, JudgmentOwner.EXECUTOR, 0,
                transcript, at, null, List.of(), (turn, outcome) -> JudgmentTurn.Decision.close(),
                closure -> afterJoinJudgment(closure, at)));
        return true;
    }

    /** J1 결과 → {@link JoinTracker} 답 하나(원래 확정 시각으로 한 번만 넣는다, W1 4). 휴식 · 불명확 · 보류는 모두 불명확이다(U11-D6). */
    private JudgmentCoordinator.Handled afterJoinJudgment(JudgmentTurn.Closure closure, Instant at) {
        JudgmentOutcomeKind kind = closure.outcome().kind();
        if (kind != JudgmentOutcomeKind.CONFIRMED && kind != JudgmentOutcomeKind.ABSTAINED) {
            return JudgmentCoordinator.Handled.NOTHING;
        }
        JoinTracker.Reply reply = JoinTracker.Reply.UNCLEAR;
        NextAction next = NextAction.REPROMPT_JOIN;
        if (kind == JudgmentOutcomeKind.CONFIRMED && "PARTICIPATE".equals(closure.outcome().firstOption())) {
            reply = JoinTracker.Reply.ACCEPT;
            next = NextAction.START_JOIN;
        } else if (kind == JudgmentOutcomeKind.CONFIRMED && "DECLINE".equals(closure.outcome().firstOption())) {
            reply = JoinTracker.Reply.REFUSE;
            next = NextAction.DECLINE_JOIN;
        }
        if (ctx.status() == SessionStatus.WAITING_CHILD && join != null && !join.isFinished()) {
            applyJoinAction(join.reply(reply, at), reply, at);
        }
        if (closure.joinTickDeferred() && ctx.status() == SessionStatus.WAITING_CHILD && join != null && !join.isFinished()
                && technicalPause == null) {
            onJoinWindow();
        }
        return new JudgmentCoordinator.Handled(next, false);
    }

    private void applyJoinAction(JoinTracker.Action action, JoinTracker.Reply reply, Instant at) {
        switch (action) {
            case START -> acceptJoin();
            case REPROMPT, CLARIFY -> {
                ctx.timers().cancel(Kind.JOIN_WINDOW);
                putEvent(EVENT_JOIN_RE_PROMPTED, "{\"prompts\":" + join.prompts() + ",\"cause\":\""
                        + (action == JoinTracker.Action.REPROMPT ? "NO_REPLY" : "UNCLEAR") + "\"}", at);
                playJoinPrompt(RE_PROMPT);
            }
            case OFFER_ALTERNATIVE -> {
                ctx.timers().cancel(Kind.JOIN_WINDOW);
                putEvent(EVENT_JOIN_ALTERNATIVE_OFFERED, "{\"prompts\":" + join.prompts() + ",\"reply\":\""
                        + (reply == null ? "NONE" : reply.name()) + "\"}", at);
                playJoinPrompt(DECLINE_ALTERNATIVE);
            }
            case DECLINE -> declineJoin();
            case NONE -> {
                // 안내 없이 같은 창을 계속 기다린다(재안내를 이미 쓴 불명확 대답)
            }
        }
    }

    /** 참여 수락 → ACTIVE(BR2.4 · BR2.6): started_at, 원음 기록 시작, 응답 생성 요청 1회. */
    private void acceptJoin() {
        ctx.timers().cancel(Kind.JOIN_WINDOW);
        Instant now = ctx.services().clock().now();
        Optional<ActivitySessionRow> row = ctx.services().transitions().transition(ctx.plan().sessionId(),
                SessionStatus.WAITING_CHILD, SessionStatus.ACTIVE, SessionChange.none().withStartedAt(now),
                SessionTransitions.Origin.REALTIME, ctx.plan().traceId());
        if (row.isEmpty()) {
            syncFromDb();
            return;
        }
        changeStatus(SessionStatus.ACTIVE, ctx.services().clock().now());
        putEvent(EVENT_JOIN_ACCEPTED, "{\"prompts\":" + (join == null ? 1 : join.prompts()) + "}", now);
        afterActiveWon();
    }

    /** ACTIVE 전이 승자의 뒷일: 진입 몫과 발언권 반환(응답 생성 요청 1회, BR2.6). */
    private void afterActiveWon() {
        enterActive();
        Instant now = ctx.services().clock().now();
        resetSilence(now);
        if (practice == null || !practice.controlsOutput()) requestResponse(InstructionTemplates.ResponseStart.FIRST);
        // U4b BR10.1: PRACTICE면 확장점 'ACTIVE 진입'
        if (practice != null) {
            PracticeSessionExtension.PracticeContext context = new PracticeSessionExtension.PracticeContext(
                    ctx.plan().sessionId(), ctx.plan().practiceJson(), ctx.plan().approved().keySet(), ctx.plan().rawAudioConsent(), now,
                    ctx.plan().approvedSpeech());
            practiceSignal(() -> practice.onActive(context, practiceOutput), true);
        }
    }

    /**
     * 참여 거절 → DECLINED(BR2.4 · BR3.2 · BR4.7): 기록기의 대기 칸을 버린 뒤 전이 트랜잭션에서 그 세션의 전사 행을 지우고, 마무리 인사를 closing-confirm
     * 상한 안에서 돌린다(말풍선 행 없음).
     */
    private void declineJoin() {
        ctx.timers().cancel(Kind.JOIN_WINDOW);
        ctx.services().recorder().discardTranscripts(ctx.plan().sessionId(), ctx.services().settings().timerTick());
        Instant now = ctx.services().clock().now();
        Optional<ActivitySessionRow> row = ctx.services().transitions().transition(ctx.plan().sessionId(),
                SessionStatus.WAITING_CHILD, SessionStatus.DECLINED,
                TransitionTable.changeFor(Trigger.JOIN_DECLINED, null, now), SessionTransitions.Origin.REALTIME,
                ctx.plan().traceId(), new SessionTransitions.Extras(null, true));
        if (row.isEmpty()) {
            syncFromDb();
            return;
        }
        changeStatus(SessionStatus.DECLINED, ctx.services().clock().now());
        putEvent(EVENT_JOIN_DECLINED, "{\"prompts\":" + (join == null ? 1 : join.prompts()) + "}", now);
        startFarewell();
    }

    /** DECLINED 마무리 인사(BR4.7 · U4b-J8): 출력 · 입력을 멈추고 CLOSING 문구 1회 → 그 끝 표식 뒤 CLOSE_TRANSPORT(DECLINED). */
    private void startFarewell() {
        bumpGeneration();
        ctx.timers().cancel(Kind.SEGMENT_QUIET);
        ctx.timers().cancel(Kind.DISCONNECT);
        ctx.timers().cancel(Kind.PLAYBACK_EXPIRY);
        ctx.segment().reset();
        ctx.relay().setAccepting(false);
        ctx.joinPromptPlaybackId(null);
        boolean closedNow = ctx.closing().beginFarewell(closingDevice(),
                ctx.plan().closing() == null ? null : ctx.plan().closing().contentVersionId(), ctx.playback(), UUID::randomUUID);
        if (closedNow) {
            finishFarewell(true);
            return;
        }
        ctx.timers().arm(Kind.CLOSING_CONFIRM, ctx.services().settings().closingConfirm());
    }

    /**
     * DECLINED 인사 순서를 끝낸다(CLOSE_TRANSPORT를 보냈거나 상한을 넘김 · 같은 기기의 새 세션). 그 뒤 정리한다(정산은 인사 순서보다 앞서지 않는다,
     * BR4.7).
     *
     * @param deviceStillOurs 기기가 아직 이 세션에 묶여 있는가(아니면 기기 명령을 보내지 않는다)
     */
    private void finishFarewell(boolean deviceStillOurs) {
        ctx.timers().cancel(Kind.CLOSING_CONFIRM);
        Instant now = ctx.services().clock().now();
        if (deviceStillOurs) {
            annotateQuietly(SessionStatus.DECLINED, SessionChange.none().withTransportClosedAt(now));
            sendControl(new SessionState(View.STANDBY));
        }
        afterTerminal();
    }

    /** 이 세션이 아직 기기 연결을 쥐고 있는가(같은 기기에 새 세션이 묶였으면 거짓, BR4.7). */
    private boolean deviceStillOurs() {
        DeviceLink current = ctx.link();
        if (current == null || !current.isOpen()) {
            return false;
        }
        UUID bound = current.boundSessionId();
        return bound == null || bound.equals(ctx.plan().sessionId());
    }

    /**
     * 종료 상태로 간 뒤의 뒷일(U4b BR8.1 · WF7): ① 공급자를 닫고 provider_closed_at ② 정산 창(공급자 연결이 닫혔다는 알림 또는 record-settle) —
     * 그 동안 온 아이 확정 전사는 발화 시작이 ended_at보다 이르면 저장한다(BR8.6) ③ ~ ⑦ {@link #finishSettle}. 정산 입구가 없으면(실행기 단위 시험
     * 기본값) 곧바로 정리한다. 세션당 한 번이다.
     */
    private void afterTerminal() {
        if (ctx.terminated() || ctx.settling()) {
            return;
        }
        stopPracticeProgress();
        if (!ctx.services().settlement().enabled()) {
            teardown();
            return;
        }
        ctx.settling(true);
        Instant now = ctx.services().clock().now();
        ctx.sessionEndedAt(now);
        try {
            ctx.sessionEndedAt(ctx.services().transitions().find(ctx.plan().sessionId()).map(ActivitySessionRow::endedAt)
                    .filter(Objects::nonNull).orElse(now));
        } catch (RuntimeException e) {
            ctx.services().events().appError(EventLog.Component.SESSION_LIFECYCLE, e);
        }
        ctx.timers().cancelAll();
        if (inputGroupId != null) {
            // 묶던 입력 전사는 정산 창 안에서 이어지는 조각을 받아 조용해지면(또는 창이 끝나면) 확정한다
            ctx.timers().arm(Kind.INPUT_TRANSCRIPT_QUIET, ctx.services().settings().inputTranscriptQuiet());
        }
        ctx.relay().setAccepting(false);
        boolean alreadyClosed = ctx.providerSession() == null || ctx.providerClosedSignal();
        closeProvider();
        SessionStatus ended = ctx.status();
        annotateQuietly(ended, SessionChange.none().withProviderClosedAt(now));
        if (alreadyClosed) {
            finishSettle();
            return;
        }
        ctx.timers().arm(Kind.RECORD_SETTLE, lifecycle().recordSettle());
    }

    /**
     * 정산 창이 끝났다(BR8.1 ③ ~ ⑦): 확장점 정산 시점 · 전사 기록기 비우기 · practice 값 · 스냅숏 + SessionRecordCaptured 한 트랜잭션. 쓰기가 실패하면
     * 앱 오류 한 줄을 남기고 정리한다(재무장이 다시 쓴다). 이 뒤에 온 사건은 무시한다(BR8.7).
     */
    private void finishSettle() {
        if (!ctx.settling() || ctx.terminated()) {
            return;
        }
        if (practice != null && practice.controlsOutput()) {
            Duration remaining = practice.settlementDelay(ctx.services().clock().now());
            if (remaining.isPositive()) {
                ctx.timers().arm(Kind.RECORD_SETTLE, remaining);
                return;
            }
        }
        ctx.timers().cancel(Kind.RECORD_SETTLE);
        quietly(this::flushInputGroupInSettle);
        Instant cutoff = ctx.services().clock().now();
        quietly(() -> ctx.transcripts().closeAll(cutoff));
        quietly(() -> ctx.services().recorder().flushSession(ctx.plan().sessionId(), ctx.services().settings().timerTick()));
        try {
            ctx.services().settlement().capture(ctx.plan().sessionId(), practiceRecordForSettle(cutoff), cutoff);
        } catch (RuntimeException e) {
            ctx.services().events().appError(EventLog.Component.SESSION_LIFECYCLE, e);
        }
        teardown();
    }

    /**
     * 연습 확장점의 정산 시점과 practice 값(BR10.6 · BR8.3): 정산 창이 끝난 뒤 정산 시점을 한 번 부르고 practice 값을 받는다. 확장점이 없거나 예외가
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

    // ------------------------------------------------------------------ 연습 확장점(U4b BR10.1 ~ BR10.6)

    /**
     * 확장점 시점 하나를 부르고 돌려받은 신호를 처리한다(BR10.3). 예외는 PRACTICE_EXTENSION_FAILED에 남기고 자유대화처럼 잇는다(BR10.1).
     *
     * @param signalsAllowed 신호를 처리하는가(CLOSING · 정산 창의 사건은 전달하되 신호를 무시한다, BR10.6 · R-10)
     */
    private void practiceSignal(java.util.function.Supplier<PracticeSessionExtension.Signal> call,
            boolean signalsAllowed) {
        PracticeSessionExtension.Signal signal;
        try {
            signal = call.get();
            publishLive(ctx.services().clock().now());
        } catch (RuntimeException e) {
            practiceFailure("EXCEPTION", e);
            return;
        }
        if (!signalsAllowed || signal == null || signal == PracticeSessionExtension.Signal.NONE) {
            return;
        }
        if (ctx.status() != SessionStatus.ACTIVE) {
            putEvent(EVENT_PRACTICE_EXTENSION_FAILED, "{\"reason\":\"SIGNAL_NOT_ACTIVE\",\"signal\":\"" + signal.name()
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

    /** 확장점 예외(BR10.1): 남기고 확장점을 끈다 — 그 세션은 자유대화처럼 잇고 정산 시점은 건너뛰며 practice는 null이다. */
    private void practiceFailure(String reason, RuntimeException cause) {
        putEvent(EVENT_PRACTICE_EXTENSION_FAILED, "{\"reason\":\"" + reason + "\"}", ctx.services().clock().now());
        ctx.services().events().appError(EventLog.Component.SESSION_LIFECYCLE, cause);
        if (practice != null && practice.controlsOutput()) {
            stopPracticeProgress();
            practiceHold = true;
            cancelPracticeTimers();
            if (ctx.status() == SessionStatus.ACTIVE) {
                multiOriginFail(Trigger.SERVER_RESTART, FailureCode.SERVER_RESTART, SessionTransitions.Origin.REALTIME);
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
     * 확장점 사건 전달(BR10.5 · BR10.6 · R-10): ACTIVE면 전달하고 신호를 처리한다. CLOSING · 정산 창이면 전달하되 신호는 무시한다(정산 창 사건 수를
     * 센다). 정산 뒤 · 그 밖 상태는 무시한다(BR8.7).
     */
    private void onPracticeEvent(PracticeSessionExtension.ExtensionEvent event) {
        if (event.name().equals("u5b-voice-failed") && event.value() instanceof kr.mujoong.realtime.voice.PracticeSpeech speech) {
            if (speech.equals(pendingPracticeSpeech)) {
                pendingPracticeSpeech = null; practiceHold = true;
                practicePlayback(speech, kr.mujoong.realtime.practice.PracticePorts.Playback.FAILED);
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
            if (ctx.settling() && current.controlsOutput()) finishSettle();
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
            kr.mujoong.realtime.practice.PracticePorts.Playback fact) {
        practicePlayback(speech, fact, ctx.services().clock().now());
    }

    private void practicePlayback(kr.mujoong.realtime.voice.PracticeSpeech speech,
            kr.mujoong.realtime.practice.PracticePorts.Playback fact, Instant receivedAt) {
        ctx.mailbox().putSafety(new SessionMailbox.PracticeEvent(new PracticeSessionExtension.ExtensionEvent(
                PracticeSessionExtension.ExtensionEvent.Kind.PLAYBACK_FACT, "u5b-playback", speech.correlationId(),
                new PracticeSessionExtension.PlaybackFact(speech, fact, speech.text(), receivedAt))));
    }

    private void stopPracticeProgress() {
        if (practice == null || !practice.controlsOutput()) return;
        practice.onStopping();
        pendingPracticeSpeech = null;
        practiceAudio.clear();
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
            if (speech != null) practicePlayback(speech, kr.mujoong.realtime.practice.PracticePorts.Playback.FAILED);
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
        return technicalPause != null || (practice != null && practice.controlsOutput()
                && (ctx.stopConfirm().isActive() || judgment.openPoint() == JudgmentPoint.J6));
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
                kr.mujoong.realtime.practice.PracticePorts.Playback.INTERRUPTED, now));
    }

    /** 기술 일시정지와 종료 확인 동안 남은 반응 시간을 보존한다. */
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
     * W4: 확장이 요청한 판정(J2 ~ J5). 거절 조건은 열린 턴 · 지점 밖 · 최근 확정 전사 4개 밖 · 기준 모양 틀림 · 공급자 없음이다(ACTIVE가 아니면
     * 호출 앞에서 PRACTICE_EXTENSION_FAILED로 거절한다). 다음 행동은 확장이 정하므로 실행부는 지시를 내지 않는다(BR8.2).
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
                target.text(), target.at(), shaped, judgment.recentBefore(target.seq()),
                (turn, outcome) -> JudgmentTurn.Decision.close(), closure -> afterExtensionJudgment(closure, correlationId)));
        return true;
    }

    /** 확장 소유 판정이 닫혔다: 결과(확률 없음) 또는 보류 · 장애 사건, 모은 출력 전사가 있으면 그 사건도 확장에 보낸다(BR8.3). */
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
                sendControl(new SessionState(View.THINKING));
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
                    id -> ctx.services().requests().instructionsAppend(id, action.content()),
                    () -> ctx.mailbox().putSafety(new SessionMailbox.JudgmentInstructionFailed(turnId)), true);
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

    /** 기기 표식 · 끝내기 협력 객체가 실행기에 남은 로직으로 되돌아오는 자리. */
    private final class EndingEnv implements EndingFlow.Env {

        @Override
        public void putEvent(String type, String payloadJson, Instant at) {
            SessionExecutor.this.putEvent(type, payloadJson, at);
        }

        @Override
        public void bumpGeneration() {
            SessionExecutor.this.bumpGeneration();
        }

        @Override
        public long bumpOutput(OutputGeneration.BumpReason reason) {
            return SessionExecutor.this.bumpOutput(reason);
        }

        @Override
        public boolean fromBoundConnection(UUID connectionId) {
            return SessionExecutor.this.fromBoundConnection(connectionId);
        }

        @Override
        public ClosingSequence.Device closingDevice() {
            return SessionExecutor.this.closingDevice();
        }

        @Override
        public void syncFromDb() {
            SessionExecutor.this.syncFromDb();
        }

        @Override
        public void syncFromDb(UUID endRequestId) {
            SessionExecutor.this.syncFromDb(endRequestId);
        }

        @Override
        public boolean sendControl(WsPayload payload) {
            return SessionExecutor.this.sendControl(payload);
        }

        @Override
        public void changeStatus(SessionStatus next, Instant now) {
            SessionExecutor.this.changeStatus(next, now);
        }

        @Override
        public void annotateQuietly(SessionStatus expected, SessionChange change) {
            SessionExecutor.this.annotateQuietly(expected, change);
        }

        @Override
        public void unbind(DeviceLink target) {
            SessionExecutor.this.unbind(target);
        }

        @Override
        public void quietly(Runnable step) {
            SessionExecutor.this.quietly(step);
        }

        @Override
        public void closeProvider() {
            SessionExecutor.this.closeProvider();
        }

        @Override
        public void multiOriginFail(Trigger trigger, FailureCode code, SessionTransitions.Origin origin) {
            SessionExecutor.this.multiOriginFail(trigger, code, origin);
        }

        @Override
        public void afterFailed(CloseTransport.Reason reason) {
            SessionExecutor.this.afterFailed(reason);
        }

        @Override
        public void afterTerminal() {
            SessionExecutor.this.afterTerminal();
        }

        @Override
        public void deviceHeard() {
            SessionExecutor.this.deviceHeard();
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
            SessionExecutor.this.onJoinPromptPlayed(at);
        }

        @Override
        public void onStopConfirmPlayed(Instant at) {
            SessionExecutor.this.onStopConfirmPlayed(at);
        }

        @Override
        public void endPause(Instant now) {
            SessionExecutor.this.endPause(now);
        }

        @Override
        public void startPause(Instant since, Instant gapFrom) {
            SessionExecutor.this.startPause(since, gapFrom);
        }

        @Override
        public Instant lastDeviceMessageAt() {
            return SessionExecutor.this.lastDeviceMessageAt();
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
                kr.mujoong.realtime.practice.PracticePorts.Playback fact, Instant receivedAt) {
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
        public boolean farewellPending() {
            return clocks.farewellPending();
        }

        @Override
        public void finishFarewell(boolean deviceStillOurs) {
            SessionExecutor.this.finishFarewell(deviceStillOurs);
        }

        @Override
        public void guardrailTurnBoundary() {
            guardrail.onTurnBoundary();
        }

        @Override
        public SessionStateChanged.TechnicalPause technicalPause() {
            return technicalPause;
        }
    }

    /** 시계 구역 협력 객체가 실행기에 남은 로직으로 되돌아오는 자리. */
    private final class ClocksEnv implements SessionClocks.Env {

        @Override
        public boolean deviceStillOurs() {
            return SessionExecutor.this.deviceStillOurs();
        }

        @Override
        public ClosingSequence.Device closingDevice() {
            return SessionExecutor.this.closingDevice();
        }

        @Override
        public void finishFarewell(boolean deviceStillOurs) {
            SessionExecutor.this.finishFarewell(deviceStillOurs);
        }

        @Override
        public void onOutputQuiet() {
            SessionExecutor.this.onOutputQuiet();
        }

        @Override
        public void onInstructionWaitExpired() {
            SessionExecutor.this.onInstructionWaitExpired();
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
            SessionExecutor.this.onJoinPromptPlayed(at);
        }

        @Override
        public void onStopConfirmPlayed(Instant at) {
            SessionExecutor.this.onStopConfirmPlayed(at);
        }

        @Override
        public void redeliver() {
            SessionExecutor.this.redeliver();
        }

        @Override
        public void onJoinWindow() {
            SessionExecutor.this.onJoinWindow();
        }

        @Override
        public void onStopConfirmWindow() {
            SessionExecutor.this.onStopConfirmWindow();
        }

        @Override
        public void onSilenceTimer() {
            SessionExecutor.this.onSilenceTimer();
        }

        @Override
        public void onGapDisplayCheck() {
            SessionExecutor.this.onGapDisplayCheck();
        }

        @Override
        public void armGapDisplay() {
            SessionExecutor.this.armGapDisplay();
        }

        @Override
        public void finishSettle() {
            SessionExecutor.this.finishSettle();
        }

        @Override
        public void markUnresponsive() {
            SessionExecutor.this.markUnresponsive();
        }

        @Override
        public void afterFailed() {
            SessionExecutor.this.afterFailed();
        }

        @Override
        public void afterTerminal() {
            SessionExecutor.this.afterTerminal();
        }

        @Override
        public void syncFromDb() {
            SessionExecutor.this.syncFromDb();
        }

        @Override
        public void changeStatus(SessionStatus next, Instant now) {
            SessionExecutor.this.changeStatus(next, now);
        }

        @Override
        public void closeProvider() {
            SessionExecutor.this.closeProvider();
        }

        @Override
        public void quietly(Runnable step) {
            SessionExecutor.this.quietly(step);
        }

        @Override
        public void bumpGeneration() {
            SessionExecutor.this.bumpGeneration();
        }

        @Override
        public boolean sendControl(WsPayload payload) {
            return SessionExecutor.this.sendControl(payload);
        }

        @Override
        public SessionTransitions.Extras transitionExtras() {
            return ending.transitionExtras();
        }

        @Override
        public void teardown() {
            SessionExecutor.this.teardown();
        }
    }

    /** 제브 게이트 협력 객체가 실행기에 닿는 자리(U13). */
    private final class GuardrailEnv implements GuardrailCoordinator.Env {

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
            if (reasonOrNull != null) {
                ctx.services().metrics().gateDiscard(reasonOrNull.name());
            }
            closeAiRow(sealed, held);
        }

        @Override
        public void finishAbandoned() {
            // U12 BR5.6: 재지시가 소리로 이어지지 않으면 열린 생성형 슬롯을 실패로 한 번 닫는다
            closeGeneratedSlot(null, kr.mujoong.realtime.practice.PracticePorts.Playback.FAILED);
            finishDiscard();
        }

        @Override
        public void practiceVoided(UUID practiceSpeechId) {
            // U12 BR5.6: 무효로 닫힌 검사의 생성형 슬롯이 아직 열려 있으면 INTERRUPTED로 한 번 닫는다
            closeGeneratedSlot(practiceSpeechId, kr.mujoong.realtime.practice.PracticePorts.Playback.INTERRUPTED);
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
                    id -> ctx.services().requests().instructionsAppend(id, InstructionTemplates.Retry.RETRY_FREE_TURN.content()),
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
            // 연습 확장점 알림(BR6.5): 안전 편지함을 거쳐 실행기 스레드에서 전달한다. 운영에서 연습 모드 검사를 시작하는 것은 U12의 생성형 슬롯(speakGenerated)이다
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
     * U12 BR5.6: 열린 생성형 슬롯(PracticeSpeech.generated)을 닫고 종료 사실을 확장에 한 번 보낸다. 슬롯이 없거나 생성형이 아니거나 식별자가
     * 다르면 아무것도 하지 않는다(승인 발화 슬롯의 기존 길은 건드리지 않는다).
     *
     * @param correlationId 닫을 슬롯의 식별자(null이면 지금 열린 생성형 슬롯)
     */
    private void closeGeneratedSlot(UUID correlationId, kr.mujoong.realtime.practice.PracticePorts.Playback fact) {
        var slot = pendingPracticeSpeech;
        if (slot == null || !slot.generated() || (correlationId != null && !slot.correlationId().equals(correlationId))) {
            return;
        }
        pendingPracticeSpeech = null;
        practiceHold = true;
        practicePlayback(slot, fact);
    }

    /** U12 BR5.6: 열린 생성형 슬롯의 역할에 맞는 제브 연습 모드. */
    private static GuardrailRequest.Mode practiceGuardrailMode(kr.mujoong.realtime.voice.PracticeSpeech slot) {
        return "COACH".equals(slot.partnerRole()) ? GuardrailRequest.Mode.PRACTICE_COACH : GuardrailRequest.Mode.PRACTICE_PEER;
    }

    /** 출력 능력 거절(R-10 · BR10.4): ACTIVE가 아니거나 능력 밖 요청이면 남기고 거짓. */
    private boolean refusePractice(String capability, String reason) {
        putEvent(EVENT_PRACTICE_EXTENSION_FAILED, "{\"reason\":\"" + reason + "\",\"capability\":\"" + capability
                + "\"}", ctx.services().clock().now());
        return false;
    }

    /** 출력 능력을 쓸 수 있는가(ACTIVE이고 정산 전, 확장점이 살아 있다). */
    private boolean practiceActive() {
        return ctx.status() == SessionStatus.ACTIVE && !ctx.settling() && !ctx.terminated() && practice != null;
    }

    /** 실행기가 확장점에 주는 출력 능력(BR10.4). post 밖의 능력은 실행기 스레드에서만 부른다. */
    private final class PracticeOutputPort implements PracticeSessionExtension.PracticeOutput {

        @Override public boolean speak(kr.mujoong.realtime.voice.PracticeSpeech speech) {
            if (!practiceActive() || ctx.providerSession() == null || !ctx.providerSession().isOpen()) return false;
            bumpGeneration();
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
                // BR3.3: 패키지에 없으면 재생만 건너뛰고 곧바로 끝난 것으로 본다
                reportMissing(String.valueOf(contentCode), now);
                ctx.mailbox().putSafety(new SessionMailbox.PracticeEvent(new PracticeSessionExtension.ExtensionEvent(
                        PracticeSessionExtension.ExtensionEvent.Kind.PLAYBACK_DONE, String.valueOf(contentCode),
                        playbackId, null)));
                return true;
            }
            if (!sendControl(new PlayApprovedContent(playbackId, contentCode, item.get().contentVersionId()))) {
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
            // U11 BR3.4: 공급자 응답 취소 대신 출력 세대를 올려 만들던 응답의 남은 조각을 버린다
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
            // U12 BR5.4: 시작 패키지 approvedSpeech의 승인 문장을 기존 승인 발화 재생(speak) 길로 낸다(원문 일치라 제브 호출 없음). 승인 문장이
            // 없으면 거짓이고 호출한 확장이 생성형 알림으로 낸다
            String code = "COACH".equals(toRole) ? "COACH_SWITCH" : "PEER".equals(toRole) ? "PEER_RETURN" : null;
            String sentence = code == null ? null : ctx.plan().approvedSpeech().get(code);
            if (sentence == null) {
                return false;
            }
            try {
                return speak(kr.mujoong.realtime.voice.PracticeSpeech.scenario(UUID.randomUUID(), NOTICE_SCENARIO_ID,
                        ctx.outputGen().generation(), "COACH".equals(toRole) ? "코치" : "친구", sentence));
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
                instruction = new kr.mujoong.realtime.voice.PracticeInstruction(instructionText);
            } catch (RuntimeException e) {
                return refusePractice("speakGenerated", "INVALID");
            }
            // 한 발화마다 재지시 수를 새로 센다(앞 발화가 재지시를 썼어도 이 발화는 한 번 다시 검사받는다)
            guardrail.onTurnBoundary();
            bumpGeneration();
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
        public boolean setThinking(boolean on) {
            if (!practiceActive()) {
                return refusePractice("setThinking", "NOT_ACTIVE");
            }
            practiceThinking = on;
            if (on) {
                if (ctx.view() == View.LISTENING && ctx.devicePlaying().isEmpty()) {
                    sendControl(new SessionState(View.THINKING));
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
     * 정산 창 안 공급자 사건(BR8.6 · FR7.2): 연결 닫힘 알림이면 창을 닫고, 아이 확정 전사는 발화 시작이 ended_at보다 이르면 저장하고 아니면 버린다.
     */
    private void onLateProviderEvent(ProviderEvent event, Instant at) {
        switch (event) {
            case ProviderEvent.Closed ignored -> {
                ctx.providerClosedSignal(true);
                finishSettle();
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
     * BR2.6: 발언권을 모델에 돌려줄 때(참여 뒤 첫 응답 · 확인 질문 뒤 이어 가기 · 연습 requestResponse) 지시 하나를 보낸다. 공급자에는 응답 요청
     * type이 없어 지시가 곧 발화다(설계 개정 A2). 세대를 올리고 직렬 큐로 보낸다. 참여 전(그리고 확인 대기 동안) 시작된 자동 응답은 출력 보류가
     * 버린다(BR11.13).
     */
    private void requestResponse(InstructionTemplates.ResponseStart start) {
        sendInstruction(OutputGeneration.BumpReason.SERVER_SEND, "provider-respond-",
                id -> ctx.services().requests().responseStart(id, start), null, true);
    }

    /** 공급자 출력을 받지 않는 때(참여 확인 중 · 종료 확인 질문 중 · 확장점이 막은 동안, BR2.6 · BR4.3 · BR10.4). */
    private boolean outputHeld() {
        return ctx.status() == SessionStatus.WAITING_CHILD || ctx.stopConfirm().isActive() || judgment.holdsOutput()
                || (practiceHold && ctx.status() == SessionStatus.ACTIVE);
    }

    /** BR3.3: 빠진 승인 문구는 세션당 코드별 1회만 남긴다(payload는 코드만). */
    private void reportMissing(String contentCode, Instant at) {
        if (missingReported.add(contentCode)) {
            putEvent(EVENT_APPROVED_CONTENT_MISSING, "{\"code\":\"" + contentCode + "\"}", at);
        }
    }

    /** BR3.1: 보낸 승인 문구의 AI 말풍선 행(원문을 모르면 남기지 않는다). */
    private void approvedBubble(ApprovedItem item, Instant at) {
        if (item.text() != null && !item.text().isBlank()) {
            ctx.transcripts().approvedLine(item.text(), at);
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
            case ProviderEvent.Error ignored -> providerError();
            case ProviderEvent.Closed ignored -> providerLost();
            case ProviderEvent.InputTranscriptDelta delta -> onInputTranscriptDelta(delta, at);
            case ProviderEvent.InputTranscriptDone done -> {
                // 공급자는 이 사건을 보내지 않는다(설계 개정 A7). 대신 보내는 쪽(시험 대역)이 있으면 묶음을 그것으로 확정한 것으로 본다
                clearInputGroup();
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
                onOutputChunk(delta.delta(), delta.startMs(), null, at, received.receivedNanos());
            case ProviderEvent.OutputAudioDelta delta ->
                onOutputChunk(null, -1L, delta.audio(), at, received.receivedNanos());
        }
    }

    /**
     * 입력 전사 조각 하나(설계 개정 A7). 공급자는 전사 완료 · 말 끝 사건을 주지 않으므로 조각을 묶고, {@code input-transcript-quiet} 동안 조각이
     * 없으면 {@link #onInputTranscriptQuiet}가 그 묶음을 확정 전사로 만든다. 조각의 저장 규칙(동의 철회 · 참여 전 · 출력 범위)은 그대로다.
     */
    private void onInputTranscriptDelta(ProviderEvent.InputTranscriptDelta delta, Instant at) {
        groupInputDelta(delta);
        if (ctx.consentWithdrawnAt() != null ||ctx.status() == SessionStatus.WAITING_CHILD) {
            // BR1.7: 동의 철회 뒤 도착한 아이 전사는 저장하지 않는다(확정 때 센다). BR2.5: 참여 전 아이 전사도 저장하지 않는다
            return;
        }
        if (inRelayRange()) {
            ctx.services().latency().inputActivity(ctx.plan().sessionId());
            // BR7.3: 아이 입력 재개는 열린 구간을 확정한다(대화가 넘어갔으니 완결 요청 교정은 하지 않는다)
            if (ctx.segment().isOpen()) {
                sealAndGate(true);
            }
            ctx.transcripts().childDelta(inputGroupId, delta.delta(), at);
        }
    }

    /** 조각을 현재 묶음에 더하고(없으면 묶음을 연다) 조용 시계를 다시 건다. 묶음 id를 돌려준다. */
    private String groupInputDelta(ProviderEvent.InputTranscriptDelta delta) {
        if (inputGroupId == null) {
            inputGroupOpens++;
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
        inputGroup.setLength(0);
    }

    /** 입력 전사 묶음이 조용해졌다: 확정 전사를 지금 확정 전사 입구({@link ProviderEvent.InputTranscriptDone})와 같은 길로 흘린다. */
    private void onInputTranscriptQuiet() {
        String id = inputGroupId;
        String text = inputGroup.toString();
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
        onConfirmedChildTranscript(done, at);
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
        if (ctx.consentWithdrawnAt() != null) {
            droppedAfterConsent++;
            return;
        }
        if (ctx.status() == SessionStatus.WAITING_CHILD) {
            onJoinReply(done.transcript(), at);
            return;
        }
        if (inRelayRange()) {
            boolean uncertain = uncertain(done);
            java.util.OptionalInt seq = ctx.transcripts().childFinal(done.itemId(), done.transcript(), at, uncertain);
            // U4b BR5.1: 아이의 빈 아닌 확정 전사가 공백 시계를 0으로 한다(빈 전사는 리셋하지 않는다)
            if (ctx.status() == SessionStatus.ACTIVE && done.transcript() != null && !done.transcript().isBlank()) {
                resetSilence(at);
            }
            currentTranscriptSeq = seq.orElse(0);
            // U13 호출 지점 ③: 아이 확정 전사 = 새 턴(재지시 수를 0으로, 마지막 서버 지시를 지운다)
            guardrail.onNewTurn(done.transcript());
            if (ctx.status() == SessionStatus.ACTIVE) {
                judgment.remember(currentTranscriptSeq, done.transcript(), at);
                if (judgment.isOpen()) {
                    // U11 BR5.3 · 설계 검토 R-04: 판정 중(지시 단계 포함) 새 확정 전사 — 종료 표현이면 STOPPED, 아니면 SUPERSEDED로 닫고 이 전사를
                    // 아래 지금 흐름으로 처음부터 다시 처리한다(확장 소유 판정 단계는 턴을 그대로 두고 확장에 전달한다)
                    StopIntentJudge.Verdict verdict = stopJudge.judge(done.transcript(), !ctx.devicePlaying().isEmpty()).verdict();
                    supersedeVerdict = verdict;
                    try {
                        judgment.onTranscript(verdict == StopIntentJudge.Verdict.STOP);
                    } finally {
                        supersedeVerdict = null;
                    }
                }
            }
            boolean controlledPractice = practice != null && practice.controlsOutput();
            boolean stopHandled = controlledPractice && judgeStopIntent(done.transcript(), uncertain, at);
            if (!stopHandled && ctx.status() == SessionStatus.ACTIVE && practice != null
                    && !(controlledPractice && technicalPause != null)) {
                PracticeSessionExtension.Utterance utterance = new PracticeSessionExtension.Utterance(
                        seq.orElse(0), at, done.transcript() == null ? "" : done.transcript(), uncertain,
                        !ctx.devicePlaying().isEmpty());
                practiceSignal(() -> practice.onChildUtterance(utterance, practiceOutput), true);
            }
            // 2차 리뷰 2: 종료 의도를 푼 발화의 응답은 막힌 동안 이미 취소됐을 수 있다 — 끝 신호가 오지 않을 '생각 중'을 보내지 않는다
            if (!(controlledPractice ? stopHandled : judgeStopIntent(done.transcript(), uncertain, at))) {
                thinking();
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
    private void onOutputChunk(String transcript, long startMs, byte[] audio, Instant at, long nanos) {
        OutputGeneration.ChunkResult result = transcript != null
                ? ctx.outputGen().onTranscript(at, startMs, outputHeld())
                : ctx.outputGen().onAudio(at, audio.length, outputHeld());
        // 버리는 조각도 조용 시계를 다시 건다(BR11.13): 조각이 계속 오는 동안은 끊김이 아니다
        ctx.timers().arm(Kind.SEGMENT_QUIET, ctx.services().settings().segmentQuiet());
        if (!ctx.outputGen().awaitingNewSpeech()) {
            ctx.timers().cancel(Kind.INSTRUCTION_WAIT);
        }
        if (judgment.isOpen()) {
            // U11 BR3.1: 판정 턴 중 음성은 버리고 전사 조각만 모은다(구간 · 대화 기록 · 보호자 중계에 넣지 않는다)
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
        long gen = ctx.outputGen().generation();
        if (!ctx.segment().isOpen()) {
            instructionNotHeard = null;
            if (!guardrail.isChecking()) {
                // U13 BR5.6: 앞 구간의 제브 검사 중에 시작한 구간의 첫 조각은 앞 턴의 지연 기록을 덮지 않는다
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
     * 지시 없이 출력 세대를 올린다(U11 BR11.5 ~ BR11.7: 끼어들기 · 종료 의도 · 연습 폐기 · 보류 해제). 열린 구간은 '불완전'으로 버리고(AI 행 FINAL ·
     * false), 끊김을 기다리는 단계로 들어가며 조용 시계를 건다. 기기에서 재생 중인 음성을 멈추는 일은 호출자가 지금처럼 {@link #bumpGeneration}(기기
     * 프레임 세대)로 한다.
     *
     * @return 새 출력 세대 번호
     */
    private long bumpOutput(OutputGeneration.BumpReason reason) {
        Instant now = ctx.services().clock().now();
        long generation = ctx.outputGen().bump(now, reason);
        instructionNotHeard = null;
        ctx.timers().cancel(Kind.INSTRUCTION_WAIT);
        ctx.segment().reset();
        ctx.transcripts().aiFinal("", false, now);
        ctx.timers().arm(Kind.SEGMENT_QUIET, ctx.services().settings().segmentQuiet());
        return generation;
    }

    /**
     * 구간 조용 시계 만기(U11 BR11.7). 받는 단계에서는 열린 구간을 조용함으로 봉인해 게이트에 넣고, 지시 없이 올린 세대의 버리는 단계에서는 끊김을
     * 확인한다. 지시를 보낸 세대에서 전사만 받고 음성이 여유 시각에 닿지 못한 채 끝났으면 그 구간을 불완전으로 버린다.
     */
    private void onOutputQuiet() {
        if (pendingOutputChunks.get() > 0) {
            // 이 만기는 안전 편지함으로 먼저 들어와 일반 편지함에 쌓인 출력 조각을 앞질렀다 — 그 조각이 같은 응답의 나머지일 수 있어 지금 끊김으로 보지
            // 않는다(조각을 처리하면 조용 시계를 다시 건다)
            return;
        }
        Instant now = ctx.services().clock().now();
        OutputGeneration.QuietResult result = ctx.outputGen().onQuiet(now);
        switch (result.verdict()) {
            case SEAL_RECEIVING -> {
                if (inOutputRange()) {
                    sealAndGate(false);
                }
            }
            case NOT_YET -> ctx.timers().arm(Kind.SEGMENT_QUIET, Duration.between(now, result.waitUntil()));
            case ABANDON_NO_AUDIO -> {
                if (inOutputRange() && ctx.segment().isOpen()) {
                    discard(ctx.segment().sealByGeneration(ctx.outputGeneration().get()), GateDecision.DiscardReason.GATE_EMPTY);
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
    private void onInstructionWaitExpired() {
        switch (ctx.outputGen().onInstructionTimeout()) {
            case ACK_MISSING -> {
                ctx.services().events().appError(EventLog.Component.VOICE_RELAY, "OutputAckMissing", EventCode.Level.WARN);
                ctx.timers().arm(Kind.INSTRUCTION_WAIT, ctx.services().settings().instructionWait());
            }
            case NEW_SPEECH_NOT_OBSERVED -> {
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
        ProviderSession session = ctx.providerSession();
        if (session == null || !session.isOpen()) {
            return false;
        }
        String eventId = "mj-" + (++instructionSeq);
        ProviderRequestFactory.InstructionsAppend request = build.apply(eventId);
        Instant now = ctx.services().clock().now();
        ctx.outputGen().bumpForInstruction(now, reason, eventId);
        ctx.segment().reset();
        ctx.transcripts().aiFinal("", false, now);
        ctx.timers().cancel(Kind.SEGMENT_QUIET);
        ctx.timers().arm(Kind.INSTRUCTION_WAIT, ctx.services().settings().instructionWait());
        instructionNotHeard = onFailure;
        guardrail.onServerInstruction(replayable ? new GuardrailCoordinator.ReplayableInstruction(name, build) : null);
        ctx.sendQueue().submit(new ProviderSendQueue.Job(name, session, request, onFailure));
        return true;
    }

    /**
     * 출력 보류가 풀렸으면(참 → 거짓) 세대를 올린다(BR11.13): 보류 중 시작된 자동 응답의 꼬리가 풀린 직후 기기로 가지 않게 한다. 이번 편지함
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
     * 연결이 실제로 끊기면 뒤따르는 끊김 알림이 {@link #providerLost()}로 끝낸다(U3-R8).
     */
    private void providerError() {
        if (START_PHASE.contains(ctx.status())) {
            multiOriginFail(Trigger.PROVIDER_FAILED, FailureCode.PROVIDER_UNAVAILABLE, SessionTransitions.Origin.REALTIME);
            return;
        }
        ctx.services().events().appError(EventLog.Component.VOICE_RELAY, "ProviderDisconnected", EventCode.Level.WARN);
        // 응답 도중 오류면 열린 구간은 끝까지 오지 않은 응답이다: 응답 끝 사건이 없으니 완결로 볼 수 없어 불완전으로 닫아 폐기하고 대기 문구로 잇는다
        if (inOutputRange() && ctx.segment().isOpen()) {
            discard(ctx.segment().sealByGeneration(ctx.outputGeneration().get()), GateDecision.DiscardReason.GATE_EMPTY);
        }
    }

    /** 공급자 연결 끊김({@code session.closed} · WSS 닫힘 · 전송 오류). */
    private void providerLost() {
        if (ctx.providerClosing()) {
            // 실행기가 스스로 닫은 공급자의 끊김 알림이다(정리 · 앱 종료 T3)
            ctx.providerClosedSignal(true);
            return;
        }
        if (START_PHASE.contains(ctx.status())) {
            multiOriginFail(Trigger.PROVIDER_FAILED, FailureCode.PROVIDER_UNAVAILABLE, SessionTransitions.Origin.REALTIME);
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
     * U3-R8 · U3-J13 · AC6.6.3 · B18: ACTIVE 중 공급자가 끊기면 음성 모델 없이 대화를 이을 수 없어 기존 전이 ACTIVE → ABORTED(DISCONNECT)로
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
            syncFromDb();
            return;
        }
        changeStatus(SessionStatus.ABORTED, ctx.services().clock().now());
        closeProvider();
        bumpGeneration();
        DeviceLink current = ctx.link();
        if (current != null && current.isOpen()) {
            sendControl(new CloseTransport(CloseTransport.Reason.DISCONNECT));
            sendControl(new SessionState(View.STANDBY));
        }
        afterTerminal();
    }

    /**
     * U3-R12 · U4b(FR3.1 · BR4.1 ~ BR4.4 · FD-Q3): ACTIVE의 확정 아이 전사로 종료 의도를 판정한다(기기 재생 중 전사는 판정기가 NONE으로 본다).
     * STOP이면 곧바로 CLOSING(CHILD_STOP)이다. CONFIRM이면 확인 질문(STOP_CONFIRM)을 재생하고 공급자 출력을 막은 뒤, 재생 완료부터
     * stop-confirm-wait 안의 첫 확정 전사가 STOP이거나 참여 분류 ACCEPT면 CHILD_STOP으로 확정하고, 그 밖이거나 창이 끝나면 막은 것을 풀고 응답 생성
     * 요청을 한 번 보낸다. U3의 '막고 ACTIVE 유지 → 적극적 계속이면 해제'(CHILD_STOP_LIFTED) 경로는 없앴다.
     *
     * @return 이번 발화를 종료 의도 흐름이 맡았으면 참('생각 중'을 보내지 않는다)
     */
    private boolean judgeStopIntent(String transcript, boolean uncertain, Instant at) {
        if (ctx.status() != SessionStatus.ACTIVE) {
            return false;
        }
        if (ctx.stopConfirm().isActive()) {
            boolean stop = stopJudge.judge(transcript, false).verdict() == StopIntentJudge.Verdict.STOP;
            applyStopDecision(ctx.stopConfirm().onReply(transcript, stop, at), "REPLY", at);
            return true;
        }
        StopIntentJudge.Judgment stopJudgment = stopJudge.judge(transcript, !ctx.devicePlaying().isEmpty());
        if (stopJudgment.verdict() == StopIntentJudge.Verdict.CONFIRM && openStopJudgment(transcript, at)) {
            return true;
        }
        StopConfirmFlow.Decision decision = ctx.stopConfirm().onJudgment(stopJudgment.verdict());
        if (decision == StopConfirmFlow.Decision.CLOSE) {
            putEvent(EVENT_CHILD_STOP_INTENT, "{\"basis\":\"" + stopJudgment.basis().name() + "\"}", at);
        }
        applyStopDecision(decision, null, at);
        return decision != StopConfirmFlow.Decision.NONE;
    }

    /**
     * W2: 불명확한 종료 표현(CONFIRM)은 확인 질문을 바로 하지 않고 J6를 연다. 출력권 연습의 시계를 멈추고 연습 발화를 끊고 출력을 정리하는 것은 지금
     * 확인 질문 앞 단계와 같고, {@code stopConfirm.onJudgment}는 부르지 않는다. 열 수 없으면(공급자 없음 · 이미 열린 턴) 기록만 남기고 지금 흐름이다.
     *
     * @return J6를 열었으면 참(이번 전사를 종료 의도 흐름이 맡았다)
     */
    private boolean openStopJudgment(String transcript, Instant at) {
        JudgmentCoordinator.Readiness ready = judgment.preflight(transcript);
        if (ready != JudgmentCoordinator.Readiness.READY) {
            judgment.recordSkipped(ready, JudgmentPoint.J6, JudgmentTrigger.STOP_CONFIRM_CANDIDATE, currentTranscriptSeq,
                    NextAction.ASK_STOP_CONFIRM);
            return false;
        }
        boolean controlled = practice != null && practice.controlsOutput();
        if (controlled) {
            pausePracticeTimers(at);
            interruptPracticeSpeech(at);
        }
        stopOutputForIntent(at);
        int seq = currentTranscriptSeq;
        judgment.open(new JudgmentCoordinator.Open(JudgmentPoint.J6, JudgmentTrigger.STOP_CONFIRM_CANDIDATE, JudgmentOwner.EXECUTOR,
                seq, transcript, at, null, judgment.recentBefore(seq), this::decideStopJudgment,
                closure -> afterStopJudgment(closure, transcript, seq, at)));
        return true;
    }

    /** J6 결과 → 지시 여부. 휴식(REST)만 ACTIVE에서 지시 하나를 낸다(BR6.4): 출력권 연습은 받아 주기만, 그 밖은 받아 주고 이어 가기. */
    private JudgmentTurn.Decision decideStopJudgment(JudgmentTurn turn, JudgeOutcome outcome) {
        if (outcome.kind() == JudgmentOutcomeKind.CONFIRMED && "REST".equals(outcome.firstOption()) && ctx.status() == SessionStatus.ACTIVE) {
            boolean controlled = practice != null && practice.controlsOutput();
            if (controlled) {
                // J6가 연습 발화를 끊으며 건 출력 막음을 풀어야 받아 주는 말이 들린다(이후 막을지는 REST_REQUESTED를 받은 확장이 정한다)
                practiceHold = false;
            }
            return JudgmentTurn.Decision.instruct(controlled ? InstructionTemplates.NextAction.REST_ACK_ONLY
                    : InstructionTemplates.NextAction.REST_ACK_AND_RETURN);
        }
        return JudgmentTurn.Decision.close();
    }

    /** J6가 닫힌 뒤 결과표(W2 4)대로 한다. */
    private JudgmentCoordinator.Handled afterStopJudgment(JudgmentTurn.Closure closure, String utterance, int seq, Instant at) {
        Instant now = ctx.services().clock().now();
        boolean controlled = practice != null && practice.controlsOutput();
        JudgeOutcome outcome = closure.outcome();
        switch (outcome.kind()) {
            case SUPERSEDED -> {
                // R-14: 새 전사가 다시 CONFIRM이면 연습 시계 멈춤을 유지하고(새 J6가 이어 받는다), 아니면 재개한다
                if (supersedeVerdict != StopIntentJudge.Verdict.CONFIRM) {
                    resumePracticeTimers(now);
                }
                return JudgmentCoordinator.Handled.NOTHING;
            }
            case STOPPED, SESSION_EXITED -> {
                return JudgmentCoordinator.Handled.NOTHING;
            }
            case FAULT_CANCELLED -> {
                resumePracticeTimers(now);
                handToPractice(utterance, seq, at);
                return new JudgmentCoordinator.Handled(NextAction.NONE, true);
            }
            default -> {
                // CONFIRMED · ABSTAINED: 아래 결과표
            }
        }
        String first = outcome.kind() == JudgmentOutcomeKind.CONFIRMED ? outcome.firstOption() : null;
        if ("END_CLEAR".equals(first)) {
            putEvent(EVENT_CHILD_STOP_INTENT, "{\"basis\":\"" + StopIntentJudge.Basis.LUNA_J6.name() + "\"}", now);
            stopOutputForIntent(now);
            ending.beginClosing(Trigger.CHILD_STOP_CONFIRMED, null);
            return new JudgmentCoordinator.Handled(NextAction.CLOSE, false);
        }
        if ("REST".equals(first)) {
            resumePracticeTimers(now);
            if (controlled && ctx.status() == SessionStatus.ACTIVE) {
                ctx.mailbox().putSafety(new SessionMailbox.PracticeEvent(new PracticeSessionExtension.ExtensionEvent(
                        PracticeSessionExtension.ExtensionEvent.Kind.REST_REQUESTED, "judgment", null, null)));
            }
            NextAction next = closure.instruction() == null ? NextAction.NONE
                    : controlled ? NextAction.REST_ACK_ONLY : NextAction.REST_ACK_AND_RETURN;
            return new JudgmentCoordinator.Handled(next, true);
        }
        if ("NOT_END".equals(first)) {
            if (controlled) {
                resumePracticeTimers(now);
                handToPractice(utterance, seq, at);
            } else if (ctx.status() == SessionStatus.ACTIVE) {
                requestResponse(InstructionTemplates.ResponseStart.CONTINUE);
            }
            return new JudgmentCoordinator.Handled(NextAction.RESUME, true);
        }
        // UNCLEAR · 보류(모든 사유): 지금의 확인 질문 흐름
        if (ctx.status() == SessionStatus.ACTIVE) {
            applyStopDecision(ctx.stopConfirm().onJudgment(StopIntentJudge.Verdict.CONFIRM), null, now);
        }
        return new JudgmentCoordinator.Handled(NextAction.ASK_STOP_CONFIRM, false);
    }

    /** R-13: 판정 때문에 연습에 못 넘긴 확정 전사를 출력권 연습에 넘긴다. */
    private void handToPractice(String utterance, int seq, Instant at) {
        if (practice == null || !practice.controlsOutput() || ctx.status() != SessionStatus.ACTIVE) {
            return;
        }
        PracticeSessionExtension.Utterance held = new PracticeSessionExtension.Utterance(seq, at, utterance, false,
                !ctx.devicePlaying().isEmpty());
        practiceSignal(() -> practice.onChildUtterance(held, practiceOutput), true);
    }

    /**
     * 확인 질문 흐름의 결정을 실행한다.
     *
     * @param cause RESOLVED 사건의 근거(REPLY · EXPIRED, 질문 전이면 null)
     */
    private void applyStopDecision(StopConfirmFlow.Decision decision, String cause, Instant at) {
        switch (decision) {
            case CLOSE -> {
                ctx.timers().cancel(Kind.STOP_CONFIRM);
                ctx.stopConfirmPlaybackId(null);
                if (cause != null) {
                    putEvent(EVENT_STOP_CONFIRM_RESOLVED, "{\"result\":\"STOP\",\"cause\":\"" + cause + "\"}", at);
                }
                stopOutputForIntent(at);
                ending.beginClosing(Trigger.CHILD_STOP_CONFIRMED, null);
            }
            case ASK -> {
                if (practice != null && practice.controlsOutput()) {
                    pausePracticeTimers(at);
                    interruptPracticeSpeech(at);
                }
                stopOutputForIntent(at);
                putEvent(EVENT_STOP_CONFIRM_ASKED, null, at);
                playStopConfirm();
            }
            case RESUME -> {
                ctx.timers().cancel(Kind.STOP_CONFIRM);
                ctx.stopConfirmPlaybackId(null);
                putEvent(EVENT_STOP_CONFIRM_RESOLVED,
                        "{\"result\":\"CONTINUE\",\"cause\":\"" + (cause == null ? "REPLY" : cause) + "\"}", at);
                if (practice != null && practice.controlsOutput()) {
                    resumePracticeTimers(at);
                } else {
                    requestResponse(InstructionTemplates.ResponseStart.CONTINUE);
                }
                listeningAgain();
            }
            case NONE -> {
                // 종료 의도 아님
            }
        }
    }

    /** 확인 질문 재생(BR4.3 · BR3.1 · BR3.3): 보낸 순간 말풍선, 끝 표식이 창을 연다. 패키지에 없으면 곧바로 창을 연다. */
    private void playStopConfirm() {
        Instant now = ctx.services().clock().now();
        Optional<ApprovedItem> item = ctx.plan().approvedItem(STOP_CONFIRM);
        if (item.isEmpty()) {
            reportMissing(STOP_CONFIRM, now);
        } else {
            UUID playbackId = UUID.randomUUID();
            if (sendControl(new PlayApprovedContent(playbackId, STOP_CONFIRM, item.get().contentVersionId()))) {
                ctx.stopConfirmPlaybackId(playbackId);
                ctx.playback().started(playbackId, STOP_CONFIRM);
                ctx.timers().arm(Kind.PLAYBACK_EXPIRY, ctx.services().settings().playbackExpiry());
                approvedBubble(item.get(), now);
                return;
            }
        }
        ctx.stopConfirmPlaybackId(null);
        onStopConfirmPlayed(now);
    }

    private void onStopConfirmPlayed(Instant at) {
        ctx.stopConfirm().promptPlayed(at);
        armStopConfirm();
    }

    private void armStopConfirm() {
        Instant end = ctx.stopConfirm().windowEnd();
        if (end == null || technicalPause != null) {
            return;
        }
        Duration left = Duration.between(ctx.services().clock().now(), end);
        ctx.timers().arm(Kind.STOP_CONFIRM, left.isNegative() ? Duration.ZERO : left);
    }

    // ------------------------------------------------------------------ 공백 시계 · 기술 일시정지 · 진행 조회(U4b L4)

    /**
     * 메시지 하나를 처리한 뒤(BR5.2 · BR7.1): 공백 시계의 멈춤 조건을 다시 보고 다음 판정 시각에 시계를 다시 걸며, 진행 조회 값을 바꿔 쓴다. 상태
     * 변화는 모두 메시지 처리 안에서만 일어나므로 메시지 경계에서 보면 충분하다.
     */
    private void afterMessage() {
        if (ctx.terminated()) {
            live.set(LiveState.NONE);
            return;
        }
        Instant now = ctx.services().clock().now();
        if (ctx.status() == SessionStatus.ACTIVE) {
            silence.setPaused(silencePaused(), now);
            armSilence(now);
        } else if (ctx.timers().isArmed(Kind.SILENCE)) {
            ctx.timers().cancel(Kind.SILENCE);
        }
        publishLive(now);
    }

    /** BR5.2 멈춤 조건: 기기 재생 중 · 서버 출력 대기(열린 구간 · 게이트 대기 · 대기 문구) · 기술 일시정지 · 확인 질문 대기. */
    private boolean silencePaused() {
        return !ctx.devicePlaying().isEmpty() || ctx.playback().isPlaying() || ctx.segment().isOpen() || ctx.transcripts().hasOpenAi()
                || technicalPause != null || ctx.stopConfirm().isActive();
    }

    private void armSilence(Instant now) {
        Instant deadline = silence.nextDeadline(now);
        if (deadline == null) {
            if (ctx.timers().isArmed(Kind.SILENCE)) {
                ctx.timers().cancel(Kind.SILENCE);
            }
            return;
        }
        if (ctx.timers().isArmed(Kind.SILENCE) && deadline.equals(ctx.timers().deadline(Kind.SILENCE))) {
            return;
        }
        Duration left = Duration.between(now, deadline);
        ctx.timers().arm(Kind.SILENCE, left.isNegative() ? Duration.ZERO : left);
    }

    /** 공백 시계 만기: 누적 4분이면 경고 1회, 6분이면 CLOSING(SILENCE)(BR5.3 · BR5.4). */
    private void onSilenceTimer() {
        if (ctx.status() != SessionStatus.ACTIVE) {
            return;
        }
        Instant now = ctx.services().clock().now();
        switch (silence.tick(now)) {
            case WARN -> onSilenceWarning(now);
            case END -> ending.beginClosing(Trigger.SILENCE_TIMEOUT, null);
            case NONE -> {
                // 멈췄다 이어진 만큼 늦게 다시 건다(afterMessage)
            }
        }
    }

    /** BR5.3 · U4b-J11: 공백 경고를 켜고 패키지에 있으면 SILENCE_WARNING을 한 번 재생한다(재생은 시계를 리셋하지 않는다). */
    private void onSilenceWarning(Instant now) {
        putEvent(EVENT_SILENCE_WARNED, "{\"silentMs\":" + silence.accumulated(now).toMillis() + "}", now);
        Optional<ApprovedItem> item = ctx.plan().approvedItem(SILENCE_WARNING);
        if (item.isEmpty()) {
            reportMissing(SILENCE_WARNING, now);
            return;
        }
        if (ctx.playback().isPlaying()) {
            return;
        }
        UUID playbackId = UUID.randomUUID();
        if (sendControl(new PlayApprovedContent(playbackId, SILENCE_WARNING, item.get().contentVersionId()))) {
            ctx.playback().started(playbackId, SILENCE_WARNING);
            ctx.timers().arm(Kind.PLAYBACK_EXPIRY, ctx.services().settings().playbackExpiry());
            approvedBubble(item.get(), now);
        }
    }

    /** BR5.1: ACTIVE 진입 · 아이의 빈 아닌 확정 전사가 공백을 0으로 한다. 경고를 껐으면 SILENCE_RESET을 남긴다. */
    private void resetSilence(Instant at) {
        boolean wasWarned = silence.isWarned();
        silence.reset(at);
        if (wasWarned) {
            putEvent(EVENT_SILENCE_RESET, null, at);
        }
    }

    /** 진행 조회 값을 바꿔 쓴다(BR7.1). 공백 경고는 ACTIVE에서만 있다. */
    private void publishLive(Instant now) {
        SilenceClock.Warning warning = ctx.status() == SessionStatus.ACTIVE ? silence.warning(now) : null;
        PracticeSessionExtension.Progress progress = practice == null ? null : practice.progress();
        live.set(technicalPause == null && warning == null && progress == null ? LiveState.NONE
                : new LiveState(technicalPause, warning, progress));
    }

    /** 이 세션의 끊김 시계 기준(묶인 연결의 마지막 기기 메시지 수신 시각, U4b-R7). */
    private Instant lastDeviceMessageAt() {
        DeviceLink current = ctx.link();
        return current == null ? ctx.startAttemptAt() : current.lastMessageAt();
    }

    /** BR6.1: 마지막 기기 메시지 뒤 gap-display에 기술 일시정지를 켜는 시계(WAITING_CHILD · ACTIVE). */
    private void armGapDisplay() {
        if (!Trigger.DISCONNECT.accepts(ctx.status()) || technicalPause != null) {
            return;
        }
        Duration threshold = lifecycle().gapDisplay();
        Duration elapsed = Duration.between(lastDeviceMessageAt(), ctx.services().clock().now());
        ctx.timers().arm(Kind.GAP_DISPLAY, elapsed.isNegative() ? threshold : threshold.minus(elapsed));
    }

    /**
     * 점선 시계 만기(BR6.1 · BR6.2). 일시정지 중이면 회복을 본다 — HEARTBEAT는 편지함을 거치지 않으므로 timer-tick마다 마지막 메시지 시각을 본다.
     */
    private void onGapDisplayCheck() {
        Instant now = ctx.services().clock().now();
        if (technicalPause != null) {
            DeviceLink current = ctx.link();
            if (current != null && current.isOpen() && current.lastMessageAt().isAfter(technicalPause.since())) {
                endPause(now);
            } else {
                ctx.timers().arm(Kind.GAP_DISPLAY, ctx.services().settings().timerTick());
            }
            return;
        }
        if (!Trigger.DISCONNECT.accepts(ctx.status())) {
            return;
        }
        Instant last = lastDeviceMessageAt();
        Duration threshold = lifecycle().gapDisplay();
        Duration elapsed = Duration.between(last, now);
        if (elapsed.compareTo(threshold) < 0) {
            ctx.timers().arm(Kind.GAP_DISPLAY, threshold.minus(elapsed));
            return;
        }
        startPause(last, last);
    }

    /**
     * 기술 일시정지를 켠다(BR6.1 · U4b-J13): technicalPause{DEVICE, since}, 끊김 구간(U3와 같은 행), 참여 창 · 확인 질문 창 · 공백 시계 멈춤.
     *
     * @param since 마지막 기기 메시지 시각
     * @param gapFrom 끊김 구간을 새로 열 때의 시작 시각
     */
    private void startPause(Instant since, Instant gapFrom) {
        if (technicalPause != null || !Trigger.DISCONNECT.accepts(ctx.status())) {
            return;
        }
        Instant now = ctx.services().clock().now();
        technicalPause = new SessionStateChanged.TechnicalPause(SessionStateChanged.TechnicalPause.Kind.DEVICE, since);
        pauseStartedAt = now;
        ctx.timers().cancel(Kind.JOIN_WINDOW);
        ctx.timers().cancel(Kind.STOP_CONFIRM);
        judgment.onFault();
        pausePracticeTimers(now);
        if (practice != null && practice.controlsOutput()) {
            interruptPracticeSpeech(now);
            stopOutputForIntent(now);
        }
        if (ctx.openGapId() == null) {
            ctx.openGapId(ctx.services().gaps().open(ctx.plan().sessionId(), SessionGapRepository.Kind.DISCONNECT, gapFrom));
        }
        putEvent(EVENT_TECHNICAL_PAUSE_STARTED, "{\"kind\":\"DEVICE\"}", now);
        ctx.timers().arm(Kind.GAP_DISPLAY, ctx.services().settings().timerTick());
    }

    /** 기기 메시지가 다시 왔거나 다시 묶였다(BR6.2): 구간을 닫고 일시정지를 끈 뒤 멈췄던 창을 남은 시간부터 잇는다. */
    private void endPause(Instant now) {
        if (technicalPause == null) {
            return;
        }
        Duration paused = pauseStartedAt == null ? Duration.ZERO : Duration.between(pauseStartedAt, now);
        technicalPause = null;
        pauseStartedAt = null;
        if (paused.isNegative()) {
            paused = Duration.ZERO;
        }
        if (ctx.openGapId() != null) {
            ctx.services().gaps().close(ctx.openGapId(), now);
            ctx.openGapId(null);
        }
        putEvent(EVENT_TECHNICAL_PAUSE_ENDED, "{\"pausedMs\":" + paused.toMillis() + "}", now);
        if (ctx.status() == SessionStatus.WAITING_CHILD && join != null) {
            join.shift(paused);
            armJoinWindow();
        }
        if (ctx.stopConfirm().isActive()) {
            ctx.stopConfirm().shift(paused);
            armStopConfirm();
        }
        resumePracticeTimers(now);
        armGapDisplay();
    }

    /** 묶인 연결에서 기기 메시지가 왔다(BR6.2). */
    private void deviceHeard() {
        if (technicalPause != null) {
            endPause(ctx.services().clock().now());
        }
    }

    /** 확인 질문 창 시계 만기(BR4.4): 대답이 없으면 대화를 잇는다. */
    private void onStopConfirmWindow() {
        if (ctx.status() != SessionStatus.ACTIVE || !ctx.stopConfirm().isActive()) {
            return;
        }
        StopConfirmFlow.Decision decision = ctx.stopConfirm().tick(ctx.services().clock().now());
        if (decision == StopConfirmFlow.Decision.NONE) {
            armStopConfirm();
            return;
        }
        applyStopDecision(decision, "EXPIRED", ctx.services().clock().now());
    }

    /**
     * 종료 의도: 남은 프레임을 보내지 않고(기기 프레임 세대 올림) 출력 세대를 올려 만들던 · 재생 중이던 응답의 남은 조각을 버리고 열린 구간을
     * 버린다. 공급자에는 응답 취소 type이 없어 아무것도 보내지 않는다(U11 BR3.4 · 편차 (U11-a)).
     */
    private void stopOutputForIntent(Instant at) {
        bumpGeneration();
        bumpOutput(OutputGeneration.BumpReason.STOP_INTENT);
        guardrail.onTurnBoundary();
        ctx.transcripts().aiFinal("", false, at);
    }

    /** U3-J6: 아이 말이 끝났다(말 끝 · 전사 확정) — 듣는 중이고 재생이 없으면 '생각 중'. */
    private void thinking() {
        if (ctx.status() == SessionStatus.ACTIVE && ctx.view() == View.LISTENING && ctx.devicePlaying().isEmpty() && !outputHeld()) {
            sendControl(new SessionState(View.THINKING));
        }
    }

    /** U3-J6: 재생이 모두 끝났거나 보낼 응답이 없다 — 생각 중 · 재생 뒤면 '듣는 중'으로 돌아간다. */
    private void listeningAgain() {
        if (judgment.isOpen() || practiceThinking || guardrail.holdsThinking()) {
            // U11 BR3.6: 열린 판정 턴(또는 확장이 요청한 생각 중 유지) 동안은 듣는 중으로 되돌리지 않는다.
            // U13 BR3.4: 제브 검사 · 재지시 응답을 기다리는 동안도 같다
            return;
        }
        if (ctx.status() == SessionStatus.ACTIVE && ctx.view() != View.LISTENING && ctx.devicePlaying().isEmpty()
                && !ctx.playback().isPlaying()) {
            sendControl(new SessionState(View.LISTENING));
        }
    }

    /** U3-R10 · U3-J17: 확정 전사가 비었거나 공급자 신뢰도가 기준 미만이면 불확실. 신뢰도가 없으면 불확실로 보지 않는다. */
    private boolean uncertain(ProviderEvent.InputTranscriptDone done) {
        if (done.transcript() == null || done.transcript().isBlank()) {
            return true;
        }
        Double confidence = done.confidence();
        return confidence != null && confidence * MAX_PERCENT < ctx.services().settings().uncertainBelowPercent();
    }

    // ------------------------------------------------------------------ 게이트

    /**
     * 구간 확정(조용 시간 만기 · 아이 입력 재개) → 검사 → 송신 직전 재확인 → 송신 또는 폐기(PD-3 표 2.3-C).
     *
     * @param movedOn 아이 입력 재개 · 다른 응답 도착으로 확정했는가(대화가 이미 넘어가 완결 요청 교정을 보내지 않는다, U3-R7)
     */
    private void sealAndGate(boolean movedOn) {
        ctx.timers().cancel(Kind.SEGMENT_QUIET);
        Instant confirmedAt = ctx.services().clock().now();
        long confirmedNanos = ctx.services().clock().monotonicNanos();
        // 아이 입력 재개로 닫을 때는 조각이 이미 끊김 시간만큼 끊겨 있었어야 '완전'이다(응답이 이어지던 중이면 불완전, U11 BR11.6)
        Instant lastOutAt = ctx.segment().lastOutAt();
        boolean quietEnough = lastOutAt != null && !confirmedAt.isBefore(lastOutAt.plus(ctx.services().settings().segmentQuiet()));
        SegmentBuffer.Sealed sealed = movedOn ? ctx.segment().sealEarly(ctx.outputGeneration().get(), quietEnough)
                : ctx.segment().sealQuiet(ctx.outputGeneration().get());
        if (sealed == null) {
            return;
        }
        if (!guardrail.isChecking()) {
            // U13 BR5.6: 앞 구간의 제브 검사 중에 봉인된 구간은 앞 턴의 구간 확정 시각 · 지연 기록을 덮지 않는다
            ctx.services().latency().segmentConfirmed(ctx.plan().sessionId(), confirmedAt, confirmedNanos, sealed.firstOutNanos(),
                    sealed.lastOutNanos());
        }
        // U11 W8-5: 공급자 출력 끝(tTranscriptFinal)은 구간의 마지막 조각 시각이다(봉인 시각이 아니라 800ms 대기가 지표에 섞이지 않는다)
        ctx.transcripts().aiDone(sealed.lastOutAt());
        try {
            GateDecision inspected = ctx.services().gate().inspect(sealed);
            if (!inspected.passed()) {
                // 금지 패턴은 대화가 넘어갔어도 교정한다(같은 표현을 되풀이하지 않게)
                discard(sealed, inspected.reason(),
                        !movedOn || inspected.reason() == GateDecision.DiscardReason.GATE_PATTERN);
                return;
            }
            boolean practiceControlled = practice != null && practice.controlsOutput();
            var generatedSlot = practiceControlled && pendingPracticeSpeech != null && pendingPracticeSpeech.generated()
                    ? pendingPracticeSpeech : null;
            if (generatedSlot != null) {
                // U12 BR5.6(기능 설계 3.5): 열린 생성형 슬롯의 새 발화는 제브(연습 모드)가 ALLOW한 것만 재생된다. 제브가 꺼져 있거나 시작이
                // 실패하면 폐기한다(fail-closed). 원문 일치로 제브를 건너뛸 수 없다
                if (!guardrail.enabled() || ctx.status() != SessionStatus.ACTIVE) {
                    discard(sealed, GateDecision.DiscardReason.GATE_PATTERN, false);
                    return;
                }
                try {
                    guardrail.begin(sealed, practiceGuardrailMode(generatedSlot), generatedSlot.correlationId());
                } catch (RuntimeException e) {
                    guardrail.onBeginFailed(sealed, e);
                }
                return;
            }
            if (practiceControlled
                    && (pendingPracticeSpeech == null || !pendingPracticeSpeech.matches(sealed.transcript()))) {
                discard(sealed, GateDecision.DiscardReason.GATE_PATTERN, false);
                return;
            }
            if (practiceControlled && guardrail.enabled()) {
                // U13 분류 ①(BR7.1): 연습이 출력을 쥐고 승인 발화와 원문이 같으면 제브를 부르지 않고 기존 게이트 검사만 거친 재생이다
                guardrail.onVerbatimSkipped(GuardrailCoordinator.SOURCE_PRACTICE_SPEECH, sealed.transcript());
            } else if (!practiceControlled && guardrail.enabled() && ctx.status() == SessionStatus.ACTIVE) {
                // U13 분류 ②(BR7.1): 레지스트리가 승인 원문 · 등록 대본과 원문 일치(MATCHED)로 확인한 출력도 제브를 건너뛴다. UNKNOWN ·
                // NOT_MATCHED(일치를 확인할 수 없음)는 새 발화로 검사한다(BR7.2)
                Optional<String> verbatim = guardrail.verbatimSource(sealed.transcript());
                if (verbatim.isPresent()) {
                    guardrail.onVerbatimSkipped(verbatim.get(), sealed.transcript());
                } else {
                    // U13 호출 지점 ①(BR3.1): GPT-Live가 그 자리에서 만든 새 발화는 제브가 ALLOW한 것만 recheck → send로 간다. 결과는
                    // GuardrailArrived로 돌아와 이어진다 — 여기서는 기다리지 않는다
                    try {
                        guardrail.begin(sealed, GuardrailRequest.Mode.FREE_TALK, null);
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
                discard(sealed, GateDecision.DiscardReason.GATE_EMPTY, !movedOn);
            }
        }
    }

    /**
     * 송신 직전 재확인(NOT_ACTIVE · RECORDER_DEGRADED · 출력 레인 예약)을 하고 통과하면 보낸다. 제브 단계가 있으면 유효한 ALLOW가 온 뒤에 불린다
     * (U13 BR3.2 — 출력 레인 예약도 그때 한다. 예약에 실패하면 막힘 흐름이 아니라 지금과 같은 폐기다).
     *
     * @param held 제브 검사를 기다리며 보관한 AI 행(없으면 null — 열린 AI 행을 닫는다)
     * @param ownsLatency 지연 기록의 턴이 이 구간의 것인가(대기열에서 꺼낸 구간은 앞 턴의 기록을 덮지 않는다, BR5.6)
     * @return 기기로 보냈으면 참, recheck에서 폐기했으면 거짓
     */
    private boolean recheckAndSend(SegmentBuffer.Sealed sealed, TranscriptWriter.Held held, boolean ownsLatency) {
        DeviceLink target = ctx.link();
        boolean activeAndCurrent = ctx.status() == SessionStatus.ACTIVE && sealed.generation() == ctx.outputGeneration().get()
                && target != null && target.isOpen();
        OutputGate.Rechecked<DeviceLink.AudioReservation> rechecked = ctx.services().gate().recheck(activeAndCurrent,
                ctx.services().recorder().isDegraded(ctx.plan().sessionId()),
                () -> target == null ? Optional.empty() : target.reserveAudio(sealed.totalBytes()));
        if (!rechecked.decision().passed()) {
            discard(sealed, rechecked.decision().reason(), true, held, ownsLatency);
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
        UUID playbackId = UUID.randomUUID();
        if (ownsLatency) {
            ctx.services().latency().gatePassed(ctx.plan().sessionId(), playbackId, passAt);
        }
        // 보호자 화면 승인(aiFinal true)은 유효한 ALLOW 뒤 기기로 보내는 시점이다(U13 BR8.4). 보관 행이면 그 손잡이로 닫아 새로 열린 행을 건드리지 않는다
        if (held != null) {
            ctx.transcripts().aiFinal(held, sealed.transcript(), true, passAt);
        } else {
            ctx.transcripts().aiFinal(sealed.transcript(), true, passAt);
        }
        var speech = pendingPracticeSpeech;
        if (speech != null) {
            pendingPracticeSpeech = null; practiceHold = true;
            practiceAudio.put(playbackId, speech);
            schedulePracticeTimer("u5b-audio-" + playbackId, ctx.services().settings().playbackExpiry());
        }
        long generation = sealed.generation();
        // U3-R9 · 리뷰 반영 6: 기기로 실제 보낸 AI 음성 프레임만 기록한다(송신기가 소켓에 쓴 직후 넘긴다). 음성 레인에 못 들어간 묶음 ·
        // 세대가 올라 잘린 나머지 프레임은 기록하지 않는다
        AudioRecordings.Session aiRecording = ctx.recording();
        OutputBundle bundle = new OutputBundle(ctx.plan().sessionId(), playbackId, OutputBegin.Source.GPT_LIVE,
                OutputBundle.frame(sealed.chunks()), sealed.totalMs(), reservation,
                () -> ctx.outputGeneration().get() == generation,
                firstFrameAt -> ctx.services().latency().firstFrameSent(ctx.plan().sessionId(), playbackId, firstFrameAt),
                aiRecording == null ? frame -> { } : aiRecording::ai);
        if (!target.sendOutput(bundle)) {
            ctx.services().metrics().gateDiscard(GateDecision.DiscardReason.OUTBOUND_FULL.name());
            if (speech != null) {
                practiceAudio.remove(playbackId); cancelPracticeTimer("u5b-audio-" + playbackId);
                practicePlayback(speech, kr.mujoong.realtime.practice.PracticePorts.Playback.FAILED);
            }
        }
    }

    /** 폐기: AI 행 FINAL · false(BR6.15), 그다음 대체 문구 판정(겹침 규칙 R3-19). 교정은 사유에 따라(U3-R7). */
    private void discard(SegmentBuffer.Sealed sealed, GateDecision.DiscardReason reason) {
        discard(sealed, reason, true);
    }

    private void discard(SegmentBuffer.Sealed sealed, GateDecision.DiscardReason reason, boolean correct) {
        discard(sealed, reason, correct, null, true);
    }

    /**
     * @param held 제브 검사를 기다리며 보관한 AI 행(없으면 null — 열린 AI 행을 닫는다)
     * @param ownsLatency 지연 기록의 턴이 이 구간의 것인가(U13 BR5.6)
     */
    private void discard(SegmentBuffer.Sealed sealed, GateDecision.DiscardReason reason, boolean correct,
            TranscriptWriter.Held held, boolean ownsLatency) {
        if (!isGuardrailReason(reason)) {
            // U13 훅(BR6.6): 재지시 응답이 제브에 닿기 전에 어떤 길로든 폐기되면 협력 객체는 RETRYING에서 IDLE로 돌아온다. 마무리는 아래가 한다
            guardrail.onDiscardedBeforeCheck();
        }
        recordDiscard(sealed, held, reason, ownsLatency);
        if (pendingPracticeSpeech != null) {
            var speech = pendingPracticeSpeech; pendingPracticeSpeech = null; practiceHold = true;
            bumpOutput(OutputGeneration.BumpReason.PRACTICE_DISCARD);
            practicePlayback(speech, kr.mujoong.realtime.practice.PracticePorts.Playback.FAILED);
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
        ctx.services().metrics().gateDiscard(reason.name());
        if (ownsLatency) {
            ctx.services().latency().gateDiscarded(ctx.plan().sessionId());
        }
        closeAiRow(sealed, held);
    }

    private void closeAiRow(SegmentBuffer.Sealed sealed, TranscriptWriter.Held held) {
        String text = sealed == null ? "" : sealed.transcript();
        if (held != null) {
            ctx.transcripts().aiFinal(held, text, false, ctx.services().clock().now());
        } else {
            ctx.transcripts().aiFinal(text, false, ctx.services().clock().now());
        }
    }

    /** 폐기의 마무리: 대기 문구 재생 또는 '듣는 중' 복귀. */
    private void finishDiscard() {
        if (ctx.status() != SessionStatus.ACTIVE || ctx.playback().isPlaying() || ctx.plan().waitingFiller() == null) {
            listeningAgain();
            return;
        }
        UUID playbackId = UUID.randomUUID();
        if (sendControl(new PlayApprovedContent(playbackId, PlayApprovedContent.WAITING_FILLER,
                ctx.plan().waitingFiller().contentVersionId()))) {
            ctx.playback().started(playbackId, PlayApprovedContent.WAITING_FILLER);
            ctx.timers().arm(Kind.PLAYBACK_EXPIRY, ctx.services().settings().playbackExpiry());
        }
    }

    /**
     * U3-R7 · U3-J12: ACTIVE에서 GATE_PATTERN · GATE_EMPTY · SEGMENT_OVERSIZE로 폐기했으면 응답당 한 번 고정 교정 문장을 보낸다(걸린 범주의
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
                id -> ctx.services().requests().instructionsAppend(id, chosen), null)) {
            correctionsInARow++;
        }
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
     * R3-6 · J126: 최상위 예외 → FAILED(SERVER_RESTART) 시도 → 정리. 전이가 실패하면 고아 세션 점검이 닫는다. 전이를 이기면 BR6.8대로
     * START_SESSION이 나갔고 연결이 살아 있을 때 CLOSE_TRANSPORT(FAILED) + STANDBY를 보낸다(F4 · J290). '재시작 제외'는 보낼 연결이 없는
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
                    afterFailed();
                }
            }
        } catch (RuntimeException e) {
            ctx.services().events().appError(EventLog.Component.SESSION_LIFECYCLE, e);
        } finally {
            teardown();
        }
    }

    // ------------------------------------------------------------------ 전이 보조

    private Optional<ActivitySessionRow> transition(SessionStatus from, Trigger trigger, FailureCode code) {
        Optional<ActivitySessionRow> row = ctx.services().transitions().transition(ctx.plan().sessionId(), from, trigger.target(),
                TransitionTable.changeFor(trigger, code, ctx.services().clock().now()), SessionTransitions.Origin.REALTIME,
                ctx.plan().traceId());
        row.ifPresent(value -> changeStatus(value.status(), ctx.services().clock().now()));
        return row;
    }

    /** 출발 상태가 둘 이상인 → FAILED(FOR UPDATE로 읽은 상태). 이기면 CLOSE_TRANSPORT(FAILED) 규칙과 정리를 한다. */
    private void multiOriginFail(Trigger trigger, FailureCode code, SessionTransitions.Origin origin) {
        ActivitySessionRepository.MultiOriginTransition result = ctx.services().transitions().transitionFromAny(
                ctx.plan().sessionId(), trigger.origins(), SessionStatus.FAILED,
                read -> TransitionTable.changeFor(trigger, code, ctx.services().clock().now()), origin, ctx.plan().traceId());
        if (!result.transitioned()) {
            syncFromDb();
            return;
        }
        changeStatus(SessionStatus.FAILED, ctx.services().clock().now());
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
        changeStatus(SessionStatus.FAILED, ctx.services().clock().now());
        bumpGeneration();
        if (ctx.startSent() && ctx.link() != null && ctx.link().isOpen()) {
            sendControl(new CloseTransport(reason));
            sendControl(new SessionState(View.STANDBY));
        }
        afterTerminal();
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
        Optional<ActivitySessionRow> row = ctx.services().transitions().find(ctx.plan().sessionId());
        if (row.isEmpty()) {
            teardown();
            return;
        }
        SessionStatus before = ctx.status();
        changeStatus(row.get().status(), ctx.services().clock().now());
        if (ctx.status().isTerminal()) {
            afterTerminal();
            return;
        }
        if (ctx.status() != before) {
            adopt(row.get(), endRequestId);
        }
    }

    /**
     * F5 · J291: 살아 있는 실행기의 세션 행은 이 실행기만 전이한다(고아 점검 · 기동 정리는 실행기 없는 세션만 다룬다). 그래서 DB가 메모리보다
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

    private void annotateQuietly(SessionStatus expected, SessionChange change) {
        try {
            ctx.services().transitions().annotate(ctx.plan().sessionId(), expected, change);
        } catch (RuntimeException e) {
            ctx.services().events().appError(EventLog.Component.SESSION_LIFECYCLE, e);
        }
    }

    private void markUnresponsive() {
        DeviceLink current = ctx.link();
        if (current != null) {
            ctx.services().deviceStatus().markUnresponsive(ctx.plan().deviceId(), current.connectionId());
        }
    }

    private boolean fromBoundConnection(UUID connectionId) {
        DeviceLink current = ctx.link();
        return current != null && current.connectionId().equals(connectionId);
    }

    private boolean sendControl(WsPayload payload) {
        if (payload instanceof SessionState state) {
            // U3-J23: 연결이 없어도 표시 상태는 기억한다(다음 HELLO_OK.session.view)
            ctx.view(state.view());
        }
        DeviceLink current = ctx.link();
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
        return current.sendControl(payload, opId, ctx.plan().sessionId());
    }

    /** ACK를 기다리는 명령으로 둔다(U3-J7, 상한을 넘으면 가장 오래된 것부터 잊는다). */
    private void remember(UUID opId, WsPayload payload) {
        if (ctx.pendingCommands().size() >= MAX_PENDING_COMMANDS) {
            UUID eldest = ctx.pendingCommands().keySet().iterator().next();
            ctx.pendingCommands().remove(eldest);
        }
        ctx.pendingCommands().put(opId, payload);
    }

    /** U3 세션 이벤트 한 줄(payload는 식별자 · 숫자 · 불리언뿐, 전사 · 원음 없음). */
    private void putEvent(String type, String payloadJson, Instant at) {
        ctx.services().recorder().putEvent(new SessionEvent(UUID.randomUUID(), ctx.plan().sessionId(), type, null, payloadJson, at));
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
        ctx.outputGeneration().incrementAndGet();
        DeviceLink current = ctx.link();
        if (current != null) {
            current.clearAudio();
        }
    }

    /**
     * 세션 상태를 바꾸는 유일한 자리(U11 BR5.4, 첫 판 검토 R-15). 지금은 대입뿐이고 동작을 바꾸지 않는다 — 판정 턴이 {@code openedInStatus}를 벗어나는
     * 모든 전이를 이 자리에서 잡는다(PR2).
     */
    private void changeStatus(SessionStatus next, Instant now) {
        ctx.status(next);
        judgment.onStatus(next);
    }

    private void closeProvider() {
        ctx.providerClosing(true);
        ProviderConnector.Attempt attempt = ctx.providerAttempt();
        if (attempt != null) {
            attempt.cancel();
        }
        ProviderSession session = ctx.providerSession();
        if (session != null && session.isOpen()) {
            session.close(ctx.services().requests().sessionClose());
        }
    }

    /**
     * 종료 정리(PD-1 생명주기 3): 입력 수락 끝 · 공급자 정리 · 구간 버림 · 열린 행 닫기 · 기록기 마지막 플러시 · 원음 기록 닫기 · 레지스트리 제거.
     * 리뷰 반영 7: 단계마다 예외를 따로 받아(앱 오류 한 줄) 앞 단계가 실패해도 뒤 단계 — 특히 원음 파일 핸들과 메타의 ended_at — 를 마친다.
     * 리뷰 반영 4: 처리하지 못하고 남은 연결 교체(LinkReplaced)는 그 새 연결을 대기로 돌려보낸다.
     */
    private void teardown() {
        if (ctx.terminated()) {
            return;
        }
        ctx.terminated(true);
        ctx.outputGeneration().incrementAndGet();
        Instant now = ctx.services().clock().now();
        try {
            quietly(ctx.timers()::cancelAll);
            quietly(ctx.sendQueue()::shutdown);
            quietly(this::cancelPracticeTimers);
            quietly(judgment::shutdown);
            // U13 호출 지점 ④: 진행 중인 제브 요청을 취소한다(보관 행은 아래 closeAll이 닫는다, BR4.5)
            quietly(guardrail::onExit);
            quietly(ctx.relay()::stop);
            quietly(this::closeProvider);
            quietly(() -> {
                ProviderSession session = ctx.providerSession();
                if (session != null) {
                    session.close();
                }
            });
            quietly(ctx.segment()::reset);
            quietly(ctx.playback()::clear);
            quietly(() -> ctx.transcripts().closeAll(now));
            quietly(() -> ctx.services().latency().sessionEnded(ctx.plan().sessionId()));
            quietly(() -> ctx.services().recorder().finish(ctx.plan().sessionId()));
            quietly(() -> unbind(ctx.link()));
            ctx.pendingCommands().clear();
            quietly(() -> {
                if (ctx.recording() != null) {
                    ctx.recording().close(now);
                }
            });
        } finally {
            for (Message left : ctx.mailbox().closeAndRelease()) {
                if (left instanceof LinkReplaced replaced) {
                    quietly(() -> EndingFlow.standDown(replaced.link(), ctx.plan().sessionId(), ctx.status(), replaced.resumed()));
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
            target.unbindSessionIf(ctx.plan().sessionId());
        }
    }

    /** 정리 단계 하나: 예외는 앱 오류 한 줄로 남기고 다음 단계로 간다. */
    private void quietly(Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            ctx.services().events().appError(EventLog.Component.SESSION_LIFECYCLE, e);
        }
    }

    @Override
    public String toString() {
        return "SessionExecutor[sessionId=" + ctx.plan().sessionId() + ", status=" + ctx.status() + "]";
    }
}
