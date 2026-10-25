package kr.mujoong.realtime.practiceflow;

import static org.mockito.Mockito.mock;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.log.OwnerHasher;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.EndRequestReason;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.ws.CloseCode;
import kr.mujoong.contract.ws.message.StartSession;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.content.LiveContentRegistry;
import kr.mujoong.realtime.gate.OutputGate;
import kr.mujoong.realtime.guardrail.GuardrailClient;
import kr.mujoong.realtime.guardrail.GuardrailMode;
import kr.mujoong.realtime.guardrail.GuardrailRequest;
import kr.mujoong.realtime.guardrail.GuardrailVerdict;
import kr.mujoong.realtime.internalhttp.InternalHttpClient;
import kr.mujoong.realtime.judgment.AbstainReason;
import kr.mujoong.realtime.judgment.JudgeClient;
import kr.mujoong.realtime.judgment.JudgeOutcome;
import kr.mujoong.realtime.judgment.JudgeRequest;
import kr.mujoong.realtime.judgment.JudgmentPoint;
import kr.mujoong.realtime.log.AsyncRecorder;
import kr.mujoong.realtime.log.LatencyRecorder;
import kr.mujoong.realtime.log.TranscriptEntry;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.practiceflow.FlowState.Sub;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.FactKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.HelpRecord;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.LiveTiming;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneRunRecord;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.VariantSkipReason;
import kr.mujoong.realtime.practiceflow.PracticeVoiceChild.Act;
import kr.mujoong.realtime.practiceflow.PracticeVoiceChild.Cue;
import kr.mujoong.realtime.practiceflow.PracticeVoiceChild.GoalLines;
import kr.mujoong.realtime.practiceflow.PracticeVoiceChild.Plan;
import kr.mujoong.realtime.practiceflow.PracticeVoiceFacts.Flow;
import kr.mujoong.realtime.runtime.TaskLauncher;
import kr.mujoong.realtime.runtime.TimerClocks;
import kr.mujoong.realtime.session.ChildContext;
import kr.mujoong.realtime.session.JudgmentSupport;
import kr.mujoong.realtime.session.NoOpPracticeSessionExtension;
import kr.mujoong.realtime.session.NoticeAssetCatalog;
import kr.mujoong.realtime.session.PracticeSessionExtension;
import kr.mujoong.realtime.session.SessionEvent;
import kr.mujoong.realtime.session.SessionExecutor;
import kr.mujoong.realtime.session.SessionMailbox;
import kr.mujoong.realtime.session.SessionSettlement;
import kr.mujoong.realtime.testing.FakeAudioRecordings;
import kr.mujoong.realtime.testing.FakeSessionGaps;
import kr.mujoong.realtime.testing.InMemoryTransitions;
import kr.mujoong.realtime.voice.PracticeSpeech;
import kr.mujoong.realtime.voice.ProviderConnector;
import kr.mujoong.realtime.voice.ProviderPort;
import kr.mujoong.realtime.voice.ProviderRequestFactory;

/**
 * 실음성 연습 대화 하나: 운영 세션 실행부({@link SessionExecutor} · {@link PracticeFlowRouter} → {@link PracticeConversation} · 게이트 ·
 * 승인 원문 대조 · 입력 전사 묶기 · 판정 · 제브)를 조립해 실제 시간으로 돌리고, 가상 기기와 합성 아이가 대화한다. 관찰은 시험 쪽 감싸기로만 한다:
 * 확장점과 그 출력 능력, 공급자 포트, 판정 포트, 제브 포트, 기록기 입구, 사건 로그 출구, 흐름 기록 자리, 가상 기기. 운영 코드는 바꾸지 않는다.
 */
final class PracticeVoiceSession {

    private static final JsonCodec JSON = JsonCodec.shared();
    private static final Pattern FIXED_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    private final PracticeVoiceHarness harness;
    private final PracticeVoiceHarness.DialoguePlan plan;
    private final GoalLines lines;
    private final PracticeVoiceFacts facts = new PracticeVoiceFacts();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID guardianId = UUID.randomUUID();
    private final UUID deviceId = UUID.randomUUID();
    private final Map<String, Integer> answersPerPhase = new HashMap<>();
    private final Set<SceneKind> silentUsed = EnumSet.noneOf(SceneKind.class);
    private final List<Map<String, Object>> prepared = new ArrayList<>();
    private String status;
    private String endRequestedFor;
    private boolean endRequested;
    private long endRequestedNanos;
    private int lastActedArm = -1;
    private int joinAnswers;
    private int stopAnswers;
    private int spokenTurns;
    private long lastChildEndNanos;
    private String lastAnswer;

    PracticeVoiceSession(PracticeVoiceHarness harness, PracticeVoiceHarness.DialoguePlan plan, GoalLines lines) {
        this.harness = harness;
        this.plan = plan;
        this.lines = lines;
    }

