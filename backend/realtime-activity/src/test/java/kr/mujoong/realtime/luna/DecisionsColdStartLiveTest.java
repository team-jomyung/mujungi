package kr.mujoong.realtime.luna;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.time.SystemMujoongClock;
import kr.mujoong.realtime.content.LiveContentResources;
import kr.mujoong.realtime.judgment.JudgmentPoint;
import kr.mujoong.realtime.judgment.JudgmentPrompt;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.testing.TestEventLoggers;
import kr.mujoong.testsupport.log.LogCapture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 첫 판정 지연 측정 — Decisions 실호출(합성 자료). 사람이 env {@code OPENAI_API_KEY}를 넣고 {@code ./gradlew lunaLiveTest}로만 돈다.
 * 세션 첫 판정이 2 ~ 4초 걸리는 원인이 연결 준비(JVM · TLS)인지 공급자 쪽 첫 요청인지 가리려고, 시한을 넉넉히 두고 아래 순서로 지연만 기록한다
 * (원문 없음, 단언 없음 — 측정 보고서가 결과다).
 * <ol>
 *   <li>A: 새 연결 · JVM 처음 — 같은 연결로 3번
 *   <li>C: 새 연결 · JVM 데운 뒤 — 1번
 *   <li>B: 새 연결 + 예열({@link RealtimeLunaClient#warmUp}) — 예열 시간과 그 뒤 1번
 *   <li>D: 예열된 연결 + 처음 보는 지시문 — 1번(공급자 쪽 지시문 첫 요청 비용)
 * </ol>
 */
@Tag("luna-live")
class DecisionsColdStartLiveTest {

    private static final Duration GENEROUS = Duration.ofSeconds(30);

    private final MujoongClock clock = new SystemMujoongClock();
    private final JsonCodec json = JsonCodec.shared();

    private static Path report() {
        String configured = System.getProperty("mujoong.luna.live.report");
        return Path.of(configured != null ? configured : "build/luna-live/report.txt");
    }

    private static void write(String line) throws IOException {
        Path report = report();
        Files.createDirectories(report.getParent());
        Files.writeString(report, line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private RealtimeLunaClient newClient() {
        LunaProperties properties = new LunaProperties(System.getenv("OPENAI_JUDGE_MODEL"), URI.create("https://api.openai.com/v1"),
                1_000_000, true);
        return new RealtimeLunaClient(properties, GENEROUS, LunaSchemas.loadDefault(json), new LunaInputSanitizer(json),
                new LunaTransport(properties.baseUrl(), LunaHosts.modeFor(), () -> System.getenv(LunaTransport.API_KEY_ENV),
                        properties.maxResponseBytes()),
                TestEventLoggers.strict(clock, LogCapture.detached(), RealtimeMetrics.inMemory()), clock, json);
    }

    private String call(RealtimeLunaClient client, String instruction) throws InterruptedException {
        Map<String, Object> input = RealtimeLunaTestFixtures.judgmentInput("그 블록 줄래? 탑 쌓고 싶어");
        @SuppressWarnings("unchecked")
        List<String> options = (List<String>) input.get("options");
        LunaDecisionQuestion question = new LunaDecisionQuestion("judgment",
                options.stream().map(option -> new LunaDecisionQuestion.Choice(option, null)).toList());
        LunaRequest request = new LunaRequest(LunaPurpose.JUDGMENT_LIVE, "cold-start-probe.v1", instruction, input, Map.of(),
                clock.now().plus(GENEROUS));
        long started = System.nanoTime();
        try {
            client.decide(request, question).toCompletableFuture().get();
            return "OK " + Duration.ofNanos(System.nanoTime() - started).toMillis() + "ms";
        } catch (ExecutionException failure) {
            String reason = failure.getCause() instanceof LunaException luna ? luna.reason().name() : "UNKNOWN";
            return reason + " " + Duration.ofNanos(System.nanoTime() - started).toMillis() + "ms";
        }
    }

    private String callWithChoices(RealtimeLunaClient client, String instruction, String descriptionSuffix)
            throws InterruptedException {
        Map<String, Object> input = RealtimeLunaTestFixtures.judgmentInput("그 블록 줄래? 탑 쌓고 싶어");
        @SuppressWarnings("unchecked")
        List<String> options = (List<String>) input.get("options");
        JudgmentPrompt prompt = JudgmentPrompt.load(LiveContentResources.loadDefault().judgment());
        LunaDecisionQuestion question = new LunaDecisionQuestion("judgment", options.stream()
                .map(option -> new LunaDecisionQuestion.Choice(option,
                        prompt.choiceDescription(JudgmentPoint.J2, option) + descriptionSuffix))
                .toList());
        LunaRequest request = new LunaRequest(LunaPurpose.JUDGMENT_LIVE, "cold-start-probe.v1", instruction, input, Map.of(),
                clock.now().plus(GENEROUS));
        long started = System.nanoTime();
        try {
            client.decide(request, question).toCompletableFuture().get();
            return "OK " + Duration.ofNanos(System.nanoTime() - started).toMillis() + "ms";
        } catch (ExecutionException failure) {
            String reason = failure.getCause() instanceof LunaException luna ? luna.reason().name() : "UNKNOWN";
            return reason + " " + Duration.ofNanos(System.nanoTime() - started).toMillis() + "ms";
        }
    }

    /**
     * 운영과 같은 판정 지시문(prompt-service-v3 J2) · 선택지 뜻으로, 예열된 연결에서 잰다. E: 운영 선택지 뜻 2번(첫 요청 · 반복),
     * F: 처음 보는 선택지 뜻 2번(공급자 쪽 질문 형식 첫 요청 비용 · 반복).
     */
    @Test
    void measuresTheProductionShapedQuestionOnAWarmConnection() throws Exception {
        String key = System.getenv(LunaTransport.API_KEY_ENV);
        assumeTrue(key != null && !key.isBlank(), "SKIPPED_NO_KEY: env OPENAI_API_KEY 없음(실호출 안 함)");
        JudgmentPrompt prompt = JudgmentPrompt.load(LiveContentResources.loadDefault().judgment());
        String instruction = prompt.instructions(JudgmentPoint.J2);
        RealtimeLunaClient client = newClient();
        client.warmUp().toCompletableFuture().get();
        call(client, "합성 시험 자료다. 선택지 하나를 고른다.");

        String e1 = callWithChoices(client, instruction, "");
        String e2 = callWithChoices(client, instruction, "");
        String nonce = " (" + UUID.randomUUID() + ")";
        String f1 = callWithChoices(client, instruction, nonce);
        String f2 = callWithChoices(client, instruction, nonce);

        write("app=realtime probe=COLD_START_SHAPE evidence=REAL_API_SYNTHETIC instructionChars=" + instruction.length()
                + " E(production-question)=[" + e1 + ", " + e2 + "]"
                + " F(new-choice-descriptions)=[" + f1 + ", " + f2 + "]");
    }

    @Test
    void measuresWhereTheFirstJudgmentSpendsItsTime() throws Exception {
        String key = System.getenv(LunaTransport.API_KEY_ENV);
        if (key == null || key.isBlank()) {
            write("app=realtime probe=COLD_START outcome=SKIPPED_NO_KEY evidence=NONE");
        }
        assumeTrue(key != null && !key.isBlank(), "SKIPPED_NO_KEY: env OPENAI_API_KEY 없음(실호출 안 함)");
        String instruction = "합성 시험 자료다. 아이 발화가 판정 기준에 얼마나 맞는지 choices 중 하나를 고르고 선택지마다 확률을 매긴다.";

        RealtimeLunaClient a = newClient();
        String a1 = call(a, instruction);
        String a2 = call(a, instruction);
        String a3 = call(a, instruction);

        String c1 = call(newClient(), instruction);

        RealtimeLunaClient b = newClient();
        long warmStarted = System.nanoTime();
        b.warmUp().toCompletableFuture().get();
        long warmMs = Duration.ofNanos(System.nanoTime() - warmStarted).toMillis();
        String b1 = call(b, instruction);

        String d1 = call(b, instruction + " 측정 표식 " + UUID.randomUUID() + ".");

        write("app=realtime probe=COLD_START evidence=REAL_API_SYNTHETIC"
                + " A(cold-jvm,new-conn)=[" + a1 + ", " + a2 + ", " + a3 + "]"
                + " C(warm-jvm,new-conn)=[" + c1 + "]"
                + " B(warm-jvm,warmUp " + warmMs + "ms)=[" + b1 + "]"
                + " D(warm-conn,new-instruction)=[" + d1 + "]");
    }
}
