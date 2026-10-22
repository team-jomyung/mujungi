package kr.mujoong.realtime.testing;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.ws.message.StartSession;
import kr.mujoong.realtime.gate.OutputGate;
import kr.mujoong.realtime.gate.SeedDictionary;
import kr.mujoong.realtime.gateway.DeviceConnectionRepository;
import kr.mujoong.realtime.log.AsyncRecorder;
import kr.mujoong.realtime.log.LatencyRecorder;
import kr.mujoong.realtime.log.TranscriptEntry;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.runtime.TaskLauncher;
import kr.mujoong.realtime.runtime.TimerClocks;
import kr.mujoong.realtime.session.ChildContext;
import kr.mujoong.realtime.session.SessionEvent;
import kr.mujoong.realtime.session.SessionExecutor;
import kr.mujoong.realtime.session.SessionMailbox;
import kr.mujoong.realtime.voice.ProviderConnector;
import kr.mujoong.realtime.voice.ProviderRequestFactory;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.time.ManualTimerDriver;
import kr.mujoong.testsupport.time.MutableClock;

/**
 * 세션 실행기 단위 시험 묶음: 가변 Clock · 두 수동 타이머 구동기 · 메모리 전이 · 가짜 기기 연결 · 가짜 공급자 · 메모리 기록기 저장소 ·
 * 작업 기록 장치. 실제 스레드 없이 {@link #drain()}으로 편지함을 처리한다(NFR6.2).
 */
public final class SessionHarness {

    /** U4b 생애 시간 값: 운영 기본값(참여 20초 · 20초, 공백 4분 · 6분, 정산 2초, 점선 10초). */
    public static final SessionExecutor.Settings.Lifecycle LIFECYCLE = new SessionExecutor.Settings.Lifecycle(
            Duration.ofSeconds(20), Duration.ofSeconds(20), Duration.ofMinutes(4), Duration.ofMinutes(6),
            Duration.ofSeconds(2), Duration.ofSeconds(10));
    /** 시험 설정: 운영 기본값을 그대로 쓴다(20초 · 30초 · 40초 · 800ms · 20초 · 1초 · 불확실 기준 60% · 입력 전사 조용 1초). */
    public static final SessionExecutor.Settings SETTINGS = new SessionExecutor.Settings(Duration.ofSeconds(20),
            Duration.ofSeconds(30), Duration.ofSeconds(40), Duration.ofMillis(800), Duration.ofSeconds(20),
            Duration.ofSeconds(1), 50, 1000, 60, LIFECYCLE, Duration.ofMillis(1000), Duration.ofMillis(600), Duration.ofSeconds(10), Duration.ZERO);
    /** U4b 참여 응답의 공급자 항목 id(참여 확인 대답 "응"). */
    public static final String JOIN_ITEM = "join-reply";
    /** 시험 세션의 종료 표현 스냅숏(기본 표현 + 추가 표현 하나). */
    public static final List<String> STOP_EXPRESSIONS = List.of("무중아 그만할래", "이제 끝");