    /** 대화 하나를 돌리고 보고서 한 줄을 만든다. 상한 · 오류로 끝나도 그때까지의 사실을 남긴다. */
    PracticeVoiceReport.DialogueRun run() {
        long startedNanos = System.nanoTime();
        // 4회차 H4: 계획이 쓸 아이 말을 대화 전에 모두 소리로 만든다(대화 중 TTS를 기다리지 않는다)
        for (String text : PracticeVoiceChild.linesToPrepare(plan.plan(), lines, plan.goal().script().variantScene().isPresent())) {
            PracticeVoiceHarness.Prepared ready = harness.prepare(text);
            prepared.add(ready.report(text));
            if (ready.capRefused()) {
                return notStarted("TTS_CALL_CAP", startedNanos);
            }
            if (ready.clip() == null && PracticeVoiceChild.JOIN.equals(text)) {
                return notStarted("TTS_FAILED_BEFORE_START", startedNanos);
            }
        }
        Assembly assembly = null;
        Map<String, Object> session = new LinkedHashMap<>();
        try {
            assembly = assemble();
            assembly.device.attach(assembly.executor);
            assembly.startTicker();
            assembly.executor.start();
            loop(assembly.executor, assembly.device, startedNanos);
            if (!assembly.executor.isTerminated()) {
                status = status == null ? "HARNESS_FORCED_CLOSE" : status;
            }
        } catch (RuntimeException failure) {
            status = "ERROR";
            facts.notice("하니스 오류(" + failure.getClass().getSimpleName() + ") — 대화를 정리했다");
        } finally {
            if (assembly != null) {
                assembly.close();
                session.putAll(assembly.sessionReport());
            }
        }
        double connected = facts.connectedSeconds();
        double usage = facts.usageSeconds();
        harness.budget.finishSession(Math.max(connected, usage), facts.providerStarted());
        if (status == null) {
            Object endDetail = session.get("endDetail");
            status = "PRACTICE_COMPLETED".equals(endDetail) ? "COMPLETED" : "SESSION_" + session.get("finalStatus");
        }
        return PracticeVoiceReport.dialogue(harness, plan, lines, facts.snapshot(), status, endRequestedFor, session, prepared,
                (System.nanoTime() - startedNanos) / 1e9);
    }

    private PracticeVoiceReport.DialogueRun notStarted(String reason, long startedNanos) {
        status = reason;
        return PracticeVoiceReport.dialogue(harness, plan, lines, facts.snapshot(), status, null, Map.of("notStarted", reason), prepared,
                (System.nanoTime() - startedNanos) / 1e9);
    }

    // ------------------------------------------------------------------ 조립

    /** 대화 하나의 장치 묶음(닫을 것들). */
    private final class Assembly {
        final RealtimeMetrics metrics = RealtimeMetrics.inMemory();
        final EventLogger events;
        final InMemoryTransitions transitions;
        final ProviderPort port;
        final ObservedRecorder recorder;
        final TimerClocks timers;
        final PracticeVoiceDevice device;
        final SessionExecutor executor;
        private volatile boolean tickerStop;
        private Thread ticker;

        Assembly() {
            RealtimeProperties properties = harness.properties;
            MujoongClock clock = harness.clock;
            // 운영 사건 로그(LENIENT · 병합 창 · 보안 상한은 운영 설정 값). 줄은 하니스 수집기로 받아 사실로 옮긴다
            events = new EventLogger(clock, OwnerHasher.ephemeral(), new FactsSink(facts), metrics, EventLog.Strictness.LENIENT,
                    properties.observability().logMergeWindow(), properties.security().eventLogRateCap());
            transitions = new InMemoryTransitions(clock);
            transitions.seed(sessionId, guardianId, deviceId);
            port = harness.backends.openProvider(properties);
            ObservedProvider provider = new ObservedProvider(port, facts);
            TaskLauncher launcher = TaskLauncher.virtualThreads();
            ProviderRequestFactory requests = new ProviderRequestFactory();
            ProviderConnector connector = new ProviderConnector(provider, requests, properties.provider().connectTimeout(), launcher,
                    harness.backends.providerProfile());
            LatencyRecorder latency = new LatencyRecorder(events, metrics, clock, properties);
            recorder = new ObservedRecorder(new MemoryStore(), launcher, latency, events, metrics, clock, properties.limits().recorderQueue(),
                    properties.limits().recorderFlushConcurrency(), facts);
            timers = TimerClocks.system(clock);
            device = new PracticeVoiceDevice(deviceId, clock, facts, harness.catalog.audioMs(), harness.catalog.contentTexts());
            ObservedFlowRecorder flowRecorder = new ObservedFlowRecorder(new PracticeFlowTestKit.MemoryRecorder(), facts);
            AtomicReference<PracticeConversation> conversation = new AtomicReference<>();
            PracticeFlowSettings flowSettings = PracticeFlowSettings.from(properties);
            // 시작 패키지는 목표 하나만 담아 다음 목표 대본 요청(core 내부 호출)은 일어나지 않는다. 요청자는 운영 모양 그대로 둔다
            PracticeScriptRequester requester = new PracticeScriptRequester(mock(InternalHttpClient.class), Runnable::run,
                    flowSettings.slotGuard());
            Map<String, String> labels = PracticeVoiceReport.lineLabels(plan.goal().script(), harness.catalog.speechTexts());
            PracticeSessionExtension.Factory factory = id -> new ObservedExtension(new PracticeFlowRouter(
                    NoOpPracticeSessionExtension.FACTORY.create(id), () -> {
                        PracticeConversation created = new PracticeConversation(id, guardianId, clock, flowSettings, requester,
                                flowRecorder, failure -> {
                                    facts.flowFailure(code(failure));
                                    events.appError(EventLog.Component.SESSION_LIFECYCLE, failure);
                                });
                        conversation.set(created);
                        return created;
                    }), conversation::get, facts, labels);
            ObservedJudge judge = new ObservedJudge(harness.backends.judge(harness, events), facts, harness.budget, harness.prompt.version());
            JudgmentSupport judgment = new JudgmentSupport(judge, null, properties.timing().judgment().liveBudget(), harness.prompt.version(),
                    harness.registry.resources().judgment());
            SessionSettlement settlement = (id, practice, cutoffAt) -> {
                facts.practiceRecord(PracticeVoiceReport.plainOrNull(practice));
                return true;
            };
            SessionExecutor.Services services = new SessionExecutor.Services(transitions,
                    id -> id.equals(deviceId) ? Optional.of(device) : Optional.empty(),
                    (device1, connection) -> facts.notice("실행부가 기기를 무응답으로 표시했다"),
                    connector, requests, new OutputGate(harness.dictionary), recorder, latency, timers, launcher, events, metrics, clock,
                    SessionExecutor.Settings.from(properties), new FakeSessionGaps(), new FakeAudioRecordings(), settlement, factory,
                    judgment, harness.guardrailFor(new ObservedGuardrail(harness.guardrailClient(), facts)), new NoticeAssetCatalog());
            // 운영 시작 처리처럼 세션 콘텐츠 계획(승인 문구 판 · 승인 발화)을 고정한다(제브 승인 원문 일치가 읽는다)
            harness.registry.pin(sessionId, harness.catalog.contentVersions(), harness.catalog.registrySpeech());
            executor = new SessionExecutor(sessionPlan(), services, new SessionMailbox(services.settings().mailboxCapacity()),
                    () -> facts.terminated = true);
        }

