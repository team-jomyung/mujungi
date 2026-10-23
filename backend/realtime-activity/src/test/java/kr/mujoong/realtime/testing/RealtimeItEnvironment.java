package kr.mujoong.realtime.testing;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.realtime.gateway.DeviceConnectionRepository;
import kr.mujoong.realtime.gateway.DeviceConnectionRow;
import kr.mujoong.realtime.gateway.SocketRegistry;
import kr.mujoong.realtime.session.ChildContext;
import kr.mujoong.realtime.session.ConsentSnapshot;
import kr.mujoong.realtime.session.StartSessionHandler;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.device.DeviceSimulatorClient;
import kr.mujoong.testsupport.http.ContractStubServer;
import kr.mujoong.testsupport.http.ContractStubServer.StubResponse;
import kr.mujoong.testsupport.provider.FakeProviderWsServer;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Realtime 통합 시험 묶음(Step 30): 공용 PostgreSQL(표를 비우고 시작) · Core 계약 대역(verify-token · 연결 보고 · 이벤트 수신) · 가짜
 * 공급자 WS 서버 · test 프로필 앱. 기기 하나(실행마다 만든 토큰)를 등록된 기기로 답하게 해 둔다. 시험 소스 전용이다.
 */
public final class RealtimeItEnvironment implements AutoCloseable {

    /** A deterministic PCM tone for tests that expect speech output; all-zero PCM is transport silence. */
    public static byte[] audiblePcm(int bytes) {
        byte[] pcm = new byte[bytes];
        for (int i = 0; i + 1 < bytes; i += 2) {
            short sample = (short) (2000 * Math.sin(2 * Math.PI * (i / 2) / 60));
            pcm[i] = (byte) sample;
            pcm[i + 1] = (byte) (sample >>> 8);
        }
        return pcm;
    }

    /** test 프로필의 허용 Origin(application-test.yml). */
    public static final String ORIGIN = "https://mujoong.invalid";
    public static final Duration WAIT = Duration.ofSeconds(10);

    public final MujoongPostgres postgres;
    public final RealtimeDb db;
    public final ContractStubServer core;
    /** Core 대역의 verify-token 경로(응답 스크립트를 시험이 바꾼다). */
    public final ContractStubServer.Route verifyRoute;
    /** Core 대역의 연결 상태 보고 경로. */
    public final ContractStubServer.Route connectionRoute;
    /** Core 대역의 이벤트 수신 경로. */
    public final ContractStubServer.Route eventsRoute;
    public final FakeProviderWsServer provider;
    public final RealtimeTestApp app;
    public final String internalToken = TestSecrets.internalServiceToken();
    public final String deviceToken = TestSecrets.deviceToken();
    public final UUID deviceId = UUID.randomUUID();
    public final UUID guardianId = UUID.randomUUID();
    public final UUID childId = UUID.randomUUID();
    private DeviceSimulatorClient currentDevice;
    private static final UUID SPOKEN_CLOSING = UUID.fromString("11111111-1111-4111-8111-111111111119");
    public record NoticePrelude(UUID playbackId, UUID contentVersionId, int sequenceNo) { }
    private final Map<UUID, NoticePrelude> noticePreludes = new LinkedHashMap<>();

    private RealtimeItEnvironment(Map<String, String> extraArgs, URI providerOverride,
            ApplicationContextInitializer<ConfigurableApplicationContext> initializer) {
        this.db = RealtimeDb.open();
        this.postgres = db.postgres();
        db.wipe();
        this.core = ContractStubServer.start().requireInternalToken(internalToken);
        this.verifyRoute = core.route("POST", "/internal/devices/verify-token")
                .requestSchema("internal-verify-token-request.schema.json")
                .respond(StubResponse.json(200, verifiedBody()));
        this.connectionRoute = core.route("PUT", "/internal/devices/{deviceId}/connection")
                .requestSchema("internal-device-connection-report.schema.json")
                .respond(StubResponse.json(200, "{}"));
        this.eventsRoute = core.route("POST", "/internal/events").respond(StubResponse.json(200, "{}"));
        // 옛 시험의 순서(아이 전사 확정 뒤 AI 말)를 흉내 낸다: 앱 시험 설정의 input-transcript-quiet(150ms)보다 길게 기다린다
        this.provider = FakeProviderWsServer.start().childConfirmDelay(Duration.ofMillis(300));
        Map<String, String> args = new LinkedHashMap<>(RealtimeTestApp.defaults(postgres, core.baseUrl(),
                providerOverride != null ? providerOverride : provider.uri(), internalToken));
        // 첫 요청의 스키마 검증 · JIT 지연으로 100ms verify 제한에 걸리지 않게 시험 묶음만 늘린다(I-3: verify < hello 유지)
        args.put("mujoong.internal-http.verify-token-timeout", "1s");
        args.put("mujoong.timing.device.hello-timeout", "2s");
        args.putAll(extraArgs);
        this.app = RealtimeTestApp.start(args, context -> {
            initializer.initialize(context);
            context.addBeanFactoryPostProcessor(factory -> {
                if (!factory.containsSingleton("noticeAssetCatalog")) factory.registerSingleton("noticeAssetCatalog",
                        kr.mujoong.realtime.session.ServiceGuardrailFixtures.catalog(SPOKEN_CLOSING));
            });
        });
    }

