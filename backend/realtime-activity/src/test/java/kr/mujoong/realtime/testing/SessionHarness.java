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

    /** U4b 생애 시간 값: 운영 기본값(참여 20초 · 20초, 공백 4분 · 6분, 확인 질문 20초, 정산 2초, 점선 10초). */
    public static final SessionExecutor.Settings.Lifecycle LIFECYCLE = new SessionExecutor.Settings.Lifecycle(
            Duration.ofSeconds(20), Duration.ofSeconds(20), Duration.ofMinutes(4), Duration.ofMinutes(6),
            Duration.ofSeconds(20), Duration.ofSeconds(2), Duration.ofSeconds(10));
    /** 시험 설정: 운영 기본값을 그대로 쓴다(20초 · 30초 · 40초 · 800ms · 20초 · 1초 · 불확실 기준 60%). */
    public static final SessionExecutor.Settings SETTINGS = new SessionExecutor.Settings(Duration.ofSeconds(20),
            Duration.ofSeconds(30), Duration.ofSeconds(40), Duration.ofMillis(800), Duration.ofSeconds(20),
            Duration.ofSeconds(1), 50, 1000, 60, LIFECYCLE);
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
    /** U4b: 활동 종류(create 전에 바꾼다). */
    public kr.mujoong.contract.status.ActivityType activityType = kr.mujoong.contract.status.ActivityType.FREE_TALK;
    /** U4b: 마무리 · 대기 문구 밖의 승인 문구(코드 → 원문, create 전에 넣는다). 없으면 그 재생은 건너뛴다(BR3.3). */
    public final java.util.Map<String, String> extraApproved = new java.util.LinkedHashMap<>();
    /** U4b: 승인 문구 코드 → 버전(extraApproved로 만든 것). */
    public final java.util.Map<String, UUID> approvedVersions = new java.util.LinkedHashMap<>();
    public final LatencyRecorder latency = new LatencyRecorder(events, metrics, clock, 100, Duration.ofMinutes(10));
    public final AsyncRecorder recorder = new AsyncRecorder(store, TaskLauncher.inline(), latency, events, metrics, clock,
            200, 2);
    public final List<UUID[]> unresponsive = new ArrayList<>();
    public final UUID sessionId = UUID.randomUUID();
    public final UUID guardianId = UUID.randomUUID();
    public final UUID deviceId = UUID.randomUUID();
    public final UUID closingVersion = UUID.randomUUID();
    public final UUID fillerVersion = UUID.randomUUID();
    public final SessionExecutor.Settings settings;
    public final SessionMailbox mailbox;
    public FakeDeviceLink link;
    public SessionExecutor executor;
    public boolean removed;

    public SessionHarness() {
        this(SETTINGS);
    }

    public SessionHarness(SessionExecutor.Settings settings) {
        this.settings = settings;
        this.mailbox = new SessionMailbox(settings.mailboxCapacity());
        this.link = new FakeDeviceLink(deviceId, clock.now());
    }

    /** 실행기를 만든다(아직 Bootstrap 전). */
    public SessionExecutor create() {
        transitions.seed(sessionId, guardianId, deviceId);
        SessionExecutor.Services services = new SessionExecutor.Services(transitions,
                id -> id.equals(deviceId) ? Optional.ofNullable(link) : Optional.empty(),
                (device, connection) -> unresponsive.add(new UUID[] {device, connection}),
                new ProviderConnector(provider, new ProviderRequestFactory(), Duration.ofSeconds(10), launcher, "test"),
                new ProviderRequestFactory(), new OutputGate(SeedDictionary.loadDefault()), recorder, latency,
                new TimerClocks(safety, quiet), launcher, events, metrics, clock, settings, gaps, recordings);
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
                new SessionExecutor.ApprovedItem("CLOSING", closingVersion),
                new SessionExecutor.ApprovedItem("WAITING_FILLER", fillerVersion), rawAudioConsent, STOP_EXPRESSIONS,
                activityType, approved, null);
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
        boot();
        ackAndReady();
        join();
    }

    /** U4b: 참여 확인에 아이가 "응"이라고 답한다(공급자 입력 전사 확정). */
    public void join() {
        childSays(JOIN_ITEM, "응");
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
    public static final class MemoryStore implements AsyncRecorder.Store {

        public final List<TranscriptEntry> transcripts = new ArrayList<>();
        public final List<SessionEvent> sessionEvents = new ArrayList<>();
        public final List<DeviceConnectionRepository.LastSeen> lastSeen = new ArrayList<>();
        public int failuresLeft;
        public int writes;

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
        public synchronized void writeLastSeen(Collection<DeviceConnectionRepository.LastSeen> seen) {
            lastSeen.addAll(seen);
        }

        /** 번호의 지금 행. */
        public synchronized Optional<TranscriptEntry> row(int seq) {
            return transcripts.stream().filter(entry -> entry.sequenceNo() == seq).findFirst();
        }
    }
}