        /** 운영 기록기 틱(flush-interval)처럼 확정 전 칸을 주기적으로 비운다. */
        void startTicker() {
            Duration interval = harness.properties.timing().recorder().flushInterval();
            ticker = Thread.ofVirtual().name("voice-recorder-tick").start(() -> {
                while (!tickerStop) {
                    try {
                        recorder.tick();
                        Thread.sleep(interval);
                    } catch (InterruptedException interrupted) {
                        return;
                    } catch (RuntimeException failure) {
                        facts.notice("기록기 틱 오류(" + failure.getClass().getSimpleName() + ")");
                    }
                }
            });
        }

        void close() {
            Thread thread = executor.thread();
            if (thread != null) {
                try {
                    thread.join(Duration.ofSeconds(5));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            tickerStop = true;
            if (ticker != null) {
                ticker.interrupt();
            }
            try {
                recorder.drain(Duration.ofSeconds(2));
            } catch (RuntimeException failure) {
                facts.notice("기록기 정리 오류(" + failure.getClass().getSimpleName() + ")");
            }
            device.close(CloseCode.GOING_AWAY);
            closeQuietly(timers);
            harness.backends.closeProvider(port);
            harness.registry.release(sessionId);
            facts.providerClosed();
        }

        Map<String, Object> sessionReport() {
            Map<String, Object> report = new LinkedHashMap<>();
            transitions.find(sessionId).ifPresent(row -> {
                report.put("finalStatus", row.status().name());
                report.put("endReason", row.endReason() == null ? null : row.endReason().name());
                report.put("endDetail", row.endDetail());
            });
            report.put("transitions", transitions.applied().stream().map(applied -> applied.from() + "→" + applied.to()).toList());
            report.put("executorTerminated", executor.isTerminated());
            return report;
        }
    }

    private Assembly assemble() {
        return new Assembly();
    }

    /** 운영 시작 명령과 같은 모양의 세션 고정 값(연습 조각 · 음성 있는 승인 문구 · 음성 없는 승인 발화 · 종료 표현). */
    private SessionExecutor.Plan sessionPlan() {
        List<StartSession.ApprovedAudio> audio = new ArrayList<>();
        Map<String, SessionExecutor.ApprovedItem> approved = new LinkedHashMap<>();
        for (PracticeVoiceHarness.ApprovedAudio item : harness.catalog.content().values()) {
            audio.add(new StartSession.ApprovedAudio(item.code(), item.versionId(), "/media/approved/" + item.file()));
            approved.put(item.code(), new SessionExecutor.ApprovedItem(item.code(), item.versionId(), item.text()));
        }
        return new SessionExecutor.Plan(sessionId, deviceId, UUID.randomUUID().toString().replace("-", ""), childContext(), audio,
                approved.get("CLOSING"), approved.get("WAITING_FILLER"), false, PracticeVoiceChild.STOP_EXPRESSIONS, ActivityType.PRACTICE,
                approved, plan.goal().json(), guardianId, harness.catalog.speechTexts());
    }

    static ChildContext childContext() {
        return new ChildContext(PracticeVoiceChild.CHILD_NAME, PracticeVoiceChild.CHILD_AGE, PracticeVoiceChild.CHILD_INTERESTS);
    }

    // ------------------------------------------------------------------ 아이 쪽

    /** 세션이 끝날 때까지 실제 시간으로 지켜보며 차례가 오면 말하고, 상한을 넘으면 끝내기를 요청한다. */
    private void loop(SessionExecutor executor, PracticeVoiceDevice device, long startedNanos) {
        PracticeVoiceHarness.Timing timing = harness.timing();
        while (!executor.isTerminated()) {
            long now = System.nanoTime();
            if (!endRequested) {
                String cap = capReached(now - startedNanos);
                if (cap != null) {
                    status = cap;
                    requestEnd(executor, cap);
                }
            } else if (now - endRequestedNanos > timing.endWait().toNanos()) {
                facts.notice("끝내기 요청 뒤 " + timing.endWait().toSeconds() + "초 안에 세션이 닫히지 않아 하니스가 정리했다");
                break;
            }
            SessionStatus session = executor.status();
            if (!endRequested && (session == SessionStatus.PREPARING || session == SessionStatus.DEVICE_ACK)
                    && now - startedNanos > timing.startWait().toNanos()) {
                status = "START_TIMEOUT";
                requestEnd(executor, status);
            }
            if (!endRequested && !device.childSpeaking() && device.quietFor(timing.quietGap())
                    && now - lastChildEndNanos >= timing.trailingSilence().toNanos()) {
                if (session == SessionStatus.WAITING_CHILD && facts.joinPromptEnds > joinAnswers) {
                    joinAnswers = facts.joinPromptEnds;
                    speak(executor, device, "JOIN", PracticeVoiceChild.JOIN, "참여 안내 → 고정 참여 대답");
                } else if (session == SessionStatus.ACTIVE && facts.stopConfirmEnds > stopAnswers) {
                    stopAnswers = facts.stopConfirmEnds;
                    boolean stop = plan.plan() == Plan.STOP;
                    speak(executor, device, stop ? "STOP_CONFIRM_YES" : "KEEP_GOING",
                            stop ? PracticeVoiceChild.STOP_CONFIRM_YES : PracticeVoiceChild.KEEP_GOING, "종료 확인 질문 → 계획대로");
                } else if (session == SessionStatus.ACTIVE && awaitingChild()) {
                    childTurn(executor, device);
                }
            }
            sleep(timing.poll());
        }
    }

    /** 상한 사유(대화 시간 · GPT-Live 초 합계 · 판정과 아이 말 호출 · TTS). 없으면 null. */
    private String capReached(long elapsedNanos) {
        PracticeVoiceHarness.Caps caps = harness.caps();
        if (elapsedNanos >= caps.dialogue().toNanos()) {
            return "TIME_CAP";
        }
        double sessionSeconds = Math.max(facts.connectedSeconds(), facts.usageSeconds());
        if (harness.budget.gptLiveSeconds() + sessionSeconds >= caps.gptLiveSeconds()) {
            return "GPT_LIVE_SECONDS_CAP";
        }
        return facts.capHit;
    }

    /** 보호자 종료와 같은 길(EndRequested GUARDIAN_STOP)로 끝내기를 요청한다. */
    private void requestEnd(SessionExecutor executor, String reason) {
        endRequested = true;
        endRequestedFor = reason;
        endRequestedNanos = System.nanoTime();
        facts.notice("하니스가 끝내기를 요청했다(" + reason + ")");
        executor.mailbox().putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP,
                new CompletableFuture<>()));
    }

