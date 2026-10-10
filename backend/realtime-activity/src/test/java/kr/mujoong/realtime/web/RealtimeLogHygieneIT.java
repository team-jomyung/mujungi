package kr.mujoong.realtime.web;

import static kr.mujoong.realtime.testing.RealtimeItEnvironment.WAIT;
import static kr.mujoong.realtime.testing.RealtimeItEnvironment.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.secret.SecretCompare;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.WsEnvelope;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.message.OutputBegin;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.realtime.gateway.ConnectionSender;
import kr.mujoong.realtime.gateway.DeviceWebSocketHandler;
import kr.mujoong.realtime.gateway.WsFrameGuard;
import kr.mujoong.realtime.session.ActivitySessionRepository;
import kr.mujoong.realtime.session.ChildContext;
import kr.mujoong.realtime.session.ConsentSnapshot;
import kr.mujoong.realtime.session.NewActivitySession;
import kr.mujoong.realtime.startup.RealtimeStartupChecks;
import kr.mujoong.realtime.testing.InternalApi;
import kr.mujoong.realtime.testing.RealtimeItEnvironment;
import kr.mujoong.realtime.voice.InputRelay;
import kr.mujoong.testsupport.device.DeviceSimulatorClient;
import kr.mujoong.testsupport.log.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Realtime 로그 · 페이로드 · 원음 위생 시험 (U1 3.5 Step 35 · SD-15 · SD-22 · SD-23 · BR8.7 · BR5.11 · BR5.13 · BR7.1 · NFR9.8 ·
 * NFR12.2 · NFR12.3 · B1 ③의 앱 쪽 확인).
 *
 * <p>실제 앱을 띄워 기기 연결 → 시작 → 표식 바이트를 실은 입력 음성 → 정상 턴 · 금지 패턴 턴 → 진행 조회 → 끝내기 → ENDED까지 돈 뒤:
 *
 * <ul>
 *   <li>앱이 표준 출력에 낸 모든 줄(사건 줄 · 제3자 APP_ERROR 줄)과 사건 수집기에 기기 토큰 · 그 해시 · 내부 토큰 · 공급자 키 · 아이 전사 ·
 *       통과한 AI 말 · 폐기된 금지 문장 · 음성 표식이 0건이다. 빈 수집으로 통과하지 않게 주요 사건 줄이 있는지도 본다.
 *   <li>전달된 Outbox 행은 모두 payload {@code {}} · purged_at이 있다(SD-23).
 *   <li>DB 어느 realtime 표에도 음성 표식이 없다(원음 비저장, SD-22).
 *   <li>프레임 경로(0x01 수신 · 입력 중계 · 프레임 판정 · 송신기) 위에서 연 DB 연결이 0건이다(SD-22, 실행 중 탐지기).
 * </ul>
 */
@Tag("u1")
@Tag("db")
class RealtimeLogHygieneIT {

    private static final String CHILD_TEXT = "하늘이비밀전사첫문장";
    private static final String AI_TEXT = "무중비밀응답둘째문장";
    private static final String FORBIDDEN_TEXT = "검사해 보니 진단이 필요해요";
    private static final String MARKER = "SD22-MARKER-";
    private static final int FRAMES = 60;
    private static final List<String> TABLES = List.of("realtime_activity_session", "realtime_session_event",
            "realtime_session_stop_expression", "realtime_transcript", "realtime_device_connection",
            "realtime_outbox_event", "realtime_inbox_event");

    private FramePathProbe probe;
    private StdoutTee stdout;
    private RealtimeItEnvironment env;
    private LogCapture logs;

    @BeforeEach
    void start() {
        probe = new FramePathProbe();
        stdout = StdoutTee.install();
        try {
            env = RealtimeItEnvironment.startWith(Map.of(
                    "mujoong.timing.session.device-ack-timeout", "20s",
                    "mujoong.timing.session.closing-confirm", "30s",
                    "mujoong.timing.device.disconnect", "40s",
                    "mujoong.limits.db-lock-timeout", "200ms"),
                    context -> context.getBeanFactory().addBeanPostProcessor(probe));
            logs = LogCapture.attach();
        } catch (RuntimeException e) {
            stdout.restore();
            throw e;
        }
    }

    @AfterEach
    void stop() {
        stdout.restore();
        try {
            if (logs != null) {
                logs.close();
            }
        } finally {
            if (env != null) {
                env.close();
            }
        }
    }