    public final MutableClock clock = new MutableClock();
    public final ManualTimerDriver safety = new ManualTimerDriver(clock);
    public final ManualTimerDriver quiet = new ManualTimerDriver(clock);
    public final LogCapture logs = LogCapture.detached();
    public final RealtimeMetrics metrics = RealtimeMetrics.inMemory();
    public final EventLogger events = TestEventLoggers.strict(clock, logs, metrics);
    public final InMemoryTransitions transitions = new InMemoryTransitions(clock);
    public final FakeProviderPort provider = new FakeProviderPort();
    public final ManualLauncher launcher = new ManualLauncher();
    public final MemoryStore store = new MemoryStore();
    public final FakeSessionGaps gaps = new FakeSessionGaps();
    public final FakeAudioRecordings recordings = new FakeAudioRecordings();
    /** 원음 기록 동의(create 전에 바꾼다). */
    public boolean rawAudioConsent;
    /** U4b: 정산 입구(create 전에 바꾼다). 기본은 정산 없음 — 종료 상태로 가면 정산 창 없이 곧바로 정리한다. */
    public kr.mujoong.realtime.session.SessionSettlement settlement = kr.mujoong.realtime.session.SessionSettlement.NONE;
    /** U4b: 연습 확장점 공장(create 전에 바꾼다). 기본은 무동작. */
    public kr.mujoong.realtime.session.PracticeSessionExtension.Factory practice =
            kr.mujoong.realtime.session.NoOpPracticeSessionExtension.FACTORY;
    /** U4b: 연습 조각 JSON(create 전에 바꾼다). */
    public String practiceJson;
    /** U4b: 활동 종류(create 전에 바꾼다). */
    public kr.mujoong.contract.status.ActivityType activityType = kr.mujoong.contract.status.ActivityType.FREE_TALK;
    /** U4b: 마무리 · 대기 문구 밖의 승인 문구(코드 → 원문, create 전에 넣는다). 없으면 그 재생은 건너뛴다(BR3.3). */
    public final java.util.Map<String, String> extraApproved = new java.util.LinkedHashMap<>();
    /** U12: 시작 패키지 approvedSpeech의 승인 발화 문장 지도(코드 → 원문, create 전에 넣는다). 확장의 역할 전환 알림이 쓴다. */
    public final java.util.Map<String, String> approvedSpeech = new java.util.LinkedHashMap<>();
    /** U4b: 승인 문구 코드 → 버전(extraApproved로 만든 것). */
    public final java.util.Map<String, UUID> approvedVersions = new java.util.LinkedHashMap<>();
    public final LatencyRecorder latency = new LatencyRecorder(events, metrics, clock, 100, Duration.ofMinutes(10));
    public final AsyncRecorder recorder = new AsyncRecorder(store, TaskLauncher.inline(), latency, events, metrics, clock,
            200, 2);
    /** U11: 판정 기록기(메모리 저장소 {@link MemoryStore#judgments}에 쓴다, 실행기 연결은 Step 20 · 21). */
    public final kr.mujoong.realtime.judgment.JudgmentLiveRecorder judgmentRecorder =
            new kr.mujoong.realtime.judgment.JudgmentLiveRecorder(store, TaskLauncher.inline(), events);
    public final List<UUID[]> unresponsive = new ArrayList<>();
    public final UUID sessionId = UUID.randomUUID();
    public final UUID guardianId = UUID.randomUUID();
    public final UUID deviceId = UUID.randomUUID();
    public final UUID closingVersion = UUID.randomUUID();
    public String closingText;
    public boolean autoCompleteNoticeForJoin = true;
    public record NoticePrelude(List<kr.mujoong.contract.ws.message.PlayApprovedContent> plays, List<TranscriptEntry> rows) { }
    public NoticePrelude noticePrelude = new NoticePrelude(List.of(), List.of());
    public final UUID fillerVersion = UUID.randomUUID();
    public final SessionExecutor.Settings settings;
    public final SessionMailbox mailbox;
    public FakeDeviceLink link;
    public SessionExecutor executor;
    public boolean removed;
    /** U11: 판정 장치(create 전에 {@link #useJudge()}로 바꾼다). 기본은 공급자 없음 — 판정 턴을 열지 않는다. */
    public kr.mujoong.realtime.session.JudgmentSupport judgment = kr.mujoong.realtime.session.JudgmentSupport.NONE;
    /** U11: {@link #useJudge()} 뒤의 시험용 판정기. */
    public FakeJudgeClient judge;
    /** U13: 제브 기록기(메모리 저장소 {@link MemoryStore#guardrails}에 쓴다). */
    public final kr.mujoong.realtime.guardrail.GuardrailEventRecorder guardrailRecorder =
            new kr.mujoong.realtime.guardrail.GuardrailEventRecorder(store, TaskLauncher.inline(), events);
    /** U13: 제브 장치(create 전에 {@link #useGuardrail()}로 바꾼다). 기본은 검사 없음(DISABLED) — 제브 단계를 건너뛴다. */
    public kr.mujoong.realtime.guardrail.GuardrailSupport guardrail = kr.mujoong.realtime.guardrail.GuardrailSupport.DISABLED;
    /** 실제 서비스 자원과 구별되는, 시험이 명시적으로 주입하는 안내 검수 대역. */
    public kr.mujoong.realtime.session.NoticeAssetCatalog noticeAssets = new kr.mujoong.realtime.session.NoticeAssetCatalog();
    /** U13: {@link #useGuardrail()} 뒤의 시험용 제브 대역(손으로 푸는 약속). */
    public FakeGuardrailClient guard;