    /** 흐름이 아이 대답을 기다리는가(무응답 시계가 새로 걸렸고 · 판정이 열려 있지 않다). */
    private boolean awaitingChild() {
        Flow flow = facts.flow;
        return flow != null && facts.noResponseArmed && facts.noResponseArms != lastActedArm && !flow.judgmentOpen()
                && facts.judgmentsInFlight.get() == 0;
    }

    private void childTurn(SessionExecutor executor, PracticeVoiceDevice device) {
        lastActedArm = facts.noResponseArms;
        Flow flow = facts.flow;
        String phaseKey = flow.sceneKind() + ":" + flow.phase();
        int answers = answersPerPhase.getOrDefault(phaseKey, 0);
        Cue cue = new Cue(flow.phase(), flow.sub(), flow.sceneKind(), facts.lastLineSource, answers, silentUsed.contains(flow.sceneKind()),
                lastAnswer);
        Act act = PracticeVoiceChild.decide(plan.plan(), cue, lines);
        if (Act.NONE.equals(act.kind())) {
            facts.childTurn(Act.NONE, null, null, act.rule(), "NOT_AWAITING");
            return;
        }
        if (Act.SILENT.equals(act.kind())) {
            silentUsed.add(flow.sceneKind());
            facts.childTurn(Act.SILENT, null, null, act.rule(), "SILENT");
            return;
        }
        if (spokenTurns >= PracticeVoiceChild.MAX_CHILD_TURNS) {
            status = "CHILD_TURN_LIMIT";
            requestEnd(executor, status);
            return;
        }
        answersPerPhase.merge(phaseKey, 1, Integer::sum);
        speak(executor, device, act.key(), act.text(), act.rule());
        if (!"HELP_ACCEPT".equals(act.key()) && !act.key().startsWith("VARIANT_") && !"OPENING_REPLY".equals(act.key())) {
            lastAnswer = act.text();
        }
    }