    @Test
    void aWholeSessionLeavesNoSecretsTranscriptsOrAudioInLogsPayloadsOrTheDatabase() {
        runSession(env);

        awaitTrue(() -> count(env, "select count(*) from realtime.realtime_outbox_event where status <> 'PUBLISHED'")
                == 0, "Outbox 전부 전달");
        assertOutboxPurged(env);
        assertNoMarkerInTables(env);
        assertThat(probe.framePathConnections.get()).isZero();
        assertThat(probe.allConnections.get()).isPositive();

        String printed = stdout.captured();
        // 빈 수집으로 통과하지 않는다: 이 흐름의 주요 사건 줄이 실제로 나왔다
        assertThat(printed).contains("\"event\":\"SESSION_TRANSITION\"").contains("\"event\":\"LATENCY_TURN\"")
                .contains("\"event\":\"DEVICE_CONNECTION\"").contains("\"event\":\"SESSION_ITEMS_SERVED\"");
        assertNoneLeaked(printed, forbiddenValues(env));
    }

    @Test
    void rejectedInputsAreNeverEchoedIntoResponsesOrLogs() {
        String marker = "LEAKPROBE" + UUID.randomUUID().toString().replace("-", "");
        InternalApi api = env.api();

        // 형식 위반 패키지(표시 이름 11자 이상)에 실은 값은 400 봉투 · 로그에 없다(SD-11)
        String body = InternalApi.startBody(UUID.randomUUID(), env.guardianId, env.childId, env.deviceId)
                .replace("\"displayName\":\"하늘\"", "\"displayName\":\"" + marker + "\"");
        InternalApi.Reply rejected = api.post("/internal/sessions", body);
        assertThat(rejected.status()).isEqualTo(400);
        // 틀린 내부 토큰으로 보낸 값은 401 사건 줄에 없다(SD-8 · SD-15)
        InternalApi wrongToken = new InternalApi(env.app.internalUri(""), marker);
        assertThat(wrongToken.post("/internal/events", "{\"x\":\"" + marker + "\"}").status()).isEqualTo(401);
        // 형식이 틀린 HELLO에 실은 값은 닫기 사유 · 로그에 없다(BR3.6)
        DeviceSimulatorClient device = DeviceSimulatorClient.open(env.app.deviceUri(), RealtimeItEnvironment.ORIGIN,
                env.deviceToken, WAIT);
        device.sendRaw("{\"type\":\"HELLO\",\"seq\":1,\"sentAt\":\"x\",\"payload\":{\"clientVersion\":\"" + marker
                + "\"}}");
        assertThat(device.awaitClose(WAIT)).contains(4400);

        assertThat(rejected.body()).doesNotContain(marker);
        assertThat(logs.events(EventCode.SECURITY_INTERNAL_TOKEN_FAILED)).hasSize(1);
        assertNoneLeaked(stdout.captured(), List.of(marker, env.deviceToken));
    }

    @Test
    void aDatabaseFailureAnswers503AndLogsOnlyTheExceptionKind() {
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = UUID.randomUUID();
        InternalApi api = env.api();
        ActivitySessionRepository blocker = new ActivitySessionRepository(env.db.jdbc());

        // 같은 id의 커밋 안 된 행이 잠금을 쥐면 시작의 INSERT가 lock_timeout(200ms)과 재시도 끝에 실패한다(RD-22)
        env.db.rolledBack(() -> {
            blocker.insert(new NewActivitySession(sessionId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    ActivityType.FREE_TALK, "blocker", new ConsentSnapshot("1.0", true, true, false, false),
                    new ChildContext("바다", 9, List.of()), null, null),
                    env.app.bean(kr.mujoong.contract.time.MujoongClock.class).now());
            InternalApi.Reply failed = api.post("/internal/sessions",
                    InternalApi.startBody(sessionId, env.guardianId, env.childId, env.deviceId));
            assertThat(failed.status()).isEqualTo(503);
            assertThat(failed.code()).isEqualTo("SERVICE_NOT_READY");
            assertThat(failed.header("Retry-After")).isNotBlank();
        });

        List<Map<String, Object>> appErrors = stdout.capturedEvents("APP_ERROR");
        assertThat(appErrors).isNotEmpty().allSatisfy(event -> assertThat(LogCapture.attrs(event).keySet())
                .isSubsetOf("errorKind", "repeat"));
        assertNoneLeaked(stdout.captured(), List.of(sessionId.toString(), "realtime_activity_session", "lock timeout",
                "canceling statement"));
        // 잠금이 풀리면 같은 요청이 성공한다(Core의 재시도 · 번역 경로, BR6.7)
        assertThat(api.post("/internal/sessions", InternalApi.startBody(sessionId, env.guardianId, env.childId,
                env.deviceId)).status()).isEqualTo(202);
        assertThat(device.await(WsMessageType.START_SESSION, WAIT).sessionId()).isEqualTo(sessionId);
    }