    /** 기본 묶음. */
    public static RealtimeItEnvironment start() {
        return new RealtimeItEnvironment(Map.of(), null, context -> { });
    }

    /** 앱 인자 · 공급자 주소를 바꾼 묶음. */
    public static RealtimeItEnvironment start(Map<String, String> extraArgs, URI providerOverride) {
        return new RealtimeItEnvironment(extraArgs, providerOverride, context -> { });
    }

    /** 앱 인자와 빈 생성 전 초기화(수동 시계 · 시험 장치 등록)를 준 묶음. */
    public static RealtimeItEnvironment startWith(Map<String, String> extraArgs,
            ApplicationContextInitializer<ConfigurableApplicationContext> initializer) {
        return new RealtimeItEnvironment(extraArgs, null, initializer);
    }

    /** 이 앱의 내부 API 호출기(실행마다 만든 내부 토큰). */
    public InternalApi api() {
        return new InternalApi(app.internalUri(""), internalToken);
    }

    /** verify-token 200 본문(이 묶음의 기기). */
    public String verifiedBody() {
        return "{\"deviceId\":\"" + deviceId + "\",\"guardianId\":\"" + guardianId + "\",\"childId\":\"" + childId
                + "\",\"registrationStatus\":\"REGISTERED\"}";
    }

    /** 기기를 연결하고 HELLO_OK까지 받는다. 원장이 CONNECTED가 되고 소켓 레지스트리에 오를 때까지 기다린다. */
    public DeviceSimulatorClient connectDevice() {
        DeviceSimulatorClient device = DeviceSimulatorClient.open(app.deviceUri(), ORIGIN, deviceToken, WAIT);
        currentDevice = device;
        kr.mujoong.contract.ws.message.HelloOk ok = device.hello(WAIT);
        awaitTrue(() -> ledger().map(row -> row.state() == ConnectionState.CONNECTED
                && ok.connectionId().equals(row.connectionId())).orElse(false), "원장 CONNECTED");
        awaitTrue(() -> app.bean(SocketRegistry.class).isLive(deviceId, ok.connectionId()), "소켓 레지스트리");
        return device;
    }

    /** 이어가기 요청(HELLO resume)과 함께 연결한 결과(U3). */
    public record Connected(DeviceSimulatorClient device, kr.mujoong.contract.ws.message.HelloOk helloOk) {
    }

    /**
     * 기기를 연결하고 HELLO(resume = 주어진 세션)로 HELLO_OK까지 받는다(U3-J4). 원장이 이 연결(CONNECTED 또는 끊김이 잦으면 UNSTABLE)이 되고
     * 소켓 레지스트리에 오를 때까지 기다린다.
     */
    public Connected connectDevice(UUID resumeSessionId) {
        DeviceSimulatorClient device = DeviceSimulatorClient.open(app.deviceUri(), ORIGIN, deviceToken, WAIT);
        currentDevice = device;
        kr.mujoong.contract.ws.message.HelloOk ok = device.hello(WAIT, resumeSessionId);
        awaitTrue(() -> ledger().map(row -> (row.state() == ConnectionState.CONNECTED
                || row.state() == ConnectionState.UNSTABLE) && ok.connectionId().equals(row.connectionId())).orElse(false),
                "원장 CONNECTED · UNSTABLE");
        awaitTrue(() -> app.bean(SocketRegistry.class).isLive(deviceId, ok.connectionId()), "소켓 레지스트리");
        return new Connected(device, ok);
    }

    /** 원장 행. */
    public Optional<DeviceConnectionRow> ledger() {
        return app.bean(DeviceConnectionRepository.class).find(deviceId);
    }