    /** 미리 만든 소리 → 20ms 프레임 → 무음. 말한 뒤 실행부가 그 말을 받을 때까지(또는 시한까지) 기다린다. */
    private void speak(SessionExecutor executor, PracticeVoiceDevice device, String key, String text, String rule) {
        PracticeVoiceFacts.ChildTurn turn = facts.childTurn(Act.SPEAK, key, text, rule, null);
        spokenTurns++;
        PracticeVoiceHarness.Prepared ready = harness.prepare(text);
        facts.update(() -> {
            turn.ttsOutcome = ready.outcome();
            turn.ttsLatencyMs = ready.latencyMs();
            turn.ttsCached = ready.cached();
        });
        if (ready.capRefused()) {
            facts.capHit = "TTS_CALL_CAP";
            return;
        }
        if (ready.clip() == null) {
            facts.notice("TTS 실패(" + ready.outcome() + ") — 이 차례는 말하지 않았다");
            lastChildEndNanos = System.nanoTime();
            return;
        }
        int utterancesBefore = facts.childUtterances;
        int rowsBefore = facts.childRowCount();
        SessionStatus statusBefore = executor.status();
        PracticeVoiceDevice.Spoken spoken = device.say(ready.clip());
        spoken.await();
        long first = spoken.firstVoicedNanos() > 0 ? spoken.firstVoicedNanos() : spoken.firstFrameNanos();
        long end = spoken.voicedEndNanos() > 0 ? spoken.voicedEndNanos() : spoken.lastFrameNanos();
        long sendEnd = spoken.lastFrameNanos();
        facts.update(() -> {
            turn.audioMs = ready.clip().audioMs();
            turn.speechStartMs = first > 0 ? facts.msOf(first) : null;
            turn.speechEndMs = end > 0 ? facts.msOf(end) : null;
            turn.sendEndMs = sendEnd > 0 ? facts.msOf(sendEnd) : null;
        });
        lastChildEndNanos = sendEnd > 0 ? sendEnd : System.nanoTime();
        long deadline = System.nanoTime() + harness.timing().transcriptWait().toNanos();
        while (System.nanoTime() < deadline && !executor.isTerminated() && facts.childUtterances == utterancesBefore
                && facts.childRowCount() == rowsBefore && executor.status() == statusBefore) {
            sleep(harness.timing().poll());
        }
    }

    // ------------------------------------------------------------------ 관찰자

    /** 확장점(라우터 → 새 흐름)을 감싸 시점마다 흐름 상태 · 사건을 남긴다. 모든 시점과 기본 메서드를 그대로 넘긴다. */
    static final class ObservedExtension implements PracticeSessionExtension {

        private final PracticeSessionExtension delegate;
        private final Supplier<PracticeConversation> conversation;
        private final PracticeVoiceFacts facts;
        private final Map<String, String> labels;
        private final Map<PracticeOutput, ObservedOutput> outputs = new IdentityHashMap<>();

        ObservedExtension(PracticeSessionExtension delegate, Supplier<PracticeConversation> conversation, PracticeVoiceFacts facts,
                Map<String, String> labels) {
            this.delegate = delegate;
            this.conversation = conversation;
            this.facts = facts;
            this.labels = labels;
        }

        private PracticeOutput wrap(PracticeOutput output) {
            return output == null ? null : outputs.computeIfAbsent(output, o -> new ObservedOutput(o, this));
        }

        Flow snapshot() {
            PracticeConversation current = conversation.get();
            return current == null ? null : Flow.of(current.state());
        }

        private Signal after(String where, Signal signal) {
            try {
                facts.flow(snapshot());
                if (signal != null && signal != Signal.NONE) {
                    facts.signal(where, signal.name());
                }
            } catch (RuntimeException ignored) {
                // 관찰 실패는 흐름에 영향을 주지 않는다
            }
            return signal;
        }

        @Override
        public boolean controlsOutput() {
            return delegate.controlsOutput();
        }

        @Override
        public boolean speaksAsMujoong() {
            return delegate.speaksAsMujoong();
        }

        @Override
        public boolean collectingIntroduction() {
            return delegate.collectingIntroduction();
        }

        @Override
        public boolean canYieldUnplayedSpeech() {
            return delegate.canYieldUnplayedSpeech();
        }

        @Override
        public Progress progress() {
            return delegate.progress();
        }

        @Override
        public boolean handlesAudioRecovery() {
            return delegate.handlesAudioRecovery();
        }

        @Override
        public String diagnosticPhase() {
            return delegate.diagnosticPhase();
        }

        @Override
        public String diagnosticSub() {
            return delegate.diagnosticSub();
        }

        @Override
        public void onStopping() {
            delegate.onStopping();
            after("onStopping", null);
        }

        @Override
        public Duration settlementDelay(Instant now) {
            return delegate.settlementDelay(now);
        }

        @Override
        public Signal onActive(PracticeContext context, PracticeOutput output) {
            facts.active();
            return after("onActive", delegate.onActive(context, wrap(output)));
        }

        @Override
        public Signal onChildUtterance(Utterance utterance, PracticeOutput output) {
            if (utterance != null) {
                facts.heard(utterance.seq(), utterance.text(), utterance.uncertain(), utterance.devicePlaying());
            }
            return after("onChildUtterance", delegate.onChildUtterance(utterance, wrap(output)));
        }

        @Override
        public Signal onChildSpeechStarted(Instant at, PracticeOutput output) {
            facts.childSpeechStarted();
            return after("onChildSpeechStarted", delegate.onChildSpeechStarted(at, wrap(output)));
        }

        @Override
        public Signal onExtensionEvent(ExtensionEvent event, PracticeOutput output) {
            try {
                facts.extensionEvent(event);
            } catch (RuntimeException ignored) {
                // 관찰 실패는 흐름에 영향을 주지 않는다
            }
            return after("onExtensionEvent", delegate.onExtensionEvent(event, wrap(output)));
        }