    /** 시험용 제브 대역을 쓴다(create 전에 부른다): 실제 모드(REAL 표시), 기록은 {@link MemoryStore#guardrails}로 간다. 대역일 뿐 실제 제브가 아니다. */
    public FakeGuardrailClient useGuardrail() {
        guard = new FakeGuardrailClient();
        guardrail = new kr.mujoong.realtime.guardrail.GuardrailSupport(kr.mujoong.realtime.guardrail.GuardrailMode.REAL, guard,
                guardrailRecorder, Duration.ofMillis(800), FakeGuardrailClient.POLICY);
        return guard;
    }

    /** 시험용 판정기를 쓴다(create 전에 부른다): 공급자 있음, 기록은 {@link MemoryStore#judgments}로 간다. */
    public FakeJudgeClient useJudge() {
        judge = new FakeJudgeClient();
        judgment = new kr.mujoong.realtime.session.JudgmentSupport(judge, judgmentRecorder, Duration.ofSeconds(4), "prompt-v1",
                kr.mujoong.realtime.content.LiveContentResources.loadDefault().judgment());
        return judge;
    }

    public SessionHarness() {
        this(SETTINGS);
    }

    public SessionHarness(SessionExecutor.Settings settings) {
        this.settings = settings;
        this.provider.newSpeechMargin(settings.newSpeechMargin());
        this.mailbox = new SessionMailbox(settings.mailboxCapacity());
        this.link = new FakeDeviceLink(deviceId, clock.now());
        kr.mujoong.realtime.session.ServiceGuardrailFixtures.configure(this);
    }