    /** 시작 명령(U1 자유대화 패키지). */
    public StartSessionHandler.StartCommand startCommand(UUID sessionId, ChildContext childContext) {
        Map<String, StartSessionHandler.ApprovedContentItem> approved = new LinkedHashMap<>();
        approved.put(PlayApprovedContent.CLOSING, new StartSessionHandler.ApprovedContentItem(SPOKEN_CLOSING,
                "알겠어. 안녕.", "/media/approved/closing.wav"));
        approved.put(kr.mujoong.realtime.session.ServiceGuardrailFixtures.CODE, new StartSessionHandler.ApprovedContentItem(
                kr.mujoong.realtime.session.ServiceGuardrailFixtures.INTRO, kr.mujoong.realtime.session.ServiceGuardrailFixtures.TEXT,
                kr.mujoong.realtime.session.ServiceGuardrailFixtures.URL));
        approved.put(kr.mujoong.realtime.session.SessionExecutor.RE_PROMPT, new StartSessionHandler.ApprovedContentItem(
                UUID.fromString("11111111-1111-4111-8111-111111111117"), kr.mujoong.realtime.session.ServiceGuardrailFixtures.TEXT,
                "/media/approved/re_prompt.wav"));
        approved.put(PlayApprovedContent.WAITING_FILLER, new StartSessionHandler.ApprovedContentItem(UUID.randomUUID(),
                "음, 잠깐만 생각해 볼게.", "/media/approved/filler.wav"));
        return new StartSessionHandler.StartCommand(sessionId, UUID.randomUUID(), guardianId, childId, deviceId,
                ActivityType.FREE_TALK, new ConsentSnapshot("1.0", true, true, false, false), childContext,
                List.of(new StartSessionHandler.StopExpressionItem("무중아 그만할래", true)), approved);
    }

    /** 기본 아이 맥락으로 시작한다. */
    public StartSessionHandler.StartResult start(UUID sessionId) {
        return app.bean(StartSessionHandler.class).start(startCommand(sessionId,
                new ChildContext("하늘", 8, List.of("공룡"))), "0123456789abcdef0123456789abcdef");
    }

    /**
     * U4b(FR11.3 활성화 경로): 세션이 참여 확인(WAITING_CHILD)에 들어오면 아이가 "응"이라고 답하게 하고(가짜 공급자의 입력 전사 확정) ACTIVE가
     * 될 때까지 기다린다. 시작 패키지에 시작 안내가 없으면 창이 곧바로 열린다.
     */
    public void join(UUID sessionId) {
        completeNotice(sessionId);
        childSays("join-" + sessionId, "응");
        awaitOrDump(sessionId, () -> sessionStatus(sessionId) == kr.mujoong.contract.status.SessionStatus.ACTIVE, "ACTIVE");
        provider.await("session.instructions.append", 1, Duration.ofSeconds(3));
    }

    public void completeNotice(UUID sessionId) {
        awaitOrDump(sessionId,
                () -> sessionStatus(sessionId) == kr.mujoong.contract.status.SessionStatus.WAITING_CHILD,
                "WAITING_CHILD");
        var facts = app.bean(kr.mujoong.realtime.session.SessionGuardrailFactsRepository.class);
        if (app.bean(kr.mujoong.realtime.session.ActivitySessionRepository.class).find(sessionId).orElseThrow().activityType() == ActivityType.FREE_TALK) {
            awaitOrDump(sessionId, () -> facts.latest(sessionId).map(value -> value.notice()
                    == kr.mujoong.realtime.session.SessionGuardrailFacts.Notice.PENDING
                    || value.notice() == kr.mujoong.realtime.session.SessionGuardrailFacts.Notice.COMPLETED).orElse(false), "current notice attempt");
        }
        if (facts.latest(sessionId).map(value -> value.notice() == kr.mujoong.realtime.session.SessionGuardrailFacts.Notice.PENDING).orElse(false)) {
            var envelope = currentDevice.await(value -> sessionId.equals(value.sessionId())
                    && value.payload() instanceof PlayApprovedContent play
                    && play.contentVersionId().equals(kr.mujoong.realtime.session.ServiceGuardrailFixtures.INTRO), WAIT);
            var play = (PlayApprovedContent) envelope.payload();
            currentDevice.ack(envelope.opId(), sessionId);
            currentDevice.playbackMark(sessionId, play.playbackId(), kr.mujoong.contract.ws.message.PlaybackMark.Status.COMPLETED);
            awaitOrDump(sessionId, () -> facts.latest(sessionId).map(value -> value.notice()
                    == kr.mujoong.realtime.session.SessionGuardrailFacts.Notice.COMPLETED).orElse(false), "notice COMPLETED");
        }
        if (facts.latest(sessionId).map(value -> value.notice() == kr.mujoong.realtime.session.SessionGuardrailFacts.Notice.COMPLETED).orElse(false)) {
            var play = currentDevice.received(kr.mujoong.contract.ws.WsMessageType.PLAY_APPROVED_CONTENT).stream()
                    .filter(envelope -> sessionId.equals(envelope.sessionId()))
                    .map(envelope -> (PlayApprovedContent) envelope.payload())
                    .filter(value -> value.contentVersionId().equals(kr.mujoong.realtime.session.ServiceGuardrailFixtures.INTRO))
                    .findFirst().orElseThrow();
            var transcripts = app.bean(kr.mujoong.realtime.log.TranscriptRepository.class);
            awaitOrDump(sessionId, () -> transcripts.findAll(sessionId).stream().anyMatch(row -> row.sequenceNo() == 1
                    && row.speaker() == kr.mujoong.realtime.log.TranscriptEntry.Speaker.AI && row.isFinal()
                    && row.approvedForDisplay() && row.text().equals(kr.mujoong.realtime.session.ServiceGuardrailFixtures.TEXT)), "approved notice prelude row");
            noticePreludes.putIfAbsent(sessionId, new NoticePrelude(play.playbackId(), play.contentVersionId(), 1));
        }
    }