    private static void assertNoneLeaked(String printed, List<String> values) {
        for (String value : values) {
            assertThat(printed).as("표준 출력에 남으면 안 되는 값").doesNotContain(value);
        }
    }

    private static void runSession(RealtimeItEnvironment env) {
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = UUID.randomUUID();
        InternalApi api = env.api();
        assertThat(api.post("/internal/sessions", InternalApi.startBody(sessionId, env.guardianId, env.childId,
                env.deviceId)).status()).isEqualTo(202);
        WsEnvelope command = device.await(WsMessageType.START_SESSION, WAIT);
        device.ack(command.opId(), sessionId);
        device.deviceReady(sessionId, true, true);
        awaitStatus(env, sessionId, SessionStatus.ACTIVE);

        byte[] pcm = markedPcm();
        // 입력 대기열은 50프레임(L1, 넘치면 오래된 것부터 버림)이라 60프레임을 한 번에 몰아 보내면 부하에 따라 일부가 버려진다.
        // 기기처럼 나눠 보내고 묶음마다 중계를 기다린다(Step 36 재실행에서 드러난 경주)
        for (int i = 0; i < FRAMES; i++) {
            device.sendAudio(pcm);
            if (i % 20 == 0) {
                device.heartbeat(20L);
            }
            if ((i + 1) % 20 == 0) {
                int sent = i + 1;
                awaitTrue(() -> env.provider.audioHashes().size() >= sent, "입력 음성 중계 " + sent);
            }
        }
        awaitTrue(() -> env.provider.audioHashes().size() >= FRAMES, "입력 음성 중계");

        env.provider.emitTurn("item-1", CHILD_TEXT, "resp-1", AI_TEXT, List.of(new byte[4800]));
        WsEnvelope begin = device.await(WsMessageType.OUTPUT_BEGIN, WAIT);
        device.await(WsMessageType.OUTPUT_END, WAIT);
        device.playbackMark(sessionId, ((OutputBegin) begin.payload()).playbackId(), PlaybackMark.Status.STARTED);
        env.provider.emitTurn("item-2", "응", "resp-2", FORBIDDEN_TEXT, List.of(new byte[4800]));
        device.await(envelope -> envelope.payload() instanceof PlayApprovedContent play
                && PlayApprovedContent.WAITING_FILLER.equals(play.contentCode()), WAIT);
        awaitTrue(() -> api.get("/internal/sessions/" + sessionId + "/view?sinceSeq=0").json().get("items").size() >= 2,
                "진행 조회 항목");
        assertThat(api.get("/internal/sessions/" + sessionId + "/view?sinceSeq=0").body()).doesNotContain(FORBIDDEN_TEXT);

        assertThat(api.post("/internal/sessions/" + sessionId + "/end",
                InternalApi.endBody(UUID.randomUUID(), "GUARDIAN_STOP")).status()).isEqualTo(202);
        WsEnvelope closing = device.await(envelope -> envelope.payload() instanceof PlayApprovedContent play
                && PlayApprovedContent.CLOSING.equals(play.contentCode()), WAIT);
        device.playbackMark(sessionId, ((PlayApprovedContent) closing.payload()).playbackId(),
                PlaybackMark.Status.COMPLETED);
        device.await(WsMessageType.CLOSE_TRANSPORT, WAIT);
        device.voiceSessionClosed(sessionId);
        awaitStatus(env, sessionId, SessionStatus.ENDED);
        awaitTrue(() -> logsHave(env, sessionId), "전사 FINAL 기록");
    }

    private static boolean logsHave(RealtimeItEnvironment env, UUID sessionId) {
        return count(env, "select count(*) from realtime.realtime_transcript where session_id = '" + sessionId
                + "' and assembly_status = 'FINAL'") >= 3;
    }

    private static void awaitStatus(RealtimeItEnvironment env, UUID sessionId, SessionStatus status) {
        awaitTrue(() -> env.app.bean(ActivitySessionRepository.class).find(sessionId)
                .map(row -> row.status() == status).orElse(false), "세션 " + status);
    }

    private static void assertOutboxPurged(RealtimeItEnvironment env) {
        assertThat(count(env, "select count(*) from realtime.realtime_outbox_event where status = 'PUBLISHED'"))
                .isGreaterThanOrEqualTo(3);
        assertThat(count(env, "select count(*) from realtime.realtime_outbox_event where status = 'PUBLISHED'"
                + " and (payload_json <> '{}'::jsonb or purged_at is null)")).isZero();
    }