        @Override
        public void onSettle(EndContext end) {
            delegate.onSettle(end);
            facts.settled(PracticeVoiceReport.ordered("finalStatus", end.finalStatus() == null ? null : end.finalStatus().name(),
                    "endReason", end.endReason() == null ? null : end.endReason().name(), "endDetail", end.endDetail(),
                    "eventsInSettleWindow", end.eventsInSettleWindow()));
            after("onSettle", null);
        }

        @Override
        public Object practiceRecord() {
            return delegate.practiceRecord();
        }
    }

    /** 확장점 출력 능력을 감싸 말 요청 · 시계 · 판정 요청을 남긴다(결과는 그대로 돌려준다). */
    static final class ObservedOutput implements PracticeSessionExtension.PracticeOutput {

        private final PracticeSessionExtension.PracticeOutput delegate;
        private final ObservedExtension owner;

        ObservedOutput(PracticeSessionExtension.PracticeOutput delegate, ObservedExtension owner) {
            this.delegate = delegate;
            this.owner = owner;
        }

        private PracticeVoiceFacts facts() {
            return owner.facts;
        }

        @Override
        public UUID sessionId() {
            return delegate.sessionId();
        }

        @Override
        public boolean playApproved(String contentCode) {
            facts().outputCall("playApproved");
            return delegate.playApproved(contentCode);
        }

        @Override
        public boolean instructProvider(String text) {
            facts().outputCall("instructProvider");
            return delegate.instructProvider(text);
        }

        @Override
        public boolean requestResponse() {
            facts().outputCall("requestResponse");
            return delegate.requestResponse();
        }

        @Override
        public boolean cancelResponse() {
            facts().outputCall("cancelResponse");
            return delegate.cancelResponse();
        }

        @Override
        public boolean holdOutput(boolean hold) {
            facts().outputCall("holdOutput(" + hold + ")");
            return delegate.holdOutput(hold);
        }

        @Override
        public boolean schedule(String name, Duration delay) {
            boolean accepted = delegate.schedule(name, delay);
            facts().timerScheduled(name, delay, accepted);
            return accepted;
        }

        @Override
        public boolean cancel(String name) {
            boolean cancelled = delegate.cancel(name);
            facts().timerCancelled(name, cancelled);
            return cancelled;
        }

        @Override
        public boolean post(String name, Object value) {
            return delegate.post(name, value);
        }

        @Override
        public boolean speak(PracticeSpeech speech) {
            Flow at = owner.snapshot();
            // 실행부가 그 자리에서 공급자 지시를 보내므로 시도를 먼저 연다(지시 · 첫 출력이 이 시도에 붙는다)
            PracticeVoiceFacts.Attempt attempt = speech == null ? null : facts().speakRequested(speech, at,
                    owner.labels.getOrDefault(PracticeVoiceReport.lineKey(speech.text()), "승인 원문(대본 칸 미상)"));
            boolean accepted = delegate.speak(speech);
            if (attempt != null) {
                facts().sendResult(attempt, accepted);
            }
            return accepted;
        }

        @Override
        public boolean cancelSpeech(UUID correlationId) {
            boolean cancelled = delegate.cancelSpeech(correlationId);
            facts().speechCancelled(correlationId);
            return cancelled;
        }

        @Override
        public boolean requestJudgment(JudgmentPoint point, long transcriptSeq, UUID correlationId, Map<String, Object> criteria) {
            boolean accepted = delegate.requestJudgment(point, transcriptSeq, correlationId, criteria);
            facts().judgmentRequested(point, transcriptSeq, correlationId, accepted);
            return accepted;
        }

        @Override
        public boolean announceRoleSwitch(String toRole) {
            facts().outputCall("announceRoleSwitch");
            return delegate.announceRoleSwitch(toRole);
        }

        @Override
        public boolean speakGenerated(UUID correlationId, String role, String instructionText) {
            Flow at = owner.snapshot();
            String last = facts().lastApprovedText();
            String recovery = last == null ? null : PracticeInstructions.recoverApprovedNarration(last);
            String source = recovery != null && recovery.equals(instructionText) ? "생성형:RECOVERY(뜻 전하기)"
                    : "생성형:" + (at == null ? "?" : at.sub() != Sub.NONE ? at.sub().name() : at.phase().name());
            PracticeVoiceFacts.Attempt attempt = facts().generatedRequested(correlationId, role, instructionText, at, source);
            boolean accepted = delegate.speakGenerated(correlationId, role, instructionText);
            facts().sendResult(attempt, accepted);
            return accepted;
        }

        @Override
        public boolean managesSpeechTimeouts() {
            return delegate.managesSpeechTimeouts();
        }

        @Override
        public boolean setThinking(boolean on) {
            return delegate.setThinking(on);
        }
    }

    /** 기록기 저장 입구를 감싸 전사 행 · 세션 사건을 사실로 남긴다(저장은 메모리, 동작은 운영 기록기 그대로). */
    static final class ObservedRecorder extends AsyncRecorder {

        private final PracticeVoiceFacts facts;