    /** 실행기를 만든다(아직 Bootstrap 전). */
    public SessionExecutor create() {
        transitions.seed(sessionId, guardianId, deviceId);
        SessionExecutor.Services services = new SessionExecutor.Services(transitions,
                id -> id.equals(deviceId) ? Optional.ofNullable(link) : Optional.empty(),
                (device, connection) -> unresponsive.add(new UUID[] {device, connection}),
                new ProviderConnector(provider, new ProviderRequestFactory(), Duration.ofSeconds(10), launcher, "test"),
                new ProviderRequestFactory(), new OutputGate(SeedDictionary.loadDefault()), recorder, latency,
                new TimerClocks(safety, quiet), launcher, events, metrics, clock, settings, gaps, recordings, settlement,
                practice, judgment, guardrail, noticeAssets);
        List<StartSession.ApprovedAudio> audio = new ArrayList<>(List.of(
                new StartSession.ApprovedAudio("CLOSING", closingVersion, "/media/approved/closing.wav"),
                new StartSession.ApprovedAudio("WAITING_FILLER", fillerVersion, "/media/approved/filler.wav")));
        java.util.Map<String, SessionExecutor.ApprovedItem> approved = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, String> extra : extraApproved.entrySet()) {
            UUID version = approvedVersions.computeIfAbsent(extra.getKey(), code -> UUID.randomUUID());
            approved.put(extra.getKey(), new SessionExecutor.ApprovedItem(extra.getKey(), version, extra.getValue()));
            audio.add(new StartSession.ApprovedAudio(extra.getKey(), version,
                    "/media/approved/" + extra.getKey().toLowerCase(java.util.Locale.ROOT) + ".wav"));
        }
        SessionExecutor.Plan plan = new SessionExecutor.Plan(sessionId, deviceId, "0123456789abcdef0123456789abcdef",
                new ChildContext("하늘", 8, List.of("공룡")), audio,
                new SessionExecutor.ApprovedItem("CLOSING", closingVersion, closingText),
                new SessionExecutor.ApprovedItem("WAITING_FILLER", fillerVersion), rawAudioConsent, STOP_EXPRESSIONS,
                activityType, approved, practiceJson, guardianId, approvedSpeech);
        executor = new SessionExecutor(plan, services, mailbox, () -> removed = true);
        return executor;
    }

    /** Bootstrap을 처리하고 공급자 연결 작업을 돌린다(공급자 정상이면 started까지 편지함에 들어간다). */
    public void boot() {
        if (executor == null) {
            create();
        }
        executor.bootstrapOnly();
        drain();
        launcher.runAll("provider-connect-");
        drain();
    }

    /** START_SESSION opId. */
    public UUID startOpId() {
        return link.sent().stream().filter(sent -> sent.payload() instanceof StartSession).findFirst()
                .orElseThrow().opId();
    }

    /** ACK + DEVICE_READY(OK)를 넣고 처리한다. */
    public void ackAndReady() {
        mailbox.offerGeneral(new SessionMailbox.DeviceAck(link.connectionId(), startOpId(), clock.now()));
        mailbox.offerGeneral(new SessionMailbox.DeviceReadyReceived(link.connectionId(),
                new kr.mujoong.contract.ws.message.DeviceReady(kr.mujoong.contract.ws.message.DeviceReady.Availability.OK,
                        kr.mujoong.contract.ws.message.DeviceReady.Availability.OK,
                        kr.mujoong.contract.ws.message.DeviceReady.Prefetch.OK),
                clock.now()));
        drain();
    }

    /**
     * ACTIVE까지 간다. U4b(FR11.3): ACK · READY 뒤 참여 확인(WAITING_CHILD)을 거치므로 참여 응답을 한 번 보낸다(시작 안내가 패키지에 없으면 창이
     * 곧바로 열린다).
     */
    public void activate() {
        activateUnsettled();
        settleSend();
    }

    /** ACTIVE까지 가되 참여 뒤 첫 응답 지시(직렬 큐 작업)는 아직 보내지 않은 채 둔다(U11 W9 시험). */
    public void activateUnsettled() {
        boot();
        ackAndReady();
        join();
        if (autoCompleteNoticeForJoin && activityType == kr.mujoong.contract.status.ActivityType.FREE_TALK
                && executor.status() == kr.mujoong.contract.status.SessionStatus.ACTIVE) {
            var plays = link.sent(kr.mujoong.contract.ws.message.PlayApprovedContent.class).stream()
                    .filter(play -> play.contentCode().equals(SessionExecutor.INTRO_FREE_TALK)
                            && play.contentVersionId().equals(kr.mujoong.realtime.session.ServiceGuardrailFixtures.INTRO)).toList();
            var rows = store.transcripts.stream().filter(row -> row.speaker() == TranscriptEntry.Speaker.AI
                    && row.approvedForDisplay() && row.text().equals(kr.mujoong.realtime.session.ServiceGuardrailFixtures.TEXT)).toList();
            noticePrelude = new NoticePrelude(plays, rows);
            var seqs = rows.stream().map(TranscriptEntry::sequenceNo).collect(java.util.stream.Collectors.toSet());
            store.transcripts.removeIf(row -> seqs.contains(row.sequenceNo()));
            link.checkpointApproved(plays.stream().map(kr.mujoong.contract.ws.message.PlayApprovedContent::playbackId)
                    .collect(java.util.stream.Collectors.toSet()));
        }
    }

    /**
     * 직렬 큐에 쌓인 서버 지시(참여 뒤 첫 응답 등, 설계 개정 A2)를 공급자로 내보낸다. 확인 사건 · 새 말은 보내지 않는다 — 시험이 {@link #speak}로
     * 공급자처럼 말하게 한다.
     */
    public void settleSend() {
        launcher.runAll("provider-");
        drain();
    }

    /**
     * 공급자가 말한다(시간 없는 전사 + 음성). 확인 사건을 안 보낸 지시가 있으면 가짜 공급자 세션이 먼저 확인 사건 · 새 말 시작 · 낡은 꼬리 흉내
     * 채움을 보내 실행기가 {@code audioBytes}만큼의 음성부터 받게 한다(설계 개정 A4). 보낸 뒤 편지함을 처리한다.
     */
    public void speak(String transcript, int audioBytes) {
        if (transcript != null) {
            provider.last().emit(new kr.mujoong.realtime.voice.ProviderPort.ProviderEvent.OutputTranscriptDelta(transcript));
        }
        if (audioBytes > 0) {
            provider.last().emit(new kr.mujoong.realtime.voice.ProviderPort.ProviderEvent.OutputAudioDelta(new byte[audioBytes]));
        }
        drain();
    }

    /** U4b: 참여 확인에 아이가 "응"이라고 답한다(공급자 입력 전사 확정). */
    public void join() {
        if (autoCompleteNoticeForJoin && activityType == kr.mujoong.contract.status.ActivityType.FREE_TALK
                && executor.status() == kr.mujoong.contract.status.SessionStatus.WAITING_CHILD) completeNotice();
        childSays(JOIN_ITEM, "응");
    }

    /** 기본 회귀 대역도 검수 메타데이터와 현재 실제 완료 표식을 명시적으로 거친다. */
    public void completeNotice() {
        var plays = link.sent(kr.mujoong.contract.ws.message.PlayApprovedContent.class);
        if (plays.isEmpty()) return;
        var play = plays.getLast();
        if (!play.contentCode().equals(SessionExecutor.INTRO_FREE_TALK) && !play.contentCode().equals(SessionExecutor.RE_PROMPT)) return;
        link.sent().stream().filter(sent -> sent.payload() instanceof kr.mujoong.contract.ws.message.PlayApprovedContent value
                && value.playbackId().equals(play.playbackId())).findFirst().ifPresent(sent -> {
                    if (sent.opId() != null) mailbox.offerGeneral(new SessionMailbox.DeviceAck(link.connectionId(), sent.opId(), clock.now()));
                });
        mailbox.putSafety(new SessionMailbox.PlaybackMarkReceived(link.connectionId(),
                new kr.mujoong.contract.ws.message.PlaybackMark(play.playbackId(),
                        kr.mujoong.contract.ws.message.PlaybackMark.Status.COMPLETED, null, clock.now(), null, null), clock.now()));
        drain();
    }

    /** 알려진 승인 콘텐츠 판/현재 playback 제어와 approvedLine의 즉시 확정 시각이 모두 맞는 시험 행만 구분한다. */
    public boolean isApprovedContentRow(TranscriptEntry row) {
        if (row.speaker() != TranscriptEntry.Speaker.AI || !row.approvedForDisplay()
                || row.startedAt() == null || !row.startedAt().equals(row.endedAt())) return false;
        return link.sent(kr.mujoong.contract.ws.message.PlayApprovedContent.class).stream().anyMatch(play -> {
            UUID version = play.contentCode().equals("CLOSING") ? closingVersion : approvedVersions.get(play.contentCode());
            String text = play.contentCode().equals("CLOSING") ? closingText : extraApproved.get(play.contentCode());
            return play.contentVersionId().equals(version) && row.text().equals(text);
        });
    }

    /** 아이 확정 전사 하나를 공급자 이벤트로 흘려 넣고 처리한다. */
    public void childSays(String itemId, String text) {
        provider.last().emit(new kr.mujoong.realtime.voice.ProviderPort.ProviderEvent.InputTranscriptDone(itemId, text,
                null));
        drain();
    }

    /** 편지함을 비울 때까지 처리한다. */
    public int drain() {
        return executor.drain();
    }

    /** 두 시계를 함께 앞당기며 도래한 타이머를 마감 순서대로 실행하고 편지함을 처리한다. */
    public void advance(Duration duration) {
        Instant target = clock.now().plus(duration);
        while (true) {
            Optional<Instant> next = earliest(target);
            if (next.isEmpty()) {
                break;
            }
            Duration gap = Duration.between(clock.now(), next.get());
            if (gap.isPositive()) {
                clock.advance(gap);
            }
            safety.runDue();
            quiet.runDue();
            drain();
        }
        Duration rest = Duration.between(clock.now(), target);
        if (rest.isPositive()) {
            clock.advance(rest);
        }
        drain();
    }

    /**
     * U4b: 기기가 살아 있게(HEARTBEAT처럼 마지막 메시지 시각을 1초마다 새로 하며) 시간을 앞당긴다. WAITING_CHILD · ACTIVE는 점선(10초) · 끊김(40초)
     * 시계가 돌므로, 끊김을 보지 않는 시험은 이것으로 기다린다.
     */
    public void advanceAlive(Duration duration) {
        Instant target = clock.now().plus(duration);
        Duration step = Duration.ofSeconds(1);
        while (clock.now().isBefore(target)) {
            Duration left = Duration.between(clock.now(), target);
            if (link != null) {
                link.touch(clock.now());
            }
            advance(left.compareTo(step) < 0 ? left : step);
        }
        if (link != null) {
            link.touch(clock.now());
        }
    }

    private Optional<Instant> earliest(Instant target) {
        Optional<Instant> a = safety.nextDeadline().filter(at -> !at.isAfter(target));
        Optional<Instant> b = quiet.nextDeadline().filter(at -> !at.isAfter(target));
        if (a.isEmpty()) {
            return b;
        }
        if (b.isEmpty()) {
            return a;
        }
        return Optional.of(a.get().isBefore(b.get()) ? a.get() : b.get());
    }

    /** 메모리 기록기 저장소. */
    public static final class MemoryStore implements AsyncRecorder.Store, kr.mujoong.realtime.judgment.JudgmentLiveRecorder.Store,
            kr.mujoong.realtime.guardrail.GuardrailEventRecorder.Store {

        public final List<TranscriptEntry> transcripts = new ArrayList<>();
        public final List<SessionEvent> sessionEvents = new ArrayList<>();
        public final List<DeviceConnectionRepository.LastSeen> lastSeen = new ArrayList<>();
        public int failuresLeft;
        public int writes;
        /** U11: 판정 기록(id = turnId, 같은 id는 덮어쓴다). */
        public final List<kr.mujoong.realtime.judgment.JudgmentLiveRecord> judgments = new ArrayList<>();
        /** U13: 제브 검사 기록(id = 검사 요청 id, 같은 id는 덮어쓴다). */
        public final List<kr.mujoong.realtime.guardrail.GuardrailEventRecord> guardrails = new ArrayList<>();

        @Override
        public synchronized void write(UUID sessionId, List<TranscriptEntry> rows, List<SessionEvent> events) {
            if (failuresLeft > 0) {
                failuresLeft--;
                throw new org.springframework.dao.TransientDataAccessResourceException("시험용 기록기 장애");
            }
            writes++;
            for (TranscriptEntry row : rows) {
                transcripts.removeIf(old -> old.sequenceNo() == row.sequenceNo()
                        && old.sessionId().equals(row.sessionId())
                        && old.assemblyStatus() == TranscriptEntry.AssemblyStatus.PARTIAL);
                boolean closed = transcripts.stream().anyMatch(old -> old.sequenceNo() == row.sequenceNo()
                        && old.sessionId().equals(row.sessionId()));
                if (!closed) {
                    transcripts.add(row);
                }
            }
            sessionEvents.addAll(events);
        }

        @Override
        public synchronized void write(kr.mujoong.realtime.judgment.JudgmentLiveRecord record) {
            if (failuresLeft > 0) {
                failuresLeft--;
                throw new org.springframework.dao.TransientDataAccessResourceException("시험용 기록기 장애");
            }
            judgments.removeIf(old -> old.id().equals(record.id()));
            judgments.add(record);
        }

        @Override
        public synchronized void write(kr.mujoong.realtime.guardrail.GuardrailEventRecord record) {
            guardrails.removeIf(old -> old.id().equals(record.id()));
            guardrails.add(record);
        }

        @Override
        public synchronized void writeLastSeen(Collection<DeviceConnectionRepository.LastSeen> seen) {
            lastSeen.addAll(seen);
        }

        /** 번호의 지금 행. */
        public synchronized Optional<TranscriptEntry> row(int seq) {
            return transcripts.stream().filter(entry -> entry.sequenceNo() == seq).findFirst();
        }
    }
}