    /** 실제 안내의 session/playback/contentVersion 및 준비 seq로만 활동 이후 관측을 분리한다. */
    public List<kr.mujoong.realtime.log.TranscriptRow> activityTranscripts(UUID sessionId) {
        var prelude = noticePreludes.get(sessionId);
        return app.bean(kr.mujoong.realtime.log.TranscriptRepository.class).findAll(sessionId).stream()
                .filter(row -> prelude == null || row.sequenceNo() != prelude.sequenceNo()).toList();
    }

    /**
     * {@link #awaitTrue}와 같되, 시한을 넘기면 진단(세션 상태 · 가짜 공급자가 받은 메시지 type · 앱 스레드 스택)을 표준 출력에 남긴 뒤 실패한다.
     * CI에서 한 번만 난 DEVICE_ACK 멈춤(PR #6)의 원인을 시험 보고서로 보기 위한 것이다. 전사 · 토큰은 찍지 않는다.
     */
    public void awaitOrDump(UUID sessionId, BooleanSupplier condition, String what) {
        try {
            awaitTrue(condition, what);
        } catch (AssertionError e) {
            System.out.println(diagnostics(sessionId, what));
            throw e;
        }
    }

    /** 진단 한 묶음(문자열). 가상 스레드까지 담으려고 HotSpot 스레드 덤프를 쓰고 kr.mujoong 프레임이 있는 스레드만 남긴다. */
    public String diagnostics(UUID sessionId, String what) {
        StringBuilder out = new StringBuilder("=== 진단: ").append(what).append(" 기다림 시한 초과 ===\n");
        out.append("session status: ").append(sessionStatus(sessionId)).append('\n');
        out.append("provider received types: ").append(provider.received().stream()
                .map(node -> node.path("type").asText()).toList()).append('\n');
        out.append(appThreads());
        return out.append("=== 진단 끝 ===").toString();
    }

    private static String appThreads() {
        try {
            java.nio.file.Path file = java.nio.file.Files.createTempFile("realtime-it-threads", ".txt");
            java.nio.file.Files.delete(file);
            java.lang.management.ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class)
                    .dumpThreads(file.toString(), com.sun.management.HotSpotDiagnosticMXBean.ThreadDumpFormat.TEXT_PLAIN);
            String dump = java.nio.file.Files.readString(file);
            java.nio.file.Files.deleteIfExists(file);
            StringBuilder kept = new StringBuilder();
            for (String block : dump.split("\\R\\R")) {
                if (block.contains("kr.mujoong.realtime") && !block.contains("jdk.internal.vm.ThreadDumper")) {
                    kept.append(block).append("\n\n");
                }
            }
            return kept.toString();
        } catch (java.io.IOException | RuntimeException e) {
            return "thread dump failed: " + e + '\n';
        }
    }

    /** 가짜 공급자로 아이 확정 전사 하나를 흘린다(조각 · 확정). */
    public void childSays(String itemId, String text) {
        // 공급자는 입력 전사 완료 사건을 주지 않는다(설계 개정 A7): 조각만 보내면 앱이 input-transcript-quiet 뒤 확정한다
        long clock = provider.audioClockMs();
        provider.emitInputTranscript(text, clock, clock);
    }

    /** 세션 행의 지금 상태(없으면 null). */
    public kr.mujoong.contract.status.SessionStatus sessionStatus(UUID sessionId) {
        return app.bean(kr.mujoong.realtime.session.ActivitySessionRepository.class).find(sessionId)
                .map(kr.mujoong.realtime.session.ActivitySessionRow::status).orElse(null);
    }

    /** 조건이 참이 될 때까지 기다린다. */
    public static void awaitTrue(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(10);
        }
        throw new AssertionError("기다린 조건이 오지 않았다: " + what);
    }

    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        try {
            app.close();
        } finally {
            provider.close();
            core.close();
            db.wipe();
            db.close();
        }
    }
}