        ObservedRecorder(Store store, TaskLauncher launcher, LatencyRecorder latency, EventLogger events, RealtimeMetrics metrics,
                MujoongClock clock, int capacity, int flushConcurrency, PracticeVoiceFacts facts) {
            super(store, launcher, latency, events, metrics, clock, capacity, flushConcurrency);
            this.facts = facts;
        }

        @Override
        public boolean putTranscript(TranscriptEntry entry) {
            try {
                facts.transcript(entry);
            } catch (RuntimeException ignored) {
                // 관찰 실패는 기록에 영향을 주지 않는다
            }
            return super.putTranscript(entry);
        }

        @Override
        public void putEvent(SessionEvent event) {
            try {
                facts.sessionEvent(event);
            } catch (RuntimeException ignored) {
                // 관찰 실패는 기록에 영향을 주지 않는다
            }
            super.putEvent(event);
        }
    }

    /** 기록기 저장소(메모리, 쓰기는 버린다 — 행은 입구에서 이미 관찰했다). */
    static final class MemoryStore implements AsyncRecorder.Store {

        @Override
        public void write(UUID sessionId, List<TranscriptEntry> transcripts, List<SessionEvent> events) {
            // DB 대신: 행은 ObservedRecorder 입구에서 남겼다
        }

        @Override
        public void writeLastSeen(Collection<kr.mujoong.realtime.gateway.DeviceConnectionRepository.LastSeen> seen) {
            // 기기 마지막 시각은 하니스가 쓰지 않는다
        }
    }

    /** 공급자 포트를 감싸 연결 · 지시 · 사건을 남긴다(보내는 값은 그대로, 키 · 주소는 남기지 않는다). */
    static final class ObservedProvider implements ProviderPort {

        private final ProviderPort delegate;
        private final PracticeVoiceFacts facts;

        ObservedProvider(ProviderPort delegate, PracticeVoiceFacts facts) {
            this.delegate = delegate;
            this.facts = facts;
        }

        @Override
        public URI endpoint() {
            return delegate.endpoint();
        }

        @Override
        public ProviderSession open(Listener listener, Duration timeout) throws ProviderUnavailableException, InterruptedException {
            ProviderSession session = delegate.open(event -> {
                try {
                    facts.providerEvent(event);
                } catch (RuntimeException ignored) {
                    // 관찰 실패는 공급자 사건 전달에 영향을 주지 않는다
                }
                listener.onEvent(event);
            }, timeout);
            facts.providerOpened();
            return new ObservedSession(session, facts);
        }
    }

    /** 공급자 세션 감싸기(지시 송신 시각만 더 남긴다). */
    static final class ObservedSession implements ProviderPort.ProviderSession {

        private final ProviderPort.ProviderSession delegate;
        private final PracticeVoiceFacts facts;

        ObservedSession(ProviderPort.ProviderSession delegate, PracticeVoiceFacts facts) {
            this.delegate = delegate;
            this.facts = facts;
        }

        @Override
        public void start(ProviderRequestFactory.SessionStart request) throws ProviderPort.ProviderUnavailableException {
            delegate.start(request);
        }

        @Override
        public void sendInputAudio(ProviderRequestFactory.InputAudioAppend request) throws ProviderPort.ProviderUnavailableException {
            delegate.sendInputAudio(request);
        }

        @Override
        public void appendInstructions(ProviderRequestFactory.InstructionsAppend request)
                throws ProviderPort.ProviderUnavailableException {
            try {
                facts.instructionSent(request);
            } catch (RuntimeException ignored) {
                // 관찰 실패는 송신에 영향을 주지 않는다
            }
            delegate.appendInstructions(request);
        }

        @Override
        public void close(ProviderRequestFactory.SessionClose request) {
            delegate.close(request);
            facts.providerClosed();
        }

        @Override
        public void close() {
            delegate.close();
            facts.providerClosed();
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }
    }

    /**
     * 판정 포트(운영 Decisions 경로)를 감싸 지점 · 결과 · 확률 · 지연을 남기고, 하니스 호출 상한(판정 + 아이 말)을 건다. 판정 턴이 시한에 결과를 취소하면
     * '시한 취소'로 적고(4회차 H1) 안쪽 요청도 취소한다. 상한을 넘으면 부르지 않고 보류를 돌려주며 대화를 끝내게 한다.
     */
    static final class ObservedJudge implements JudgeClient {

        private final JudgeClient delegate;
        private final PracticeVoiceFacts facts;
        private final PracticeVoiceHarness.Budget budget;
        private final String promptVersion;

        ObservedJudge(JudgeClient delegate, PracticeVoiceFacts facts, PracticeVoiceHarness.Budget budget, String promptVersion) {
            this.delegate = delegate;
            this.facts = facts;
            this.budget = budget;
            this.promptVersion = promptVersion;
        }

        @Override
        public boolean available() {
            return delegate.available();
        }