    private static void assertNoMarkerInTables(RealtimeItEnvironment env) {
        String hex = HexFormat.of().formatHex(MARKER.getBytes(StandardCharsets.US_ASCII));
        for (String table : TABLES) {
            MapSqlParameterSource params = new MapSqlParameterSource().addValue("marker", MARKER).addValue("hex", hex);
            Long found = env.db.single("select count(*) from realtime." + table + " t"
                    + " where strpos(t::text, :marker) > 0 or strpos(t::text, :hex) > 0", params, Long.class);
            assertThat(found).as(table).isZero();
        }
    }

    private static List<String> forbiddenValues(RealtimeItEnvironment env) {
        byte[] tokenHash = SecretCompare.sha256(env.deviceToken.getBytes(StandardCharsets.UTF_8));
        List<String> values = new ArrayList<>(List.of(env.deviceToken, env.internalToken,
                HexFormat.of().formatHex(tokenHash), Base64.getUrlEncoder().withoutPadding().encodeToString(tokenHash),
                CHILD_TEXT, AI_TEXT, FORBIDDEN_TEXT, MARKER,
                Base64.getEncoder().encodeToString(markedPcm()).substring(0, 32)));
        values.add(env.app.context().getEnvironment().getRequiredProperty(RealtimeStartupChecks.PROVIDER_API_KEY));
        return values;
    }

    private static long count(RealtimeItEnvironment env, String sql) {
        Long value = env.db.single(sql, new MapSqlParameterSource(), Long.class);
        return value == null ? 0 : value;
    }

    /** 960B PCM에 ASCII 표식을 되풀이해 싣는다. */
    private static byte[] markedPcm() {
        byte[] marker = MARKER.getBytes(StandardCharsets.US_ASCII);
        byte[] pcm = new byte[960];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = marker[i % marker.length];
        }
        return pcm;
    }

    /** 앱 DataSource를 감싸 연결을 연 호출 경로가 프레임 경로인지 센다(시험 소스 전용 탐지기). */
    static final class FramePathProbe implements BeanPostProcessor {

        final AtomicInteger framePathConnections = new AtomicInteger();
        final AtomicInteger allConnections = new AtomicInteger();

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof DataSource dataSource && "dataSource".equals(beanName)) {
                return new DelegatingDataSource(dataSource) {
                    @Override
                    public Connection getConnection() throws SQLException {
                        record();
                        return super.getConnection();
                    }

                    @Override
                    public Connection getConnection(String username, String password) throws SQLException {
                        record();
                        return super.getConnection(username, password);
                    }
                };
            }
            return bean;
        }

        private void record() {
            allConnections.incrementAndGet();
            if (StackWalker.getInstance().walk(frames -> frames.anyMatch(FramePathProbe::onFramePath))) {
                framePathConnections.incrementAndGet();
            }
        }

        private static boolean onFramePath(StackWalker.StackFrame frame) {
            String type = frame.getClassName();
            return type.equals(DeviceWebSocketHandler.class.getName()) && "handleBinaryMessage".equals(frame.getMethodName())
                    || type.startsWith(InputRelay.class.getName())
                    || type.equals(WsFrameGuard.class.getName())
                    || type.startsWith(ConnectionSender.class.getName());
        }
    }

    /** 표준 출력을 원래 출력과 메모리에 함께 쓴다(Logback 콘솔 출력은 쓸 때마다 System.out을 본다). */
    static final class StdoutTee {

        private final PrintStream original;
        private final ByteArrayOutputStream copy = new ByteArrayOutputStream();

        private StdoutTee(PrintStream original) {
            this.original = original;
        }

        static StdoutTee install() {
            StdoutTee tee = new StdoutTee(System.out);
            OutputStream both = new OutputStream() {
                @Override
                public void write(int b) {
                    synchronized (tee.copy) {
                        tee.copy.write(b);
                    }
                    tee.original.write(b);
                }

                @Override
                public void write(byte[] bytes, int offset, int length) {
                    synchronized (tee.copy) {
                        tee.copy.write(bytes, offset, length);
                    }
                    tee.original.write(bytes, offset, length);
                }
            };
            System.setOut(new PrintStream(both, true, StandardCharsets.UTF_8));
            return tee;
        }

        String captured() {
            synchronized (copy) {
                return copy.toString(StandardCharsets.UTF_8);
            }
        }

        /** 잡은 줄 중 사건 코드가 {@code event}인 JSON 줄. */
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> capturedEvents(String event) {
            List<Map<String, Object>> found = new ArrayList<>();
            for (String line : captured().split("\\R")) {
                if (line.startsWith("{") && line.contains("\"event\":\"" + event + "\"")) {
                    found.add(JsonCodec.shared().read(line, Map.class));
                }
            }
            return found;
        }

        void restore() {
            System.setOut(original);
        }
    }
}