        @Override
        public CompletionStage<JudgeOutcome> judge(JudgeRequest request, UUID turnId) {
            boolean allowed = !request.childUtterance().isBlank() && !request.uncertain() ? budget.takeJudgment() : true;
            PracticeVoiceFacts.JudgeCall call = facts.judgeStarted(request, !allowed);
            if (!allowed) {
                facts.capHit = "TEXT_CALL_CAP";
                JudgeOutcome capped = JudgeOutcome.abstained(AbstainReason.PROVIDER_ERROR, Duration.ZERO, null, promptVersion);
                facts.judgeFinished(call, capped, null, false);
                return CompletableFuture.completedFuture(capped);
            }
            facts.judgmentsInFlight.incrementAndGet();
            AtomicBoolean settled = new AtomicBoolean();
            CompletableFuture<JudgeOutcome> inner;
            try {
                inner = delegate.judge(request, turnId).toCompletableFuture();
            } catch (RuntimeException failure) {
                if (settled.compareAndSet(false, true)) {
                    facts.judgmentsInFlight.decrementAndGet();
                    facts.judgeFinished(call, null, failure.getClass().getSimpleName(), false);
                }
                throw failure;
            }
            CompletableFuture<JudgeOutcome> outer = new CompletableFuture<>();
            inner.whenComplete((outcome, failure) -> {
                if (settled.compareAndSet(false, true)) {
                    facts.judgmentsInFlight.decrementAndGet();
                    facts.judgeFinished(call, outcome, failure == null ? null : failure.getClass().getSimpleName(), false);
                }
                if (failure != null) {
                    outer.completeExceptionally(failure);
                } else {
                    outer.complete(outcome);
                }
            });
            outer.whenComplete((value, failure) -> {
                if (outer.isCancelled()) {
                    if (settled.compareAndSet(false, true)) {
                        facts.judgmentsInFlight.decrementAndGet();
                        facts.judgeFinished(call, null, null, true);
                    }
                    inner.cancel(true);
                }
            });
            return outer;
        }
    }

    /** 제브 포트를 감싸 검사마다 모드 · 결정 · 사유 · 지연을 남긴다(후보 문장은 합성 대화의 GPT-Live 생성 문장이다). */
    static final class ObservedGuardrail implements GuardrailClient {

        private final GuardrailClient delegate;
        private final PracticeVoiceFacts facts;

        ObservedGuardrail(GuardrailClient delegate, PracticeVoiceFacts facts) {
            this.delegate = delegate;
            this.facts = facts;
        }

        @Override
        public GuardrailMode mode() {
            return delegate.mode();
        }

        @Override
        public boolean available() {
            return delegate.available();
        }

        @Override
        public CompletionStage<GuardrailVerdict> check(GuardrailRequest request, Instant deadline) {
            PracticeVoiceFacts.GuardCheck check = facts.guardStarted(request);
            CompletionStage<GuardrailVerdict> stage = delegate.check(request, deadline);
            stage.whenComplete((verdict, failure) -> facts.guardFinished(check, verdict,
                    failure == null ? null : failure.getClass().getSimpleName()));
            return stage;
        }
    }

    /** 흐름 기록 자리를 감싸 장면 결과(수행 시점 · 변형 건너뜀 사유 · 사실)를 남긴다(저장은 메모리). */
    static final class ObservedFlowRecorder implements FlowRecorder {

        private final FlowRecorder delegate;
        private final PracticeVoiceFacts facts;

        ObservedFlowRecorder(FlowRecorder delegate, PracticeVoiceFacts facts) {
            this.delegate = delegate;
            this.facts = facts;
        }

        @Override
        public void sceneStarted(SceneRunRecord started) {
            delegate.sceneStarted(started);
            facts.sceneStarted(started.id(), started.sceneKind().name());
        }

        @Override
        public void fact(UUID sceneRunId, FactKind kind, Instant at) {
            delegate.fact(sceneRunId, kind, at);
            facts.sceneFact(sceneRunId, kind.name());
        }

        @Override
        public void help(HelpRecord help) {
            delegate.help(help);
            facts.help(PracticeVoiceReport.ordered("kind", help.kind().name(), "role", help.role().name(), "exampleGiven",
                    help.exampleGiven(), "consent", help.consentAnswer() == null ? null : help.consentAnswer().name(), "chars",
                    help.contentChars()));
        }

        @Override
        public void sceneOutcome(UUID sceneRunId, Instant endedAt, LiveTiming timing, VariantSkipReason skip, int retryCount,
                int reexplainCount) {
            delegate.sceneOutcome(sceneRunId, endedAt, timing, skip, retryCount, reexplainCount);
            facts.sceneOutcome(sceneRunId, timing == null ? null : timing.name(), skip == null ? null : skip.name(), retryCount,
                    reexplainCount);
        }

        @Override
        public Object practiceRecord() {
            return delegate.practiceRecord();
        }
    }

    /** 운영 사건 로그 줄(JSON 한 줄)을 받아 사실로 옮기는 출구. */
    static final class FactsSink implements EventLog.Sink {

        private final PracticeVoiceFacts facts;

        FactsSink(PracticeVoiceFacts facts) {
            this.facts = facts;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void write(EventCode.Level level, String line) {
            try {
                facts.logLine(JSON.read(line, LinkedHashMap.class));
            } catch (RuntimeException unreadable) {
                facts.notice("사건 로그 한 줄을 읽지 못했다(" + unreadable.getClass().getSimpleName() + ")");
            }
        }
    }

    /** 흐름이 알리는 앱 오류는 고정 사유 코드만 남긴다(그 밖은 예외 종류). */
    static String code(RuntimeException failure) {
        String message = failure.getMessage();
        return message != null && FIXED_CODE.matcher(message).matches() ? message : failure.getClass().getSimpleName();
    }

    static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // 정리 경로: 닫기 실패는 결과에 영향이 없다
        }
    }
}
